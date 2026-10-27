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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.core.llm.Message;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Replaying a persisted session must restore thought blocks, not only the visible answer. */
class WebReplayTest {

    @Test
    void persistedThinkingIsReplayedAsThinkingDeltaBeforeTheAnswer() {
        final var messages = new ArrayList<Message>();
        messages.add(Message.user("do it"));
        messages.add(new Message(
                "assistant", "the answer", null, null, null, Map.of("thinking", "let me think about this")));

        final var events = WebReplay.replay("s1", messages);

        final var types = events.stream().map(WebEventDto::type).toList();
        assertEquals(List.of("user", "thinking-delta", "text-delta"), types);
        // the thinking must precede the answer so the reducer groups it as a thought block above it
        assertEquals("let me think about this", events.get(1).message());
        assertEquals("the answer", events.get(2).message());
    }

    @Test
    void assistantWithoutThinkingStillReplaysPlainly() {
        final var messages = new ArrayList<Message>();
        messages.add(Message.user("do it"));
        messages.add(Message.assistant("plain answer", null));

        final var events = WebReplay.replay("s1", messages);

        final var types = events.stream().map(WebEventDto::type).toList();
        assertEquals(List.of("user", "text-delta"), types);
        assertTrue(events.stream().noneMatch(e -> "thinking-delta".equals(e.type())));
    }

    @Test
    void blankThinkingIsSkipped() {
        final var messages = new ArrayList<Message>();
        messages.add(new Message("assistant", "answer", null, null, null, Map.of("thinking", "  \n")));

        final var events = WebReplay.replay("s1", messages);

        assertEquals(
                List.of("text-delta"), events.stream().map(WebEventDto::type).toList());
    }

    @Test
    void legacyUnScrubbedPersistedThinkingIsPassedThroughVerbatim() {
        // WebReplay is a faithful mirror: it does not scrub. A legacy persisted thinking that
        // predates the AgentLoop persistence scrub is passed through as-is; the frontend render
        // chokepoint (stripEchoedRefs in the Markdown component) is what hides any remnant.
        final var messages = new ArrayList<Message>();
        messages.add(Message.user("hi"));
        messages.add(
                new Message("assistant", "the answer", null, null, null, Map.of("thinking", "reasoning @42@ here")));

        final var events = WebReplay.replay("s1", messages);

        final var types = events.stream().map(WebEventDto::type).toList();
        assertEquals(List.of("user", "thinking-delta", "text-delta"), types);
        assertEquals("reasoning @42@ here", events.get(1).message());
        assertEquals("the answer", events.get(2).message());
    }

    @Test
    void scrubbedPersistedThinkingReplaysWithoutAnySeam() {
        // AgentLoop strips echoed "@N@" seams at persistence, so a clean stored message must
        // replay without inventing a seam either.
        final var messages = new ArrayList<Message>();
        messages.add(Message.user("hi"));
        messages.add(new Message(
                "assistant", "the answer", null, null, null, Map.of("thinking", "reasoning carefully here")));

        final var events = WebReplay.replay("s1", messages);

        final var types = events.stream().map(WebEventDto::type).toList();
        assertEquals(List.of("user", "thinking-delta", "text-delta"), types);
        assertTrue(events.stream()
                .filter(e -> e.message() != null)
                .noneMatch(e -> e.message().contains("@42@")));
    }
}
