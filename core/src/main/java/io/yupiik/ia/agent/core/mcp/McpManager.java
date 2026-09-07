package io.yupiik.ia.agent.core.mcp;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.McpServerConfiguration;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
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
    private final Map<String, McpClient> clients = new ConcurrentHashMap<>();
    private final Map<String, List<EmbeddedTool>> toolsByServer = new ConcurrentHashMap<>();

    public McpManager(final CodingAgentConfiguration configuration, final JsonMapper mapper,
                      final List<EmbeddedTool> localTools) {
        this.configuration = configuration;
        this.mapper = mapper;
        this.localTools = localTools;
    }

    protected McpManager() {
        this.configuration = null;
        this.mapper = null;
        this.localTools = null;
    }

    public void start() {
        if (configuration.mcp().enabled()) {
            final var local = new LocalMcpServer(localTools);
            try {
                local.initialize();
                clients.put("yca", local);
                final var imported = new ArrayList<EmbeddedTool>();
                for (final var tool : local.listTools()) {
                    imported.add(new LocalMcpTool(local, localTools.stream()
                            .filter(t -> t.name().equals(tool.name()))
                            .findFirst()
                            .orElseThrow()));
                }
                toolsByServer.put("yca", imported);
            } catch (final RuntimeException e) {
                LOGGER.warning("Local tool server registration failed: " + e.getMessage());
            }
        }
        for (final var server : configuration.mcp().servers()) {
            if (!server.enabled()) {
                continue;
            }
            try {
                final McpClient client;
                if (server.url() != null && !server.url().isBlank()) {
                    client = new HttpMcpClient(server.name(), server.url(), server.apiKey(), server.headers(),
                            configuration.mcp().callTimeoutSeconds(), mapper);
                } else if (server.command() != null && !server.command().isBlank()) {
                    final var command = new ArrayList<String>();
                    command.add(server.command());
                    if (server.args() != null) {
                        command.addAll(server.args());
                    }
                    client = new StdioMcpClient(server.name(), command, server.env() == null ? List.of() : server.env(), mapper);
                } else {
                    LOGGER.warning("MCP server " + server.name() + " has neither url nor command, skipped");
                    continue;
                }
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
    }

    public List<EmbeddedTool> tools() {
        final var out = new ArrayList<EmbeddedTool>();
        toolsByServer.values().forEach(out::addAll);
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

    public record ServerStatus(String name, ServerState state) {
    }

    public List<ServerStatus> serverStatuses() {
        final var out = new ArrayList<ServerStatus>();
        final var mcp = configuration.mcp();
        if (mcp != null && mcp.enabled()) {
            if (clients.containsKey("yca")) {
                out.add(new ServerStatus("yca", ServerState.ENABLED));
            } else {
                out.add(new ServerStatus("yca", ServerState.FAILED));
            }
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
}
