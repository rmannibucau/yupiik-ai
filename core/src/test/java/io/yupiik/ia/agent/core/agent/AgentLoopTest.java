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
import io.yupiik.ia.agent.core.configuration.AgentConfiguration;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.TestConfigs;
import io.yupiik.ia.agent.core.context.ContextManager;
import io.yupiik.ia.agent.core.context.UsageTracker;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatCompletionRequest;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.PauseEvent;
import io.yupiik.ia.agent.core.llm.Tool;
import io.yupiik.ia.agent.core.llm.ToolCall;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.runtime.StatusReporter;
import io.yupiik.ia.agent.core.session.SessionEntry;
import io.yupiik.ia.agent.core.session.SessionStore;
import io.yupiik.ia.agent.core.snapshot.SnapshotStore;
import io.yupiik.ia.agent.core.todo.TodoItem;
import io.yupiik.ia.agent.core.todo.TodoStore;
import io.yupiik.ia.agent.core.tools.ToolExecutionService;
import io.yupiik.ia.agent.core.tools.ToolRegistry;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import io.yupiik.llm.client.model.LlmStreamEvent;
import io.yupiik.llm.client.model.LlmStreamEvent.End;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
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

    private CodingAgentConfiguration configuration(final boolean continueOnTruncation) {
        return TestConfigs.codingAgent(Map.ofEntries(
                Map.entry("yca.storageHome", storageHome.toString()),
                Map.entry("yca.provider.providers.length", "1"),
                Map.entry("yca.provider.providers.0.id", "default"),
                Map.entry("yca.provider.providers.0.baseUrl", "http://localhost:1/v1"),
                Map.entry("yca.provider.providers.0.apiKey", "test-key"),
                Map.entry("yca.provider.providers.0.model", "test-model"),
                Map.entry("yca.provider.providers.0.api", "auto"),
                Map.entry("yca.provider.providers.0.timeoutSeconds", "5"),
                Map.entry("yca.llm.retriesMaxAttempts", "1"),
                Map.entry("yca.llm.retriesBaseDelayMs", "1"),
                Map.entry("yca.llm.maxIterations", "10"),
                Map.entry("yca.llm.continueOnTruncation", Boolean.toString(continueOnTruncation))));
    }

    private AgentLoop loop(final ChatClient client, final TodoStore todoStore, final CodingAgentConfiguration config) {
        events.clear();
        return new AgentLoop(
                client,
                new ToolRegistry(null) {
                    @Override
                    public List<Tool> toWireTools() {
                        return List.of();
                    }

                    @Override
                    public io.yupiik.ia.agent.core.tools.EmbeddedTool tool(final String name) {
                        return null;
                    }
                },
                new ToolExecutionService() {},
                config,
                new PermissionService(null),
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
                        events.add((AgentEvent) event);
                    }
                },
                null,
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
                todoStore);
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
                    final BooleanSupplier cancelled) {
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

    private static class ScriptedReply {
        private final String content;
        private final String finishReason;
        private final boolean firePauses;
        private final RuntimeException error;

        private ScriptedReply(
                final String content,
                final String finishReason,
                final boolean firePauses,
                final RuntimeException error) {
            this.content = content;
            this.finishReason = finishReason;
            this.firePauses = firePauses;
            this.error = error;
        }

        ScriptedReply(final String content, final String finishReason, final boolean firePauses) {
            this(content, finishReason, firePauses, null);
        }

        ScriptedReply(final String content, final String finishReason, final RuntimeException error) {
            this(content, finishReason, false, error);
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
