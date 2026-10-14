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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class LlmWireTest {
    @Test
    void wireNeverContainsTheSpeakerIdentity() {
        final var m = Message.withIdentity(Message.assistant("a", List.of()), "p", "m", null);
        final var wire = LlmWire.toLlmMessage(m);
        assertFalse(wire.extensions().containsKey(Message.IDENTITY_KEY), "identity must be stripped on the wire");
        // unrelated others keys are preserved
        final var withOther = new Message(
                "user", "x", null, null, null, Map.of("is_error", true, Message.IDENTITY_KEY, Map.of("provider", "p")));
        final var otherWire = LlmWire.toLlmMessage(withOther);
        assertFalse(otherWire.extensions().containsKey(Message.IDENTITY_KEY));
        assertFalse(otherWire.extensions().isEmpty());
    }

    /**
     * P4: inline {@code  thinking} blocks (GLM-style / Qwen templates without a dedicated
     * reasoning channel) must be stripped before the assistant message enters the conversation,
     * otherwise the thoughts are persisted and replayed verbatim on the next request, making the
     * model reconstruct them. The visible text around the blocks is preserved.
     */
    @Test
    void visibleTextStripsInlineThinkingBlocks() {
        final var raw = new StringBuilder(
                "let me check the pom\n think the build fails because of the plugin\n think\nso I edit it");
        final var stripped = LlmWire.visibleText(raw);
        org.junit.jupiter.api.Assertions.assertFalse(
                stripped.contains("think"), "inline thinking must be removed: " + stripped);
        org.junit.jupiter.api.Assertions.assertTrue(stripped.contains("let me check the pom"), stripped);
        org.junit.jupiter.api.Assertions.assertTrue(stripped.contains("so I edit it"), stripped);
    }

    /**
     * Item 3: the non-streaming {@code toChatResult} path must apply the same reasoning strip as the
     * streaming one, so a stored / resumed assistant message never carries inline GLM/Qwen reasoning.
     */
    @Test
    void toMessageStripsInlineReasoningOnTheNonStreamPath() {
        final var wire = new io.yupiik.llm.client.model.LlmMessage(
                "assistant", "head\n think reasoning here\n response tail", null, null, null, Map.of());
        final var message = LlmWire.toMessage(wire);
        org.junit.jupiter.api.Assertions.assertEquals("head\n response tail", message.content());
    }

    /**
     * An interrupt pressed while the provider delivers no further frame (silent generation / a long
     * single chunk) must abandon the stream through the bounded-wait path: {@code pump} must cancel
     * the subscription before returning a partial result, so the caller's concurrency permit release
     * (which hooks {@code cancel()}) fires instead of silently leaking the slot.
     */
    @Test
    void pumpAbortsAndCancelsSubscriptionOnNoFrameInterrupt() throws Exception {
        final var cancelled = new AtomicBoolean(false);
        final var subscriptionCancelled = new AtomicBoolean(false);
        final Flow.Publisher<io.yupiik.llm.client.model.LlmStreamEvent> silent =
                subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                    @Override
                    public void request(final long n) {
                        // never emit a frame, never complete: only the bounded-wait abort path ends it
                    }

                    @Override
                    public void cancel() {
                        subscriptionCancelled.set(true);
                    }
                });

        final var result = CompletableFuture.supplyAsync(() -> {
            try {
                return LlmWire.pump(silent, event -> {}, cancelled::get);
            } catch (final Exception e) {
                throw new RuntimeException(e);
            }
        });

        // let the first bounded-wait timeout elapse with cancellation still off
        Thread.sleep(250);
        assertFalse(result.isDone(), "with no cancel the silent stream must keep waiting");
        // now interrupt: the no-frame abort path must cancel the subscription and end the pump
        cancelled.set(true);
        final var message = result.get(5, TimeUnit.SECONDS);
        assertNotNull(message, "pump must return a partial message on interrupt");
        assertTrue(subscriptionCancelled.get(), "the no-frame abort must cancel the subscription");
    }
}
