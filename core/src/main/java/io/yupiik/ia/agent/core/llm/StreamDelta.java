package io.yupiik.ia.agent.core.llm;

public record StreamDelta(Type type, String text, ToolCall toolCall, Usage usage, String finishReason) {

    public enum Type {TEXT, REASONING, TOOL_CALL, USAGE, FINISH}

    public static StreamDelta text(final String text) {
        return new StreamDelta(Type.TEXT, text, null, null, null);
    }

    public static StreamDelta reasoning(final String text) {
        return new StreamDelta(Type.REASONING, text, null, null, null);
    }

    public static StreamDelta toolCall(final ToolCall call) {
        return new StreamDelta(Type.TOOL_CALL, null, call, null, null);
    }

    public static StreamDelta usage(final Usage usage) {
        return new StreamDelta(Type.USAGE, null, null, usage, null);
    }

    public static StreamDelta finish(final String reason) {
        return new StreamDelta(Type.FINISH, null, null, null, reason);
    }
}
