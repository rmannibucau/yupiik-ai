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
package io.yupiik.ia.agent.core.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.agent.FeedbackTiming;
import io.yupiik.ia.agent.core.llm.Message;
import java.util.List;
import org.junit.jupiter.api.Test;

class FeedbackTranscriptTest {
    @Test
    void pendingFlushAndFinalKeepTheSameHeadingButNextFeedbackGetsANewOne() {
        final var transcript = transcript();
        transcript.startUserTurn("hi");
        transcript.onEvent(new AgentEvent.FeedbackStarted("text", 1000, "s", null, null));
        transcript.onEvent(new AgentEvent.TextDelta("first", "s"));
        final var pending = transcript.markdown("", "");
        assertTrue(pending.contains("**[1000] Assistant**\n\nfirst"), pending);
        assertSame(pending, transcript.markdown("", ""));
        transcript.onEvent(new AgentEvent.ToolStarted("read", "{}", "s"));
        transcript.onEvent(new AgentEvent.FeedbackStarted("thinking", 2000, "s", null, null));
        transcript.onEvent(new AgentEvent.ThinkingDelta("why", "s"));
        assertEquals("[2000] Assistant - Thinking", transcript.thinkingLabel());
        transcript.onEvent(new AgentEvent.FeedbackStarted("text", 3000, "s", null, null));
        transcript.onEvent(new AgentEvent.TextDelta("last", "s"));
        transcript.onEvent(new AgentEvent.TurnDone("last", "s"));
        final var result = transcript.markdown("", "");
        assertEquals(1, result.split("\\Q**[1000] Assistant**\\E", -1).length - 1, result);
        assertEquals(1, result.split("\\Q**[3000] Assistant**\\E", -1).length - 1, result);
        assertTrue(result.indexOf("first") < result.indexOf("why"), result);
        assertTrue(result.indexOf("why") < result.indexOf("last"), result);
    }

    @Test
    void replayUsesCapturedTimeAndLegacyNeverUsesNow() {
        final var transcript = transcript();
        final var timed =
                new Message("assistant", "answer", null, null, null, FeedbackTiming.stamp(null, "text", 1000));
        final var replay = transcript.replay(List.of(timed, Message.assistant("legacy", null)));
        assertTrue(replay.contains("**[1000] Assistant**\n\nanswer"), replay);
        assertTrue(replay.contains("**Assistant**\n\nlegacy"), replay);
    }

    private ConversationTranscript transcript() {
        final var transcript = new ConversationTranscript();
        transcript.setFeedbackLabelResolver(
                (identity, user, at) -> (at == null ? "" : "[" + at + "] ") + (user ? "You" : "Assistant"));
        return transcript;
    }
}
