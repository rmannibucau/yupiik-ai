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

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfigurations;
import io.yupiik.ia.agent.core.context.BackoffSleeper;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies the web's "close tab" semantics ({@code session.cancelAll}) align on the TUI's
 * closeTab: the running turn is interrupted (the {@code cancelled} supplier the {@code TurnRunner}
 * polls flips to true), the queued prompts are dropped so no absorbed follow-up resurrects the
 * interrupted work, and the session itself stays attached and resumable. Also guards the contrast
 * with the plain {@code session.cancel} (Stop button), which interrupts the turn but keeps the
 * queue intact.
 */
@ExtendWith(TestWebJsonMapper.class)
@Timeout(30)
class WebSessionManagerCloseTest {

    @TempDir
    Path storageHome;

    private JsonMapper mapper;
    private WebSessionManager manager;
    private CancelCapturingTurnRunner turnRunner;

    @BeforeEach
    void setup(final JsonMapper mapper) {
        this.mapper = mapper;
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
                new BackoffSleeper(),
                null,
                null,
                null,
                null,
                null,
                null);
        this.turnRunner = new CancelCapturingTurnRunner();
        this.manager = new WebSessionManager(
                runtimeFactory,
                new NoopShellRegistry(),
                new SlashRegistry() {},
                turnRunner,
                sessionStore,
                new SessionLifecycle(sessionStore),
                new SseBroker(this.mapper),
                this.mapper,
                new BtwAnswerer(null, null, null, null, null, null, null, null, null, null, null, null, null));
    }

    /** Idle submit starts a turn; busy()=true then queues follow-ups behind it. */
    private void startTurnWithQueued(String... queued) {
        manager.attach("s1");
        manager.submit("s1", "main", null); // idle -> starts a turn, captured by turnRunner
        manager.attach("s1").env().session().busy(true); // simulate the running turn
        for (final var q : queued) {
            manager.submit("s1", q, null); // busy -> queued
        }
    }

    @Test
    void cancelAllInterruptsTheTurnAndClearsTheQueueKeepingTheSessionAttached() {
        startTurnWithQueued("q1", "q2");
        assertTrue(turnRunner.cancelled != null, "the running turn must expose a cancelled supplier");
        assertEquals(2, manager.queuedPromptCount("s1"));
        assertTrue(manager.isAttached("s1"));

        manager.cancelAll("s1");

        // the running turn received the interrupt signal (TUI closeTab equivalent)
        assertTrue(turnRunner.cancelled.getAsBoolean(), "cancelAll must interrupt the running turn");
        // every queued follow-up was dropped, so nothing can resurrect the interrupted work
        assertEquals(0, manager.queuedPromptCount("s1"), "cancelAll must clear the queued prompts");
        // the session is still attached and resumable, unlike a destructive delete/detach
        assertTrue(manager.isAttached("s1"), "cancelAll must not detach the session");
    }

    @Test
    void plainCancelInterruptsTheTurnButKeepsTheQueue() {
        startTurnWithQueued("q1", "q2");
        assertEquals(2, manager.queuedPromptCount("s1"));
        assertTrue(manager.isAttached("s1"));

        manager.cancel("s1");

        // Stop button semantics: interrupts the running turn but preserves the queued prompts
        assertTrue(turnRunner.cancelled.getAsBoolean(), "cancel must interrupt the running turn");
        assertEquals(2, manager.queuedPromptCount("s1"), "plain cancel must NOT clear the queued prompts");
        assertTrue(manager.isAttached("s1"));
    }

    @Test
    void cancelAllOnAnIdleSessionIsSafeAndKeepsItAttached() {
        manager.attach("s1");
        assertEquals(0, manager.queuedPromptCount("s1"));
        assertTrue(manager.isAttached("s1"));

        manager.cancelAll("s1");

        // no turn, no queue: still a no-op that leaves the session resumable (flag resets on submit)
        assertTrue(manager.isAttached("s1"));
        assertEquals(0, manager.queuedPromptCount("s1"));
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

    /** Records the {@code cancelled} supplier of the running turn without executing it. */
    private static final class CancelCapturingTurnRunner extends TurnRunner {
        private volatile BooleanSupplier cancelled;

        private CancelCapturingTurnRunner() {
            super();
        }

        @Override
        public CompletionStage<Boolean> submit(final TurnRequest request) {
            this.cancelled = request.cancelled();
            return CompletableFuture.completedFuture(Boolean.TRUE);
        }
    }
}
