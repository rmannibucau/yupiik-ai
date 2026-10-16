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

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfigurations;
import io.yupiik.ia.agent.core.question.Question;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies the web {@code ask_user} feedback gate contract: when the interactive request is
 * cancelled/rejected (modal Cancel or Stop), {@code awaitRpc} resolves to {@code null} and the gate
 * surfaces a clean {@code {"cancelled":true}} answer instead of collapsing to a null/headless error
 * that would make the model's turn spin.
 */
@ExtendWith(TestWebJsonMapper.class)
@Timeout(30)
class WebSessionManagerFeedbackGateTest {

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
    void aCancelledAskIsReportedAsCleanCancelledAnswer() throws Exception {
        manager.attach("s1");
        // register a subscription so pushRpc delivers the session.ask frame to a capturable sink
        final var frames = new java.util.concurrent.ConcurrentLinkedQueue<String>();
        broker.subscribe("s1", bytes -> frames.add(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)));

        // the feedback gate blocks the asking thread on awaitRpc until the request is resolved;
        // drive it from a background thread so the main thread can perform the cancel.
        final var result = new AtomicReference<Map<String, Object>>();
        final var done = new CompletableFuture<Void>();
        final var asking = Thread.startVirtualThread(() -> {
            try {
                result.set(manager.runtime("s1")
                        .feedback()
                        .ask(List.of(new Question("q1", "Pick?", List.of("a", "b"), false))));
            } finally {
                done.complete(null);
            }
        });

        // a session.ask rpc frame was pushed (put runs before pushRpc, so the future is pending now)
        for (int i = 0; i < 100; i++) {
            if (frames.stream().anyMatch(f -> f.contains("session.ask"))) {
                break;
            }
            Thread.sleep(20);
            if (i == 99) {
                throw new AssertionError("no session.ask frame arrived");
            }
        }

        // cancel the turn (modal Cancel / Stop): unblocks awaitRpc with null
        manager.cancel("s1");
        done.join();

        assertNotNull(result.get(), "the ask must have returned (not hang) after the cancel");
        assertEquals(Map.of("cancelled", true), result.get());
        asking.join();
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

    /** Concrete {@code SseBroker} for wiring; rpc/agent frames are captured via a test subscription. */
    private static final class FakeSseBroker extends SseBroker {
        private FakeSseBroker(final JsonMapper mapper) {
            super(mapper);
        }
    }

    /** Controllable {@code BtwAnswerer}: overrides the 6-arg ask to return per-question futures. */
    private static final class FakeBtwAnswerer extends BtwAnswerer {
        private FakeBtwAnswerer() {
            super(null, null, null, null, null, null, null, null, null, null, null, null);
        }

        @Override
        public CompletionStage<BtwAnswerer.BtwAnswer> ask(
                final String question,
                final String sessionId,
                final io.yupiik.ia.agent.core.context.UsageTracker usage,
                final java.util.function.BooleanSupplier cancelled,
                final io.yupiik.ia.agent.core.permissions.ApprovalGate sessionGate,
                final io.yupiik.ia.agent.core.llm.ChatSettings sessionSettings) {
            return CompletableFuture.completedFuture(new BtwAnswerer.BtwAnswer("unused", false));
        }

        @Override
        public CompletionStage<BtwAnswerer.BtwAnswer> ask(
                final String question,
                final String sessionId,
                final io.yupiik.ia.agent.core.context.UsageTracker usage,
                final java.util.function.BooleanSupplier cancelled,
                final io.yupiik.ia.agent.core.permissions.ApprovalGate sessionGate,
                final io.yupiik.ia.agent.core.llm.ChatSettings sessionSettings,
                final io.yupiik.ia.agent.core.agent.SystemPromptBuilder systemPromptBuilder) {
            return ask(question, sessionId, usage, cancelled, sessionGate, sessionSettings);
        }
    }
}
