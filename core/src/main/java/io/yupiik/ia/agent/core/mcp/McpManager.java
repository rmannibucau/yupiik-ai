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

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.lifecycle.Destroy;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.McpServerConfiguration;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.maven.knowledge.process.ProcessService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

@ApplicationScoped
public class McpManager {
    private static final Logger LOGGER = Logger.getLogger(McpManager.class.getName());

    private final CodingAgentConfiguration configuration;
    private final JsonMapper mapper;
    private final List<EmbeddedTool> localTools;
    private final ExtendedHttpClient httpClient;
    private final ProcessService processes;
    private final Map<String, McpClient> clients = new ConcurrentHashMap<>();
    private final Map<String, List<EmbeddedTool>> toolsByServer = new ConcurrentHashMap<>();

    public McpManager(
            final CodingAgentConfiguration configuration,
            final JsonMapper mapper,
            final List<EmbeddedTool> localTools,
            final ExtendedHttpClient httpClient,
            final ProcessService processes) {
        this.configuration = configuration;
        this.mapper = mapper;
        this.localTools = localTools;
        this.httpClient = httpClient;
        this.processes = processes;
    }

    protected McpManager() {
        this.configuration = null;
        this.mapper = null;
        this.localTools = null;
        this.httpClient = null;
        this.processes = null;
    }

    public void start() {
        if (configuration.tools().builtin().enabled()) {
            startLocal();
        }
        for (final var server : configuration.mcp().servers()) {
            if (server.enabled()) {
                startServer(server);
            }
        }
    }

    /**
     * Restart every enabled {@link McpClient} (built-in local tools included):
     * the previous client is closed and removed then a fresh one is created and
     * initialized, and its tools are re-imported.
     */
    public void reset() {
        if (configuration.tools().builtin().enabled()) {
            startLocal();
        }
        for (final var server : configuration.mcp().servers()) {
            if (server.enabled()) {
                startServer(server);
            }
        }
    }

    /**
     * Restart a single server by name (either a configured one or the built-in
     * {@code yca} one). Existing tools keep working during the swap since they
     * delegate to {@link #call(String, String, Map)}.
     *
     * @param name the server name.
     */
    public void reset(final String name) {
        if ("yca".equals(name)) {
            if (!configuration.tools().builtin().enabled()) {
                throw new IllegalArgumentException("the built-in 'yca' MCP server is disabled");
            }
            startLocal();
            return;
        }
        final var server = configuration.mcp().servers().stream()
                .filter(candidate -> name.equals(candidate.name()))
                .findFirst()
                .orElseThrow(() ->
                        new IllegalArgumentException("unable to reset MCP server '" + name + "': no such server"));
        if (!server.enabled()) {
            throw new IllegalArgumentException("MCP server '" + name + "' is disabled");
        }
        startServer(server);
    }

    private void startLocal() {
        close("yca");
        toolsByServer.remove("yca");
        // Built-in tools are served in-process: no MCP round-trip, error ToolResult(s) keep their exact output.
        // An optional allow/deny-list slims the wire tool schemas (see features/provider-tuning).
        final var builtin = configuration.tools().builtin();
        final var filtered = localTools == null
                ? List.<EmbeddedTool>of()
                : localTools.stream()
                        .filter(t -> builtin.keeps(t.name()))
                        .collect(java.util.stream.Collectors.toList());
        toolsByServer.put("yca", new ArrayList<>(filtered));
    }

    private void startServer(final McpServerConfiguration server) {
        close(server.name());
        toolsByServer.remove(server.name());
        try {
            final var client = buildClient(server);
            client.initialize();
            clients.put(server.name(), client);
            final var imported = new ArrayList<EmbeddedTool>();
            for (final var tool : client.listTools()) {
                imported.add(new McpEmbeddedTool(this, server.name(), tool));
            }
            toolsByServer.put(server.name(), imported);
            LOGGER.info("MCP server " + server.name() + " ready, " + imported.size() + " tool(s)");
        } catch (final IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOGGER.warning("MCP server " + server.name() + " failed: " + e.getMessage());
        }
    }

    private McpClient buildClient(final McpServerConfiguration server) {
        if (server.url() != null && !server.url().isBlank()) {
            return new HttpMcpClient(
                    server.name(),
                    server.url(),
                    server.apiKey(),
                    server.headers(),
                    configuration.mcp().callTimeoutSeconds(),
                    mapper,
                    httpClient);
        }
        if (server.command() != null && !server.command().isBlank()) {
            final var command = new ArrayList<String>();
            command.add(server.command());
            if (server.args() != null) {
                command.addAll(server.args());
            }
            return new StdioMcpClient(
                    server.name(), command, server.env() == null ? List.of() : server.env(), mapper, processes);
        }
        throw new IllegalArgumentException("MCP server " + server.name() + " has neither url nor command");
    }

    private void close(final String name) {
        final var previous = clients.remove(name);
        if (previous != null) {
            try {
                previous.close();
            } catch (final RuntimeException e) {
                LOGGER.warning("MCP server " + name + " close failed: " + e.getMessage());
            }
        }
    }

    public List<EmbeddedTool> tools() {
        final var out = new ArrayList<EmbeddedTool>();
        toolsByServer.values().forEach(out::addAll);
        return out;
    }

    public Map<String, List<EmbeddedTool>> toolsByServer() {
        final var out = new LinkedHashMap<String, List<EmbeddedTool>>();
        toolsByServer.forEach((server, serverTools) -> out.put(server, List.copyOf(serverTools)));
        return out;
    }

    public boolean isReadOnly(final String serverName, final String toolName) {
        if (configuration.mcp() == null || configuration.mcp().servers() == null) {
            return false;
        }
        return configuration.mcp().servers().stream()
                .filter(server -> serverName.equals(server.name()))
                .findFirst()
                .map(server -> server.isReadOnly(toolName))
                .orElse(false);
    }

    public enum ServerState {
        ENABLED,
        DISABLED,
        FAILED
    }

    public record ServerStatus(String name, ServerState state) {}

    public List<ServerStatus> serverStatuses() {
        final var out = new ArrayList<ServerStatus>();
        final var mcp = configuration.mcp();
        if (configuration.tools().builtin().enabled()) {
            out.add(new ServerStatus("yca", ServerState.ENABLED));
        }
        if (mcp != null && mcp.servers() != null) {
            for (final var server : mcp.servers()) {
                if (!server.enabled()) {
                    out.add(new ServerStatus(server.name(), ServerState.DISABLED));
                } else if (clients.containsKey(server.name())) {
                    out.add(new ServerStatus(server.name(), ServerState.ENABLED));
                } else {
                    out.add(new ServerStatus(server.name(), ServerState.FAILED));
                }
            }
        }
        return out;
    }

    public String call(final String serverName, final String toolName, final Map<String, Object> args) {
        final var client = clients.get(serverName);
        if (client == null) {
            throw new IllegalStateException("MCP server " + serverName + " is not started");
        }
        try {
            return client.callTool(toolName, args);
        } catch (final IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new UncheckedIOException(new IOException(e.getMessage(), e));
        }
    }

    /**
     * Close every running {@link McpClient} (stdio subprocesses and stream-backed clients included)
     * and drop the cached tool metadata. Invoked as an application-scoped bean destroy so MCP
     * subprocesses are torn down on application shutdown, not only on restart/reset.
     */
    @Destroy
    public void close() {
        clients.values().forEach(this::quietClose);
        clients.clear();
        toolsByServer.clear();
    }

    private void quietClose(final McpClient client) {
        try {
            client.close();
        } catch (final RuntimeException e) {
            LOGGER.warning("MCP client close failed: " + e.getMessage());
        }
    }
}
