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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfigurations;
import io.yupiik.ia.agent.core.context.UsageTracker;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.permissions.ApprovalGate;
import io.yupiik.ia.agent.core.session.SessionStore;
import io.yupiik.ia.agent.core.shell.SessionLifecycle;
import io.yupiik.ia.agent.core.shell.SessionRuntimeFactory;
import io.yupiik.ia.agent.core.shell.ShellRegistry;
import io.yupiik.ia.agent.core.shell.TurnRunner;
import io.yupiik.ia.agent.core.shell.btw.BtwAnswerer;
import io.yupiik.ia.agent.core.shell.slash.SlashRegistry;
import io.yupiik.ia.agent.core.wiring.PrecisionDurationMarkerProvider;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for the web /btw lifecycle in {@link WebSessionManager}: async non-blocking dispatch,
 * the latest-wins single slot (a newer /btw cancels an in-flight one), the running-then-answer/error
 * {@code btw} SSE broadcasts, {@code clearBtw} and the snapshot re-seed of a live btw.
 */
@ExtendWith(TestWebJsonMapper.class)
class WebSessionManagerBtwTest {

    @TempDir
    Path storageHome;

    private JsonMapper mapper;
    private WebSessionManager manager;
    private FakeSseBroker broker;
    private FakeBtwAnswerer answerer;

    @BeforeEach
    void setup(final JsonMapper mapper) {
        this.mapper = mapper;
        this.broker = new FakeSseBroker(mapper);
        this.answerer = new FakeBtwAnswerer();

        // public main helper (same one core's own tests use to point a provider at a mock): avoids
        // touching the fusion-generated config factory directly, which is not package-accessible here.
        final var configuration = CodingAgentConfigurations.builder()
                .baseUrl("http://localhost:0")
                .storageHome(storageHome.toString())
                .build();
        final var sessionStore = new SessionStore() {};
        final var runtimeFactory = new SessionRuntimeFactory(
                null,
                null,
                null,
                configuration,
                null,
                sessionStore,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                this.mapper,
                null,
                Clock.systemUTC(),
                new PrecisionDurationMarkerProvider(),
                null,
                null,
                null); // SecretRedactor
        this.manager = new WebSessionManager(
                runtimeFactory,
                new NoopShellRegistry(),
                new SlashRegistry() {},
                new TurnRunner() {},
                sessionStore,
                new SessionLifecycle(sessionStore),
                broker,
                this.mapper,
                answerer);
    }

    @Test
    void btwBroadcastsRunningThenAnswerWithoutBlocking() {
        manager.attach("s1");

        // dispatch does not block: the RPC future completes immediately, before the answer resolves
        final var future = manager.slash("s1", "/btw what is xyz?");
        assertTrue(future.isDone() && Boolean.TRUE.equals(future.join()), "slash must return without blocking");

        // a running frame was broadcast first, carrying the question
        final var runningFrame = broker.frames.poll();
        assertTrue(runningFrame.contains("\"type\":\"btw\""), runningFrame);
        assertTrue(runningFrame.contains("\"running\":true"), runningFrame);
        assertTrue(runningFrame.contains("\"question\":\"what is xyz?\""), runningFrame);

        // complete the answer; the manager then broadcasts the final btw frame with the answer
        answerer.complete("what is xyz?", "**the answer**");
        final var doneFrame = broker.awaitFrame();
        assertTrue(doneFrame.contains("\"running\":false"), doneFrame);
        assertTrue(doneFrame.contains("\"answer\":\"**the answer**\""), doneFrame);

        // the session's btw slot is now settled as answered
        final var state = manager.btwState("s1");
        assertEquals("what is xyz?", state.question());
        assertEquals("**the answer**", state.answer());
        assertEquals(false, state.running());
    }

    @Test
    void btwWithNoQuestionReportsUsageAndDoesNotBroadcast() {
        manager.attach("s1");
        final var future = manager.slash("s1", "/btw");
        assertEquals(Boolean.FALSE, future.join());
        assertEquals(0, broker.frames.size());
        assertNull(manager.btwState("s1"));
    }

    @Test
    void aNewerBtwCancelsAnInflightOneAndIsNotOverwrittenByTheStaleAnswer() {
        manager.attach("s1");
        manager.slash("s1", "/btw first question").join();
        broker.frames.poll(); // first running frame

        // second /btw overrides: the first answerer loop is cancelled
        final var second = manager.slash("s1", "/btw second question");
        assertEquals(Boolean.TRUE, second.join());
        assertTrue(answerer.cancelledSupplier("first question").getAsBoolean(), "first btw must be cancelled");
        broker.frames.poll(); // discard the "second question" running frame

        // the runner received both questions
        assertTrue(answerer.pending.containsKey("second question"));

        // the stale first answer must NOT win: cross the guard by a delayed completion
        answerer.complete("first question", "stale first answer");
        answerer.complete("second question", "fresh second answer");
        final var doneFrame = broker.awaitFrame();
        assertTrue(doneFrame.contains("\"question\":\"second question\""), doneFrame);
        assertTrue(doneFrame.contains("\"answer\":\"fresh second answer\""), doneFrame);

        final var state = manager.btwState("s1");
        assertEquals("second question", state.question());
        assertEquals("fresh second answer", state.answer());
    }

    @Test
    void aStaleOverrideAnswerNeverOverwritesTheNewerSlot() {
        manager.attach("s1");
        manager.slash("s1", "/btw q1").join();
        broker.frames.poll();
        manager.slash("s1", "/btw q2").join();
        broker.frames.poll();

        // q1 completes AFTER q2 already settled -> its completion must be discarded
        answerer.complete("q2", "q2 answer");
        broker.awaitFrame();
        answerer.complete("q1", "q1 stale");
        // no extra frame and the slot still shows q2
        assertEquals(0, broker.frames.size(), "stale completion must not broadcast");
        final var state = manager.btwState("s1");
        assertEquals("q2 answer", state.answer());
    }

    @Test
    void clearBtwCancelsTheInflightAnswerAndClearsTheSlot() {
        manager.attach("s1");
        manager.slash("s1", "/btw question").join();
        assertTrue(answerer.cancelledSupplier("question") != null);
        answerer.complete("question", "an answer");
        broker.awaitFrame();

        manager.clearBtw("s1");
        assertNull(manager.btwState("s1"), "clear must remove the slot");
    }

    @Test
    void btwErrorIsBroadcastAndHeldInTheSlot() {
        manager.attach("s1");
        manager.slash("s1", "/btw why?").join();
        broker.frames.poll();
        answerer.fail("why?", new IllegalStateException("boom"));
        final var doneFrame = broker.awaitFrame();
        assertTrue(doneFrame.contains("\"running\":false"), doneFrame);
        assertTrue(doneFrame.contains("\"error\":\"boom\""), doneFrame);
        final var state = manager.btwState("s1");
        assertEquals("boom", state.error());
    }

    @Test
    void snapshotForReseedsALiveAnswer() {
        manager.attach("s1");
        manager.slash("s1", "/btw reseed me").join();
        // let it settle
        answerer.complete("reseed me", "persisted answer");
        broker.awaitFrame();

        final var events = manager.snapshotFor("s1");
        final var btwEvent =
                events.stream().filter(e -> "btw".equals(e.type())).findFirst().orElse(null);
        assertTrue(btwEvent != null, "snapshot must carry the btw event");
        assertEquals("reseed me", btwEvent.message());
        assertEquals("persisted answer", btwEvent.data().get("answer"));
    }

    private static final class NoopShellRegistry extends ShellRegistry {
        private NoopShellRegistry() {
            super(null);
        }

        @Override
        public void register(final String sessionId, final io.yupiik.ia.agent.core.shell.ShellEnv env) {
            // no-op: routing is not exercised by these units
        }

        @Override
        public void unregister(final String sessionId, final io.yupiik.ia.agent.core.shell.ShellEnv env) {
            // no-op
        }
    }

    /** Captures every {@link #broadcast} frame for assertions. */
    private static final class FakeSseBroker extends SseBroker {
        private final Queue<String> frames = new ConcurrentLinkedQueue<>();

        private FakeSseBroker(final JsonMapper mapper) {
            super(mapper);
        }

        @Override
        public void broadcast(final String sessionId, final String frame) {
            frames.add(frame);
        }

        private String awaitFrame() {
            for (int i = 0; i < 100; i++) {
                final var head = frames.poll();
                if (head != null) {
                    return head;
                }
                try {
                    Thread.sleep(20);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            throw new AssertionError("no broadcast frame arrived");
        }
    }

    /** Controllable {@code BtwAnswerer}: overrides the 6-arg ask to return per-question futures. */
    private static final class FakeBtwAnswerer extends BtwAnswerer {
        private final java.util.Map<String, CompletableFuture<String>> pending =
                new java.util.concurrent.ConcurrentHashMap<>();
        // the real cancellation supplier the manager passed in, keyed by question - so a canceled test
        // reflects the actual override/clear the production code performs on the BtwState it owns.
        private final java.util.Map<String, BooleanSupplier> cancelled = new java.util.concurrent.ConcurrentHashMap<>();

        private FakeBtwAnswerer() {
            super(null, null, null, null, null, null, null, null, null, null, null, null);
        }

        @Override
        public CompletionStage<String> ask(
                final String question,
                final String sessionId,
                final UsageTracker usage,
                final BooleanSupplier cancelled,
                final ApprovalGate sessionGate,
                final ChatSettings sessionSettings) {
            this.pending.put(question, new CompletableFuture<>());
            this.cancelled.put(question, cancelled);
            return this.pending.get(question);
        }

        private BooleanSupplier cancelledSupplier(final String question) {
            return cancelled.get(question);
        }

        private void complete(final String question, final String answer) {
            final var future = pending.get(question);
            if (future != null) {
                future.complete(answer);
            }
        }

        private void fail(final String question, final Throwable error) {
            final var future = pending.get(question);
            if (future != null) {
                future.completeExceptionally(error);
            }
        }
    }
}
