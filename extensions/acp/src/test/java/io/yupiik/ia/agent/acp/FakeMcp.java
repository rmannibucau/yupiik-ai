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
package io.yupiik.ia.agent.acp;

import io.yupiik.ia.agent.core.mcp.McpWire;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Real child process with private MCP pipes. Never launched by production. */
public final class FakeMcp {
    private FakeMcp() {}

    public static void main(final String[] args) throws Exception {
        try (final var mapper = CodecsTest.mapper();
                final var reader =
                        new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8.newDecoder()))) {
            Files.writeString(
                    Path.of(args[0]), Long.toString(ProcessHandle.current().pid()));
            System.err.println(System.getenv("ACP_TEST_SECRET"));
            String line;
            while ((line = reader.readLine()) != null) {
                final var request = mapper.fromString(McpWire.Envelope.class, line);
                if (request.id() == null) {
                    continue;
                }
                final Object result =
                        switch (request.method()) {
                            case "initialize" -> new McpWire.Initialized("2025-06-18");
                            case "tools/list" -> {
                                final var params =
                                        mapper.fromString(McpWire.ListParams.class, mapper.toString(request.params()));
                                yield params.cursor() == null
                                        ? new McpWire.Tools(
                                                List.of(new McpWire.Descriptor(
                                                        "echo",
                                                        "Echo test environment and cwd",
                                                        Map.of("type", "object"))),
                                                "page2")
                                        : new McpWire.Tools(
                                                List.of(new McpWire.Descriptor(
                                                        "second", "Second page", Map.of("type", "object"))),
                                                null);
                            }
                            case "tools/call" ->
                                new McpWire.Result(
                                        false,
                                        List.of(new McpWire.Content(
                                                "text",
                                                "MCP_OK:" + System.getenv("ACP_TEST_VALUE") + ":"
                                                        + Path.of("").toAbsolutePath()
                                                        + "x"
                                                                .repeat(Integer.parseInt(System.getenv()
                                                                        .getOrDefault("ACP_TEST_PADDING", "0"))))),
                                        null);
                            default -> new McpWire.Empty();
                        };
                System.out.println(
                        mapper.toString(new McpWire.Envelope("2.0", request.id(), null, null, result, null)));
                System.out.flush();
            }
        }
    }
}
