/**
 * Copyright 2016 Palantir Technologies
 *
 * Licensed under the BSD-3 License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.palantir.atlasdb.keyvalue.kafka;

import java.util.Set;
import java.util.stream.Collectors;

import org.junit.ClassRule;

import com.palantir.atlasdb.containers.Containers;
import com.palantir.atlasdb.containers.KafkaContainer;
import com.palantir.atlasdb.keyvalue.api.KeyValueService;
import com.palantir.atlasdb.keyvalue.api.TableReference;
import com.palantir.atlasdb.keyvalue.impl.AbstractKeyValueServiceTest;

public class KafkaKeyValueServiceTest extends AbstractKeyValueServiceTest {
    @ClassRule
    public static final Containers CONTAINERS = new Containers(KafkaKeyValueServiceTest.class)
            .with(new KafkaContainer());

    @Override
    protected KeyValueService getKeyValueService() {
        return KafkaKeyValueService.create(null);
    }

    @Override
    public void tearDown() {
        cleanup(keyValueService);
    }

    private static void cleanup(KeyValueService db) {
        Set<TableReference> nonMetadataTables = db.getAllTableNames().stream()
                .filter(tableRef -> !tableRef.getNamespace().getName().equals("default")
                        || !tableRef.getTablename().equals("_metadata"))
                .collect(Collectors.toSet());
        db.dropTables(nonMetadataTables);
    }
}
