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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link WebSessionManager#submit}: every accepted prompt (fresh submit on an idle
 * session, and a prompt queued behind a running turn) must surface immediately to the live SSE
 * subscribers as a {@code user} agent event - the exact type {@link WebReplay} emits for a persisted
 * user message - so the browser transcript renders the prompt live instead of only after a reload.
 * Slash commands are never broadcast as user turns.
 */
@ExtendWith(TestWebJsonMapper.class)
class WebSessionManagerPromptTest {

    @TempDir
    Path storageHome;

    private JsonMapper mapper;
    private WebSessionManager manager;
    private FakeSseBroker broker;
    private FakeTurnRunner turnRunner;

    @BeforeEach
    void setup(final JsonMapper mapper) {
        this.mapper = mapper;
        this.broker = new FakeSseBroker(mapper);
        this.turnRunner = new FakeTurnRunner();

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
                null, // memory production policy
                null, // quota store
                null, // SecretRedactor
                null); // NotificationService
        this.manager = new WebSessionManager(
                runtimeFactory,
                new NoopShellRegistry(),
                new SlashRegistry() {},
                turnRunner,
                sessionStore,
                new SessionLifecycle(sessionStore),
                broker,
                this.mapper,
                new BtwAnswerer(null, null, null, null, null, null, null, null, null, null, null, null, null, null));
    }

    @Test
    void freshSubmitBroadcastsAUserEventForThePrompt() {
        manager.attach("s1");

        manager.submit("s1", "hello there", null);

        final var frame = broker.frames.poll();
        assertNotNull(frame, "a user frame must be broadcast for a fresh submit");
        assertTrue(frame.contains("\"type\":\"user\""), frame);
        assertTrue(frame.contains("\"message\":\"hello there\""), frame);
        // the prompt was handed to the turn runner (not queued)
        assertEquals("hello there", turnRunner.lastPrompt);
        assertEquals(0, manager.queuedPromptCount("s1"));
    }

    @Test
    void queuedSubmitBroadcastsAUserEventAndEnqueuesInsteadOfStartingATurn() {
        // mark the session busy (simulating a running turn)
        manager.attach("s1").env().session().busy(true);

        manager.submit("s1", "follow up meanwhile", null);

        final var frame = broker.frames.poll();
        assertNotNull(frame, "a user frame must be broadcast for a queued submit");
        assertTrue(frame.contains("\"type\":\"user\""), frame);
        assertTrue(frame.contains("\"message\":\"follow up meanwhile\""), frame);
        // the turn runner was NOT invoked: the prompt is queued for the next checkpoint
        assertNull(turnRunner.lastPrompt);
        assertEquals(1, manager.queuedPromptCount("s1"));
    }

    @Test
    void resumeDoesNotBroadcastAUserEvent() {
        manager.attach("s1");

        // prompt == null means resume: no new user turn, so no user broadcast
        final var future = manager.submit("s1", null, null);
        assertEquals(Boolean.TRUE, future.join());
        assertEquals(0, broker.frames.size(), "resume must not broadcast a user event");
        // resume reaches the turn runner with a null prompt (does not start a fresh user turn)
        assertNull(turnRunner.lastPrompt);
    }

    @Test
    void slashCommandIsNotBroadcastAsAUserEvent() {
        manager.attach("s1");
        manager.submit("s1", "/model", null);
        // slash commands route to the lifecycle/slash path, never as a user turn
        assertEquals(0, broker.frames.size(), "slash commands must not broadcast a user event");
    }

    private static final class NoopShellRegistry extends ShellRegistry {
        private NoopShellRegistry() {
            super(null);
        }

        @Override
        public void register(final String sessionId, final io.yupiik.ia.agent.core.shell.ShellEnv env) {
            // no-op
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
    }

    /** Records the latest {@link TurnRequest} prompt without running a real turn. */
    private static final class FakeTurnRunner extends TurnRunner {
        private volatile String lastPrompt;

        private FakeTurnRunner() {
            super();
        }

        @Override
        public CompletionStage<Boolean> submit(final TurnRequest request) {
            this.lastPrompt = request.prompt();
            return CompletableFuture.completedFuture(Boolean.TRUE);
        }
    }
}
