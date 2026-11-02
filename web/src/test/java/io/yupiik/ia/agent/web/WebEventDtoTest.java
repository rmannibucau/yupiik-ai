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

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.llm.Usage;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Mapping of the tool lifecycle events to the flat SSE DTO the browser consumes. */
class WebEventDtoTest {

    @Test
    void toolStreamCarriesNameAndFragment() {
        final var dto = WebEventDto.of(new AgentEvent.ToolStream("bash", "line 1", "s1", null, null));
        assertEquals("tool-stream", dto.type());
        // message = tool name (the frontend binds stream fragments to the running card by name)
        assertEquals("bash", dto.message());
        // the streamed fragment travels in data.fragment
        assertEquals("line 1", dto.data().get("fragment"));
        assertEquals(true, dto.data().get("stream"));
    }

    @Test
    void toolStartedAndFinishedCarryTheSameName() {
        final var started = WebEventDto.of(new AgentEvent.ToolStarted("write_file", "{}", 1, "s1", null, null));
        final var finished = WebEventDto.of(new AgentEvent.ToolFinished("write_file", "ok", true, "s1", null, null));
        assertEquals("tool-started", started.type());
        assertEquals("write_file", started.message());
        assertEquals(1L, started.data().get("ordinal"));
        assertEquals("tool-finished", finished.type());
        assertEquals("write_file", finished.message());
        assertEquals("ok", finished.data().get("output"));
        assertEquals(true, finished.data().get("success"));
        assertEquals(false, finished.data().get("pending"));
    }

    @Test
    void pendingToolFinishedCarriesThePendingFlag() {
        // a pending tool (e.g. a collect wait that timed out) must surface as pending so the web chip
        // renders it neutral rather than a hard failure
        final var finished = WebEventDto.of(
                new AgentEvent.ToolFinished("check_subagents", "pending", false, true, 3, "s1", null, null));
        assertEquals("tool-finished", finished.type());
        assertEquals(false, finished.data().get("success"));
        assertEquals(true, finished.data().get("pending"));
    }

    @Test
    void subagentEventsCarryIdentityAndModel() {
        final var started = WebEventDto.of(new AgentEvent.SubagentStarted("do the thing", "s1", "sub-1", "child"));
        assertEquals("subagent-started", started.type());
        assertEquals("do the thing", started.message());
        assertEquals("sub-1", started.data().get("subagentId"));
        assertEquals("child", started.data().get("subagentName"));
        assertEquals(null, started.data().get("model"), "the model is unresolved at spawn");

        final var completed = WebEventDto.of(
                new AgentEvent.SubagentCompleted("done", true, false, "s1", "sub-1", "child", "gpt-5.5"));
        assertEquals("subagent-completed", completed.type());
        assertEquals("done", completed.message());
        assertEquals(true, completed.data().get("success"));
        assertEquals(false, completed.data().get("cancelled"));
        assertEquals("sub-1", completed.data().get("subagentId"));
        assertEquals("child", completed.data().get("subagentName"));
        assertEquals("gpt-5.5", completed.data().get("model"), "the effective model must ride the payload");
    }

    @Test
    void usageUpdatedExposesLiveCompletionAndReasoningTokens() {
        final var dto = WebEventDto.of(new AgentEvent.UsageUpdated(
                new Usage(10, 120, 130, 0, 0, 40, 0),
                50,
                2.0,
                0,
                -1,
                "model-x",
                "s1",
                null,
                null,
                AgentEvent.UsageUpdated.Quota.NONE));
        assertEquals("usage-updated", dto.type());
        assertEquals("model-x", dto.message());
        assertEquals(120, dto.data().get("completionTokens"));
        // reasoning is carried for the frontend's live per-turn "out + reasoning" display
        assertEquals(40, dto.data().get("reasoningTokens"));
        assertEquals(50, dto.data().get("estimatedContextTokens"));
        assertEquals(2.0, dto.data().get("contextPercent"));
    }

    @Test
    void compactedCarriesTheMergedKindsAndMode() {
        // several reductions of the same checkpoint are merged into one event: the message keeps
        // the flat "kinds|mode" shape with the comma-joined kinds, so the web frontend can split
        // on the pipe without re-parsing per-kind payloads
        final var dto = WebEventDto.of(new AgentEvent.Compacted(List.of("dedup", "prune"), "CLEARED", "s1"));
        assertEquals("compacted", dto.type());
        assertEquals("dedup,prune|CLEARED", dto.message());
    }

    @Test
    void compactionNoticesCarryTheLifecycleMarker() {
        // the compaction lifecycle (core A) surfaces to the browser as a compacting flag on the
        // notice frames: the start notice arms it, the result notices (compacted/gate) clear it.
        final var start = WebEventDto.of(
                AgentEvent.Notice.compactionStart("Compaction in progress... (\u2248 51234 estimated tokens)", "s1"));
        assertEquals("notice", start.type());
        assertEquals(true, start.data().get("compacting"));

        final var done = WebEventDto.of(AgentEvent.Notice.compacted("Compacted: context 68% -> 41%", "s1"));
        assertEquals(false, done.data().get("compacting"));

        final var gate = WebEventDto.of(AgentEvent.Notice.compactionGateEscalated(
                "Compaction gate: the context cannot be compacted below the trigger.", "s1"));
        assertEquals(false, gate.data().get("compacting"), "an escalation must clear the flag");

        // a non-compaction notice never flips the flag (absent, not false)
        final var unrelated =
                WebEventDto.of(new AgentEvent.Notice(AgentEvent.Notice.Level.WARN, "retrying (1/3)", "s1"));
        assertEquals(null, unrelated.data().get("compacting"));
    }
}
