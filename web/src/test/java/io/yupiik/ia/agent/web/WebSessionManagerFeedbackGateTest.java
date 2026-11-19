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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.agent.SystemPromptBuilder;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfigurations;
import io.yupiik.ia.agent.core.context.BackoffSleeper;
import io.yupiik.ia.agent.core.context.UsageTracker;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.notifications.NotificationService;
import io.yupiik.ia.agent.core.permissions.ApprovalGate;
import io.yupiik.ia.agent.core.question.Question;
import io.yupiik.ia.agent.core.question.UserFeedbackService;
import io.yupiik.ia.agent.core.session.SessionStore;
import io.yupiik.ia.agent.core.shell.SessionLifecycle;
import io.yupiik.ia.agent.core.shell.SessionRuntimeFactory;
import io.yupiik.ia.agent.core.shell.ShellEnv;
import io.yupiik.ia.agent.core.shell.ShellRegistry;
import io.yupiik.ia.agent.core.shell.TurnRunner;
import io.yupiik.ia.agent.core.shell.btw.BtwAnswerer;
import io.yupiik.ia.agent.core.shell.slash.SlashRegistry;
import io.yupiik.ia.agent.core.tools.ToolRegistry;
import io.yupiik.ia.agent.core.wiring.PrecisionDurationMarkerProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
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
    private final BlockingQueue<String> alerts = new LinkedBlockingQueue<>();

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
                new WebSlashCommands(new SlashRegistry() {}, null, sessionStore),
                new TurnRunner() {},
                sessionStore,
                new SessionLifecycle(sessionStore),
                broker,
                this.mapper,
                answerer,
                null,
                null,
                new NotificationService(null, null) {
                    @Override
                    public void onInputRequired(
                            final String session, final String title, final int count, final String source) {
                        alerts.add(session + ":input:" + count + ":" + source);
                    }

                    @Override
                    public void onApprovalRequired(
                            final String session, final String title, final String tool, final String source) {
                        alerts.add(session + ":approval:" + tool + ":" + source);
                    }
                });
    }

    @AfterEach
    void cleanup() {
        try {
            if (manager != null) {
                manager.close();
            }
        } finally {
            if (broker != null) {
                broker.close();
            }
        }
    }

    @Test
    void aCancelledAskIsReportedAsCleanCancelledAnswer() throws Exception {
        manager.attach("s1");
        // register a subscription so pushRpc delivers the session.ask frame to a capturable sink
        final var frames = new ConcurrentLinkedQueue<String>();
        broker.subscribe("s1", bytes -> frames.add(new String(bytes, StandardCharsets.UTF_8)));

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

        try {
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
            done.get(5, TimeUnit.SECONDS);

            assertNotNull(result.get(), "the ask must have returned (not hang) after the cancel");
            assertEquals(Map.of("cancelled", true), result.get());
        } finally {
            try {
                manager.cancel("s1");
            } finally {
                asking.interrupt();
                asking.join(5_000);
                assertFalse(asking.isAlive(), "asking thread must terminate");
            }
        }
    }

    @Test
    void newPromptsNotifyOnceForOwningSessionAndReplayIsSilent() throws Exception {
        manager.attach("s1");
        manager.attach("s2");
        final var input = CompletableFuture.supplyAsync(() -> manager.runtime("s1")
                .feedback()
                .ask(List.of(
                        new Question("q1", "secret question", List.of("secret choice"), false),
                        new Question("q2", "second", List.of(), false))));
        final var approval = CompletableFuture.supplyAsync(() -> manager.runtime("s2")
                .permissions()
                .gate()
                .approve("secret title", "secret detail", "bash", Map.of("command", "secret command")));
        assertEquals(
                Set.of("s1:input:2:main", "s2:approval:bash:main"),
                Set.of(alerts.poll(5, TimeUnit.SECONDS), alerts.poll(5, TimeUnit.SECONDS)));
        final var replay = new ConcurrentLinkedQueue<String>();
        manager.subscribe("s1", bytes -> replay.add(new String(bytes, StandardCharsets.UTF_8)));
        assertTrue(replay.stream().anyMatch(frame -> frame.contains("session.ask")));
        assertTrue(alerts.isEmpty(), "replay must not notify");
        final var rpc = replay.stream()
                .filter(frame -> frame.contains("session.ask"))
                .findFirst()
                .orElseThrow();
        final var data = rpc.substring(rpc.indexOf("data:") + 5).strip();
        final var id = String.valueOf(mapper.fromString(Map.class, data).get("id"));
        assertTrue(manager.resolve("s1", id, Map.of("q1", "answer")));
        assertEquals(Map.of("q1", "answer"), input.get(5, TimeUnit.SECONDS));
        manager.cancel("s2");
        assertEquals(ApprovalGate.Result.DENY, approval.get(5, TimeUnit.SECONDS));
        assertTrue(alerts.isEmpty(), "resolution and cancellation must not notify");
    }

    @Test
    void closingBtwCancelsOnlyItsRequestsAndStaleGatesStaySilent() throws Exception {
        manager.attach("s1");
        final var frames = new ConcurrentLinkedQueue<String>();
        broker.subscribe("s1", bytes -> frames.add(new String(bytes, StandardCharsets.UTF_8)));
        manager.slash("s1", "/btw clarification").get(5, TimeUnit.SECONDS);
        final var main = CompletableFuture.supplyAsync(
                () -> manager.runtime("s1").feedback().ask(List.of(new Question("m", "main", List.of(), false))));
        assertEquals("s1:input:1:main", alerts.poll(5, TimeUnit.SECONDS));
        final var side = CompletableFuture.supplyAsync(
                () -> answerer.feedback.ask(List.of(new Question("b", "btw", List.of(), false))));
        assertEquals("s1:input:1:btw", alerts.poll(5, TimeUnit.SECONDS));
        final var permission =
                CompletableFuture.supplyAsync(() -> answerer.approval.approve("title", "detail", "bash", Map.of()));
        assertEquals("s1:approval:bash:btw", alerts.poll(5, TimeUnit.SECONDS));
        manager.clearBtw("s1");
        assertEquals(Map.of("cancelled", true), side.get(5, TimeUnit.SECONDS));
        assertEquals(ApprovalGate.Result.DENY, permission.get(5, TimeUnit.SECONDS));
        assertEquals(
                2,
                frames.stream()
                        .filter(frame -> frame.contains("session.requestCancelled"))
                        .count());
        assertFalse(main.isDone(), "closing btw must not resolve main feedback");
        assertEquals(
                Map.of("cancelled", true),
                answerer.feedback.ask(List.of(new Question("stale", "stale", List.of(), false))));
        assertTrue(alerts.isEmpty());
        manager.cancel("s1");
        assertEquals(Map.of("cancelled", true), main.get(5, TimeUnit.SECONDS));
    }

    @Test
    void webConfirmationNotifiesOnceAndAlreadyResolvedPublicationsAreSilent() throws Exception {
        final var session = manager.attach("s1");
        final var confirmation = session.env().io().confirm("private confirmation");
        assertEquals("s1:input:1:main", alerts.poll(5, TimeUnit.SECONDS));
        manager.cancel("s1");
        assertFalse(confirmation.toCompletableFuture().get(5, TimeUnit.SECONDS));

        broker.subscribe("s1", bytes -> {
            final var frame = new String(bytes, StandardCharsets.UTF_8);
            if (frame.contains("session.ask")) {
                final var data = frame.substring(frame.indexOf("data:") + 5).strip();
                final var id = String.valueOf(mapper.fromString(Map.class, data).get("id"));
                manager.resolve("s1", id, Map.of("q", "immediate"));
            }
        });
        assertEquals(
                Map.of("q", "immediate"),
                session.runtime().feedback().ask(List.of(new Question("q", "private", List.of(), false))));
        assertTrue(alerts.isEmpty(), "a request already resolved during publication must not notify");
    }

    private static final class NoopShellRegistry extends ShellRegistry {
        private NoopShellRegistry() {
            super(null);
        }

        @Override
        public void register(final String sessionId, final ShellEnv env) {
            // no-op: routing is not exercised by these units
        }

        @Override
        public void unregister(final String sessionId, final ShellEnv env) {
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
        private UserFeedbackService feedback;
        private ApprovalGate approval;

        private FakeBtwAnswerer() {
            super(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        }

        @Override
        public CompletionStage<BtwAnswerer.BtwAnswer> ask(
                final String question,
                final String sessionId,
                final UsageTracker usage,
                final BooleanSupplier cancelled,
                final ApprovalGate sessionGate,
                final ChatSettings sessionSettings,
                final Path workdir) {
            return CompletableFuture.completedFuture(new BtwAnswerer.BtwAnswer("unused", false));
        }

        @Override
        public CompletionStage<BtwAnswerer.BtwAnswer> ask(
                final String question,
                final String sessionId,
                final UsageTracker usage,
                final BooleanSupplier cancelled,
                final ApprovalGate sessionGate,
                final ChatSettings sessionSettings,
                final SystemPromptBuilder systemPromptBuilder,
                final UserFeedbackService feedback,
                final Path workdir,
                final ToolRegistry registry,
                final Set<String> ceiling) {
            assertNotNull(ceiling);
            this.feedback = feedback;
            this.approval = sessionGate;
            return new CompletableFuture<>();
        }
    }
}
