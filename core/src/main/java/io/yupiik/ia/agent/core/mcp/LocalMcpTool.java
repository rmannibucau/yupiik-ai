package io.yupiik.ia.agent.core.mcp;

import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolResult;

import java.util.Map;

class LocalMcpTool implements EmbeddedTool {
    private final LocalMcpServer server;
    private final EmbeddedTool delegate;

    LocalMcpTool(final LocalMcpServer server, final EmbeddedTool delegate) {
        this.server = server;
        this.delegate = delegate;
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public String description() {
        return delegate.description();
    }

    @Override
    public ToolCategory category() {
        return delegate.category();
    }

    @Override
    public Map<String, Object> schema() {
        return delegate.schema();
    }

    @Override
    public ToolResult execute(final Map<String, Object> args) {
        try {
            return ToolResult.ok(server.callTool(delegate.name(), args));
        } catch (final RuntimeException e) {
            return ToolResult.error("Tool " + delegate.name() + " failed: " + e.getMessage());
        } catch (final Exception e) {
            return ToolResult.error("Tool " + delegate.name() + " failed: " + e.getMessage());
        }
    }
}
