package io.yupiik.ia.agent.core.mcp;

import io.yupiik.ia.agent.core.tools.EmbeddedTool;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

class LocalMcpServer implements McpClient {
    private final List<EmbeddedTool> tools;

    LocalMcpServer(final List<EmbeddedTool> tools) {
        this.tools = tools;
    }

    @Override
    public void initialize() {
        // in-process, nothing to do
    }

    @Override
    public List<McpServerTool> listTools() {
        final var out = new ArrayList<McpServerTool>();
        for (final var tool : tools) {
            out.add(new McpServerTool(tool.name(), tool.description(), tool.schema()));
        }
        return out;
    }

    @Override
    public String callTool(final String name, final Map<String, Object> arguments) throws IOException {
        for (final var tool : tools) {
            if (tool.name().equals(name)) {
                final var result = tool.execute(arguments);
                if (!result.success()) {
                    throw new IOException(result.output());
                }
                return result.output();
            }
        }
        throw new IOException("Unknown local tool " + name);
    }

    @Override
    public void close() {
        // nothing to close
    }
}
