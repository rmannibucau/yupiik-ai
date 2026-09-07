package io.yupiik.ia.agent.core.tools;

public record ToolResult(boolean success, String output) {

    public static ToolResult ok(final String output) {
        return new ToolResult(true, output);
    }

    public static ToolResult error(final String output) {
        return new ToolResult(false, output);
    }
}
