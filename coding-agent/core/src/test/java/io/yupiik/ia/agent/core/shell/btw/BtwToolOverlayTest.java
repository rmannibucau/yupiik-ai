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
package io.yupiik.ia.agent.core.shell.btw;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.agent.AgentLoop;
import io.yupiik.ia.agent.core.configuration.TestConfigs;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatCompletionRequest;
import io.yupiik.ia.agent.core.llm.ChatResult;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.llm.InBandToolCallExtractor;
import io.yupiik.ia.agent.core.llm.MarkupToolCallScanner;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.ToolCall;
import io.yupiik.ia.agent.core.llm.ToolCallPairingRepair;
import io.yupiik.ia.agent.core.permissions.ApprovalGate;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.session.InMemorySessionStore;
import io.yupiik.ia.agent.core.shell.SessionRuntime;
import io.yupiik.ia.agent.core.shell.ShellEnv;
import io.yupiik.ia.agent.core.shell.ShellOutcome;
import io.yupiik.ia.agent.core.shell.ShellSession;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.tools.ToolExecutionService;
import io.yupiik.ia.agent.core.tools.ToolRegistry;
import io.yupiik.ia.agent.core.tools.ToolResult;
import io.yupiik.ia.agent.test.TestJsonMapper;
import java.nio.file.Path;
import java.time.Clock;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

@ExtendWith(TestJsonMapper.class)
@Timeout(20)
class BtwToolOverlayTest {
    @TempDir
    Path root;

    @Test
    void explicitToolsStaySessionLocalAcrossABAAndConcurrentAB(final JsonMapper mapper) throws Exception {
        try (final var fixture = new Fixture(mapper, Map.of())) {
            for (final var marker : List.of("A", "B", "A")) {
                assertEquals(
                        "done",
                        fixture.ask(marker, fixture.registry(marker, true, true), Set.of("client_lookup"), () -> false)
                                .get(5, TimeUnit.SECONDS)
                                .text());
            }
            fixture.barrier = new CyclicBarrier(2);
            final var a = fixture.ask("A", fixture.registry("A", true, true), Set.of("client_lookup"), () -> false);
            final var b = fixture.ask("B", fixture.registry("B", true, true), Set.of("client_lookup"), () -> false);
            assertEquals("done", a.get(5, TimeUnit.SECONDS).text());
            assertEquals("done", b.get(5, TimeUnit.SECONDS).text());
            assertEquals(3, fixture.invocations.stream().filter("A"::equals).count());
            assertEquals(2, fixture.invocations.stream().filter("B"::equals).count());
            for (final var request : fixture.requests) {
                assertEquals(
                        request.sessionId(),
                        request.tools().getFirst().function().parameters().get("title"));
                assertEquals("model-" + request.sessionId(), request.settings().model());
                request.messages().stream()
                        .filter(message -> "tool".equals(message.role()))
                        .forEach(message -> assertEquals("client:" + request.sessionId(), message.content()));
            }
            assertEquals(0, fixture.approvals.get());
            // Legacy explicit-root invocation is genuinely standalone: it uses only the injected app registry.
            fixture.barrier = null;
            fixture.answerer
                    .ask("standalone", "app", null, () -> false, fixture.gate, root)
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
            assertEquals("app", fixture.invocations.getLast());
        }
    }

    @Test
    void ceilingsReadonlyUsabilityAndDenialsCannotBeWidenedByModelCalls(final JsonMapper mapper) throws Exception {
        for (var variant = 0; variant < 5; variant++) {
            final var denied = variant == 3;
            try (final var fixture =
                    new Fixture(mapper, denied ? Map.of("yca.permissions.deny", "client_lookup") : Map.of())) {
                final var registry = variant == 4 ? null : fixture.registry("A", variant != 1, variant != 2);
                final var ceiling = variant == 0 ? Set.<String>of() : Set.of("client_lookup");
                assertEquals(
                        "done",
                        fixture.ask("A", registry, ceiling, () -> false)
                                .get(5, TimeUnit.SECONDS)
                                .text());
                assertTrue(
                        fixture.invocations.isEmpty(), "a hallucinated unavailable or denied call must never execute");
                assertEquals(
                        0, fixture.approvals.get(), "readonly side requests must not auto-approve unavailable tools");
                assertEquals(denied ? 1 : 0, fixture.requests.getFirst().tools().size());
            }
        }
    }

    @Test
    void toolLessSchemaUsesExplicitOverlayWithoutExecutingAnyCall(final JsonMapper mapper) throws Exception {
        try (final var fixture = new Fixture(mapper, Map.of("yca.slashcommands.btw.tools", "false"))) {
            final var answer = fixture.ask("A", fixture.registry("A", true, true), Set.of("client_lookup"), () -> false)
                    .get(5, TimeUnit.SECONDS);
            assertTrue(answer.retryable(), "the model's tool-only answer is not executed in tool-less mode");
            assertEquals(1, fixture.requests.size());
            assertEquals(
                    "A",
                    fixture.requests
                            .getFirst()
                            .tools()
                            .getFirst()
                            .function()
                            .parameters()
                            .get("title"));
            assertTrue(fixture.invocations.isEmpty());
            assertEquals(0, fixture.approvals.get());
        }
    }

    @Test
    void explicitSessionApiRequiresAbsoluteNormalizedWorkdir(final JsonMapper mapper) {
        try (final var fixture = new Fixture(mapper, Map.of())) {
            final var registry = fixture.registry("A", true, true);
            assertThrows(
                    NullPointerException.class,
                    () -> fixture.answerer.ask(
                            "lookup",
                            "A",
                            null,
                            () -> false,
                            fixture.gate,
                            null,
                            null,
                            null,
                            null,
                            registry,
                            Set.of()));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> fixture.answerer.ask(
                            "lookup",
                            "A",
                            null,
                            () -> false,
                            fixture.gate,
                            null,
                            null,
                            null,
                            Path.of("relative"),
                            registry,
                            Set.of()));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> fixture.answerer.ask(
                            "lookup",
                            "A",
                            null,
                            () -> false,
                            fixture.gate,
                            null,
                            null,
                            null,
                            root.resolve(".."),
                            registry,
                            Set.of()));
            assertThrows(
                    NullPointerException.class,
                    () -> fixture.answerer.ask(
                            "lookup", "A", null, () -> false, fixture.gate, null, null, null, root, registry, null));
            assertTrue(fixture.requests.isEmpty());
        }
    }

    @Test
    void capturedCeilingIsImmutableAndCancellationDoesNotDisposeBorrowedTools(final JsonMapper mapper)
            throws Exception {
        try (final var fixture = new Fixture(mapper, Map.of())) {
            fixture.entered = new CountDownLatch(1);
            fixture.release = new CountDownLatch(1);
            final var ceiling = new HashSet<>(Set.of("client_lookup"));
            final var registry = fixture.registry("A", true, true);
            final var first = fixture.ask("A", registry, ceiling, () -> false);
            try {
                assertTrue(fixture.entered.await(5, TimeUnit.SECONDS));
                ceiling.clear();
            } finally {
                fixture.release.countDown();
            }
            assertEquals("done", first.get(5, TimeUnit.SECONDS).text());
            assertEquals(List.of("A"), fixture.invocations);

            fixture.entered = new CountDownLatch(1);
            fixture.release = new CountDownLatch(1);
            final var cancelled = new AtomicBoolean();
            final var second =
                    fixture.ask("B", fixture.registry("B", true, true), Set.of("client_lookup"), cancelled::get);
            try {
                assertTrue(fixture.entered.await(5, TimeUnit.SECONDS));
                cancelled.set(true);
            } finally {
                fixture.release.countDown();
            }
            final var error = assertThrows(ExecutionException.class, () -> second.get(5, TimeUnit.SECONDS));
            assertInstanceOf(InterruptedException.class, error.getCause());
            assertEquals(List.of("A"), fixture.invocations);
            assertEquals(
                    "client:A",
                    registry.tool("client_lookup")
                            .execute(Map.of(), ToolContext.empty(root))
                            .output());
        }
    }

    @Test
    void liveShellCommandBorrowsTheRuntimeOverlayAndCeiling(final JsonMapper mapper) throws Exception {
        try (final var fixture = new Fixture(mapper, Map.of())) {
            for (final var marker : List.of("A", "B", "A")) {
                final var registry = fixture.registry(marker, true, true);
                final var settings =
                        ChatSettings.EMPTY.withProviderId("default").withModel("model-" + marker);
                final var loop = new AgentLoop(root) {
                    @Override
                    public ToolRegistry effectiveToolRegistry() {
                        return registry;
                    }

                    @Override
                    public Set<String> availableToolNames() {
                        return Set.of("client_lookup");
                    }

                    @Override
                    public ChatSettings chatSettings() {
                        return settings;
                    }

                    @Override
                    public void close() {}
                };
                final var permissions = new PermissionService(TestConfigs.codingAgent(Map.of()), root);
                final var runtime = new SessionRuntime(loop, permissions, null, null, null, null, null, null, root);
                final var env = new ShellEnv(
                        null, null, new ShellSession().sessionId(marker).runtime(runtime), null);
                try {
                    final var outcome = new BtwSlashCommands(fixture.answerer)
                            .commands()
                            .getFirst()
                            .action()
                            .apply("lookup " + marker, env)
                            .toCompletableFuture()
                            .get(5, TimeUnit.SECONDS);
                    assertInstanceOf(ShellOutcome.Transcript.class, outcome);
                    if (fixture.invocations.size() == 3) {
                        fixture.entered = new CountDownLatch(1);
                        fixture.release = new CountDownLatch(1);
                        final var pending = new BtwSlashCommands(fixture.answerer)
                                .commands()
                                .getFirst()
                                .action()
                                .apply("blocked lookup", env)
                                .toCompletableFuture();
                        try {
                            assertTrue(fixture.entered.await(5, TimeUnit.SECONDS));
                            assertTrue(pending.cancel(true));
                        } finally {
                            fixture.release.countDown();
                        }
                        fixture.executor.shutdown();
                        assertTrue(fixture.executor.awaitTermination(5, TimeUnit.SECONDS));
                    }
                } finally {
                    runtime.close();
                }
            }
            assertEquals(List.of("A", "B", "A"), fixture.invocations, "cancelled side work must not execute a tool");
            assertTrue(fixture.requests.stream()
                    .noneMatch(request -> request.tools().stream()
                            .anyMatch(tool ->
                                    tool.function().parameters().toString().contains("app"))));
        }
    }

    private final class Fixture implements AutoCloseable {
        private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        private final List<String> invocations = new CopyOnWriteArrayList<>();
        private final List<ChatCompletionRequest> requests = new CopyOnWriteArrayList<>();
        private final AtomicInteger approvals = new AtomicInteger();
        private final ApprovalGate gate = (title, detail, name, args) -> {
            approvals.incrementAndGet();
            return ApprovalGate.Result.DENY;
        };
        private final BtwAnswerer answerer;
        private volatile CyclicBarrier barrier;
        private volatile CountDownLatch entered;
        private volatile CountDownLatch release;
        private final ChatClient client = new ChatClient() {
            @Override
            public ChatResult chat(final ChatCompletionRequest request, final BooleanSupplier cancelled)
                    throws InterruptedException {
                requests.add(request);
                if (request.messages().stream().noneMatch(message -> "tool".equals(message.role()))) {
                    if (barrier != null) {
                        try {
                            barrier.await(5, TimeUnit.SECONDS);
                        } catch (final Exception e) {
                            throw new IllegalStateException(e);
                        }
                    }
                    if (entered != null) {
                        entered.countDown();
                        assertTrue(release.await(5, TimeUnit.SECONDS));
                    }
                    return new ChatResult(
                            Message.assistant(
                                    null,
                                    List.of(new ToolCall(
                                            "lookup",
                                            "function",
                                            new ToolCall.FunctionCall("client_lookup", "{}"),
                                            0))),
                            null);
                }
                return new ChatResult(Message.assistant("done", null), null);
            }
        };

        private Fixture(final JsonMapper mapper, final Map<String, String> extra) {
            final var values = new HashMap<>(Map.of(
                    "yca.storageHome", root.resolve("storage").toString(), "yca.slashcommands.btw.tools", "true"));
            values.putAll(extra);
            final var configuration = TestConfigs.codingAgent(values);
            final var clock = Clock.systemUTC();
            final var store = new InMemorySessionStore(clock);
            final var emitter = new Emitter() {
                @Override
                public <T> void emit(final T event) {}
            };
            answerer = new BtwAnswerer(
                    client,
                    store,
                    configuration,
                    executor,
                    new InBandToolCallExtractor(new MarkupToolCallScanner()),
                    registry("app", true, true),
                    new ToolExecutionService(configuration, emitter, List.of()),
                    mapper,
                    null,
                    clock,
                    emitter,
                    null,
                    new ToolCallPairingRepair(store, clock),
                    null,
                    null);
        }

        private ToolRegistry registry(final String marker, final boolean readonly, final boolean usable) {
            final EmbeddedTool tool = new EmbeddedTool() {
                @Override
                public String name() {
                    return "client_lookup";
                }

                @Override
                public String description(final Path workdir) {
                    return "Session client " + marker;
                }

                @Override
                public ToolCategory category() {
                    return ToolCategory.MCP;
                }

                @Override
                public boolean readonly() {
                    return readonly;
                }

                @Override
                public boolean usable(final Path workdir) {
                    return usable;
                }

                @Override
                public Map<String, Object> schema() {
                    return Map.of("type", "object", "title", marker);
                }

                @Override
                public ToolResult execute(final Map<String, Object> args, final ToolContext context) {
                    assertEquals(root, context.projectDir());
                    if (context.toolRegistry() != null) {
                        assertSame(
                                this,
                                context.toolRegistry().tool(name()),
                                "tool context must carry the exact borrowed overlay");
                    }
                    invocations.add(marker);
                    return ToolResult.ok("client:" + marker);
                }
            };
            return new ToolRegistry(null) {
                @Override
                public List<EmbeddedTool> tools() {
                    return List.of(tool);
                }
            }.withTools(List.of());
        }

        private CompletableFuture<BtwAnswerer.BtwAnswer> ask(
                final String marker,
                final ToolRegistry registry,
                final Set<String> ceiling,
                final BooleanSupplier cancelled) {
            return answerer.ask(
                            "lookup " + marker,
                            marker,
                            null,
                            cancelled,
                            gate,
                            ChatSettings.EMPTY.withProviderId("default").withModel("model-" + marker),
                            null,
                            null,
                            root,
                            registry,
                            ceiling)
                    .toCompletableFuture();
        }

        @Override
        public void close() {
            executor.close();
            answerer.release();
            client.close();
        }
    }
}
