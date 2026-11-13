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
package io.yupiik.ia.agent.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfigurations;
import io.yupiik.ia.agent.core.mcp.McpManager;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

@ExtendWith(TestWebJsonMapper.class)
class WebMcpStatusTest {
    @TempDir
    Path home;

    @Test
    void reportsConfiguredTransportsWithoutStartingServersOrExposingSecrets(final JsonMapper mapper) {
        final var configuration = configuration(Map.ofEntries(
                Map.entry("yca.mcp.servers.length", "4"),
                Map.entry("yca.mcp.servers.0.name", "remote"),
                Map.entry(
                        "yca.mcp.servers.0.url",
                        "https://user:password@mcp.example.test:8443/custom%20path/mcp?token=query-secret#fragment-secret"),
                Map.entry("yca.mcp.servers.0.apiKey", "api-secret"),
                Map.entry("yca.mcp.servers.0.headers", "Authorization=header-secret"),
                Map.entry("yca.mcp.servers.0.command", "ignored-command-secret"),
                Map.entry("yca.mcp.servers.1.name", "local"),
                Map.entry("yca.mcp.servers.1.command", "command-secret"),
                Map.entry("yca.mcp.servers.1.args", "--token,arg-secret"),
                Map.entry("yca.mcp.servers.1.env", "TOKEN=env-secret"),
                Map.entry("yca.mcp.servers.1.dir", "/dir-secret"),
                Map.entry("yca.mcp.servers.1.enabled", "false"),
                Map.entry("yca.mcp.servers.2.name", "plain"),
                Map.entry("yca.mcp.servers.2.url", "http://[::1]:8080/custom/mcp"),
                Map.entry("yca.mcp.servers.3.name", "unconfigured")));
        final var manager = new McpManager(configuration, null, List.of(), null, null);
        final var rpc = rpc(configuration, manager);
        final var statuses = rpc.mcpStatus();
        assertEquals(
                List.of(
                        new WebJsonRpc.McpDto("yca", "NOT_STARTED", "in-process", null),
                        new WebJsonRpc.McpDto(
                                "remote",
                                "NOT_STARTED",
                                "http",
                                "https://[redacted]@mcp.example.test:8443/custom%20path/mcp?[redacted]#[redacted]"),
                        new WebJsonRpc.McpDto("local", "DISABLED", "stdio", null),
                        new WebJsonRpc.McpDto("plain", "NOT_STARTED", "http", "http://[::1]:8080/custom/mcp"),
                        new WebJsonRpc.McpDto("unconfigured", "NOT_STARTED", null, null)),
                statuses);
        final var json = mapper.toString(statuses);
        for (final var secret : List.of(
                "user",
                "password",
                "query-secret",
                "fragment-secret",
                "api-secret",
                "header-secret",
                "command-secret",
                "arg-secret",
                "env-secret",
                "dir-secret")) {
            assertFalse(json.contains(secret), json);
        }
        assertEquals(statuses, rpc.mcpStatus()); // A status read must not initialize clients.
    }

    @Test
    void omitsMalformedOrUnsupportedUrlsRatherThanFallingBackToRawConfiguration() {
        for (final var url : List.of(
                "https://user:invalid secret@example.test/mcp",
                "https://user:secret@/mcp",
                "file:///secret",
                "javascript:secret",
                "https://example.test/mcp?token=%broken")) {
            final var configuration = configuration(Map.of(
                    "yca.mcp.servers.length", "1",
                    "yca.mcp.servers.0.name", "remote",
                    "yca.mcp.servers.0.url", url));
            final var manager = new McpManager(configuration, null, List.of(), null, null);
            final var remote = rpc(configuration, manager).mcpStatus().get(1);
            assertEquals("http", remote.transport());
            assertNull(remote.url(), url);
        }
    }

    @Test
    void resetStillDelegatesNamedAndAllRequests() {
        final var configuration = configuration(Map.of());
        final var calls = new ArrayList<String>();
        final var manager = new McpManager(configuration, null, List.of(), null, null) {
            @Override
            public void reset() {
                calls.add("all");
            }

            @Override
            public void reset(final String name) {
                calls.add(name);
            }
        };
        final var rpc = rpc(configuration, manager);
        assertEquals(Map.of("reset", true), rpc.mcpReset("remote"));
        rpc.mcpReset(null);
        rpc.mcpReset("");
        rpc.mcpReset("  ");
        assertEquals(List.of("remote", "all", "all", "all"), calls);
    }

    private CodingAgentConfiguration configuration(final Map<String, String> custom) {
        final var properties = new HashMap<>(custom);
        properties.put("yca.tools.builtin.enabled", "true");
        return CodingAgentConfigurations.builder()
                .baseUrl("http://localhost:0")
                .storageHome(home.toString())
                .custom(properties)
                .build();
    }

    private WebJsonRpc rpc(final CodingAgentConfiguration configuration, final McpManager manager) {
        return new WebJsonRpc(null, null, null, null, null, manager, configuration, null, null, null, null, null);
    }
}
