package io.yupiik.ia.agent.core.mcp;

import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolResult;

import java.util.Map;

class McpEmbeddedTool implements EmbeddedTool {
    private static final java.util.regex.Pattern UNSAFE_NAME = java.util.regex.Pattern.compile("[^A-Za-z0-9_-]");

    private final McpManager manager;
    private final String serverName;
    private final McpServerTool tool;
    private final String qualifiedName;

    McpEmbeddedTool(final McpManager manager, final String serverName, final McpServerTool tool) {
        this.manager = manager;
        this.serverName = serverName;
        this.tool = tool;
        this.qualifiedName = sanitize(serverName) + "_" + sanitize(tool.name());
    }

    private static String sanitize(final String s) {
        return UNSAFE_NAME.matcher(s).replaceAll("_");
    }

    @Override
    public String name() {
        return qualifiedName;
    }

    @Override
    public String description() {
        return "[" + serverName + "] " + (tool.description() == null ? tool.name() : tool.description());
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.MCP;
    }

    @Override
    public Map<String, Object> schema() {
        return tool.inputSchema() == null ? Map.of("type", "object", "properties", Map.of()) : tool.inputSchema();
    }

    @Override
    public boolean readonly() {
        return manager.isReadOnly(serverName, tool.name());
    }

    @Override
    public ToolResult execute(final Map<String, Object> args) {
        try {
            return ToolResult.ok(manager.call(serverName, tool.name(), args));
        } catch (final RuntimeException e) {
            return ToolResult.error("MCP tool " + qualifiedName + " failed: " + e.getMessage());
        } catch (final Exception e) {
            return ToolResult.error("MCP tool " + qualifiedName + " failed: " + e.getMessage());
        }
    }
}
