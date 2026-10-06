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
import io.yupiik.maven.knowledge.process.ProcessService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies that closing a stdio MCP client kills and reaps the spawned server through the shared
 * ProcessService (previously {@code close()} only sent SIGTERM without waiting, orphaning servers
 * that ignore it). The fake server answers {@code initialize} then traps TERM and keeps running.
 */
@ExtendWith(TestJsonMapper.class)
class StdioMcpClientTest {

    @Test
    void closeDisposesTheSpawnedServer(@TempDir final Path tmp, final JsonMapper mapper) throws Exception {
        Assumptions.assumeTrue(!System.getProperty("os.name", "").toLowerCase().contains("win"));
        final var service = new ProcessService();
        service.reapGrace(300);

        final var script = tmp.resolve("mcp-server.sh");
        Files.writeString(
                script,
                "#!/bin/sh\n"
                        + "read line\n"
                        + "id=$(printf '%s' \"$line\" | sed -n 's/.*\"id\":\\([0-9][0-9]*\\).*/\\1/p')\n"
                        + "printf '{\"jsonrpc\":\"2.0\",\"id\":%s,\"result\":{\"protocolVersion\":\"2025-06-18\","
                        + "\"capabilities\":{},\"serverInfo\":{\"name\":\"x\",\"version\":\"1\"}}}\\n' \"$id\"\n"
                        + "trap '' TERM\n"
                        + "while :; do sleep 1; done\n");
        script.toFile().setExecutable(true);

        final var client =
                new StdioMcpClient("test", List.of("/bin/sh", script.toString()), List.of(), mapper, service);
        client.initialize();
        assertEquals(1, service.trackedCount(), "the MCP server must be tracked");

        client.close();
        assertEquals(0, service.trackedCount(), "close() must dispose the spawned server through ProcessService");
    }
}
