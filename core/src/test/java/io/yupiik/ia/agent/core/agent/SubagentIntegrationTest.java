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
package io.yupiik.ia.agent.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.configuration.AgentConfiguration;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ProviderConfiguration;
import io.yupiik.ia.agent.core.configuration.TestConfigs;
import io.yupiik.ia.agent.core.context.BackoffSleeper;
import io.yupiik.ia.agent.core.context.ContextManager;
import io.yupiik.ia.agent.core.context.UsageTracker;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatCompletionRequest;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.PauseEvent;
import io.yupiik.ia.agent.core.llm.Tool;
import io.yupiik.ia.agent.core.llm.ToolCall;
import io.yupiik.ia.agent.core.session.SessionEntry;
import io.yupiik.ia.agent.core.session.SessionStore;
import io.yupiik.ia.agent.core.snapshot.SnapshotStore;
import io.yupiik.ia.agent.core.todo.TodoItem;
import io.yupiik.ia.agent.core.todo.TodoStore;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.tools.ToolExecutionService;
import io.yupiik.ia.agent.core.tools.ToolRegistry;
import io.yupiik.ia.agent.core.tools.ToolResult;
import io.yupiik.ia.agent.core.tools.builtin.CheckSubagentsTool;
import io.yupiik.ia.agent.core.wiring.PrecisionDurationMarkerProvider;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import io.yupiik.ia.agent.test.TestJsonMapper;
import io.yupiik.llm.client.model.LlmStreamEvent;
import io.yupiik.llm.client.model.LlmStreamEvent.End;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

@ExtendWith(TestJsonMapper.class)
class SubagentIntegrationTest {
    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    @TempDir
    Path storageHome;

    private JsonMapper mapper;

    @BeforeEach
    void bindMapper(final JsonMapper mapper) {
        this.mapper = mapper;
    }

    private final List<AgentEvent> events = new ArrayList<>();

    /**
     * Waits for a {@link AgentEvent.SubagentCompleted} to be published and returns the snapshot.
     * The subagent runner completes the state (which is what {@code awaitResult()} waits for) <b>before</b> emitting the
     * event, so asserting on the collected events right after the result is visible is a race.
     */
    private List<AgentEvent.SubagentCompleted> awaitCompletions(final long timeout, final TimeUnit unit)
            throws InterruptedException {
        final var deadline = System.nanoTime() + unit.toNanos(timeout);
        List<AgentEvent.SubagentCompleted> completions;
        do {
            synchronized (events) {
                completions = events.stream()
                        .filter(AgentEvent.SubagentCompleted.class::isInstance)
                        .map(AgentEvent.SubagentCompleted.class::cast)
                        .toList();
            }
            if (!completions.isEmpty()) {
                return completions;
            }
            Thread.sleep(5);
        } while (System.nanoTime() < deadline);
        return completions;
    }

    private List<AgentEvent> snapshot() {
        synchronized (events) {
            return List.copyOf(events);
        }
    }

    private CodingAgentConfiguration config(final Map<String, String> overrides) {
        final var entries = new HashMap<String, String>();
        entries.put("yca.storageHome", storageHome.toString());
        entries.put("yca.subagents.enabled", "true");
        entries.put("yca.subagents.maxConcurrent", "2");
        entries.put("yca.llm.maxConcurrent", "4");
        entries.put("yca.tools.parallelReadonly", "true");
        entries.putAll(overrides);
        return TestConfigs.codingAgent(entries);
    }

    private ProviderManager providerManager(final int maxConcurrent) {
        return new ProviderManager() {
            @Override
            public ProviderConfiguration current() {
                return new ProviderConfiguration(
                        "default",
                        "http://test",
                        "key",
                        "model",
                        io.yupiik.ia.agent.core.llm.ApiFlavor.AUTO,
                        -1,
                        -1,
                        "",
                        null,
                        List.of(),
                        maxConcurrent,
                        300,
                        "",
                        Map.of(),
                        Map.of(),
                        "",
                        null,
                        null,
                        null,
                        null,
                        null);
            }
        };
    }

    private SessionStore noopStore() {
        return new SessionStore() {
            @Override
            public void append(final String id, final SessionEntry entry) {}
        };
    }

    private SnapshotStore noopSnapshotStore() {
        return new SnapshotStore() {
            @Override
            public synchronized Checkpoint save(final String sessionId, final String label) {
                return new Checkpoint(label, 0);
            }

            @Override
            public synchronized Retained restore(final String sessionId, final String summary) {
                return new Retained(summary);
            }

            @Override
            public synchronized void cancel(final String sessionId) {}

            @Override
            public String checkpointLabel(final String sessionId) {
                return null;
            }

            @Override
            public List<Retained> retained(final String sessionId) {
                return List.of();
            }
        };
    }

    private TodoStore noopTodoStore() {
        return new TodoStore() {
            @Override
            public List<TodoItem> all() {
                return List.of();
            }

            @Override
            public synchronized List<TodoItem> replace(final List<TodoItem> items) {
                return items;
            }
        };
    }

    private ContextManager noPruningManager() {
        return new ContextManager() {
            @Override
            public boolean pruneNeeded(final List<Message> messages, final int extraToolTokens) {
                return false;
            }

            @Override
            public boolean compactNeeded(final List<Message> messages, final int extraToolTokens) {
                return false;
            }
        };
    }

    private ToolExecutionService directExecutions() {
        return new ToolExecutionService() {
            @Override
            public ToolResult execute(
                    final EmbeddedTool tool, final Map<String, Object> args, final ToolContext context) {
                return tool.execute(args, context);
            }
        };
    }

    private ToolRegistry barrierRegistry(final CyclicBarrier barrier, final String... names) {
        return new ToolRegistry(null) {
            @Override
            public List<Tool> toWireTools() {
                return List.of();
            }

            @Override
            public EmbeddedTool tool(final String name) {
                if (!List.of(names).contains(name)) {
                    return null;
                }
                return new EmbeddedTool() {
                    @Override
                    public String name() {
                        return name;
                    }

                    @Override
                    public String description() {
                        return "test tool";
                    }

                    @Override
                    public ToolCategory category() {
                        return ToolCategory.READ;
                    }

                    @Override
                    public Map<String, Object> schema() {
                        return Map.of();
                    }

                    @Override
                    public boolean readonly() {
                        return true;
                    }

                    @Override
                    public ToolResult execute(final Map<String, Object> args) {
                        try {
                            barrier.await(250, TimeUnit.MILLISECONDS);
                        } catch (final Exception e) {
                            return ToolResult.error("not concurrent: " + e);
                        }
                        return ToolResult.ok("done-" + name.substring(name.length() - 1));
                    }
                };
            }
        };
    }

    private ToolRegistry cancellableToolRegistry(final CountDownLatch releaseLatch, final String... names) {
        return new ToolRegistry(null) {
            @Override
            public List<Tool> toWireTools() {
                return List.of();
            }

            @Override
            public EmbeddedTool tool(final String name) {
                if (!List.of(names).contains(name)) {
                    return null;
                }
                return new EmbeddedTool() {
                    @Override
                    public String name() {
                        return name;
                    }

                    @Override
                    public String description() {
                        return "test tool";
                    }

                    @Override
                    public ToolCategory category() {
                        return ToolCategory.READ;
                    }

                    @Override
                    public Map<String, Object> schema() {
                        return Map.of();
                    }

                    @Override
                    public boolean readonly() {
                        return true;
                    }

                    @Override
                    public ToolResult execute(final Map<String, Object> args) {
                        try {
                            releaseLatch.await(10, TimeUnit.SECONDS);
                        } catch (final InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return ToolResult.error("cancelled");
                        } catch (final Exception e) {
                            return ToolResult.error("not concurrent: " + e);
                        }
                        return ToolResult.ok("done-" + name.substring(name.length() - 1));
                    }
                };
            }
        };
    }

    private ToolRegistry emptyToolRegistry() {
        return new ToolRegistry(null) {
            @Override
            public List<Tool> toWireTools() {
                return List.of();
            }

            @Override
            public EmbeddedTool tool(final String name) {
                return null;
            }
        };
    }

    private ChatClient scriptedClient(final Deque<ScriptedReply> replies, final List<List<Message>> requests) {
        return new ChatClient() {
            @Override
            public Message stream(
                    final ChatCompletionRequest request,
                    final Consumer<PauseEvent> pauseListener,
                    final Consumer<LlmStreamEvent> onDelta,
                    final BooleanSupplier cancelled)
                    throws IOException {
                final var reply = replies.poll();
                if (reply == null) {
                    throw new IllegalStateException("no scripted reply left");
                }
                requests.add(List.copyOf(request.messages()));
                if (reply.error() != null) {
                    throw reply.error();
                }
                if (reply.ioError() != null) {
                    throw reply.ioError();
                }
                if (reply.finishReason() != null) {
                    onDelta.accept(new End(reply.finishReason(), null));
                }
                return reply.toMessage();
            }
        };
    }

    private static class ScriptedReply {
        private final String content;
        private final String finishReason;
        private final RuntimeException error;
        private final IOException ioError;
        private final List<ToolCall> toolCalls;

        ScriptedReply(final String content, final String finishReason) {
            this(content, finishReason, null, null, null);
        }

        ScriptedReply(final String content, final String finishReason, final RuntimeException error) {
            this(content, finishReason, error, null, null);
        }

        ScriptedReply(final IOException ioError) {
            this(null, null, null, ioError, null);
        }

        private ScriptedReply(
                final String content,
                final String finishReason,
                final RuntimeException error,
                final IOException ioError,
                final List<ToolCall> toolCalls) {
            this.content = content;
            this.finishReason = finishReason;
            this.error = error;
            this.ioError = ioError;
            this.toolCalls = toolCalls;
        }

        static ScriptedReply toolCall(final String toolName, final String args) {
            return new ScriptedReply(
                    null,
                    "tool_calls",
                    null,
                    null,
                    List.of(new ToolCall("tc-" + toolName, "function", new ToolCall.FunctionCall(toolName, args), 0)));
        }

        String content() {
            return content;
        }

        String finishReason() {
            return finishReason;
        }

        RuntimeException error() {
            return error;
        }

        IOException ioError() {
            return ioError;
        }

        List<ToolCall> toolCalls() {
            return toolCalls;
        }

        Message toMessage() {
            return new Message("assistant", content, null, toolCalls, null, null);
        }
    }

    private static final class TestSubagentRunner extends SubagentRunner {
        private final CountDownLatch startLatch = new CountDownLatch(1);
        private volatile SubagentRegistry.SubagentState lastState;

        TestSubagentRunner(final SubagentRunner delegate) {
            super(
                    delegate.chatClient,
                    delegate.toolRegistry,
                    delegate.toolExecutionService,
                    delegate.configuration,
                    delegate.contextWindowCache,
                    delegate.memoryStore,
                    delegate.emitter,
                    delegate.mapper,
                    delegate.structuralCodeGraph,
                    delegate.providerManager,
                    delegate.agentProvider,
                    delegate.todoPromptResolver,
                    delegate.mavenRepositoryKnowledge,
                    delegate.clock,
                    delegate.executor,
                    delegate.subagentRegistry,
                    delegate.precisionDurationMarker,
                    delegate.backoffSleeper,
                    null,
                    delegate.quotaStore);
        }

        @Override
        public void runAsync(
                final SubagentRegistry.SubagentState state,
                final SessionStore sessionStore,
                final SnapshotStore snapshotStore,
                final TodoStore todoStore,
                final ToolContext parentContext,
                final BooleanSupplier cancelled) {
            lastState = state;
            startLatch.countDown();
            super.runAsync(state, sessionStore, snapshotStore, todoStore, parentContext, cancelled);
        }

        void awaitStart() throws InterruptedException {
            assertTrue(startLatch.await(5, TimeUnit.SECONDS));
        }

        void awaitCompletion(final SubagentRegistry.SubagentState state) throws InterruptedException {
            state.awaitResult();
        }

        SubagentRegistry.SubagentState lastState() {
            return lastState;
        }
    }

    private SubagentRegistry newRegistry(final CodingAgentConfiguration cfg, final int providerMaxConcurrent) {
        return new SubagentRegistry(cfg, providerManager(providerMaxConcurrent));
    }

    private SystemPromptBuilder mockPromptBuilder() {
        return new SystemPromptBuilder() {
            @Override
            public String build(final String prompt, final AgentConfiguration agent) {
                return "You are a test agent.\n" + (prompt != null ? prompt : "");
            }
        };
    }

    private TestSubagentRunner newRunner(
            final ChatClient client,
            final ToolRegistry registry,
            final SubagentRegistry reg,
            final CodingAgentConfiguration cfg) {
        return newRunner(client, registry, reg, cfg, EXECUTOR);
    }

    private TestSubagentRunner newRunner(
            final ChatClient client,
            final ToolRegistry registry,
            final SubagentRegistry reg,
            final CodingAgentConfiguration cfg,
            final ExecutorService executor) {
        final var mockUsageTracker = new UsageTracker() {
            @Override
            public void record(final io.yupiik.ia.agent.core.llm.Usage usage) {}

            @Override
            public int estimateContext(final List<Message> messages, final int extraToolTokens) {
                return 100;
            }

            @Override
            public double contextPercent(final int estimatedTokens) {
                return 1;
            }

            @Override
            public boolean lastResolutionWasAuto() {
                return false;
            }

            @Override
            public int rememberShrinkOnOverflow() {
                return 1000;
            }
        };
        final var mockContextManager = new ContextManager() {
            @Override
            public boolean pruneNeeded(final List<Message> messages, final int extraToolTokens) {
                return false;
            }

            @Override
            public boolean compactNeeded(final List<Message> messages, final int extraToolTokens) {
                return false;
            }
        };

        final var baseRunner = new SubagentRunner(
                client,
                registry,
                directExecutions(),
                cfg,
                null,
                null,
                new Emitter() {
                    @Override
                    public <T> void emit(final T event) {
                        synchronized (events) {
                            if (event instanceof AgentEvent agentEvent) {
                                events.add(agentEvent);
                            }
                        }
                    }
                },
                mapper,
                new io.yupiik.ia.agent.core.codegraph.StructuralCodeGraph() {
                    @Override
                    public void ensureReadyAsync() {}
                },
                new ProviderManager() {
                    @Override
                    public ResolvedModel resolveDefault() {
                        return new ResolvedModel(null, "test-model");
                    }

                    @Override
                    public ProviderConfiguration current() {
                        return null;
                    }
                },
                null,
                new TodoPromptResolver(),
                null,
                Clock.systemUTC(),
                executor,
                reg,
                new PrecisionDurationMarkerProvider(),
                new BackoffSleeper(),
                null, // secret redactor
                null); // quota store
        return new TestSubagentRunner(baseRunner);
    }

    @Test
    void rejectedSchedulingCompletesTheChildAndReleasesCapacityExactlyOnce() throws Exception {
        final var cfg = config(Map.of("yca.subagents.maxConcurrent", "1"));
        final var reg = newRegistry(cfg, 3);
        final var rejectingExecutor = Executors.newSingleThreadExecutor();
        rejectingExecutor.shutdown();
        final var runner = newRunner(
                scriptedClient(new ArrayDeque<>(), new ArrayList<>()),
                emptyToolRegistry(),
                reg,
                cfg,
                rejectingExecutor);

        final var rejected = reg.start("parent", "rejected task").orElseThrow();
        runner.runAsync(rejected, noopStore(), noopSnapshotStore(), noopTodoStore(), () -> false);
        rejected.awaitResult();
        assertEquals(
                SubagentRegistry.SubagentResult.Status.FAILED, rejected.result().status());
        assertEquals(0, reg.activeCount());

        final var replacement = reg.start("parent", "replacement").orElseThrow();
        reg.complete(rejected.id(), SubagentRegistry.SubagentResult.error("duplicate completion", ""));
        assertEquals(1, reg.activeCount(), "a duplicate completion must not release another child's permit");
        reg.cancel(replacement.id());
        assertEquals(0, reg.activeCount());
    }

    @Test
    void subagentRunsToCompletionAndResultCollected() throws Exception {
        final var cfg = config(Map.of());
        final var reg = newRegistry(cfg, 4);
        final var requests = new ArrayList<List<Message>>();
        final var replies = new ArrayDeque<>(List.of(new ScriptedReply("subagent result", "stop")));
        final var runner = newRunner(scriptedClient(replies, requests), emptyToolRegistry(), reg, cfg);
        final var checkTool = new CheckSubagentsTool(reg, mapper);

        final var state = reg.start("parent", "task").orElseThrow();
        runner.runAsync(state, noopStore(), noopSnapshotStore(), noopTodoStore(), () -> false);
        runner.awaitCompletion(state);

        final var collect = checkTool.execute(Map.of("action", "collect", "subagent_id", state.id()));
        assertTrue(collect.success(), collect.output());
        assertTrue(collect.output().contains("subagent result"));
        assertTrue(collect.output().contains("\"completed\":true"));

        // State removed after collect
        assertTrue(reg.get(state.id()).isEmpty());
        // Semaphore released
        assertTrue(reg.start("parent", "t2").isPresent());
    }

    @Test
    void multipleSubagentsRunInParallelWithinLimit() throws Exception {
        final var cfg = config(Map.of("yca.subagents.maxConcurrent", "2"));
        final var reg = newRegistry(cfg, 4); // provider max 4 -> actualMax = min(3, 2) = 2
        final var barrier = new CyclicBarrier(2);
        final var toolReg = barrierRegistry(barrier, "read_a", "read_b");

        final var replies1 = new ArrayDeque<ScriptedReply>();
        replies1.add(ScriptedReply.toolCall("read_a", "{}"));
        replies1.add(new ScriptedReply("done1", "stop"));
        final var replies2 = new ArrayDeque<ScriptedReply>();
        replies2.add(ScriptedReply.toolCall("read_b", "{}"));
        replies2.add(new ScriptedReply("done2", "stop"));

        final var req1 = new ArrayList<List<Message>>();
        final var req2 = new ArrayList<List<Message>>();
        final var runner1 = newRunner(scriptedClient(replies1, req1), toolReg, reg, cfg);
        final var runner2 = newRunner(scriptedClient(replies2, req2), toolReg, reg, cfg);

        final var s1 = reg.start("parent", "t1").orElseThrow();
        final var s2 = reg.start("parent", "t2").orElseThrow();
        runner1.runAsync(s1, noopStore(), noopSnapshotStore(), noopTodoStore(), () -> false);
        runner2.runAsync(s2, noopStore(), noopSnapshotStore(), noopTodoStore(), () -> false);

        // Wait for both tools to execute concurrently (barrier)
        assertTrue(barrier.await(3, TimeUnit.SECONDS) >= 0, "both tools should execute concurrently");

        runner1.awaitCompletion(s1);
        runner2.awaitCompletion(s2);

        final var checkTool = new CheckSubagentsTool(reg, mapper);
        final var c1 = checkTool.execute(Map.of("action", "collect", "subagent_id", s1.id()));
        final var c2 = checkTool.execute(Map.of("action", "collect", "subagent_id", s2.id()));
        assertTrue(c1.success(), c1.output());
        assertTrue(c2.success(), c2.output());
    }

    @Test
    void thirdSubagentRejectedWhenAtLimit() {
        final var cfg = config(Map.of("yca.subagents.maxConcurrent", "1"));
        final var reg = newRegistry(cfg, 3); // provider max 3 -> actualMax = min(2, 1) = 1

        assertTrue(reg.start("parent", "t1").isPresent());
        assertFalse(reg.start("parent", "t2").isPresent(), "second should be rejected at limit");
    }

    @Test
    void subagentCancellationStopsExecution() throws Exception {
        final var cfg = config(Map.of());
        final var reg = newRegistry(cfg, 4);
        final var releaseLatch = new CountDownLatch(1);
        final var toolReg = cancellableToolRegistry(releaseLatch, "slow_read");

        final var replies = new ArrayDeque<ScriptedReply>();
        replies.add(ScriptedReply.toolCall("slow_read", "{}"));
        replies.add(new ScriptedReply("never reached", "stop"));
        final var requests = new ArrayList<List<Message>>();

        final var runner = newRunner(scriptedClient(replies, requests), toolReg, reg, cfg);
        final var checkTool = new CheckSubagentsTool(reg, mapper);

        final var state = reg.start("parent", "long task").orElseThrow();
        runner.runAsync(state, noopStore(), noopSnapshotStore(), noopTodoStore(), () -> false);
        runner.awaitStart();

        // Tool is now waiting on releaseLatch - cancel it
        final var cancel = checkTool.execute(Map.of("action", "cancel", "subagent_id", state.id()));
        assertTrue(cancel.success(), cancel.output());
        assertTrue(cancel.output().contains("\"cancelled\":true"));

        // Release the latch so the tool can complete (and the subagent can process cancellation)
        releaseLatch.countDown();

        // Semaphore released
        assertTrue(reg.start("parent", "t2").isPresent());
    }

    @Test
    void subagentErrorPropagation() throws Exception {
        final var cfg = config(Map.of());
        final var reg = newRegistry(cfg, 4);
        final var replies =
                new ArrayDeque<>(List.of(new ScriptedReply("partial", "tool_calls", new RuntimeException("boom!"))));
        final var requests = new ArrayList<List<Message>>();

        final var runner = newRunner(scriptedClient(replies, requests), emptyToolRegistry(), reg, cfg);
        final var checkTool = new CheckSubagentsTool(reg, mapper);

        final var state = reg.start("parent", "failing task").orElseThrow();
        runner.runAsync(state, noopStore(), noopSnapshotStore(), noopTodoStore(), () -> false);
        runner.awaitCompletion(state);

        final var collect = checkTool.execute(Map.of("action", "collect", "subagent_id", state.id()));
        assertFalse(collect.success(), collect.output());
        assertTrue(collect.output().contains("error"));
    }

    @Test
    void parentCancelledStopsSubagent() throws Exception {
        final var cfg = config(Map.of());
        final var reg = newRegistry(cfg, 4);
        final var releaseLatch = new CountDownLatch(1);
        final var toolReg = cancellableToolRegistry(releaseLatch, "slow_read");

        final var replies = new ArrayDeque<ScriptedReply>();
        replies.add(ScriptedReply.toolCall("slow_read", "{}"));
        replies.add(new ScriptedReply("never reached", "stop"));
        final var requests = new ArrayList<List<Message>>();

        final var runner = newRunner(scriptedClient(replies, requests), toolReg, reg, cfg);
        final var checkTool = new CheckSubagentsTool(reg, mapper);

        final var cancelled = new AtomicBoolean(false);
        final var state = reg.start("parent", "task").orElseThrow();
        runner.runAsync(state, noopStore(), noopSnapshotStore(), noopTodoStore(), cancelled::get);
        runner.awaitStart();

        // Tool is waiting on releaseLatch - parent cancels
        cancelled.set(true);

        // Release the latch so the in-flight tool returns right away: the cancellation probe is
        // only read at iteration boundaries, so the loop cannot make progress until this tool
        // completed - without the release the tool would block for its whole 10s timeout and the
        // test would assert the same thing 10s later
        releaseLatch.countDown();

        // Wait for sub-agent to process cancellation
        state.awaitResult();

        // Sub-agent should have been cancelled
        final var collect = checkTool.execute(Map.of("action", "collect", "subagent_id", state.id()));
        assertFalse(collect.success(), collect.output());

        // ... and reported as a cancellation, not as a failure: a cancelled child must never
        // render as a red "Subagent failed" card in the parent transcript
        final var completions = awaitCompletions(10, TimeUnit.SECONDS);
        assertEquals(1, completions.size(), "one completion event expected, got: " + snapshot());
        final var completion = completions.getFirst();
        assertTrue(completion.cancelled(), "a cancelled sub-agent must be flagged as cancelled: " + completion);
        assertFalse(completion.success(), "a cancelled sub-agent did not succeed");
    }

    @Test
    void subagentInheritsSystemPromptAndTools() throws Exception {
        final var cfg = config(Map.of());
        final var reg = newRegistry(cfg, 4);
        final var toolReg = new ToolRegistry(null) {
            @Override
            public List<Tool> toWireTools() {
                return List.of();
            }

            @Override
            public EmbeddedTool tool(final String name) {
                if ("read".equals(name)) {
                    return new EmbeddedTool() {
                        @Override
                        public String name() {
                            return "read";
                        }

                        @Override
                        public String description() {
                            return "read";
                        }

                        @Override
                        public ToolCategory category() {
                            return ToolCategory.READ;
                        }

                        @Override
                        public Map<String, Object> schema() {
                            return Map.of();
                        }

                        @Override
                        public boolean readonly() {
                            return true;
                        }

                        @Override
                        public ToolResult execute(final Map<String, Object> args) {
                            return ToolResult.ok("file content");
                        }
                    };
                }
                return null;
            }
        };

        final var replies = new ArrayDeque<ScriptedReply>();
        replies.add(ScriptedReply.toolCall("read", "{}"));
        replies.add(new ScriptedReply("done", "stop"));
        final var requests = new ArrayList<List<Message>>();

        final var runner = newRunner(scriptedClient(replies, requests), toolReg, reg, cfg);
        final var checkTool = new CheckSubagentsTool(reg, mapper);

        final var state = reg.start("parent", "read something").orElseThrow();
        runner.runAsync(state, noopStore(), noopSnapshotStore(), noopTodoStore(), () -> false);
        runner.awaitCompletion(state);

        final var collect = checkTool.execute(Map.of("action", "collect", "subagent_id", state.id()));
        assertTrue(collect.success(), collect.output());
    }

    @Test
    void collectWithoutPollWorks() throws Exception {
        final var cfg = config(Map.of());
        final var reg = newRegistry(cfg, 4);
        final var replies = new ArrayDeque<>(List.of(new ScriptedReply("direct collect", "stop")));
        final var requests = new ArrayList<List<Message>>();

        final var runner = newRunner(scriptedClient(replies, requests), emptyToolRegistry(), reg, cfg);
        final var checkTool = new CheckSubagentsTool(reg, mapper);

        final var state = reg.start("parent", "task").orElseThrow();
        runner.runAsync(state, noopStore(), noopSnapshotStore(), noopTodoStore(), () -> false);
        runner.awaitCompletion(state);

        // Collect directly without polling first
        final var collect = checkTool.execute(Map.of("action", "collect", "subagent_id", state.id()));
        assertTrue(collect.success(), collect.output());
        assertTrue(collect.output().contains("direct collect"));
    }

    @AfterAll
    static void closeExecutor() {
        EXECUTOR.shutdownNow();
    }
}
