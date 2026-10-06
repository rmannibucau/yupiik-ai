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
package io.yupiik.ia.agent.tui;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Captures the full (uncapped) output of finished tool calls, keyed by the
 * {@code #N} ordinal embedded in the transcript tool headers, so the UI can
 * expand a truncated tool card into a full-screen overlay. Entries live in a
 * bounded ring (default 128 calls, 256 KiB of output each) to bound memory.
 * Pure and terminal-free so it is unit testable.
 */
public final class ToolOutputStore {
    /** Full, uncapped record of a finished tool call; {@code elapsedMs < 0} marks a still-running call. */
    public record Entry(String name, String args, String output, boolean failed, long elapsedMs) {
        /** Whether the call is still running (no final output/elapsed yet). */
        public boolean running() {
            return elapsedMs < 0;
        }
    }

    public static final int DEFAULT_MAX_ENTRIES = 128;
    public static final int DEFAULT_MAX_OUTPUT_CHARS = 256 * 1024;

    private final int maxEntries;
    private final int maxOutputChars;
    private final Map<Long, Entry> entries = new HashMap<>();
    private final Deque<Long> order = new ArrayDeque<>();
    private long latest = -1;

    public ToolOutputStore() {
        this(DEFAULT_MAX_ENTRIES, DEFAULT_MAX_OUTPUT_CHARS);
    }

    public ToolOutputStore(final int maxEntries, final int maxOutputChars) {
        this.maxEntries = Math.max(1, maxEntries);
        this.maxOutputChars = Math.max(1, maxOutputChars);
    }

    public void record(final long ordinal, final Entry entry) {
        if (ordinal < 0 || entry == null) {
            return;
        }
        var output = entry.output() == null ? "" : entry.output();
        if (output.length() > maxOutputChars) {
            output = output.substring(output.length() - maxOutputChars) + "\n... older output truncated";
        }
        entries.put(ordinal, new Entry(entry.name(), entry.args(), output, entry.failed(), entry.elapsedMs()));
        order.addLast(ordinal);
        while (order.size() > maxEntries) {
            entries.remove(order.removeFirst());
        }
        if (ordinal > latest) {
            latest = ordinal;
        }
    }

    /** @return the full record for the given ordinal, or null. */
    public Entry get(final long ordinal) {
        return entries.get(ordinal);
    }

    /** @return the highest recorded ordinal, or -1 when empty. */
    public long latestOrdinal() {
        return latest;
    }

    public int size() {
        return entries.size();
    }
}
