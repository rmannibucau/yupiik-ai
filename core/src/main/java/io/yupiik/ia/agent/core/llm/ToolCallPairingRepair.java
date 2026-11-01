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
package io.yupiik.ia.agent.core.llm;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.agent.ModelRoundProcessor;
import io.yupiik.ia.agent.core.session.SessionEntry;
import io.yupiik.ia.agent.core.session.SessionStore;
import io.yupiik.llm.client.LlmHttpFailureException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Heals a conversation whose assistant {@code tool_calls} blocks are not paired with a
 * CONTIGUOUS run of {@code tool} results. The fix is shared by the agent loop (which persists the
 * synthetic lost-result markers so resumed sessions stay healable) and the /btw side-answerer
 * (which only ever forwards a bounded tail slice of the session and must not write to the store).
 *
 * <p>A single core algorithm drives both entry points: {@link #repair(List)} is the pure in-memory
 * pass, {@link #repairAndPersist(String, List)} delegates to it and appends ONLY the newly-healed
 * markers to the append-only session store.
 *
 * <p>Why the heal exists: Azure / litellm strict validators reject an assistant message whose
 * {@code tool_calls} went unanswered ("an assistant message with 'tool_calls' must be followed by
 * tool messages responding to each 'tool_call_id'"), and Anthropic requires every {@code tool_use}
 * to be immediately answered by a {@code tool_result}. A turn that ended mid-tool
 * (cancel/interrupt/error), or a /btw tail slice that starts in the middle of a tool round, leaves
 * either a dangling assistant block or an orphan tool result - both rejected by the wire. The heal
 * inserts a synthetic lost-result marker right after each dangling block and drops orphan results,
 * so the wire always sees exactly one contiguous result per call id.
 */
@ApplicationScoped
public class ToolCallPairingRepair {
    /** Prefix of the synthetic marker content; {@link java.lang.String#startsWith(String)} can detect it. */
    public static final String TOOL_RESULT_LOST_MARKER = "[tool result lost:";

    /** Full synthetic marker content: honest mid-flight cancellation note, marked as an error. */
    public static final String LOST_RESULT_TEXT =
            "[tool result lost: the model's tool call was interrupted before it could complete this turn]";

    /**
     * Upper bound on the assistant tool call ids a single block can carry before the repair stops
     * tracking them: beyond this a corrupted/oversized assistant block is impossible to heal anyway,
     * so the repair conservatively leaves it untouched rather than blowing up.
     */
    private static final int MAX_REPAIR_PENDING_TOOL_IDS = 256;

    private final SessionStore sessionStore;
    private final Clock clock;

    public ToolCallPairingRepair(final SessionStore sessionStore, final Clock clock) {
        this.sessionStore = sessionStore;
        this.clock = clock;
    }

    /** Outcome of the pure repair pass. {@code messages} is the ORIGINAL list reference when unchanged. */
    public record RepairResult(List<Message> messages, int healed, int droppedOrphans, boolean changed) {}

    /**
     * Pure in-memory repair of a conversation slice (no store writes, no mutation of the input when
     * nothing needs fixing - the returned list is then the input reference itself). The messages are
     * rebuilt so every assistant {@code tool_calls} block is immediately followed by a contiguous run
     * of tool results (keeping existing results, inserting lost-result markers for unanswered calls),
     * and any tool message that does not claim a pending call id (orphan) is dropped.
     *
     * @param messages the conversation to repair.
     * @return the repaired messages (same reference when unchanged) plus repair counters.
     */
    public RepairResult repair(final List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return new RepairResult(messages, 0, 0, false);
        }
        final var repaired = new ArrayList<Message>(messages.size() + 8);
        var droppedOrphans = 0;
        var healed = 0;
        var changed = false;
        var pending = new LinkedHashSet<String>();
        for (final var message : messages) {
            if (!"tool".equals(message.role())) {
                // a non-tool message (a newer assistant reply, a user prompt, a notice, the end of the
                // history) closes the previous assistant block's contiguous run: ids still awaiting a
                // result there are healed with a marker right before whatever now follows.
                if (!pending.isEmpty()) {
                    for (final var id : pending) {
                        repaired.add(Message.tool(id, LOST_RESULT_TEXT, true));
                        healed++;
                        changed = true;
                    }
                    pending = new LinkedHashSet<>();
                }
                repaired.add(message);
                if ("assistant".equals(message.role()) && message.toolCalls() != null) {
                    for (final var call : message.toolCalls()) {
                        if (call != null && call.id() != null && pending.size() < MAX_REPAIR_PENDING_TOOL_IDS) {
                            pending.add(call.id());
                        }
                    }
                }
                continue;
            }
            if (pending.remove(message.toolCallId())) {
                repaired.add(message);
            } else {
                // orphan response: no contiguous assistant tool_calls claim it - Azure/litellm reject
                // "messages with role 'tool' must be a response to a tool call", so it must not reach
                // the wire (a marker for the same id, if needed, is inserted at its assistant block).
                droppedOrphans++;
                changed = true;
            }
        }
        for (final var id : pending) {
            repaired.add(Message.tool(id, LOST_RESULT_TEXT, true));
            healed++;
            changed = true;
        }
        if (!changed) {
            return new RepairResult(messages, 0, 0, false);
        }
        return new RepairResult(repaired, healed, droppedOrphans, true);
    }

    /**
     * Repair pass + persistence: mutates {@code messages} in place, and appends to the append-only
     * session store ONLY the markers whose id carried NO tool response anywhere in the pre-repair
     * history (so a resumed session never appends a fresh orphan marker at the tail on every resume).
     *
     * @param sessionId the session being resumed.
     * @param messages the working conversation, repaired in place.
     * @return the repair outcome; {@code healed} counts the NEWLY persisted markers (matching the
     *     original status semantics: a marker for an id that already had a session entry is not
     *     persisted again and not counted).
     */
    public RepairResult repairAndPersist(final String sessionId, final List<Message> messages) {
        final var repaired = repair(messages);
        if (!repaired.changed()) {
            return repaired;
        }
        // ids that already carried a response somewhere in the pre-repair history: the marker(s) they
        // carried may be strays (dropped by this pass), but their session entry already exists, so the
        // healed id must NOT be persisted again - appending would add a new orphan marker at the tail of
        // the append-only session on every resume.
        final var persisted = new HashSet<String>();
        for (final var message : messages) {
            if ("tool".equals(message.role()) && message.toolCallId() != null) {
                persisted.add(message.toolCallId());
            }
        }
        var persistedMarkers = 0;
        messages.clear();
        messages.addAll(repaired.messages());
        if (sessionStore != null) {
            for (final var message : repaired.messages()) {
                if ("tool".equals(message.role())
                        && message.toolCallId() != null
                        && String.valueOf(message.content()).startsWith(TOOL_RESULT_LOST_MARKER)
                        && persisted.add(message.toolCallId())) {
                    persistedMarkers++;
                    sessionStore.append(
                            sessionId,
                            SessionEntry.of(
                                    clock.millis(),
                                    "tool",
                                    String.valueOf(message.content()),
                                    null,
                                    message.toolCallId(),
                                    null,
                                    Map.of("is_error", true)));
                }
            }
        }
        return new RepairResult(repaired.messages(), persistedMarkers, repaired.droppedOrphans(), true);
    }

    /**
     * Tells whether an LLM failure is the Azure/litellm strict-validator rejection of an assistant
     * {@code tool_calls} block that has no matching {@code tool}-role result ("An assistant message
     * with 'tool_calls' must be followed by tool messages responding to each 'tool_call_id'...").
     * The signature check is owned by {@link ModelRoundProcessor#isToolCallPairingRejected} (single
     * source of truth); this adds the authoritative status-code narrowing and the message-only
     * fallback for failures that do not carry a body.
     *
     * @param failure the thrown low-level request failure (the 400 propagates to the caller as an
     *     {@link java.io.IOException}, possibly a {@link LlmHttpFailureException} carrying the provider body).
     * @return whether the failure is a tool-call pairing rejection that the repair can fix.
     */
    public static boolean isPairingRejection(final Throwable failure) {
        // prefer the typed failure: the status code is authoritative and never depends on
        // message formatting ("LLM request failed (400)" is a bare prefix heuristic)
        if (failure instanceof LlmHttpFailureException httpFailure) {
            return httpFailure.status() == 400 && ModelRoundProcessor.isToolCallPairingRejected(httpFailure);
        }
        final var message = failure == null ? null : failure.getMessage();
        if (message == null || !message.startsWith("LLM request failed (400)")) {
            return false;
        }
        if (failure instanceof LlmHttpFailureException httpFailure
                && ModelRoundProcessor.isToolCallPairingRejected(httpFailure)) {
            return true;
        }
        final var lowerTxt = message.toLowerCase(Locale.ROOT);
        return lowerTxt.contains("tool_calls")
                && (lowerTxt.contains("must be followed")
                        || lowerTxt.contains("no response message")
                        || lowerTxt.contains("did not have")
                        || lowerTxt.contains("without tool messages"));
    }
}
