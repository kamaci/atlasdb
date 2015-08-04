package com.palantir.paxos;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.locks.ReentrantLock;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Predicate;
import com.google.common.collect.Collections2;
import com.google.common.collect.Lists;
import com.google.common.io.Closeables;
import com.google.common.io.Files;
import com.google.protobuf.ByteString;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.palantir.common.base.Throwables;
import com.palantir.common.persist.Persistable;
import com.palantir.paxos.persistence.generated.PaxosPersistence;
import com.palantir.util.crypto.Sha256Hash;
import com.palantir.util.file.FileUtils;

public class PaxosStateLogImpl<V extends Persistable & Versionable> implements PaxosStateLog<V> {

    private final ReentrantLock lock = new ReentrantLock();
    private final HashMap<Long, Long> seqToVersionMap = new HashMap<Long, Long>();

    private static final String TMP_FILE_SUFFIX = ".tmp";
    private static final Logger log = LoggerFactory.getLogger(PaxosStateLogImpl.class);

    private static Predicate<File> nameIsALongPredicate() {
        return new Predicate<File>() {
            @Override
            public boolean apply(@Nullable File file) {
                if (file == null) {
                    return false;
                }
                try {
                    getSeqFromFilename(file);
                    return true;
                } catch (NumberFormatException e) {
                    return false;
                }
            }};
    }

    private static final Comparator<File> nameAsLongComparator() {
        return new Comparator<File>() {
            @Override
            public int compare(File f1, File f2) {
                Long s1 = getSeqFromFilename(f1);
                Long s2 = getSeqFromFilename(f2);
                return s1.compareTo(s2);
            }
        };
    }

    private static enum Extreme { GREATEST, LEAST }

    final String path;

    public PaxosStateLogImpl(String path) {
        this.path = path;
        try {
            FileUtils.mkdirsWithRetry(new File(path));
            if (getGreatestLogEntry() == PaxosAcceptor.NO_LOG_ENTRY) {
                // For a brand new log, we create a lowest entry so #getLeastLogEntry will return the right thing
                // If we didn't add this then we could miss seq 0 and accept seq 1, then when we restart we will
                // start ignoring seq 0 which may cause things to get stalled
                Files.touch(new File(path, getFilenameFromSeq(PaxosAcceptor.NO_LOG_ENTRY)));
            }
        } catch (IOException e) {
            throw Throwables.throwUncheckedException(e);
        }
    }

    @Override
    public void writeRound(long seq, V round) {
        lock.lock();
        try {
            // reject old state
            Long latestVersion = seqToVersionMap.get(seq);
            if (latestVersion != null && round.getVersion() < latestVersion) {
                return;
            }

            // do write
            writeRoundInternal(seq, round);
        } finally {
            lock.unlock();
        }
    }

    private void writeRoundInternal(long seq, V round) {
        String name = getFilenameFromSeq(seq);
        File tmpFile = new File(path, name + TMP_FILE_SUFFIX);

        // compute checksum hash
        byte[] bytes = round.persistToBytes();
        byte[] hash = Sha256Hash.computeHash(bytes).getBytes();
        PaxosPersistence.PaxosHeader header = PaxosPersistence.PaxosHeader.newBuilder().setChecksum(
                ByteString.copyFrom(hash)).build();

        FileOutputStream fileOut = null;
        try {
            fileOut = new FileOutputStream(tmpFile);
            header.writeDelimitedTo(fileOut);
            CodedOutputStream out = CodedOutputStream.newInstance(fileOut);
            out.writeBytesNoTag(ByteString.copyFrom(bytes));
            out.flush();
            fileOut.getFD().sync();
            fileOut.close();
        } catch (IOException e) {
            log.error("problem writing paxos state", e);
            throw Throwables.throwUncheckedException(e);
        } finally {
            try { fileOut.close(); } catch (IOException e) {}
        }

        // overwrite file with tmp
        File file = new File(path, name);
        tmpFile.renameTo(file);

        // update version
        seqToVersionMap.put(seq, round.getVersion());
    }

    @Override
    public byte[] readRound(long seq) throws IOException {
        lock.lock();
        try {
            File file = new File(path, getFilenameFromSeq(seq));
            return getBytesAndCheckChecksum(file);
        } finally {
            lock.unlock();
        }
    }

    private static String getFilenameFromSeq(long seq) {
        return Long.toString(seq);
    }

    private static long getSeqFromFilename(File file) throws NumberFormatException {
        return Long.parseLong(file.getName());
    }

    @Override
    public long getLeastLogEntry() {
        return getExtremeLogEntry(Extreme.LEAST);
    }

    @Override
    public long getGreatestLogEntry() {
        return getExtremeLogEntry(Extreme.GREATEST);
    }

    public long getExtremeLogEntry(Extreme extreme) {
        lock.lock();
        try {
            File dir = new File(path);
            List<File> files = getLogEntries(dir);
            if (files == null) {
                return PaxosAcceptor.NO_LOG_ENTRY;
            }

            try {
                File file = (extreme == Extreme.GREATEST)
                        ? Collections.max(files, nameAsLongComparator())
                        : Collections.min(files, nameAsLongComparator());
                long seq = getSeqFromFilename(file);
                return seq;
            } catch (NoSuchElementException e) {
                return PaxosAcceptor.NO_LOG_ENTRY;
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void truncate(long toDeleteInclusive) {
        lock.lock();
        try {
            long greatestLogEntry = getGreatestLogEntry();
            if (greatestLogEntry >= 0) {
                // We never want to remove our most recent entry
                toDeleteInclusive = Math.min(greatestLogEntry - 1, toDeleteInclusive);
            }
            File dir = new File(path);
            List<File> files = getLogEntries(dir);
            Collections.<File> sort(files, nameAsLongComparator());
            for (File file : files) {
                long fileSeq = getSeqFromFilename(file);
                if (fileSeq <= toDeleteInclusive) {
                    if (file.delete()) {
                        log.warn("failed to delete log file %s", file.getAbsolutePath());
                    }
                } else {
                    break;
                }
            }
        } finally {
            lock.unlock();
        }
    }

    private List<File> getLogEntries(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return null;
        }
        return Lists.newArrayList(Collections2.filter(Arrays.asList(files), nameIsALongPredicate()));
    }

    /**
     * Gets the data payload of the given file (data minus header) and verfies the header checksum.
     *
     * @param file to read data bytes from
     * @return data after the checksum in the file
     * @throws IOException when the data checksum fails or there is another problem reading from disk
     */
    private byte[] getBytesAndCheckChecksum(File file) throws IOException {
        lock.lock();
        try {
            InputStream fileIn = null;
            PaxosPersistence.PaxosHeader.Builder headerBuilder =
                    PaxosPersistence.PaxosHeader.newBuilder();
            try {
                fileIn = new FileInputStream(file);
                headerBuilder.mergeDelimitedFrom(fileIn);
                CodedInputStream in = CodedInputStream.newInstance(fileIn);
                byte[] bytes = in.readBytes().toByteArray();
                byte[] checksum = Sha256Hash.computeHash(bytes).getBytes();
                if (Arrays.equals(headerBuilder.getChecksum().toByteArray(), checksum)) {
                    return bytes;
                } else {
                    throw new CorruptLogFileException();
                }
            } catch (FileNotFoundException e) {
            } catch (IOException e) {
                log.error("problem reading paxos state");
                throw Throwables.rewrap(e);
            } finally {
                Closeables.closeQuietly(fileIn);
            }
        } finally {
            lock.unlock();
        }
        return null;
    }

}
