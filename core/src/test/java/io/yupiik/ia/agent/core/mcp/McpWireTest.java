/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.yupiik.ia.agent.core.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.test.TestJsonMapper;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@ExtendWith(TestJsonMapper.class)
class McpWireTest {
    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"answer\":42}",
                "{\"decimal\":0.12345678901234567890123456789}",
                "{\"large\":123456789012345678901234567890}",
                "{\"values\":[42,0.1234567890123456789,\"42\",true,null,{\"nested\":17}]}",
                "{}"
            })
    void structuredResultsPreserveJsonTypesAndNumericPrecision(final String structured, final JsonMapper mapper)
            throws Exception {
        final var decoded = McpWire.decode(
                mapper,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"content\":[],\"structuredContent\":" + structured + "}}");
        final var result = McpWire.payload(mapper, decoded.get("result"), McpWire.Result.class);
        assertEquals(mapper.fromString(Object.class, structured), result.structuredContent());
        assertEquals(
                mapper.fromString(Object.class, structured),
                mapper.fromString(Object.class, McpWire.text(mapper, result)));
    }
}
