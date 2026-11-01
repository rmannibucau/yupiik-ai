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
package io.yupiik.ia.agent.core.cli;

import io.yupiik.ia.agent.core.context.ContextCompression;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * Buffers streamed text chunks and emits only completed lines so a line-based
 * renderer such as {@link MarkdownColorizer#colorizeLine(String)} keeps
 * fenced-code and inline state consistent across chunk boundaries. The
 * trailing partial line is held until more text arrives or {@link #flush()}
 * is called at the end of the turn.
 *
 * <p>Each completed line is scrubbed of echoed compact "{@code @N@}" seams
 * (see {@link ContextCompression#stripEchoedRefs}) before being rendered. A
 * seam tag can legally split across two incoming chunks, so the scrub runs on
 * the drained line (after fragments are joined) instead of per-chunk, which is
 * the only place the fragments are guaranteed to be contiguous.
 */
public class StreamTextBuffer {
    /** The mirror half of a split seam tag ("4@" after "@1250" was held pending) as its own line:
     * digits then a single '@' with no other content is display noise and must not render. */
    private static final Pattern MIRROR_HALF_LINE = Pattern.compile("[ \\t]*[1-9]\\d*@[ \\t]*");

    private final StringBuilder pending = new StringBuilder();
    private final UnaryOperator<String> lineRenderer;
    private volatile ContextCompression compression = new ContextCompression(null);

    public StreamTextBuffer(final UnaryOperator<String> lineRenderer) {
        this.lineRenderer = lineRenderer;
    }

    /**
     * Rebinds the seam-scrub source once the owning session runtime is available (live refs), so
     * the evidence-gated glued-seam strip keys off the refs the agent loop actually renders. A null
     * argument resets to a fresh instance.
     */
    public void bindCompression(final ContextCompression value) {
        this.compression = value != null ? value : new ContextCompression(null);
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
            out.append(renderLine(pending.substring(0, newline))).append('\n');
            pending.delete(0, newline + 1);
            newline = pending.indexOf("\n");
        }
        if (flush && !pending.isEmpty()) {
            final var rendered = renderLine(pending.toString());
            if (!rendered.isEmpty()) {
                out.append(rendered);
                if (out.charAt(out.length() - 1) != '\n') {
                    out.append('\n');
                }
            }
            pending.setLength(0);
        }
        return out.toString();
    }

    /** Every completed line is scrubbed of echoed compact "{@code @N@}" seams before rendering:
     * complete tags, whole-line bare anchors, the evidence-gated glued-marker form (@Ent...10632)
     * and the mirror half of a split tag ("4@" alone after "@1250" was withheld). The withheld
     * result may be empty - the caller decides whether it still drains as a newline. */
    private String renderLine(final String line) {
        final var scrubbed = compression.withholdDanglingPrefix(line);
        if (scrubbed.isEmpty() || MIRROR_HALF_LINE.matcher(scrubbed).matches()) {
            return ""; // the mirror half of a split tag has no word of its own: hide it entirely
        }
        return lineRenderer.apply(scrubbed);
    }
}
