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
import io.yupiik.fusion.jsonrpc.impl.JsonRpcMethod;
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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression test for the {@code session.resolve} cancel path: the frontend answers a pending
 * approval/ask/question with {@code result: null} to cancel, and fusion's {@code @JsonRpcParam}
 * {@code required} flag treats {@code null} as *missing*, so marking {@code result} required rejected
 * the whole call with {@code Missing 'result'} before {@link WebSessionManager#resolve} ever ran. That
 * left the browser's cancel unanswered and the running turn blocked until the RPC timeout watchdog.
 *
 * <p>The cancel ({@code result: null}) must dispatch through the real fusion-generated parameter
 * mapping (which previously compiled with {@code findParameter(...,"result",2,true,...)}) and resolve
 * the pending ask into the documented {@code {"cancelled":true}} answer instead of surfacing a
 * JSON-RPC error.
 */
@ExtendWith(TestWebJsonMapper.class)
@Timeout(30)
class WebSessionResolveNullResultTest {

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
                null, // memory production policy
                null, // quota store
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
    void aNullResultCancelResolvesThroughTheGeneratedSessionResolveMapping() throws Exception {
        manager.attach("s1");
        final var frames = new ConcurrentLinkedQueue<String>();
        broker.subscribe("s1", bytes -> frames.add(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)));

        // a pending ask blocked on awaitRpc, driven from a background thread (mirrors a tool round
        // awaiting the browser's answer).
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

        for (int i = 0; i < 100; i++) {
            if (frames.stream().anyMatch(f -> f.contains("session.ask"))) {
                break;
            }
            Thread.sleep(20);
            if (i == 99) {
                throw new AssertionError("no session.ask frame arrived");
            }
        }

        // capture the request id the frame carries so the resolve targets the actual pending request
        final long requestId = frames.stream()
                .filter(f -> f.contains("session.ask"))
                .flatMap(f -> java.util.Arrays.stream(f.split("\\R")))
                .filter(l -> l.startsWith("data:") && l.contains("\"id\""))
                .map(l -> l.substring("data:".length()))
                .map(l -> (Map<?, ?>) mapper.read(Map.class, new java.io.StringReader(l)))
                .map(m -> ((Number) m.get("id")).longValue())
                .findFirst()
                .orElseThrow();

        // the frontend's cancel: session.resolve with result:null. This goes through the real
        // fusion-generated param mapping for resolveRequest - the exact path that used to throw
        // "Missing 'result' parameter." because result was marked required.
        final var webJsonRpc =
                new WebJsonRpc(null, null, manager, null, null, null, null, null, null, null, null, null);
        final var generated = new WebJsonRpc$resolveRequest$FusionJsonRpcMethod(webJsonRpc, mapper);
        final java.util.Map<String, Object> params = new java.util.HashMap<>();
        params.put("id", "s1");
        params.put("requestId", String.valueOf(requestId));
        params.put("result", null); // the browser cancel - null is a valid resolution
        final CompletionStage<?> dispatched = generated.invoke(new JsonRpcMethod.Context(null, params));
        final var response = (Map<?, ?>) dispatched.toCompletableFuture().join();

        done.join();
        asking.join();

        // the JSON-RPC call succeeded (no Missing 'result' error) and marked the resolve as handled
        assertEquals(Map.of("resolved", true), response);
        // and the pending ask got the documented cancelled answer instead of spinning forever
        assertNotNull(result.get(), "the ask must have returned (not hang) after the null-result resolve");
        assertEquals(Map.of("cancelled", true), result.get());
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

    /** Concrete {@code SseBroker} for wiring; rpc frames are captured via a test subscription. */
    private static final class FakeSseBroker extends SseBroker {
        private FakeSseBroker(final JsonMapper mapper) {
            super(mapper);
        }
    }

    /** Controllable {@code BtwAnswerer}: overrides the 6-arg ask to return per-question futures. */
    private static final class FakeBtwAnswerer extends BtwAnswerer {
        private FakeBtwAnswerer() {
            super(null, null, null, null, null, null, null, null, null, null, null, null, null);
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
