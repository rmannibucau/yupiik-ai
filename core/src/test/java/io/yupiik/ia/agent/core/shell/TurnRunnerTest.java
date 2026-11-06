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
import io.yupiik.ia.agent.core.agent.SubagentRegistry;
import io.yupiik.ia.agent.core.llm.CodexLoginService;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.maven.MavenRepositoryKnowledgeService;
import io.yupiik.ia.agent.core.permissions.ApprovalGate;
import io.yupiik.ia.agent.core.question.FeedbackGate;
import io.yupiik.ia.agent.core.session.AgentSessionService;
import io.yupiik.llm.client.codex.CodexNotAuthenticatedException;
import io.yupiik.llm.client.codex.model.CodexTokenStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

class TurnRunnerTest {
    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    @Test
    void runsThePromptThroughBeginAndEndTurn() {
        final var loop = new RecordingLoop();
        final var frontend = new RecordingFrontend();
        final var session = new ShellSession();
        final var env = new ShellEnv(frontend, new FixedIo(true), session, null);
        final var runner = new TurnRunner(noIndexing(), EXECUTOR, agentSessionService());

        final var ran = runner.submit(new TurnRunner.TurnRequest(
                        loop,
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
        final var runner = new TurnRunner(noIndexing(), EXECUTOR, agentSessionService());

        runner.submit(new TurnRunner.TurnRequest(loop, env, "s1", null, null, List::of, () -> false))
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
        final var runner = new TurnRunner(noIndexing(), EXECUTOR, agentSessionService());
        loop.onRun = () -> seenBusy.set(session.busy());

        runner.submit(new TurnRunner.TurnRequest(loop, env, "s1", null, "go", List::of, () -> false))
                .toCompletableFuture()
                .join();

        assertTrue(seenBusy.get());
        assertFalse(session.busy());
    }

    @Test
    void declinedConfirmSkipsTheTurnAndReturnsFalse() throws Exception {
        final var loop = new RecordingLoop();
        final var env = new ShellEnv(new RecordingFrontend(), new FixedIo(false), new ShellSession(), null);
        final var runner = new TurnRunner(indexing(3), EXECUTOR, agentSessionService());

        final var ran = runner.submit(new TurnRunner.TurnRequest(loop, env, "s1", null, "hi", List::of, () -> false))
                .toCompletableFuture()
                .join();

        assertFalse(ran);
        assertNull(loop.prompt().get());
        assertNull(loop.resumedSession().get());
    }

    @Test
    void pendingModeIsAppliedAtTheEndOfTheTurn() {
        final var loop = new RecordingLoop();
        final var frontend = new RecordingFrontend();
        final var session = new ShellSession();
        final var agentSession = new RecordingAgentSessionService();
        final var env = new ShellEnv(frontend, new FixedIo(true), session, null);
        final var runner = new TurnRunner(noIndexing(), EXECUTOR, agentSession);
        session.pendingMode("build");

        runner.submit(new TurnRunner.TurnRequest(loop, env, "s1", null, "go", List::of, () -> false))
                .toCompletableFuture()
                .join();

        assertEquals("build", agentSession.lastMode());
        assertNull(session.pendingMode(), "the pending mode must be consumed");
        assertFalse(session.busy());
    }

    @Test
    void interruptedTurnLatchesTheCancelOfItsSubagents() {
        // the UI resets its interrupt flag when the next turn starts, so the cancel is pushed to
        // the sub-agents here, at turn end, while the flag still reads true
        final var env = new ShellEnv(new RecordingFrontend(), new FixedIo(true), new ShellSession(), null);
        final var latched = new AtomicReference<String>();
        final var interrupted = new AtomicBoolean(false);
        final var loop = new RecordingLoop();
        loop.onRun = () -> interrupted.set(true);
        final var runner = new TurnRunner(noIndexing(), EXECUTOR, agentSessionService(), recordingRegistry(latched));

        runner.submit(new TurnRunner.TurnRequest(
                        loop,
                        env,
                        "s1",
                        "coder",
                        "hi",
                        () -> List.of(new Message("user", null, "old", null, null, null)),
                        interrupted::get))
                .toCompletableFuture()
                .join();

        assertEquals("s1", latched.get(), "the turn's own sub-agents must be latched cancelled");
    }

    @Test
    void completedTurnLeavesSubagentsRunning() {
        final var env = new ShellEnv(new RecordingFrontend(), new FixedIo(true), new ShellSession(), null);
        final var latched = new AtomicReference<String>();
        final var interrupted = new AtomicBoolean(false);
        final var runner = new TurnRunner(noIndexing(), EXECUTOR, agentSessionService(), recordingRegistry(latched));

        runner.submit(new TurnRunner.TurnRequest(
                        new RecordingLoop(),
                        env,
                        "s1",
                        "coder",
                        "hi",
                        () -> List.of(new Message("user", null, "old", null, null, null)),
                        interrupted::get))
                .toCompletableFuture()
                .join();

        assertNull(latched.get(), "a turn which was not interrupted must not cancel sub-agents");
    }

    /** Registry recording the sessions whose children a turn-end cancel asked to latch. */
    private static SubagentRegistry recordingRegistry(final AtomicReference<String> latched) {
        return new SubagentRegistry(null, null) {
            @Override
            public int cancelByParentSession(final String parentSessionId) {
                latched.set(parentSessionId);
                return 1;
            }
        };
    }

    /** Recording AgentSessionService: setMode records the argument instead of mutating real state. */
    private static final class RecordingAgentSessionService extends AgentSessionService {
        private String lastMode;

        RecordingAgentSessionService() {
            super(null, null, null, null, null, null, null, null, null, null);
        }

        @Override
        public ModeChange setMode(final String arg, final String sessionId, final SessionRuntime runtime) {
            lastMode = arg;
            return new ModeChange("mode: " + arg, null, null);
        }

        String lastMode() {
            return lastMode;
        }
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

    /** Default (do-nothing) recording session service for the non-mode tests. */
    private static AgentSessionService agentSessionService() {
        return new RecordingAgentSessionService();
    }

    private record FixedIo(boolean confirm) implements ShellIO {

        @Override
        public CompletionStage<Boolean> confirm(final String question) {
            return completedStage(confirm);
        }

        @Override
        public CompletionStage<Optional<String>> readLine(final String prompt) {
            return completedStage(Optional.empty());
        }

        @Override
        public CompletionStage<Optional<String>> pick(final String title, final List<String> options) {
            return completedStage(Optional.empty());
        }

        @Override
        public ApprovalGate approvalGate() {
            return null;
        }

        @Override
        public FeedbackGate feedbackGate() {
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

    @Test
    void firstCodexUseRunsLoginAndRetriesTheTurn() {
        final var loop = new RecordingLoop();
        final var runs = new AtomicReference<>(0);
        loop.onRun = () -> {
            if (runs.updateAndGet(v -> v + 1) == 1) {
                throw new CodexNotAuthenticatedException("first-fail");
            }
        };
        final var captor = new CapturingLoginService();
        final var frontend = new RecordingFrontend();
        final var env = new ShellEnv(frontend, new FixedIo(true), new ShellSession(), null);
        final var runner = new TurnRunner(noIndexing(), EXECUTOR, agentSessionService(), null, captor);

        final var ran = runner.submit(new TurnRunner.TurnRequest(
                        loop,
                        env,
                        "s-codex",
                        "coder",
                        "hi",
                        () -> List.of(new Message("user", null, "old", null, null, null)),
                        () -> false))
                .toCompletableFuture()
                .join();

        assertTrue(ran, "the turn must complete after a successful interactive login");
        assertEquals(1, captor.signIns.get(), "the login flow must run exactly once");
        assertEquals("first-fail", captor.providerKey.get(), "the missing session provider must be signed in");
        assertEquals(2, runs.get(), "the turn body must run again after login");
    }

    /** A login service that records the call without performing a real network flow. */
    private static final class CapturingLoginService extends CodexLoginService {
        private final AtomicReference<Integer> signIns = new AtomicReference<>(0);
        private final AtomicReference<String> providerKey = new AtomicReference<>();

        private CapturingLoginService() {
            super(null, null, null);
        }

        @Override
        public CodexTokenStore signIn(
                final String provider,
                final boolean device,
                final java.util.function.Consumer<String> urlSink,
                final java.util.function.Predicate<Boolean> cancelled) {
            signIns.set(signIns.get() + 1);
            providerKey.set(provider);
            if (urlSink != null) {
                urlSink.accept("http://localhost:1/auth/callback");
            }
            return new CodexTokenStore("acc", "ref", "acct", System.currentTimeMillis() + 3600_000, "id");
        }
    }

    @AfterAll
    static void closeExecutor() {
        EXECUTOR.shutdownNow();
    }
}
