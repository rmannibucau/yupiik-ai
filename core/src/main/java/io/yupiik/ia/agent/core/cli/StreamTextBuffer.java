package io.yupiik.ia.agent.core.cli;

import java.util.function.UnaryOperator;

/**
 * Buffers streamed text chunks and emits only completed lines so a line-based
 * renderer such as {@link MarkdownColorizer#colorizeLine(String)} keeps
 * fenced-code and inline state consistent across chunk boundaries. The
 * trailing partial line is held until more text arrives or {@link #flush()}
 * is called at the end of the turn.
 */
public class StreamTextBuffer {
    private final StringBuilder pending = new StringBuilder();
    private final UnaryOperator<String> lineRenderer;

    public StreamTextBuffer(final UnaryOperator<String> lineRenderer) {
        this.lineRenderer = lineRenderer;
    }

    /**
     * Buffers a chunk and returns the lines completed by it, rendered and
     * newline-terminated.
     */
    public String append(final String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return "";
        }
        pending.append(chunk);
        return drain(false);
    }

    /**
     * Emits the remaining partial line, if any, terminated by a newline.
     */
    public String flush() {
        return drain(true);
    }

    public void reset() {
        pending.setLength(0);
    }

    private String drain(final boolean flush) {
        final var out = new StringBuilder();
        var newline = pending.indexOf("\n");
        while (newline >= 0) {
            out.append(lineRenderer.apply(pending.substring(0, newline))).append('\n');
            pending.delete(0, newline + 1);
            newline = pending.indexOf("\n");
        }
        if (flush && pending.length() > 0) {
            out.append(lineRenderer.apply(pending.toString()));
            if (out.charAt(out.length() - 1) != '\n') {
                out.append('\n');
            }
            pending.setLength(0);
        }
        return out.toString();
    }
}
