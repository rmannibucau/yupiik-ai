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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.codegraph.StructuralCodeGraph;
import io.yupiik.ia.agent.core.configuration.AgentConfiguration;
import io.yupiik.ia.agent.core.configuration.AgentMode;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.TestConfigs;
import io.yupiik.ia.agent.core.context.ContextManager;
import io.yupiik.ia.agent.core.context.UsageTracker;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatCompletionRequest;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.PauseEvent;
import io.yupiik.ia.agent.core.llm.Tool;
import io.yupiik.ia.agent.core.llm.ToolCall;
import io.yupiik.ia.agent.core.llm.Usage;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.runtime.StatusReporter;
import io.yupiik.ia.agent.core.session.SessionEntry;
import io.yupiik.ia.agent.core.session.SessionStore;
import io.yupiik.ia.agent.core.todo.TodoItem;
import io.yupiik.ia.agent.core.todo.TodoStore;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.tools.ToolExecutionService;
import io.yupiik.ia.agent.core.tools.ToolRegistry;
import io.yupiik.ia.agent.core.tools.ToolResult;
import io.yupiik.ia.agent.core.tools.builtin.ReadFileTool;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import io.yupiik.ia.agent.test.TestJsonMapper;
import io.yupiik.llm.client.model.LlmStreamEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/** Public-loop regressions: coverage is what the model received, not what it requested. */
@ExtendWith(TestJsonMapper.class)
class ExplorationPlateauRegressionTest {
    @TempDir
    Path home;

    private JsonMapper mapper;
    private Path source;
    private int nextCall;

    @BeforeEach
    void prepare(final JsonMapper mapper) throws IOException {
        this.mapper = mapper;
        source = home.resolve("source.txt");
        Files.write(
                source,
                IntStream.rangeClosed(1, 500).mapToObj(i -> "source line " + i).toList());
    }

    @Test
    void negativeOneNudgesAtFirstRedundantRoundWithoutStopping() {
        final var fixture = fixture(Map.of("yca.llm.plateauRounds", "-1"));
        assertEquals("done", fixture.run(read(1, 20), read(2, 10), answer()));
        assertEquals(1, fixture.notices("exploration-plateau nudge"));
        assertEquals(3, fixture.requests.size());
        fixture.assertNoErrors();
    }

    @Test
    void negativeOneWithFeedbackNoneNeverNudges() {
        final var fixture = fixture(Map.of(
                "yca.llm.plateauRounds", "-1", "yca.provider.providers.0.models.0.behavior.plateauFeedback", "none"));
        assertEquals("done", fixture.run(read(1, 20), read(2, 10), read(3, 5), answer()));
        assertEquals(0, fixture.notices("exploration-plateau nudge"));
        fixture.assertNoErrors();
    }

    @Test
    void positiveLimitKeepsHardStopWithFeedbackNone() {
        final var fixture = fixture(Map.of(
                "yca.llm.plateauRounds", "2",
                "yca.provider.providers.0.models.0.behavior.plateauFeedback", "none"));
        assertNull(fixture.run(read(1, 30), read(2, 10), read(3, 10), answer()));
        assertEquals(3, fixture.requests.size());
        assertEquals(0, fixture.notices("exploration-plateau nudge"));
        fixture.assertError("Exploration plateau");
    }

    @Test
    void positiveLimitNudgesOnceBeforeStopping() {
        final var fixture = fixture(Map.of(
                "yca.llm.plateauRounds", "4",
                "yca.provider.providers.0.models.0.behavior.plateauFeedback", "nudge_2"));
        assertNull(fixture.run(read(1, 30), read(2, 10), read(3, 10), read(4, 10), read(5, 10), answer()));
        assertEquals(5, fixture.requests.size());
        assertEquals(1, fixture.notices("exploration-plateau nudge"));
        fixture.assertError("Exploration plateau");
    }

    @Test
    void successfulWriteInvalidatesPreviouslyDeliveredReadCoverage() {
        final var fixture = plateauStopsAtOne();
        fixture.extraTools.add(tool("write_file", ToolCategory.EDIT, false, ToolResult.ok("updated")));
        assertEquals(
                "done",
                fixture.run(
                        read(1, 30), batch(call("write_file", Map.of("path", source.toString(), "content", "updated"))),
                        read(2, 10), answer()));
        fixture.assertNoErrors();
    }

    @Test
    void plateauNudgeRemainsOneShotAfterNovelCoverageResetsTheStreak() {
        final var fixture = fixture(Map.of("yca.llm.plateauRounds", "-1"));
        assertEquals("done", fixture.run(read(1, 20), read(2, 10), read(100, 20), read(101, 10), answer()));
        assertEquals(1, fixture.notices("exploration-plateau nudge"));
        fixture.assertNoErrors();
    }

    @Test
    void adjacentSmallReadsInsideTheSameFormerBucketAreNovel() {
        final var fixture = plateauStopsAtOne();
        assertEquals("done", fixture.run(read(1, 10), read(11, 10), read(21, 10), answer()));
        fixture.assertNoErrors();
    }

    @Test
    void partiallyOverlappingReadIsNovelWhenItDeliversAnUnseenTail() {
        final var fixture = plateauStopsAtOne();
        assertEquals("done", fixture.run(read(1, 20), read(15, 20), answer()));
        fixture.assertNoErrors();
    }

    @Test
    void containedReadIsRedundantEvenWhenItsRequestedBucketDiffers() {
        final var fixture = plateauStopsAtOne();
        assertNull(fixture.run(read(1, 400), read(201, 10), answer()));
        assertEquals(2, fixture.requests.size());
        fixture.assertError("Exploration plateau");
    }

    @Test
    void adjacentDeliveredIntervalsMergeForLaterContainedRead() {
        final var fixture = plateauStopsAtOne();
        assertNull(fixture.run(read(1, 20), read(21, 20), read(10, 20), answer()));
        assertEquals(3, fixture.requests.size());
        fixture.assertError("Exploration plateau");
    }

    @Test
    void requestedLimitDoesNotInventCoverageBeyondEof() {
        // The first read asks for 1000 lines but only receives 500. Appending and reading the
        // new tail must remain novel, even though that tail lay inside the old requested range.
        final var fixture = plateauStopsAtOne();
        // A scripted client callback changes the file, not a tool/write marker that could reset
        // the coverage tracker and hide a requested-versus-delivered regression.
        fixture.beforeReply = index -> {
            if (index == 1) {
                try {
                    Files.writeString(source, "new line\n", StandardOpenOption.APPEND);
                } catch (final IOException e) {
                    throw new IllegalStateException(e);
                }
            }
        };
        assertEquals("done", fixture.run(read(1, 1000), read(501, 1), answer()));
        fixture.assertNoErrors();
    }

    @Test
    void unknownReadOutputIsNovelRatherThanGuessedFromArguments() {
        final var fixture = plateauStopsAtOne();
        fixture.readOverride = tool("read", ToolCategory.READ, true, ToolResult.ok("opaque read payload"));
        assertEquals("done", fixture.run(read(1, 20), read(2, 10), read(3, 5), answer()));
        fixture.assertNoErrors();
    }

    @Test
    void mixedRedundantReadAndFailureResetsPlateau() {
        assertMixedReset("failing", ToolCategory.READ, true, ToolResult.error("temporary read failure"));
    }

    @Test
    void mixedRedundantReadAndPendingWorkResetsPlateau() {
        assertMixedReset("pending_work", ToolCategory.READ, true, ToolResult.pending("worker is still running"));
    }

    @Test
    void mixedRedundantReadAndMutatingBashResetsPlateau() {
        assertMixedReset("bash", ToolCategory.BASH, false, ToolResult.ok("updated generated file"));
    }

    @Test
    void mixedRedundantReadAndUnclassifiedSuccessfulWorkResetsPlateau() {
        assertMixedReset("inspect_remote", ToolCategory.READ, true, ToolResult.ok("remote inspection complete"));
    }

    @Test
    void mixedRedundantReadAndRejectedUnknownToolResetsPlateau() {
        final var fixture = fixture(Map.of(
                "yca.llm.plateauRounds", "2", "yca.provider.providers.0.models.0.behavior.plateauFeedback", "none"));
        assertEquals(
                "done",
                fixture.run(
                        read(1, 30),
                        read(2, 10),
                        batch(readCall(3, 10), call("not_registered", Map.of())),
                        read(4, 10),
                        answer()));
        assertTrue(fixture.events.stream()
                .anyMatch(e -> e instanceof AgentEvent.ToolFinished finished
                        && finished.output().contains("Unknown tool")));
        fixture.assertNoErrors();
    }

    @Test
    void mixedRedundantReadAndDoomRefusalResetsPlateau() {
        final var fixture = fixture(Map.of(
                "yca.llm.plateauRounds",
                "2",
                "yca.provider.providers.0.models.0.behavior.plateauFeedback",
                "none",
                "yca.tools.repeatSuccessThreshold",
                "1"));
        fixture.extraTools.add(tool("bash", ToolCategory.BASH, false, ToolResult.ok("stable command output")));
        final var args = Map.<String, Object>of("command", "touch generated.txt");
        assertEquals(
                "done",
                fixture.run(
                        batch(call("bash", args)),
                        read(1, 30),
                        read(2, 10),
                        batch(readCall(3, 10), call("bash", args)),
                        read(4, 10),
                        answer()));
        assertEquals(1, fixture.notices("doom-loop refusal"));
        fixture.assertNoErrors();
    }

    @Test
    void plateauNudgeDoesNotRenewTheIterationWindow() {
        final var fixture = fixture(Map.of("yca.llm.maxIterations", "3", "yca.llm.plateauRounds", "-1"));
        assertNull(fixture.run(read(1, 30), read(2, 10), read(3, 10), answer()));
        assertEquals(1, fixture.notices("exploration-plateau nudge"));
        fixture.assertCappedAt(3);
    }

    @Test
    void explorationNudgeDoesNotRenewTheIterationWindow() {
        final var fixture = fixture(Map.of("yca.llm.maxIterations", "3", "yca.llm.explorationNudgeRounds", "2"));
        assertNull(fixture.run(read(1, 10), read(11, 10), read(21, 10), answer()));
        assertEquals(1, fixture.notices("exploration nudge"));
        fixture.assertCappedAt(3);
    }

    @Test
    void doomLoopNudgeDoesNotRenewTheIterationWindow() {
        final var fixture = fixture(Map.of("yca.llm.maxIterations", "3", "yca.tools.repeatSuccessThreshold", "1"));
        fixture.extraTools.add(tool("bash", ToolCategory.BASH, false, ToolResult.ok("stable command output")));
        final var args = Map.<String, Object>of("command", "touch generated.txt");
        assertNull(fixture.run(batch(call("bash", args)), batch(call("bash", args)), read(1, 10), answer()));
        assertEquals(1, fixture.notices("doom-loop refusal"));
        fixture.assertCappedAt(3);
    }

    @Test
    void textEndRoundGateDoesNotRenewTheIterationWindow() {
        final var fixture = fixture(Map.of(
                "yca.llm.maxIterations", "3",
                "yca.provider.providers.0.models.0.behavior.turnContinuesOnTextEnd", "true"));
        fixture.todos.items = List.of(new TodoItem("finish the work", "in_progress"));
        assertNull(fixture.run(
                read(1, 10),
                Message.assistant("still working", null),
                Message.assistant("not finished yet", null),
                answer()));
        assertEquals(2, fixture.notices("continuing the round automatically"));
        fixture.assertCappedAt(3);
    }

    @Test
    void unboundedWindowStillAllowsAnAnswerAfterBothNudges() {
        final var fixture = fixture(Map.of(
                "yca.llm.maxIterations", "-1", "yca.llm.plateauRounds", "-1", "yca.llm.explorationNudgeRounds", "1"));
        assertEquals("done", fixture.run(read(1, 30), read(2, 10), read(100, 10), read(101, 5), answer()));
        assertEquals(1, fixture.notices("exploration nudge"));
        assertEquals(1, fixture.notices("exploration-plateau nudge"));
        assertEquals(5, fixture.requests.size());
        fixture.assertNoErrors();
    }

    @Test
    void todoAutoContinuationStillStartsAFreshWindowAtTheCap() {
        final var fixture = fixture(Map.of(
                "yca.llm.maxIterations", "2", "yca.todos.continueOnUnfinished", "true", "yca.todos.maxContinues", "1"));
        fixture.todos.items = List.of(new TodoItem("finish the work", "in_progress"));
        fixture.extraTools.add(new EmbeddedTool() {
            @Override
            public String name() {
                return "close_todos";
            }

            @Override
            public String description() {
                return "complete the work";
            }

            @Override
            public ToolCategory category() {
                return ToolCategory.EDIT;
            }

            @Override
            public Map<String, Object> schema() {
                return Map.of("type", "object");
            }

            @Override
            public ToolResult execute(final Map<String, Object> args) {
                fixture.todos.items = List.of(new TodoItem("finish the work", "completed"));
                return ToolResult.ok("todo completed");
            }
        });
        assertEquals("done", fixture.run(read(1, 10), read(11, 10), batch(call("close_todos", Map.of())), answer()));
        assertEquals(4, fixture.requests.size());
        assertEquals(1, fixture.notices("continuing automatically"));
        fixture.assertNoErrors();
    }

    private void assertMixedReset(
            final String name, final ToolCategory category, final boolean readonly, final ToolResult result) {
        final var fixture = fixture(Map.of(
                "yca.llm.plateauRounds", "2", "yca.provider.providers.0.models.0.behavior.plateauFeedback", "none"));
        fixture.extraTools.add(tool(name, category, readonly, result));
        final var args = "bash".equals(name)
                ? Map.<String, Object>of("command", "touch generated.txt")
                : Map.<String, Object>of();
        assertEquals(
                "done",
                fixture.run(read(1, 30), read(2, 10), batch(readCall(3, 10), call(name, args)), read(4, 10), answer()));
        assertTrue(
                fixture.events.stream()
                        .anyMatch(e -> e instanceof AgentEvent.ToolFinished finished
                                && finished.output().contains(result.output())),
                "the mixed call must actually finish");
        fixture.assertNoErrors();
    }

    private Fixture plateauStopsAtOne() {
        return fixture(Map.of(
                "yca.llm.plateauRounds", "1", "yca.provider.providers.0.models.0.behavior.plateauFeedback", "none"));
    }

    private Fixture fixture(final Map<String, String> overrides) {
        final var entries = new HashMap<String, String>();
        entries.put("yca.storageHome", home.toString());
        entries.put("yca.home", home.resolve("home").toString());
        entries.put("yca.mode", "build");
        entries.put("yca.permissions.mode", "yolo");
        entries.put("yca.provider.providers.length", "1");
        entries.put("yca.provider.providers.0.id", "default");
        entries.put("yca.provider.providers.0.baseUrl", "http://localhost:1/v1/chat/completions");
        entries.put("yca.provider.providers.0.apiKey", "test-key");
        entries.put("yca.provider.providers.0.model", "test-model");
        entries.put("yca.provider.providers.0.models.length", "1");
        entries.put("yca.provider.providers.0.models.0.model", "test-model");
        entries.put("yca.llm.maxIterations", "20");
        entries.put("yca.llm.explorationNudgeRounds", "0");
        entries.put("yca.llm.retriesMaxAttempts", "1");
        entries.put("yca.todos.continueOnUnfinished", "false");
        entries.putAll(overrides);
        return new Fixture(TestConfigs.codingAgent(entries));
    }

    private Message read(final int offset, final int limit) {
        return batch(readCall(offset, limit));
    }

    private ToolCall readCall(final int offset, final int limit) {
        return call("read", Map.of("path", source.toString(), "offset", offset, "limit", limit));
    }

    private ToolCall call(final String name, final Map<String, Object> args) {
        return new ToolCall(
                "call-" + nextCall++, "function", new ToolCall.FunctionCall(name, mapper.toString(args)), 0);
    }

    private static Message batch(final ToolCall... calls) {
        return Message.assistant(null, List.of(calls));
    }

    private static Message answer() {
        return Message.assistant("done", null);
    }

    private static EmbeddedTool tool(
            final String name, final ToolCategory category, final boolean readonly, final ToolResult result) {
        return new EmbeddedTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "scripted " + name;
            }

            @Override
            public ToolCategory category() {
                return category;
            }

            @Override
            public boolean readonly() {
                return readonly;
            }

            @Override
            public Map<String, Object> schema() {
                return Map.of("type", "object");
            }

            @Override
            public ToolResult execute(final Map<String, Object> args) {
                return result;
            }
        };
    }

    private static final class MutableTodos extends TodoStore {
        private List<TodoItem> items = List.of();

        @Override
        public List<TodoItem> all() {
            return items;
        }
    }

    private final class Fixture {
        private final CodingAgentConfiguration config;
        private final List<AgentEvent> events = new ArrayList<>();
        private final List<List<Message>> requests = new ArrayList<>();
        private final List<EmbeddedTool> extraTools = new ArrayList<>();
        private final MutableTodos todos = new MutableTodos();
        private EmbeddedTool readOverride;
        private Consumer<Integer> beforeReply = index -> {};

        private Fixture(final CodingAgentConfiguration config) {
            this.config = config;
        }

        private String run(final Message... script) {
            final var replies = new ArrayDeque<>(List.of(script));
            final ChatClient client = new ChatClient() {
                @Override
                public Message stream(
                        final ChatCompletionRequest request,
                        final Consumer<PauseEvent> pauseListener,
                        final Consumer<LlmStreamEvent> onDelta,
                        final BooleanSupplier cancelled) {
                    beforeReply.accept(requests.size());
                    requests.add(List.copyOf(request.messages()));
                    // AssertionError must escape the loop's normal provider-error handling: running
                    // out of script is a regression, never an alternative successful stop condition.
                    assertTrue(!replies.isEmpty(), "unexpected extra model request: " + requests.size());
                    final var reply = replies.removeFirst();
                    onDelta.accept(new LlmStreamEvent.End(reply.toolCalls() == null ? "stop" : "tool_calls", null));
                    return reply;
                }
            };
            final var tools = new ArrayList<EmbeddedTool>();
            tools.add(readOverride == null ? new ReadFileTool(config) : readOverride);
            tools.addAll(extraTools);
            final var registry = new ToolRegistry(null) {
                @Override
                public List<Tool> toWireTools() {
                    return toWireTools(tool -> true);
                }

                @Override
                public List<Tool> toWireTools(final Predicate<EmbeddedTool> filter) {
                    return tools.stream()
                            .filter(filter)
                            .map(tool -> Tool.function(tool.name(), tool.description(), tool.schema()))
                            .toList();
                }

                @Override
                public EmbeddedTool tool(final String name) {
                    return tools.stream()
                            .filter(tool -> tool.name().equals(name))
                            .findFirst()
                            .orElse(null);
                }
            };
            final var executions = new ToolExecutionService() {
                @Override
                public ToolResult execute(
                        final EmbeddedTool tool, final Map<String, Object> args, final ToolContext context) {
                    return tool.execute(args, context);
                }
            };
            final var permissions = new PermissionService(config);
            permissions.setMode(AgentMode.BUILD);
            final var loop = new AgentLoop(
                    client,
                    registry,
                    executions,
                    config,
                    permissions,
                    new UsageTracker() {
                        @Override
                        public void record(final Usage usage) {}

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
                    },
                    null,
                    new ContextManager() {
                        @Override
                        public boolean pruneNeeded(final List<Message> messages, final int extraToolTokens) {
                            return false;
                        }

                        @Override
                        public boolean compactNeeded(final List<Message> messages, final int extraToolTokens) {
                            return false;
                        }
                    },
                    null,
                    new SessionStore() {
                        @Override
                        public void append(final String id, final SessionEntry entry) {}
                    },
                    new SystemPromptBuilder() {
                        @Override
                        public String build(final String prompt, final AgentConfiguration agent) {
                            return "system prompt";
                        }
                    },
                    new MemoryExtractor() {
                        @Override
                        public void asyncExtractNow(final List<Message> messages) {}

                        @Override
                        public void asyncExtractNow(final List<Message> messages, final ChatSettings settings) {}

                        @Override
                        public void asyncExtractNow(
                                final List<Message> messages, final ChatSettings settings, final boolean eligible) {}
                    },
                    new StatusReporter(),
                    new Emitter() {
                        @Override
                        public <T> void emit(final T event) {
                            if (event instanceof AgentEvent agentEvent) {
                                events.add(agentEvent);
                            }
                        }
                    },
                    mapper,
                    new StructuralCodeGraph() {
                        @Override
                        public void ensureReadyAsync() {}
                    },
                    new ProviderManager(config, mapper),
                    null,
                    Clock.systemUTC(),
                    null,
                    todos,
                    null,
                    null);
            return loop.run("inspect the source and complete the work", null, "plateau-regression", null, () -> false);
        }

        private long notices(final String text) {
            return events.stream()
                    .filter(e -> e instanceof AgentEvent.Notice notice
                            && notice.message().contains(text))
                    .count();
        }

        private void assertNoErrors() {
            assertTrue(
                    events.stream().noneMatch(AgentEvent.Error.class::isInstance),
                    () -> "unexpected errors: " + events);
        }

        private void assertError(final String text) {
            assertTrue(
                    events.stream()
                            .anyMatch(e -> e instanceof AgentEvent.Error error
                                    && error.message().contains(text)),
                    () -> "missing error '" + text + "': " + events);
        }

        private void assertCappedAt(final int requestsAllowed) {
            assertEquals(
                    requestsAllowed,
                    requests.size(),
                    "nudges/round gates must consume the current window, not replace it");
            assertError("Maximum iterations reached");
        }
    }
}
