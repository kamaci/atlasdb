/**
 * // Copyright 2015 Palantir Technologies
 * //
 * // Licensed under the BSD-3 License (the "License");
 * // you may not use this file except in compliance with the License.
 * // You may obtain a copy of the License at
 * //
 * // http://opensource.org/licenses/BSD-3-Clause
 * //
 * // Unless required by applicable law or agreed to in writing, software
 * // distributed under the License is distributed on an "AS IS" BASIS,
 * // WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * // See the License for the specific language governing permissions and
 * // limitations under the License.
 */
package com.palantir.common.collect;

import static com.google.common.base.Preconditions.checkNotNull;

import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

import javax.annotation.Nullable;

import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.AbstractIterator;
import com.google.common.collect.ForwardingIterator;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.google.common.collect.PeekingIterator;
import com.google.common.collect.UnmodifiableIterator;
import com.palantir.common.base.Throwables;
import com.palantir.common.base.Visitors;
import com.palantir.util.Pair;
import com.palantir.util.Visitor;

public class IteratorUtils {

    private static final Random rand = new Random();

    /**
     * Get first element or null if the iterator is empty.
     */
    public static <T> T getFirst(Iterator<? extends T> iter) {
        return getFirst(iter, null);
    }

    public static <T> T getFirst(Iterator<? extends T> iter, @Nullable T defaultValue) {
        if (iter.hasNext()) {
            return iter.next();
        } else {
            return defaultValue;
        }
    }

    /**
     * This simply returns an iterable that returns the passed iterator.
     * This is so we can use iterators in a for loop.
     * The only good use for this is in a foreach loop.
     * <p>
     * <code>
     * for (Long l : IteratorUtils.forEach(it)) {
     * </code>
     */
    public static <T> Iterable<T> forEach(final Iterator<T> it) {
        return new Iterable<T>() {
            @Override
            public Iterator<T> iterator() {
                return it;
            }
        };
    }

    public static <T> Collection<T> chooseRandomElements(Iterator<? extends T> it, final int k) {
        Preconditions.checkArgument(k >= 0);
        List<T> ret = Lists.newArrayList();

        int i = 0; // i is the element number
        while (it.hasNext()) {
            i++;
            T next = it.next();
            if (ret.size() < k) {
                ret.add(next);
            } else {
                int nextInt = rand.nextInt(i);
                if (nextInt < k) {
                    ret.set(nextInt, next);
                }
            }
            assert i != Integer.MAX_VALUE : "i is too big";
        }
        return ret;
    }

    /**
     * Returns the elements of {@code unfiltered} that satisfy a predicate,
     * calling {@code passVisitor.visit()} on all that satisfy the predicate and
     * {@code failVisitor.visit()} on all that do not.
     */
    public static <T> UnmodifiableIterator<T> filterAndVisit(final Iterator<? extends T> unfiltered,
            final Predicate<? super T> predicate, @Nullable Visitor<? super T> passVisitor,
            @Nullable Visitor<? super T> failVisitor) {
        checkNotNull(unfiltered);
        checkNotNull(predicate);
        final Visitor<? super T> actualPassVisitor;
        if (passVisitor != null) {
            actualPassVisitor = passVisitor;
        } else {
            actualPassVisitor = Visitors.emptyVisitor();
        }
        final Visitor<? super T> actualFailVisitor;
        if (failVisitor != null) {
            actualFailVisitor = failVisitor;
        } else {
            actualFailVisitor = Visitors.emptyVisitor();
        }
        return new AbstractIterator<T>() {
            @Override protected T computeNext() {
                try {
                    while (unfiltered.hasNext()) {
                        T element = unfiltered.next();
                        if (predicate.apply(element)) {
                            actualPassVisitor.visit(element);
                            return element;
                        } else {
                            actualFailVisitor.visit(element);
                        }
                    }
                    return endOfData();
                } catch (Exception e) {
                    throw Throwables.throwUncheckedException(e);
                }
            }
        };
    }

    public static <T> Iterator<T> wrap(final Iterator<? extends T> iterator) {
        if (iterator == null) {
            return null;
        }
        return new ForwardingIterator<T>() {
            @SuppressWarnings("unchecked")
            @Override
            protected Iterator<T> delegate() {
                return (Iterator<T>)iterator;
            }
        };
    }

    private IteratorUtils() {
        throw new AssertionError("uninstantiable");
    }

    /**
     * The iterators provided to this function have to be sorted and strictly increasing.
     */
    public static <T> Iterator<T> mergeIterators(Iterator<? extends T> one, Iterator<? extends T> two,
            final Comparator<? super T> ordering, final Function<? super Pair<T, T>, ? extends T> mergeFunction) {
        Preconditions.checkNotNull(mergeFunction);
        Preconditions.checkNotNull(ordering);
        final PeekingIterator<T> a = Iterators.peekingIterator(one);
        final PeekingIterator<T> b = Iterators.peekingIterator(two);
        return new AbstractIterator<T>() {
            @Override
            protected T computeNext() {
                if (!a.hasNext() && !b.hasNext()) {
                    return endOfData();
                }
                if (!a.hasNext()) {
                    T ret =  b.next();
                    if (b.hasNext()) {
                        assert ordering.compare(ret, b.peek()) < 0;
                    }
                    return ret;
                }
                if (!b.hasNext()) {
                    T ret =  a.next();
                    if (a.hasNext()) {
                        assert ordering.compare(ret, a.peek()) < 0;
                    }
                    return ret;
                }
                T peekA = a.peek();
                T peekB = b.peek();
                int comp = ordering.compare(peekA, peekB);
                if (comp == 0) {
                    return mergeFunction.apply(Pair.create(a.next(), b.next()));
                } else if (comp < 0) {
                    T ret =  a.next();
                    if (a.hasNext()) {
                        assert ordering.compare(ret, a.peek()) < 0;
                    }
                    return ret;
                } else {
                    T ret =  b.next();
                    if (b.hasNext()) {
                        assert ordering.compare(ret, b.peek()) < 0;
                    }
                    return ret;
                }
            }
        };
    }

    /**
     * This will take 2 iterators sorted by the given ordering and will return an iterator that
     * visits every element in the first one not in the second one.
     *
     * @param a must be sorted by ordering.
     * @param b must be sorted by ordering.
     * @param ordering
     */
    public static <T> Iterator<T> iteratorDifference(final Iterator<? extends T> a,
                                                     final Iterator<? extends T> b,
                                                     final Comparator<? super T> ordering) {
        Preconditions.checkNotNull(ordering);
        if (!a.hasNext()) {
            return Iterators.emptyIterator();
        }
        if (!b.hasNext()) {
            return IteratorUtils.wrap(a);
        }
        return new AbstractIterator<T>() {
            T currentB = b.next();

            @Override
            protected T computeNext() {
                while (a.hasNext()) {
                    T possibleNext = a.next();

                    // Search for a new currentB that is >= possibleNext
                    while (b.hasNext() && ordering.compare(possibleNext, currentB) > 0) {
                        currentB = b.next();
                    }

                    if (ordering.compare(possibleNext, currentB) != 0) {
                        return possibleNext;
                    }
                }
                return endOfData();
            }
        };
    }

    public static <T, U> Iterator<Pair<T, U>> zip(final Iterator<? extends T> it1, final Iterator<? extends U> it2) {
        return new AbstractIterator<Pair<T,U>>() {
            @Override
            protected Pair<T, U> computeNext() {
                if (!it1.hasNext() && !it2.hasNext()) {
                    return endOfData();
                }
                T v1 = null;
                if (it1.hasNext()) {
                    v1 = it1.next();
                }
                U v2 = null;
                if (it2.hasNext()) {
                    v2 = it2.next();
                }
                return Pair.create(v1, v2);
            }
        };
    }
}
