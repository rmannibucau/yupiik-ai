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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.ia.agent.core.configuration.AgentConfiguration;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.TestConfigs;
import io.yupiik.ia.agent.core.context.CompactionResult;
import io.yupiik.ia.agent.core.context.ContextManager;
import io.yupiik.ia.agent.core.context.ContextView;
import io.yupiik.ia.agent.core.context.UsageTracker;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatCompletionRequest;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.MidStreamFailureException;
import io.yupiik.ia.agent.core.llm.PauseEvent;
import io.yupiik.ia.agent.core.llm.Tool;
import io.yupiik.ia.agent.core.llm.ToolCall;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.runtime.StatusReporter;
import io.yupiik.ia.agent.core.runtime.VirtualThreadExecutor;
import io.yupiik.ia.agent.core.session.SessionEntry;
import io.yupiik.ia.agent.core.session.SessionStore;
import io.yupiik.ia.agent.core.snapshot.SnapshotStore;
import io.yupiik.ia.agent.core.todo.TodoItem;
import io.yupiik.ia.agent.core.todo.TodoStore;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolExecutionService;
import io.yupiik.ia.agent.core.tools.ToolRegistry;
import io.yupiik.ia.agent.core.tools.ToolResult;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import io.yupiik.llm.client.model.LlmStreamEvent;
import io.yupiik.llm.client.model.LlmStreamEvent.End;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentLoopTest {
    @TempDir
    Path storageHome;

    private final List<AgentEvent> events = new ArrayList<>();
    private final AtomicReference<Consumer<PauseEvent>> pauseListenerReference = new AtomicReference<>();

    @Test
    void unknownToolDiagnosticNamesOffendingToolAndPointsToTools() throws Exception {
        final var diagnostic = diagnostic("github_review");
        assertTrue(diagnostic.contains("\"github_review\""), "should name the offending tool");
        assertTrue(diagnostic.contains("not in this session's toolset"), "should explain why it is rejected");
        assertTrue(diagnostic.contains("/tools"), "should point at the way to list valid tools");
    }

    @Test
    void unknownToolDiagnosticIsStableText() throws Exception {
        assertEquals(
                "Unknown tool \"foo\": not in this session's toolset (built-in or enabled MCP)."
                        + " Run /tools to list the valid tools; only exact names from the request's tools field are accepted.",
                diagnostic("foo"));
    }

    @Test
    void truncatedReplyIsAutoContinuedThenTurnEndsCleanly() {
        final var replies = new ArrayDeque<>(List.of(
                new ScriptedReply("let me star", "length", false),
                new ScriptedReply("t the implementation", "stop", false)));
        final var requests = new ArrayList<List<Message>>();
        final var result = loop(chatClient(replies, requests), null, configuration(true))
                .run("do it", null, "s1", null, () -> false);

        assertEquals("t the implementation", result);
        assertEquals(2, requests.size(), "truncated reply must trigger exactly one more LLM call");
        final var nudge = requests.get(1).getLast();
        assertEquals("user", nudge.role());
        assertTrue(
                nudge.content().toString().contains("cut off by the output token limit"),
                "the continuation nudge must tell the model to resume");
        final var notices =
                events.stream().filter(AgentEvent.Notice.class::isInstance).toList();
        assertEquals(1, notices.size());
        assertEquals(AgentEvent.Notice.Level.WARN, ((AgentEvent.Notice) notices.getFirst()).level());
        assertTrue(((AgentEvent.Notice) notices.getFirst()).message().contains("(1/3)"));
        assertTrue(events.getLast() instanceof AgentEvent.TurnDone);
    }

    @Test
    void truncatedReplyGivesUpAfterCapWithAnErrorNotice() {
        final var replies = new ArrayDeque<ScriptedReply>();
        for (var i = 0; i < 5; i++) {
            replies.add(new ScriptedReply("truncated " + i, "length", false));
        }
        final var requests = new ArrayList<List<Message>>();
        final var result = loop(chatClient(replies, requests), null, configuration(true))
                .run("do it", null, "s1", null, () -> false);

        assertEquals(4, requests.size(), "3 continuations then a final call");
        final var notices =
                events.stream().filter(AgentEvent.Notice.class::isInstance).toList();
        assertEquals(4, notices.size(), "3 continuation warnings then the give-up error");
        assertEquals(AgentEvent.Notice.Level.ERROR, ((AgentEvent.Notice) notices.getLast()).level());
        assertTrue(((AgentEvent.Notice) notices.getLast()).message().contains("Gave up after 3 continuations"));
        assertTrue(events.getLast() instanceof AgentEvent.TurnDone);
    }

    @Test
    void truncationIsSurfacedWithoutAutoContinueWhenDisabled() {
        final var replies = new ArrayDeque<>(List.of(new ScriptedReply("partial answer", "length", false)));
        final var requests = new ArrayList<List<Message>>();
        final var result = loop(chatClient(replies, requests), null, configuration(false))
                .run("do it", null, "s1", null, () -> false);

        assertEquals("partial answer", result);
        assertEquals(1, requests.size(), "no extra LLM call when continueOnTruncation is off");
        final var notices =
                events.stream().filter(AgentEvent.Notice.class::isInstance).toList();
        assertEquals(1, notices.size());
        assertEquals(AgentEvent.Notice.Level.ERROR, ((AgentEvent.Notice) notices.getFirst()).level());
        assertTrue(((AgentEvent.Notice) notices.getFirst()).message().contains("continueOnTruncation"));
    }

    @Test
    void truncationCapComesFromConfiguration() {
        final var replies = new ArrayDeque<ScriptedReply>();
        for (var i = 0; i < 4; i++) {
            replies.add(new ScriptedReply("truncated " + i, "length", false));
        }
        final var requests = new ArrayList<List<Message>>();
        final var result = loop(
                        chatClient(replies, requests),
                        null,
                        configuration(true, Map.of("yca.llm.truncationContinuesMax", "1")))
                .run("do it", null, "s1", null, () -> false);

        assertEquals("truncated 1", result);
        assertEquals(2, requests.size(), "1 continuation then a final call");
        final var notices =
                events.stream().filter(AgentEvent.Notice.class::isInstance).toList();
        assertEquals(2, notices.size(), "1 continuation warning then the give-up error");
        assertEquals(AgentEvent.Notice.Level.ERROR, ((AgentEvent.Notice) notices.getLast()).level());
        assertTrue(((AgentEvent.Notice) notices.getLast()).message().contains("Gave up after 1 continuation."));
        assertTrue(events.getLast() instanceof AgentEvent.TurnDone);
    }

    @Test
    void secondContinuationSwitchesToSplitStrategy() {
        final var replies = new ArrayDeque<>(List.of(
                new ScriptedReply("truncated 0", "length", false),
                new ScriptedReply("truncated 1", "length", false),
                new ScriptedReply("truncated 2", "length", false),
                new ScriptedReply("finally done", "stop", false)));
        final var requests = new ArrayList<List<Message>>();
        final var result = loop(chatClient(replies, requests), null, configuration(true))
                .run("do it", null, "s1", null, () -> false);

        assertEquals("finally done", result);
        assertEquals(4, requests.size());
        final var firstNudge = requests.get(1).getLast().content().toString();
        assertTrue(firstNudge.contains("Continue exactly where you stopped"), firstNudge);
        final var secondNudge = requests.get(2).getLast().content().toString();
        assertTrue(secondNudge.contains("Change strategy"), secondNudge);
        assertTrue(secondNudge.contains("Do not continue the same output verbatim"), secondNudge);
        final var thirdNudge = requests.get(3).getLast().content().toString();
        assertTrue(thirdNudge.contains("Change strategy"), thirdNudge);
    }

    @Test
    void midStreamFailureIsRetriedWithATextReset() {
        final var replies = new ArrayDeque<>(List.of(
                new ScriptedReply(failure("stream interrupted after 12 chars of partial content: broken pipe")),
                new ScriptedReply("recovered", "stop", false)));
        final var requests = new ArrayList<List<Message>>();
        final var result = loop(chatClient(replies, requests), null, configuration(true))
                .run("do it", null, "s1", null, () -> false);

        assertEquals("recovered", result);
        assertEquals(2, requests.size(), "the interrupted stream must be replayed once");
        assertTrue(
                events.stream().anyMatch(AgentEvent.TextReset.class::isInstance),
                "frontends must be told to drop the partial buffer");
        final var notices =
                events.stream().filter(AgentEvent.Notice.class::isInstance).toList();
        assertEquals(1, notices.size());
        final var notice = (AgentEvent.Notice) notices.getFirst();
        assertEquals(AgentEvent.Notice.Level.WARN, notice.level());
        assertTrue(notice.message().contains("Stream interrupted (broken pipe)"), notice.message());
        assertTrue(notice.message().contains("retrying (1/3)"), notice.message());
        assertTrue(events.getLast() instanceof AgentEvent.TurnDone);
    }

    @Test
    void midStreamFailureExhaustsRetriesAndEndsWithAnError() {
        final var replies = new ArrayDeque<>(List.of(
                new ScriptedReply(failure("stream interrupted after 3 chars of partial content: reset")),
                new ScriptedReply(failure("stream interrupted after 3 chars of partial content: reset"))));
        final var requests = new ArrayList<List<Message>>();
        final var result = loop(
                        chatClient(replies, requests),
                        null,
                        configuration(true, Map.of("yca.llm.streamRetriesMaxAttempts", "1")))
                .run("do it", null, "s1", null, () -> false);

        assertNull(result, "the turn must end gracefully when retries are exhausted");
        assertEquals(2, requests.size(), "initial attempt plus one retry");
        final var errors =
                events.stream().filter(AgentEvent.Error.class::isInstance).toList();
        assertEquals(1, errors.size());
        assertTrue(((AgentEvent.Error) errors.getFirst()).message().contains("stream interrupted"));
    }

    @Test
    void runtimeFailureIsSurfacedAsAnErrorEvent() {
        final var replies = new ArrayDeque<>(
                List.of(new ScriptedReply(null, null, new IllegalStateException("provider exploded"))));
        final var result = loop(chatClient(replies, new ArrayList<>()), null, configuration(true))
                .run("do it", null, "s1", null, () -> false);

        assertNull(result, "the turn must end gracefully instead of throwing into the frontend");
        final var errors =
                events.stream().filter(AgentEvent.Error.class::isInstance).toList();
        assertEquals(1, errors.size());
        assertTrue(((AgentEvent.Error) errors.getFirst()).message().contains("provider exploded"));
    }

    @Test
    void resiliencePausesAreSurfacedAndListenerResetAfterTheTurn() {
        final var replies = new ArrayDeque<>(List.of(new ScriptedReply("done", "stop", true)));
        final var client = chatClient(replies, new ArrayList<>());
        final var result = loop(client, null, configuration(true)).run("do it", null, "s1", null, () -> false);

        assertEquals("done", result);
        assertTrue(
                events.stream()
                        .anyMatch(e -> e instanceof AgentEvent.Notice n
                                && AgentEvent.Notice.Level.INFO == n.level()
                                && n.message().contains("retrying")),
                "retry pauses must surface as an info notice");
        assertTrue(
                events.stream()
                        .anyMatch(e ->
                                e instanceof AgentEvent.Status s && s.message().contains("Rate limit")),
                "throttle pauses must surface as a status");
        assertNull(pauseListenerReference.get(), "the pause listener must be cleared after the turn");
    }

    @Test
    void turnEndingWithPendingTodosIsWarnedAbout() {
        final var todoStore = new TodoStore() {
            private List<TodoItem> todos = List.of(
                    new TodoItem("done step", "completed"),
                    new TodoItem("running step", "in_progress"),
                    new TodoItem("later step", "pending"));

            @Override
            public List<TodoItem> all() {
                return todos;
            }

            @Override
            public synchronized List<TodoItem> replace(final List<TodoItem> items) {
                todos = List.copyOf(items);
                return all();
            }
        };
        final var replies = new ArrayDeque<>(List.of(
                new ScriptedReply(null, "tool_calls", false) {
                    @Override
                    Message toMessage() {
                        return new Message(
                                "assistant",
                                null,
                                null,
                                List.of(new ToolCall("t1", "function", new ToolCall.FunctionCall("bash", "{}"), 0)),
                                null,
                                null);
                    }
                },
                new ScriptedReply("made progress", "stop", false)));
        final var result = loop(chatClient(replies, new ArrayList<>()), todoStore, configuration(true))
                .run("do it", null, "s1", null, () -> false);

        assertEquals("made progress", result);
        final var notices =
                events.stream().filter(AgentEvent.Notice.class::isInstance).toList();
        assertEquals(1, notices.size());
        final var notice = (AgentEvent.Notice) notices.getFirst();
        assertEquals(AgentEvent.Notice.Level.WARN, notice.level());
        assertEquals(
                "model ended its turn (finish_reason=stop) with 2/3 todos still pending - send 'continue' to resume, or review why it stopped",
                notice.message());
        assertEquals("stop", notice.finishReason());
    }

    @Test
    void noTodosWarningWithoutPendingItemsOrToolUsage() {
        final var todoStore = new TodoStore() {
            private List<TodoItem> todos = List.of(new TodoItem("done step", "completed"));

            @Override
            public List<TodoItem> all() {
                return todos;
            }

            @Override
            public synchronized List<TodoItem> replace(final List<TodoItem> items) {
                todos = List.copyOf(items);
                return all();
            }
        };
        final var replies = new ArrayDeque<>(List.of(new ScriptedReply("all done", "stop", false)));
        loop(chatClient(replies, new ArrayList<>()), todoStore, configuration(true))
                .run("do it", null, "s1", null, () -> false);

        assertEquals(
                0,
                events.stream().filter(AgentEvent.Notice.class::isInstance).count(),
                "completed todos and a no-tool turn must stay silent");
    }

    @Test
    void compactionInsideTheLoopIsPersistedToTheSessionStore() {
        final var compactRequested = new AtomicBoolean();
        final List<List<Message>> replaced = new ArrayList<>();
        final var manager = new ContextManager() {
            @Override
            public boolean pruneNeeded(final List<Message> messages, final int extraToolTokens) {
                return false;
            }

            @Override
            public boolean compactNeeded(final List<Message> messages, final int extraToolTokens) {
                return compactRequested.compareAndSet(false, true);
            }

            @Override
            public CompactionResult compact(final List<Message> messages) {
                return new CompactionResult(
                        List.of(
                                messages.get(0),
                                Message.user(
                                        "<compacted-context>\ndigest\n</compacted-context>\n\nContinue from this summary."),
                                messages.get(2)),
                        CompactionResult.Mode.SUMMARY,
                        "");
            }
        };
        final var store = new SessionStore() {
            @Override
            public void append(final String id, final SessionEntry entry) {}

            @Override
            public void replace(final String id, final List<Message> messages) {
                replaced.add(List.copyOf(messages));
            }
        };
        final var replies = new ArrayDeque<>(List.of(new ScriptedReply("all done", "stop", false)));
        loop(chatClient(replies, new ArrayList<>()), null, configuration(true), manager, store, null)
                .run("do it", null, "s1", List.of(Message.user("old")), () -> false);

        assertEquals(1, replaced.size(), "the compacted context must be persisted exactly once");
        final var persisted = replaced.get(0);
        assertTrue(
                persisted.stream().noneMatch(m -> "system".equals(m.role())),
                "the system prompt is rebuilt every turn and must not be stored");
        assertTrue(
                String.valueOf(persisted.get(0).content()).contains("<compacted-context>"),
                "the digest must open the persisted history");
        assertEquals("do it", String.valueOf(persisted.get(persisted.size() - 1).content()));
        final var compacted = compactedEvents();
        assertEquals(1, compacted.size());
        assertEquals("compact", compacted.get(0).kind());
        assertEquals("SUMMARY", compacted.get(0).mode());
    }

    @Test
    void manageContextDirectiveIsAppliedOnceAfterTheToolRound() {
        final var view = new ContextView();
        assertTrue(view.request(new ContextView.Directive("compact", null, 0, "manage_context")));
        final var compactRuns = new AtomicInteger();
        final var manager = new ContextManager() {
            @Override
            public boolean pruneNeeded(final List<Message> messages, final int extraToolTokens) {
                return false;
            }

            @Override
            public boolean compactNeeded(final List<Message> messages, final int extraToolTokens) {
                return false;
            }

            @Override
            public CompactionResult compact(final List<Message> messages, final String focus, final int keepLast) {
                compactRuns.incrementAndGet();
                return new CompactionResult(
                        List.of(
                                messages.get(0),
                                Message.user("<compacted-context>\ndigest\n</compacted-context>"),
                                messages.get(messages.size() - 1)),
                        CompactionResult.Mode.SUMMARY,
                        "");
            }
        };
        final var store = new SessionStore() {
            @Override
            public void append(final String id, final SessionEntry entry) {}

            @Override
            public void replace(final String id, final List<Message> messages) {}
        };
        final var calls = new AtomicInteger();
        final var client = new ChatClient() {
            @Override
            public Message stream(
                    final ChatCompletionRequest request,
                    final Consumer<LlmStreamEvent> onDelta,
                    final BooleanSupplier cancelled) {
                if (calls.incrementAndGet() == 1) {
                    return Message.assistant(
                            null, List.of(new ToolCall("t1", "function", new ToolCall.FunctionCall("bash", "{}"), 0)));
                }
                onDelta.accept(new End("stop", null));
                return Message.assistant("all done", null);
            }

            @Override
            public void setPauseListener(final Consumer<PauseEvent> listener) {}
        };
        final var result = loop(client, null, configuration(true), manager, store, view)
                .run("do it", null, "s1", null, () -> false);

        assertEquals("all done", result);
        assertEquals(1, compactRuns.get(), "the directive must be applied exactly once");
        final var compacted = compactedEvents();
        assertEquals(1, compacted.size());
        assertEquals("tool", compacted.get(0).kind());
        assertNull(view.poll(), "the directive must be consumed");
    }

    private List<AgentEvent.Compacted> compactedEvents() {
        return events.stream()
                .filter(AgentEvent.Compacted.class::isInstance)
                .map(AgentEvent.Compacted.class::cast)
                .toList();
    }

    @Test
    void consecutiveReadonlyToolsRunConcurrentlyAndResultsKeepCallOrder() {
        final var barrier = new CyclicBarrier(2);
        final var requests = new ArrayList<List<Message>>();
        final var client = scriptedToolClient(
                requests,
                List.of(
                        new ToolCall("t1", "function", new ToolCall.FunctionCall("slow_read_a", "{}"), 0),
                        new ToolCall("t2", "function", new ToolCall.FunctionCall("slow_read_b", "{}"), 1)));
        final var result = loop(
                        client,
                        null,
                        configuration(true),
                        noPruningManager(),
                        noopStore(),
                        null,
                        barrierRegistry(barrier, "slow_read_a", "slow_read_b"),
                        directExecutions(),
                        new VirtualThreadExecutor())
                .run("do it", null, "s1", null, () -> false);

        assertEquals("all done", result);
        final var tools =
                requests.get(1).stream().filter(m -> "tool".equals(m.role())).toList();
        assertEquals(2, tools.size(), "both tool results must reach the next request");
        assertEquals("t1", tools.get(0).toolCallId());
        assertEquals("done-a", tools.get(0).content());
        assertEquals("t2", tools.get(1).toolCallId());
        assertEquals("done-b", tools.get(1).content());
    }

    @Test
    void parallelReadonlyCanBeDisabledByConfiguration() {
        final var barrier = new CyclicBarrier(2);
        final var requests = new ArrayList<List<Message>>();
        final var client = scriptedToolClient(
                requests,
                List.of(
                        new ToolCall("t1", "function", new ToolCall.FunctionCall("slow_read_a", "{}"), 0),
                        new ToolCall("t2", "function", new ToolCall.FunctionCall("slow_read_b", "{}"), 1)));
        final var config = configuration(true, Map.of("yca.tools.parallelReadonly", "false"));
        loop(
                        client,
                        null,
                        config,
                        noPruningManager(),
                        noopStore(),
                        null,
                        barrierRegistry(barrier, "slow_read_a", "slow_read_b"),
                        directExecutions(),
                        new VirtualThreadExecutor())
                .run("do it", null, "s1", null, () -> false);

        final var tools =
                requests.get(1).stream().filter(m -> "tool".equals(m.role())).toList();
        assertEquals(2, tools.size());
        assertTrue(
                String.valueOf(tools.get(0).content()).contains("not concurrent"),
                "with the feature off the barrier is never satisfied: "
                        + tools.get(0).content());
    }

    @Test
    void stateChangingToolKeepsSurroundingCallsSequentialInOrder() {
        final var order = Collections.synchronizedList(new ArrayList<String>());
        final var requests = new ArrayList<List<Message>>();
        final var client = scriptedToolClient(
                requests,
                List.of(
                        new ToolCall("t1", "function", new ToolCall.FunctionCall("read_a", "{}"), 0),
                        new ToolCall("t2", "function", new ToolCall.FunctionCall("agent_x", "{}"), 1),
                        new ToolCall("t3", "function", new ToolCall.FunctionCall("read_b", "{}"), 2)));
        final var registry = new ToolRegistry(null) {
            @Override
            public List<Tool> toWireTools() {
                return List.of();
            }

            @Override
            public EmbeddedTool tool(final String name) {
                if (!List.of("read_a", "agent_x", "read_b").contains(name)) {
                    return null;
                }
                final var readonly = !"agent_x".equals(name);
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
                        // agent-category and not readonly: allowed by the permission service but
                        // never eligible for a parallel run
                        return readonly ? ToolCategory.READ : ToolCategory.AGENT;
                    }

                    @Override
                    public Map<String, Object> schema() {
                        return Map.of();
                    }

                    @Override
                    public boolean readonly() {
                        return readonly;
                    }

                    @Override
                    public ToolResult execute(final Map<String, Object> args) {
                        order.add(name);
                        return ToolResult.ok("ran " + name);
                    }
                };
            }
        };
        final var result = loop(
                        client,
                        null,
                        configuration(true),
                        noPruningManager(),
                        noopStore(),
                        null,
                        registry,
                        directExecutions(),
                        new VirtualThreadExecutor())
                .run("do it", null, "s1", null, () -> false);

        assertEquals("all done", result);
        assertEquals(List.of("read_a", "agent_x", "read_b"), order, "a state-changing call must never be overtaken");
        final var tools =
                requests.get(1).stream().filter(m -> "tool".equals(m.role())).toList();
        assertEquals(
                List.of("t1", "t2", "t3"),
                tools.stream().map(Message::toolCallId).toList());
    }

    @Test
    void thirdIdenticalConsecutiveToolCallIsRefusedWithGuidance() {
        final var executions = new AtomicInteger();
        final var requests = new ArrayList<List<Message>>();
        final var calls = new AtomicInteger();
        final var client = new ChatClient() {
            @Override
            public Message stream(
                    final ChatCompletionRequest request,
                    final Consumer<LlmStreamEvent> onDelta,
                    final BooleanSupplier cancelled) {
                requests.add(List.copyOf(request.messages()));
                final var round = calls.incrementAndGet();
                if (round <= 3) {
                    return Message.assistant(
                            null,
                            List.of(new ToolCall(
                                    "t" + round,
                                    "function",
                                    new ToolCall.FunctionCall("probe", "{\"q\":\"same\"}"),
                                    0)));
                }
                onDelta.accept(new End("stop", null));
                return Message.assistant("gave up looping", null);
            }

            @Override
            public void setPauseListener(final Consumer<PauseEvent> listener) {}
        };
        final var registry = new ToolRegistry(null) {
            @Override
            public List<Tool> toWireTools() {
                return List.of();
            }

            @Override
            public EmbeddedTool tool(final String name) {
                if (!"probe".equals(name)) {
                    return null;
                }
                return new EmbeddedTool() {
                    @Override
                    public String name() {
                        return "probe";
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
                        executions.incrementAndGet();
                        return ToolResult.ok("probe result");
                    }
                };
            }
        };
        final var result = loop(
                        client,
                        null,
                        configuration(true),
                        noPruningManager(),
                        noopStore(),
                        null,
                        registry,
                        directExecutions(),
                        new VirtualThreadExecutor())
                .run("do it", null, "s1", null, () -> false);

        assertEquals("gave up looping", result);
        assertEquals(2, executions.get(), "only the first two identical calls must execute");
        // request 4 carries the accumulated history: its last tool message is the round-3 refusal
        final var toolMessages =
                requests.get(3).stream().filter(m -> "tool".equals(m.role())).toList();
        final var refusal = toolMessages.get(toolMessages.size() - 1);
        assertTrue(
                String.valueOf(refusal.content()).contains("3 times in a row"),
                "the refused call must explain the doom loop: " + refusal.content());
    }

    @Test
    void staleReadsAreInvalidatedAfterAWriteToTheSamePath() {
        final var requests = new ArrayList<List<Message>>();
        final var calls = new AtomicInteger();
        final var file = "/tmp/opencode/stale-target.txt";
        final var client = new ChatClient() {
            @Override
            public Message stream(
                    final ChatCompletionRequest request,
                    final Consumer<LlmStreamEvent> onDelta,
                    final BooleanSupplier cancelled) {
                requests.add(List.copyOf(request.messages()));
                return switch (calls.incrementAndGet()) {
                    case 1 ->
                        Message.assistant(
                                null,
                                List.of(new ToolCall(
                                        "t1",
                                        "function",
                                        new ToolCall.FunctionCall("read_file", "{\"path\":\"" + file + "\"}"),
                                        0)));
                    case 2 ->
                        Message.assistant(
                                null,
                                List.of(new ToolCall(
                                        "t2",
                                        "function",
                                        new ToolCall.FunctionCall(
                                                "write_file", "{\"path\":\"" + file + "\",\"content\":\"new\"}"),
                                        0)));
                    default -> {
                        onDelta.accept(new End("stop", null));
                        yield Message.assistant("written", null);
                    }
                };
            }

            @Override
            public void setPauseListener(final Consumer<PauseEvent> listener) {}
        };
        final var result = loop(
                        client,
                        null,
                        configuration(true, Map.of("yca.mode", "build", "yca.permissions.mode", "yolo")),
                        noPruningManager(),
                        noopStore(),
                        null,
                        fileToolsRegistry(),
                        directExecutions(),
                        new VirtualThreadExecutor())
                .run("edit the file", null, "s1", null, () -> false);

        assertEquals("written", result);
        final var beforeWrite = requests.get(1).stream()
                .filter(m -> "tool".equals(m.role()) && "t1".equals(m.toolCallId()))
                .findFirst()
                .orElseThrow();
        assertEquals("old content", beforeWrite.content(), "the read result must be intact before the write lands");
        final var afterWrite = requests.get(2).stream()
                .filter(m -> "tool".equals(m.role()) && "t1".equals(m.toolCallId()))
                .findFirst()
                .orElseThrow();
        assertTrue(
                String.valueOf(afterWrite.content()).contains("stale read cleared"),
                "the read result must be invalidated after the write: " + afterWrite.content());
    }

    @Test
    void staleReadsFromPreviousTurnsAreIndexedFromHistory() {
        final var requests = new ArrayList<List<Message>>();
        final var calls = new AtomicInteger();
        final var file = "/tmp/opencode/stale-history.txt";
        final var client = new ChatClient() {
            @Override
            public Message stream(
                    final ChatCompletionRequest request,
                    final Consumer<LlmStreamEvent> onDelta,
                    final BooleanSupplier cancelled) {
                requests.add(List.copyOf(request.messages()));
                if (calls.incrementAndGet() == 1) {
                    return Message.assistant(
                            null,
                            List.of(new ToolCall(
                                    "t2",
                                    "function",
                                    new ToolCall.FunctionCall(
                                            "write_file", "{\"path\":\"" + file + "\",\"content\":\"new\"}"),
                                    0)));
                }
                onDelta.accept(new End("stop", null));
                return Message.assistant("written", null);
            }

            @Override
            public void setPauseListener(final Consumer<PauseEvent> listener) {}
        };
        final var history = List.of(
                Message.user("show me the file"),
                Message.assistant(
                        null,
                        List.of(new ToolCall(
                                "h1",
                                "function",
                                new ToolCall.FunctionCall("read_file", "{\"path\":\"" + file + "\"}"),
                                0))),
                Message.tool("h1", "old content"));
        final var result = loop(
                        client,
                        null,
                        configuration(true, Map.of("yca.mode", "build", "yca.permissions.mode", "yolo")),
                        noPruningManager(),
                        noopStore(),
                        null,
                        fileToolsRegistry(),
                        directExecutions(),
                        new VirtualThreadExecutor())
                .run("rewrite it", null, "s1", history, () -> false);

        assertEquals("written", result);
        final var previousRead = requests.get(1).stream()
                .filter(m -> "tool".equals(m.role()) && "h1".equals(m.toolCallId()))
                .findFirst()
                .orElseThrow();
        assertTrue(
                String.valueOf(previousRead.content()).contains("stale read cleared"),
                "reads from previous turns must be invalidated too: " + previousRead.content());
    }

    /** A registry serving {@code read_file} (READ/readonly) and {@code write_file} (EDIT) fakes. */
    private ToolRegistry fileToolsRegistry() {
        return new ToolRegistry(null) {
            @Override
            public List<Tool> toWireTools() {
                return List.of();
            }

            @Override
            public EmbeddedTool tool(final String name) {
                final var read = "read_file".equals(name);
                if (!read && !"write_file".equals(name)) {
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
                        return read ? ToolCategory.READ : ToolCategory.EDIT;
                    }

                    @Override
                    public Map<String, Object> schema() {
                        return Map.of();
                    }

                    @Override
                    public boolean readonly() {
                        return read;
                    }

                    @Override
                    public ToolResult execute(final Map<String, Object> args) {
                        return ToolResult.ok(read ? "old content" : "written");
                    }
                };
            }
        };
    }

    /** Two readonly tools meeting on a barrier: the barrier only passes when both are in flight together. */
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
                            barrier.await(2, TimeUnit.SECONDS);
                        } catch (final Exception e) {
                            return ToolResult.error("not concurrent: " + e);
                        }
                        return ToolResult.ok("done-" + name.substring(name.length() - 1));
                    }
                };
            }
        };
    }

    private ToolExecutionService directExecutions() {
        return new ToolExecutionService() {
            @Override
            public ToolResult execute(final EmbeddedTool tool, final Map<String, Object> args) {
                return tool.execute(args);
            }
        };
    }

    @Test
    void resumeRerunsTheLoopWithoutAddingAnyUserMessage() {
        final var requests = new ArrayList<List<Message>>();
        final var replies = new ArrayDeque<>(List.of(new ScriptedReply("resumed answer", "stop", false)));
        final var client = chatClient(replies, requests);
        final var appended = new ArrayList<SessionEntry>();
        final var store = new SessionStore() {
            @Override
            public void append(final String id, final SessionEntry entry) {
                appended.add(entry);
            }
        };
        final var history = new ArrayList<Message>();
        history.add(Message.user("original question"));
        history.add(Message.assistant("partial answer", null));
        final var loop = loop(client, new TodoStore() {}, configuration(true), noPruningManager(), store, null);
        loop.resume(null, "s1", history, () -> false);
        assertEquals(1, requests.size());
        final var sent = requests.get(0);
        assertEquals(3, sent.size(), "system + history verbatim, no extra user message");
        assertEquals("system", sent.get(0).role());
        assertEquals("original question", sent.get(1).content());
        assertEquals("partial answer", sent.get(2).content());
        assertTrue(
                appended.stream().noneMatch(e -> "user".equals(e.role())),
                "resume must not persist any new input (only the assistant reply is appended)");
    }

    @Test
    void resumeAliasesAreConfigurableAndCaseInsensitive() {
        final var cfg = configuration(true, Map.of("yca.resumeAliases", "continue,vas-y"));
        assertTrue(cfg.isResumeAlias("  Continue "));
        assertTrue(cfg.isResumeAlias("VAS-Y"));
        assertFalse(cfg.isResumeAlias("go"), "defaults are replaced, not merged");
        assertFalse(cfg.isResumeAlias(""));
        assertFalse(cfg.isResumeAlias(null));
    }

    private ChatClient scriptedToolClient(final List<List<Message>> requests, final List<ToolCall> firstReplyCalls) {
        final var calls = new AtomicInteger();
        return new ChatClient() {
            @Override
            public Message stream(
                    final ChatCompletionRequest request,
                    final Consumer<LlmStreamEvent> onDelta,
                    final BooleanSupplier cancelled) {
                requests.add(List.copyOf(request.messages()));
                if (calls.incrementAndGet() == 1) {
                    return Message.assistant(null, firstReplyCalls);
                }
                onDelta.accept(new End("stop", null));
                return Message.assistant("all done", null);
            }

            @Override
            public void setPauseListener(final Consumer<PauseEvent> listener) {}
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

    private SessionStore noopStore() {
        return new SessionStore() {
            @Override
            public void append(final String id, final SessionEntry entry) {}
        };
    }

    private CodingAgentConfiguration configuration(final boolean continueOnTruncation) {
        return configuration(continueOnTruncation, Map.of());
    }

    private CodingAgentConfiguration configuration(
            final boolean continueOnTruncation, final Map<String, String> extra) {
        final var entries = new HashMap<String, String>();
        entries.put("yca.storageHome", storageHome.toString());
        entries.put("yca.provider.providers.length", "1");
        entries.put("yca.provider.providers.0.id", "default");
        entries.put("yca.provider.providers.0.baseUrl", "http://localhost:1/v1");
        entries.put("yca.provider.providers.0.apiKey", "test-key");
        entries.put("yca.provider.providers.0.model", "test-model");
        entries.put("yca.provider.providers.0.api", "auto");
        entries.put("yca.provider.providers.0.timeoutSeconds", "5");
        entries.put("yca.llm.retriesMaxAttempts", "1");
        entries.put("yca.llm.retriesBaseDelayMs", "1");
        entries.put("yca.llm.maxIterations", "10");
        entries.put("yca.llm.continueOnTruncation", Boolean.toString(continueOnTruncation));
        entries.putAll(extra);
        return TestConfigs.codingAgent(entries);
    }

    private AgentLoop loop(final ChatClient client, final TodoStore todoStore, final CodingAgentConfiguration config) {
        return loop(
                client,
                todoStore,
                config,
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
                new SessionStore() {
                    @Override
                    public void append(final String id, final SessionEntry entry) {}
                },
                null);
    }

    private AgentLoop loop(
            final ChatClient client,
            final TodoStore todoStore,
            final CodingAgentConfiguration config,
            final ContextManager contextManager,
            final SessionStore sessionStore,
            final ContextView contextView) {
        return loop(client, todoStore, config, contextManager, sessionStore, contextView, null, null, null);
    }

    private AgentLoop loop(
            final ChatClient client,
            final TodoStore todoStore,
            final CodingAgentConfiguration config,
            final ContextManager contextManager,
            final SessionStore sessionStore,
            final ContextView contextView,
            final ToolRegistry registry,
            final ToolExecutionService executions,
            final VirtualThreadExecutor executor) {
        events.clear();
        final var resolvedRegistry = registry == null
                ? new ToolRegistry(null) {
                    @Override
                    public List<Tool> toWireTools() {
                        return List.of();
                    }

                    @Override
                    public io.yupiik.ia.agent.core.tools.EmbeddedTool tool(final String name) {
                        return null;
                    }
                }
                : registry;
        final var resolvedExecutions = executions == null ? new ToolExecutionService() {} : executions;
        return new AgentLoop(
                client,
                resolvedRegistry,
                resolvedExecutions,
                config,
                new PermissionService(config),
                new UsageTracker() {
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
                },
                contextManager,
                contextView,
                sessionStore,
                new SystemPromptBuilder() {
                    @Override
                    public String build(final String prompt, final AgentConfiguration agent) {
                        return "system prompt";
                    }
                },
                new MemoryExtractor() {
                    @Override
                    public void maybeExtract(final List<Message> messages) {}
                },
                new StatusReporter(),
                new Emitter() {
                    @Override
                    public <T> void emit(final T event) {
                        synchronized (events) {
                            events.add((AgentEvent) event);
                        }
                    }
                },
                new io.yupiik.fusion.json.internal.JsonMapperImpl(List.of(), key -> java.util.Optional.empty()),
                new io.yupiik.ia.agent.core.codegraph.StructuralCodeGraph() {
                    @Override
                    public void ensureReadyAsync() {}
                },
                new ProviderManager() {
                    @Override
                    public ResolvedModel resolveDefault() {
                        return new ResolvedModel(null, "test-model");
                    }
                },
                null,
                Clock.systemUTC(),
                (SnapshotStore) null,
                todoStore,
                executor);
    }

    /**
     * A ChatClient stub replaying scripted replies: each stream() call polls the next one, emits its
     * finish reason (if any) through the delta stream and, when set, fires the registered pause
     * listener once as the resilience layer would during a retry backoff.
     */
    private ChatClient chatClient(final Deque<ScriptedReply> replies, final List<List<Message>> requests) {
        return new ChatClient() {
            @Override
            public Message stream(
                    final ChatCompletionRequest request,
                    final Consumer<LlmStreamEvent> onDelta,
                    final BooleanSupplier cancelled)
                    throws IOException {
                final var reply = replies.poll();
                if (reply == null) {
                    throw new IllegalStateException("no scripted reply left");
                }
                requests.add(List.copyOf(request.messages()));
                final var listener = pauseListenerReference.get();
                if (listener != null && reply.firePauses()) {
                    listener.accept(PauseEvent.retry("LLM call failed, retrying in ~2s (attempt 2)", 2000));
                    listener.accept(PauseEvent.throttle("Rate limit reached, waiting ~5s", 5000));
                }
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

            @Override
            public void setPauseListener(final Consumer<PauseEvent> listener) {
                pauseListenerReference.set(listener);
            }
        };
    }

    private static MidStreamFailureException failure(final String message) {
        return new MidStreamFailureException(message, new IOException("broken pipe"), "partial", null);
    }

    private static class ScriptedReply {
        private final String content;
        private final String finishReason;
        private final boolean firePauses;
        private final RuntimeException error;
        private final IOException ioError;

        private ScriptedReply(
                final String content,
                final String finishReason,
                final boolean firePauses,
                final RuntimeException error,
                final IOException ioError) {
            this.content = content;
            this.finishReason = finishReason;
            this.firePauses = firePauses;
            this.error = error;
            this.ioError = ioError;
        }

        ScriptedReply(final String content, final String finishReason, final boolean firePauses) {
            this(content, finishReason, firePauses, null, null);
        }

        ScriptedReply(final String content, final String finishReason, final RuntimeException error) {
            this(content, finishReason, false, error, null);
        }

        ScriptedReply(final IOException ioError) {
            this(null, null, false, null, ioError);
        }

        String content() {
            return content;
        }

        String finishReason() {
            return finishReason;
        }

        boolean firePauses() {
            return firePauses;
        }

        RuntimeException error() {
            return error;
        }

        IOException ioError() {
            return ioError;
        }

        Message toMessage() {
            return new Message("assistant", content, null, null, null, null);
        }
    }

    private static String diagnostic(final String name) throws Exception {
        final Method m = AgentLoop.class.getDeclaredMethod("unknownToolDiagnostic", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, name);
    }
}
