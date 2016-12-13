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
package com.palantir.atlasdb.keyvalue.kafka.data;

import java.util.Map;

import org.immutables.value.Value;

import com.palantir.atlasdb.keyvalue.api.TableReference;

@Value.Immutable
public interface CreateTablesData {
    Map<TableReference, byte[]> getTableRefToTableMetadata();
}
