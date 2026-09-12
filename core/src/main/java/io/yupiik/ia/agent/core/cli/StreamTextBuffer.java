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
        if (flush && !pending.isEmpty()) {
            out.append(lineRenderer.apply(pending.toString()));
            if (out.charAt(out.length() - 1) != '\n') {
                out.append('\n');
            }
            pending.setLength(0);
        }
        return out.toString();
    }
}
