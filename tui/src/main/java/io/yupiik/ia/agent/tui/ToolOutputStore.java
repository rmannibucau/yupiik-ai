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
 * bounded FIFO ring (default 128 calls, 256 KiB of output each), optionally
 * bounded by a total character budget. Re-recording an existing ordinal
 * (stream chunks, the finish record) updates it in place without rotating the
 * eviction order, so a long-running tool cannot evict the rest of the ring
 * around itself. Pure and terminal-free so it is unit testable.
 */
public final class ToolOutputStore {
    /** Full, uncapped record of a finished tool call; {@code elapsedMs < 0} marks a still-running call. */
    public record Entry(String name, String args, String output, boolean failed, boolean pending, long elapsedMs) {
        public Entry {
            failed = failed || (elapsedMs >= 0 && !pending && BashExitStatus.failed(name, output));
        }

        /** Whether the call is still running (no final output/elapsed yet). */
        public boolean running() {
            return elapsedMs < 0;
        }

        /** Backward-compatible 5-arg form: a non-pending (terminal) tool result. */
        public Entry(
                final String name, final String args, final String output, final boolean failed, final long elapsedMs) {
            this(name, args, output, failed, false, elapsedMs);
        }
    }

    public static final int DEFAULT_MAX_ENTRIES = 128;
    public static final int DEFAULT_MAX_OUTPUT_CHARS = 256 * 1024;

    private final int maxEntries;
    private final int maxOutputChars;
    private final long maxTotalChars;
    private final Map<Long, Entry> entries = new HashMap<>();
    private final Deque<Long> order = new ArrayDeque<>();
    private long totalChars;
    private long latest = -1;
    private long maxOrdinalSeen = -1;

    public ToolOutputStore() {
        this(DEFAULT_MAX_ENTRIES, DEFAULT_MAX_OUTPUT_CHARS, 0);
    }

    public ToolOutputStore(final int maxEntries, final int maxOutputChars) {
        this(maxEntries, maxOutputChars, 0);
    }

    public ToolOutputStore(final int maxEntries, final int maxOutputChars, final long maxTotalChars) {
        this.maxEntries = Math.max(1, maxEntries);
        this.maxOutputChars = Math.max(1, maxOutputChars);
        this.maxTotalChars = Math.max(0, maxTotalChars);
    }

    public void record(final long ordinal, final Entry entry) {
        if (ordinal < 0 || entry == null) {
            return;
        }
        var output = entry.output() == null ? "" : entry.output();
        if (output.length() > maxOutputChars) {
            output = output.substring(output.length() - maxOutputChars) + "\n... older output truncated";
        }
        final var stored =
                new Entry(entry.name(), entry.args(), output, entry.failed(), entry.pending(), entry.elapsedMs());
        final var replaced = entries.put(ordinal, stored);
        // Not a LinkedHashMap on purpose: the separate insertion deque lets us plug more
        // complex eviction policies later (pinning running ordinals, two-tier aging, LRU...),
        // not just the FIFO one. The guard below is the invariant an insertion-order
        // LinkedHashMap would give for free: re-recording an existing ordinal (stream
        // chunks, the finish record) updates it in place and must never rotate the
        // eviction order, otherwise a long-running tool evicts the whole ring around it.
        if (replaced == null) {
            order.addLast(ordinal);
            totalChars += output.length();
        } else {
            totalChars += output.length() - replaced.output().length();
        }
        if (ordinal > latest) {
            latest = ordinal;
        }
        if (ordinal > maxOrdinalSeen) {
            maxOrdinalSeen = ordinal;
        }
        // FIFO eviction bound by the entry count and, when configured, by the total
        // retained characters: the oldest entries drop first until the byte budget fits.
        while (order.size() > maxEntries || (maxTotalChars > 0 && totalChars > maxTotalChars)) {
            final var evicted = order.removeFirst();
            final var entryEvicted = entries.remove(evicted);
            if (entryEvicted != null) {
                totalChars -= entryEvicted.output().length();
            }
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

    /**
     * @return the highest ordinal ever recorded, even if it was evicted since;
     *     lets the UI tell "captured then evicted" apart from "never captured".
     */
    public long maxOrdinalSeen() {
        return maxOrdinalSeen;
    }

    /** @return the configured per-entry output cap (characters). */
    public int maxOutputChars() {
        return maxOutputChars;
    }

    /** @return how many distinct tool calls are currently retained. */
    public int size() {
        return entries.size();
    }

    /** @return total characters currently retained across all entries (truncation markers included). */
    public long totalChars() {
        return totalChars;
    }
}
