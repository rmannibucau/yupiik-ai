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
package io.yupiik.ia.agent.core.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.test.TestJsonMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(TestJsonMapper.class)
class McpServerConfigurationTest {
    @Test
    void propertyDefaultsAreLoaded() {
        final var server = TestConfigs.codingAgent(Map.of(
                        "yca.mcp.servers.length", "1",
                        "yca.mcp.servers.0.name", "docs"))
                .mcp()
                .servers()
                .getFirst();
        assertSame(McpServerConfiguration.DEFAULT_AVOID_ON_ERROR_PATTERNS, server.avoidOnErrorPatterns());
        assertEquals(2, server.avoidOnErrorPatterns().size());
    }

    @Test
    void flatConfigurationReplacesDefaultPatterns() {
        final var server = TestConfigs.codingAgent(Map.of(
                        "yca.mcp.servers.length", "1",
                        "yca.mcp.servers.0.name", "docs",
                        "yca.mcp.servers.0.avoidOnErrorPatterns", "(?i)credits exhausted,explicit account suspension"))
                .mcp()
                .servers()
                .getFirst();
        assertEquals(List.of("(?i)credits exhausted", "explicit account suspension"), server.avoidOnErrorPatterns());
    }

    @Test
    void flatConfigurationSupportsCustomSeparatorAndEmptyList() {
        final var patterns = List.of("(?i)quota reached after [0-9]{1,3} requests", "(?i)credits exhausted");
        final var server = TestConfigs.codingAgent(Map.of(
                        "yca.mcp.servers.length", "1",
                        "yca.mcp.servers.0.name", "docs",
                        "yca.mcp.servers.0.avoidOnErrorPatterns", "sep=;," + String.join(";", patterns)))
                .mcp()
                .servers()
                .getFirst();
        assertEquals(patterns, server.avoidOnErrorPatterns());
        assertTrue(TestConfigs.codingAgent(Map.of(
                        "yca.mcp.servers.length", "1",
                        "yca.mcp.servers.0.name", "docs",
                        "yca.mcp.servers.0.avoidOnErrorPatterns", ""))
                .mcp()
                .servers()
                .getFirst()
                .avoidOnErrorPatterns()
                .isEmpty());
    }

    @Test
    void equalDefaultsReuseConstant() {
        final var server = TestConfigs.codingAgent(Map.of(
                        "yca.mcp.servers.length",
                        "1",
                        "yca.mcp.servers.0.name",
                        "docs",
                        "yca.mcp.servers.0.avoidOnErrorPatterns",
                        "sep=;," + String.join(";", McpServerConfiguration.DEFAULT_AVOID_ON_ERROR_PATTERNS)))
                .mcp()
                .servers()
                .getFirst();
        assertSame(McpServerConfiguration.DEFAULT_AVOID_ON_ERROR_PATTERNS, server.avoidOnErrorPatterns());
    }

    @Test
    void jsonOmittedAndNullUseDefaultsButEmptyDisablesAndCustomReplaces(final JsonMapper mapper) {
        for (final var json : List.of("{\"name\":\"docs\"}", "{\"name\":\"docs\",\"avoidOnErrorPatterns\":null}")) {
            assertSame(
                    McpServerConfiguration.DEFAULT_AVOID_ON_ERROR_PATTERNS,
                    mapper.fromString(McpServerConfiguration.class, json).avoidOnErrorPatterns());
        }
        assertTrue(mapper.fromString(McpServerConfiguration.class, "{\"name\":\"docs\",\"avoidOnErrorPatterns\":[]}")
                .avoidOnErrorPatterns()
                .isEmpty());
        assertEquals(
                List.of("credits exhausted"),
                mapper.fromString(
                                McpServerConfiguration.class,
                                "{\"name\":\"docs\",\"avoidOnErrorPatterns\":[\"credits exhausted\"]}")
                        .avoidOnErrorPatterns());
    }
}
