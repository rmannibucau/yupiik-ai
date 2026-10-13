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
package io.yupiik.ia.agent.web;

import io.yupiik.ia.agent.core.llm.Message;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Replays a session's persisted {@link Message}s as the {@code events} list of a {@code snapshot}
 * SSE frame, so a fresh browser tab seeds its transcript with the existing conversation. The frontend
 * {@code EventLog} reducer understands the emitted event types: {@code user}, {@code text-delta},
 * {@code tool-started} and {@code tool-finished}.
 *
 * <p>System-role messages are skipped: the leading working-directory marker and any notice/compaction
 * markers are display-only and never reach the browser as turns (they are also filtered at the LLM
 * wire boundary upstream).</p>
 */
final class WebReplay {
    private WebReplay() {
        // static utility
    }

    /**
     * @param sessionId the session whose messages are replayed (used as the DTO {@code sessionId})
     * @param messages  the persisted conversation, in order
     * @return replayed {@link WebEventDto}s the browser can render
     */
    static List<WebEventDto> replay(final String sessionId, final List<Message> messages) {
        final var out = new ArrayList<WebEventDto>();
        if (messages == null || messages.isEmpty()) {
            return out;
        }
        final var toolNames = Message.toolNames(messages);
        // per-tool-name count of persisted tool RESULTS (the `tool` role messages that carry real output).
        // The agent persists the assistant message (with its tool_calls) BEFORE the tool actually runs, so
        // an interrupted/cancelled turn can leave an assistant tool call with no matching result in the
        // store. Replaying that as a `tool-started` with no `tool-finished` renders a card stuck "working"
        // forever. We therefore cap the number of started cards per name to the number of finished results,
        // consumed FIFO so each result pairs with the earliest same-name started - matching how the
        // frontend resolves cards by name-deque. A started whose name has no (or no remaining) result is
        // dropped entirely: an aborted tool carries no output worth showing, mirroring the live view.
        final var finishedCounts = new HashMap<String, Integer>();
        for (final var message : messages) {
            if (message == null || !"tool".equals(message.role())) {
                continue;
            }
            final var name = Message.toolName(message, toolNames);
            // legacy sessions may store the tool name as the tool result's callId; Message.toolName already
            // recovers the real name, so the count is keyed by the resolved name (never null for a tool msg).
            finishedCounts.merge(name == null ? "tool" : name, 1, Integer::sum);
        }
        // a cumulative per-call ordinal so replayed tool lifecycle events dedup/correlate exactly like the
        // live stream's: each replayed started card gets its own ordinal and its finished carries the same
        // one (the live runner stamps ordinals from a runtime counter, so a reconnect mid-turn may not
        // rematch an in-flight call; replayed history is always ordered and completed, so the cumulative
        // counter is exact for what is rendered).
        int ordinal = 1;
        for (final var message : messages) {
            if (Message.isProjectMarker(message)) {
                continue;
            }
            final var role = message.role();
            if ("system".equals(role)) {
                continue; // notices / compaction markers: display-only
            }
            if ("user".equals(role)) {
                out.add(new WebEventDto("user", sessionId, null, null, str(message.content()), Map.of()));
                continue;
            }
            if ("assistant".equals(role)) {
                final var text = str(message.content());
                if (text != null && !text.isBlank()) {
                    out.add(new WebEventDto("text-delta", sessionId, null, null, text, Map.of()));
                }
                if (message.toolCalls() != null) {
                    for (final var call : message.toolCalls()) {
                        if (call == null
                                || call.function() == null
                                || call.function().name() == null) {
                            continue;
                        }
                        // only emit a started card while a matching finished result is still available for
                        // this name; otherwise the call was persisted but never completed (interrupted turn)
                        final var remaining =
                                finishedCounts.getOrDefault(call.function().name(), 0);
                        if (remaining <= 0) {
                            continue; // orphan tool call: skip its started so no card ever sticks "working"
                        }
                        finishedCounts.put(call.function().name(), remaining - 1);
                        out.add(new WebEventDto(
                                "tool-started",
                                sessionId,
                                null,
                                null,
                                call.function().name(),
                                Map.of("args", Map.of(), "ordinal", ordinal++)));
                    }
                }
                continue;
            }
            if ("tool".equals(role)) {
                final var name = Message.toolName(message, toolNames);
                final var success = message.others() == null
                        || !Boolean.TRUE.equals(message.others().get("is_error"));
                out.add(new WebEventDto(
                        "tool-finished",
                        sessionId,
                        null,
                        null,
                        name == null ? "tool" : name,
                        Map.of(
                                "output",
                                str(message.content()),
                                "success",
                                success,
                                "ordinal",
                                // a tool result follows its started call in persisted order, so the finished
                                // reuses the last assigned ordinal; Math.max(1,...) keeps it valid when the
                                // very first completed call's started was dropped by the orphan cap above
                                Math.max(1, ordinal - 1))));
            }
        }
        return out;
    }

    private static String str(final Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
