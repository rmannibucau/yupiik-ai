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

import static java.util.concurrent.CompletableFuture.completedStage;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.agent.AgentLoop;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.maven.MavenRepositoryKnowledgeService;
import io.yupiik.ia.agent.core.runtime.VirtualThreadExecutor;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class TurnRunnerTest {
    @Test
    void runsThePromptThroughBeginAndEndTurn() {
        final var loop = new RecordingLoop();
        final var frontend = new RecordingFrontend();
        final var session = new ShellSession();
        final var env = new ShellEnv(frontend, new FixedIo(true), session, null);
        final var runner = new TurnRunner(loop, noIndexing(), new VirtualThreadExecutor());

        final var ran = runner.submit(new TurnRunner.TurnRequest(
                        env,
                        "s1",
                        "coder",
                        "hi",
                        () -> List.of(new Message("user", null, "old", null, null, null)),
                        () -> false))
                .toCompletableFuture()
                .join();

        assertTrue(ran);
        assertEquals("hi", loop.prompt().get());
        assertEquals("s1", loop.sessionId().get());
        assertFalse(session.busy());
        assertEquals(List.of("begin", "end"), frontend.events());
    }

    @Test
    void nullPromptResumesInsteadOfRunning() {
        final var loop = new RecordingLoop();
        final var env = new ShellEnv(new RecordingFrontend(), new FixedIo(true), new ShellSession(), null);
        final var runner = new TurnRunner(loop, noIndexing(), new VirtualThreadExecutor());

        runner.submit(new TurnRunner.TurnRequest(env, "s1", null, null, List::of, () -> false))
                .toCompletableFuture()
                .join();

        assertEquals("s1", loop.resumedSession().get());
        assertNull(loop.prompt().get());
    }

    @Test
    void busyIsVisibleDuringTheTurn() {
        final var loop = new RecordingLoop();
        final var session = new ShellSession();
        final var seenBusy = new AtomicBoolean(false);
        final var env = new ShellEnv(new RecordingFrontend(), new FixedIo(true), session, null);
        final var runner = new TurnRunner(loop, noIndexing(), new VirtualThreadExecutor());
        loop.onRun = () -> seenBusy.set(session.busy());

        runner.submit(new TurnRunner.TurnRequest(env, "s1", null, "go", List::of, () -> false))
                .toCompletableFuture()
                .join();

        assertTrue(seenBusy.get());
        assertFalse(session.busy());
    }

    @Test
    void declinedConfirmSkipsTheTurnAndReturnsFalse() throws Exception {
        final var loop = new RecordingLoop();
        final var env = new ShellEnv(new RecordingFrontend(), new FixedIo(false), new ShellSession(), null);
        final var runner = new TurnRunner(loop, indexing(3), new VirtualThreadExecutor());

        final var ran = runner.submit(new TurnRunner.TurnRequest(env, "s1", null, "hi", List::of, () -> false))
                .toCompletableFuture()
                .join();

        assertFalse(ran);
        assertNull(loop.prompt().get());
        assertNull(loop.resumedSession().get());
    }

    /** Indexing service override with a fabricated in-flight run (total {@code total}). */
    private static MavenRepositoryKnowledgeService indexing(final int total) throws Exception {
        final var ctor = MavenRepositoryKnowledgeService.IndexRun.class.getDeclaredConstructor(int.class);
        ctor.setAccessible(true);
        final var run = ctor.newInstance(total);
        return new MavenRepositoryKnowledgeService() {
            @Override
            public IndexRun currentIndex() {
                return run;
            }
        };
    }

    /** Indexing service with no run in flight: the confirm gate is skipped. */
    private static MavenRepositoryKnowledgeService noIndexing() {
        return new MavenRepositoryKnowledgeService() {};
    }

    private static final class FixedIo implements ShellIO {
        private final boolean confirm;

        private FixedIo(final boolean confirm) {
            this.confirm = confirm;
        }

        @Override
        public CompletionStage<Boolean> confirm(final String question) {
            return completedStage(confirm);
        }

        @Override
        public CompletionStage<java.util.Optional<String>> readLine(final String prompt) {
            return completedStage(java.util.Optional.empty());
        }

        @Override
        public CompletionStage<java.util.Optional<String>> pick(final String title, final List<String> options) {
            return completedStage(java.util.Optional.empty());
        }

        @Override
        public io.yupiik.ia.agent.core.permissions.ApprovalGate approvalGate() {
            return null;
        }

        @Override
        public io.yupiik.ia.agent.core.question.FeedbackGate feedbackGate() {
            return null;
        }
    }

    private static final class RecordingFrontend implements ShellFrontend {
        private final List<String> events = new ArrayList<>();

        List<String> events() {
            return events;
        }

        @Override
        public void status(final String message) {}

        @Override
        public void notice(final AgentEvent.Notice.Level level, final String message) {}

        @Override
        public void transcript(final String markdown) {}

        @Override
        public void error(final String message) {}

        @Override
        public void showThinking(final boolean value) {}

        @Override
        public void showToolOutput(final ToolOutputVisibility value) {}

        @Override
        public void beginTurn() {
            events.add("begin");
        }

        @Override
        public void endTurn() {
            events.add("end");
        }
    }

    private static final class RecordingLoop extends AgentLoop {
        private final AtomicReference<String> prompt = new AtomicReference<>();
        private final AtomicReference<String> sessionId = new AtomicReference<>();
        private final AtomicReference<String> resumedSession = new AtomicReference<>();
        private Runnable onRun = () -> {};

        AtomicReference<String> prompt() {
            return prompt;
        }

        AtomicReference<String> sessionId() {
            return sessionId;
        }

        AtomicReference<String> resumedSession() {
            return resumedSession;
        }

        @Override
        public String run(
                final String prompt,
                final String agentName,
                final String sessionId,
                final List<Message> history,
                final BooleanSupplier cancelled,
                final Supplier<String> queuedPrompt) {
            this.prompt.set(prompt);
            this.sessionId.set(sessionId);
            onRun.run();
            return null;
        }

        @Override
        public String resume(
                final String agentName,
                final String sessionId,
                final List<Message> history,
                final BooleanSupplier cancelled,
                final Supplier<String> queuedPrompt) {
            this.resumedSession.set(sessionId);
            return null;
        }
    }
}
