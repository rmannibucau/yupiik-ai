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

import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.shell.ConversationTranscript;
import io.yupiik.ia.agent.core.shell.ThinkTagSplitter;

/**
 * Terminal UI view state on top of the shared {@link ConversationTranscript}:
 * only the scroll offset used by the renderer lives here, all conversation
 * state (transcript, streaming buffers, status, usage counters) is inherited.
 */
public class TuiTranscriptModel extends ConversationTranscript {
    /** Absolute transcript row shown at the top of the viewport, in {@code [0, maxViewOffset]}. */
    private volatile int anchorRow;

    /** Last known max scroll offset (total rows - viewport height), set by the renderer each frame. */
    private volatile int maxViewOffset;

    /** The splitter is injected by Fusion and shared; each transcript gets its own stream parser. */
    public TuiTranscriptModel(final ThinkTagSplitter thinkTags) {
        super(thinkTags);
    }

    private volatile boolean userScrolled;

    @Override
    public synchronized void onEvent(final AgentEvent event) {
        super.onEvent(event);
        if (event instanceof AgentEvent.TextDelta
                || event instanceof AgentEvent.ThinkingDelta
                || event instanceof AgentEvent.ToolStarted
                || event instanceof AgentEvent.ToolFinished
                || event instanceof AgentEvent.PromptIntegrated
                || event instanceof AgentEvent.TurnDone
                || event instanceof AgentEvent.Error) {
            // auto-follow only while the user has not scrolled away: streamed events
            // must never yank the viewport while reasoning/tool history is being read
            if (!userScrolled) {
                anchorRow = maxViewOffset;
            }
        }
    }

    @Override
    public synchronized void clearConversation() {
        super.clearConversation();
        userScrolled = false;
        anchorRow = 0;
        maxViewOffset = 0;
    }

    @Override
    public synchronized void startUserTurn(final String text) {
        startUserTurn(text, true);
    }

    /**
     * Starts a new user turn, optionally re-pinning the view to the live tail.
     * Explicit submissions ({@code pinToBottom=true}) jump to the bottom and re-arm
     * auto-follow. Queued/continuation turns ({@code pinToBottom=false}) leave the
     * scroll position untouched: while the user is browsing, streamed events keep the
     * view where it is, and auto-follow only resumes once the user scrolls back to the
     * bottom.
     */
    public synchronized void startUserTurn(final String text, final boolean pinToBottom) {
        super.startUserTurn(text);
        if (pinToBottom) {
            userScrolled = false;
            anchorRow = maxViewOffset;
        }
    }

    @Override
    public synchronized void reset() {
        super.reset();
        userScrolled = false;
        anchorRow = 0;
        maxViewOffset = 0;
    }

    /** A plain (non thought/tool) markdown segment belonging to one speaker. */
    public record TurnSegment(String text, boolean user) {}

    /**
     * Splits plain transcript markdown into speaker turns: a {@code ---} thematic break
     * followed by a {@code **You**} or {@code **Assistant**} heading starts a new segment
     * ({@code user} tells them apart). Plain {@code ---} breaks that are not followed by a
     * speaker heading stay inside the current segment. Content before the first boundary is
     * assistant. Code fences never reach this method (extracted before rendering).
     */
    public static java.util.List<TurnSegment> splitTurns(final String text) {
        final var lines = text.split("\n", -1);
        final var out = new java.util.ArrayList<TurnSegment>();
        var start = 0;
        var user = false;
        for (var i = 0; i < lines.length; i++) {
            if (!"---".equals(lines[i].strip())) {
                continue;
            }
            var j = i + 1;
            while (j < lines.length && lines[j].isBlank()) {
                j++;
            }
            if (j >= lines.length) {
                continue;
            }
            final var speaker = lines[j].strip();
            if (!speaker.startsWith("**You**") && !speaker.startsWith("**Assistant**")) {
                continue;
            }
            if (i > start) {
                out.add(new TurnSegment(
                        String.join("\n", java.util.Arrays.asList(lines).subList(start, i)), user));
            }
            user = speaker.startsWith("**You**");
            start = j;
        }
        if (start < lines.length) {
            out.add(new TurnSegment(
                    String.join("\n", java.util.Arrays.asList(lines).subList(start, lines.length)), user));
        }
        return out;
    }

    public int anchorRow() {
        return anchorRow;
    }

    public void anchorRow(final int value) {
        this.anchorRow = value;
    }

    public int maxViewOffset() {
        return maxViewOffset;
    }

    public void maxViewOffset(final int value) {
        this.maxViewOffset = value;
    }

    /**
     * Sticky-scrollback flag: {@code true} while the user has explicitly moved the view
     * away from the bottom (wheel, PgUp/PgDn, Ctrl+Home/End, tool/search jumps). While
     * set, streamed events no longer re-pin the view to the bottom; it clears when the
     * view returns to the bottom or an explicit user turn starts.
     */
    public boolean userScrolled() {
        return userScrolled;
    }

    public void userScrolled(final boolean value) {
        this.userScrolled = value;
    }
}
