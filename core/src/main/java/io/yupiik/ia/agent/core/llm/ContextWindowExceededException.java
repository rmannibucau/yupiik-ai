package io.yupiik.ia.agent.core.llm;

import java.io.IOException;
import java.util.Locale;

/**
 * Raised when the upstream model provider rejects a request because the prompt (and/or output)
 * exceeds the model's real context window. This is distinct from other 4xx errors so callers can
 * detect the cause and recover (e.g. compact the conversation and retry once) instead of failing.
 */
public class ContextWindowExceededException extends IOException {

    public ContextWindowExceededException() {
        super("context window exceeded");
    }

    /**
     * Heuristic: does this provider error mean the context window was exceeded? Matches on the
     * shared vocabulary used by OpenAI-compatible providers for such errors.
     */
    public static boolean isCause(final int statusCode, final String message) {
        if (statusCode < 400 || message == null || message.isBlank()) {
            return false;
        }
        final var m = message.toLowerCase(Locale.ROOT);
        return m.contains("context length")
                || m.contains("context_length")
                || m.contains("maximum context")
                || m.contains("too many tokens")
                || m.contains("exceeds the model")
                || m.contains("reduce the length");
    }
}