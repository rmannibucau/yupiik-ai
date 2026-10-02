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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.configuration.AgentConfiguration;
import io.yupiik.ia.agent.core.configuration.AgentMode;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.TestConfigs;
import io.yupiik.ia.agent.core.context.CompactionResult;
import io.yupiik.ia.agent.core.context.ContextManager;
import io.yupiik.ia.agent.core.context.ContextView;
import io.yupiik.ia.agent.core.context.TokenEstimator;
import io.yupiik.ia.agent.core.context.UsageTracker;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatCompletionRequest;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.MidStreamFailureException;
import io.yupiik.ia.agent.core.llm.PauseEvent;
import io.yupiik.ia.agent.core.llm.Tool;
import io.yupiik.ia.agent.core.llm.ToolCall;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.runtime.Cancellations;
import io.yupiik.ia.agent.core.runtime.StatusReporter;
import io.yupiik.ia.agent.core.runtime.VirtualThreadExecutor;
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
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import io.yupiik.ia.agent.test.TestJsonMapper;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

@ExtendWith(TestJsonMapper.class)
class AgentLoopTest {
    @TempDir
    Path storageHome;

    private JsonMapper mapper;

    @BeforeEach
    void bindMapper(final JsonMapper mapper) {
        this.mapper = mapper;
    }

    private final List<AgentEvent> events = new ArrayList<>();
    private final AtomicReference<Consumer<PauseEvent>> pauseListenerReference = new AtomicReference<>();
    private final AtomicReference<io.yupiik.ia.agent.core.llm.Usage> recordedUsage = new AtomicReference<>();
    private PermissionService permissionServiceOverride;

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

    /**
     * A truncation give-up with pending todos falls through to the todo auto-continue instead of ending
     * the turn: hitting the output limit is an accident, not a reason to abandon unfinished work.
     */
    @Test
    void truncationGiveUpFallsThroughToTodoAutoContinue() {
        final var config = configuration(
                true,
                Map.of(
                        "yca.mode", "build",
                        "yca.permissions.mode", "yolo",
                        "yca.todos.continueOnUnfinished", "true",
                        "yca.llm.truncationContinuesMax", "1"));
        permissionServiceOverride = new PermissionService(config);
        permissionServiceOverride.setMode(AgentMode.BUILD);
        try {
            final var todoStore =
                    mutableTodoStore(new TodoItem("done step", "completed"), new TodoItem("later step", "pending"));
            final var requests = new ArrayList<List<Message>>();
            final var replies = new ArrayDeque<>(List.of(
                    toolCallReply("t1", "noop"),
                    new ScriptedReply("truncated 0", "length", false),
                    new ScriptedReply("truncated 1", "length", false),
                    toolCallReply("t2", "close_todos"),
                    new ScriptedReply("all done", "stop", false)));
            final var result = loop(
                            chatClient(replies, requests),
                            todoStore,
                            config,
                            noContextManager(),
                            noSessionStore(),
                            null,
                            todoToolsRegistry(todoStore),
                            directExecutions(),
                            null)
                    .run("do it", null, "s1", null, () -> false);

            assertEquals("all done", result, "the truncation give-up must continue the pending todos");
            final var notices = events.stream()
                    .filter(AgentEvent.Notice.class::isInstance)
                    .map(AgentEvent.Notice.class::cast)
                    .toList();
            assertTrue(
                    notices.stream()
                            .anyMatch(n -> n.level() == AgentEvent.Notice.Level.INFO
                                    && n.message().contains("continuing automatically (1/25)")),
                    "the truncation give-up must trigger a todo continuation: " + notices);
            assertTrue(
                    notices.stream()
                            .noneMatch(n -> n.level() == AgentEvent.Notice.Level.ERROR
                                    && n.message().contains("Gave up after")),
                    "no truncation give-up error when pending todos can continue: " + notices);
        } finally {
            permissionServiceOverride = null;
        }
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
    void resiliencePausesAreSurfacedAndTheListenerNeverLeaksToTheNextTurn() {
        final var replies = new ArrayDeque<>(List.of(new ScriptedReply("done", "stop", true)));
        final var client = chatClient(replies, new ArrayList<>());
        final var agentLoop = loop(client, null, configuration(true));
        final var result = agentLoop.run("do it", null, "s1", null, () -> false);

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

        // each call carries its own listener, so a later turn on the same loop can never re-fire the previous one
        final var pausesAfterFirstTurn = pauseEvents();
        replies.add(new ScriptedReply("again", "stop", false));
        agentLoop.run("do it again", null, "s2", null, () -> false);

        assertEquals(pausesAfterFirstTurn, pauseEvents(), "a later turn must not replay the previous pauses");
    }

    private long pauseEvents() {
        return events.stream()
                .filter(e -> (e instanceof AgentEvent.Notice n && n.message().contains("retrying"))
                        || (e instanceof AgentEvent.Status s && s.message().contains("Rate limit")))
                .count();
    }

    @Test
    void turnThroughputCountsTokensOverTheTimeActuallySpentGenerating() {
        final var reply =
                new ScriptedReply("done", "stop", false, new io.yupiik.llm.client.model.Usage(10L, 100L, 110L));
        loop(timedChatClient(reply, 60, 0), null, configuration(true)).run("do it", null, "s1", null, () -> false);

        final var throughput = lastTurnMetrics().throughput();
        assertEquals(100, throughput.outputTokens(), "output tokens come from the provider usage");
        assertEquals(0, throughput.reportedMs(), "no server side generation time was reported");
        assertTrue(
                throughput.activeMs() >= 60, "time spent in the LLM call is active time, got " + throughput.activeMs());
        assertEquals(throughput.outputTokens() * 1000.0 / throughput.activeMs(), throughput.tokensPerSecond());
        assertEquals(0, throughput.reportedTokensPerSecond());
    }

    @Test
    void turnThroughputIgnoresTheIdleTimeOfThrottlesAndRetries() {
        // a 10s throttle on a 20ms generation: waiting for the provider is not generating
        final var reply =
                new ScriptedReply("done", "stop", false, new io.yupiik.llm.client.model.Usage(10L, 100L, 110L));
        loop(timedChatClient(reply, 20, 10_000), null, configuration(true)).run("do it", null, "s1", null, () -> false);

        final var throughput = lastTurnMetrics().throughput();
        assertEquals(100, throughput.outputTokens());
        assertEquals(0, throughput.activeMs(), "the throttle must be discounted, not counted as generation time");
        assertEquals(0, throughput.tokensPerSecond(), "an unmeasurable rate is 0 so the displays omit the segment");
        assertFalse(throughput.measurable());
    }

    @Test
    void turnThroughputExposesTheServerSideRateWhenTheProviderReportsOne() {
        // 100 output tokens, 2s of generation as reported by the server itself
        final var reply = new ScriptedReply(
                "done", "stop", false, new io.yupiik.llm.client.model.Usage(10L, 100L, 110L, null, null, 2_000L));
        loop(chatClient(new ArrayDeque<>(List.of(reply)), new ArrayList<>()), null, configuration(true))
                .run("do it", null, "s1", null, () -> false);

        final var throughput = lastTurnMetrics().throughput();
        assertEquals(2_000, throughput.reportedMs());
        assertEquals(50d, throughput.reportedTokensPerSecond(), 0.001d, "100 tokens over the 2s the server reported");
        assertTrue(throughput.measurable());
    }

    @Test
    void turnThroughputEstimatesTokensWhenTheProviderReportsNoUsage() {
        final var content = "a short answer with a handful of words";
        loop(
                        chatClient(
                                new ArrayDeque<>(List.of(new ScriptedReply(content, "stop", false))),
                                new ArrayList<>()),
                        null,
                        configuration(true))
                .run("do it", null, "s1", null, () -> false);

        assertEquals(
                TokenEstimator.estimate(content),
                lastTurnMetrics().throughput().outputTokens(),
                "without provider usage the produced tokens are estimated from what came back");
    }

    private AgentEvent.TurnMetrics lastTurnMetrics() {
        final var published =
                events.stream().filter(AgentEvent.TurnMetrics.class::isInstance).toList();
        assertFalse(published.isEmpty(), "the turn must publish its metrics");
        return (AgentEvent.TurnMetrics) published.getLast();
    }

    /**
     * A ChatClient stub replaying a single reply while reproducing the timing of a real generation:
     * how long the call takes and which pause the resilience layer reports before it.
     *
     * @param reply the reply to replay.
     * @param workMs how long the generation actually takes.
     * @param pauseMs the idle wait reported through the pause listener.
     * @return the stub client.
     */
    private ChatClient timedChatClient(final ScriptedReply reply, final long workMs, final long pauseMs) {
        return new ChatClient() {
            @Override
            public Message stream(
                    final ChatCompletionRequest request,
                    final ChatSettings settings,
                    final Consumer<PauseEvent> pauseListener,
                    final Consumer<LlmStreamEvent> onDelta,
                    final BooleanSupplier cancelled)
                    throws IOException {
                pauseListenerReference.set(pauseListener);
                if (pauseListener != null && pauseMs > 0) {
                    pauseListener.accept(
                            PauseEvent.throttle("Rate limit reached, waiting ~" + pauseMs + "ms", pauseMs));
                }
                sleep(workMs);
                if (reply.usage() != null) {
                    onDelta.accept(new End(null, reply.usage()));
                }
                if (reply.finishReason() != null) {
                    onDelta.accept(new End(reply.finishReason(), null));
                }
                return reply.toMessage();
            }
        };
    }

    private static void sleep(final long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Test
    void turnEndingWithPendingTodosIsWarnedAbout() {
        // the default (unconfigured) permission mode is PLAN where pending todos are expected:
        // opt into BUILD explicitly so the warning still applies
        permissionServiceOverride = new PermissionService(configuration(true));
        permissionServiceOverride.setMode(AgentMode.BUILD);
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
        try {
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
        } finally {
            permissionServiceOverride = null;
        }
    }

    /**
     * A model stopping mid-implementation with pending todos is exactly what
     * {@code yca.todos.continueOnUnfinished} exists for: instead of returning, the loop re-asks the
     * model to act, and the turn only ends once the list is closed (here by the tool that completes
     * the todos).
     */
    @Test
    void unfinishedTodosAutoContinueReAsksTheModelUntilTheListIsClosed() {
        final var config = configuration(
                true,
                Map.of(
                        "yca.mode", "build",
                        "yca.permissions.mode", "yolo",
                        "yca.todos.continueOnUnfinished", "true"));
        permissionServiceOverride = new PermissionService(config);
        permissionServiceOverride.setMode(AgentMode.BUILD);
        try {
            final var todoStore = mutableTodoStore(
                    new TodoItem("done step", "completed"),
                    new TodoItem("running step", "in_progress"),
                    new TodoItem("later step", "pending"));
            final var requests = new ArrayList<List<Message>>();
            final var replies = new ArrayDeque<>(List.of(
                    toolCallReply("t1", "noop"),
                    new ScriptedReply("made progress", "stop", false),
                    toolCallReply("t2", "close_todos"),
                    new ScriptedReply("everything is done", "stop", false)));
            final var result = loop(
                            chatClient(replies, requests),
                            todoStore,
                            config,
                            noContextManager(),
                            noSessionStore(),
                            null,
                            todoToolsRegistry(todoStore),
                            directExecutions(),
                            null)
                    .run("do it", null, "s1", null, () -> false);

            assertEquals("everything is done", result);
            assertEquals(4, requests.size(), "the loop re-asked the model once after the mid-turn stop");
            assertTrue(
                    requests.get(2).stream()
                            .anyMatch(m -> "user".equals(m.role())
                                    && String.valueOf(m.content())
                                            .contains("todo list still has pending or in_progress items")),
                    "the continuation must be sent as an explicit instruction, not a silent retry: " + requests.get(2));
            final var notices = events.stream()
                    .filter(AgentEvent.Notice.class::isInstance)
                    .map(AgentEvent.Notice.class::cast)
                    .toList();
            assertEquals(1, notices.size(), "no warning notice: the turn ended on a closed list: " + notices);
            assertEquals(AgentEvent.Notice.Level.INFO, notices.getFirst().level());
            assertEquals(
                    "model ended its turn (finish_reason=stop) with 2/3 todos still pending; continuing automatically (1/25).",
                    notices.getFirst().message());
        } finally {
            permissionServiceOverride = null;
        }
    }

    /** The continuation budget is bounded per turn: once exhausted, the warning notice is emitted again. */
    @Test
    void unfinishedTodosAutoContinueStopsAtTheConfiguredCap() {
        final var config = configuration(
                true,
                Map.of(
                        "yca.mode", "build",
                        "yca.permissions.mode", "yolo",
                        "yca.todos.continueOnUnfinished", "true",
                        "yca.todos.maxContinues", "2"));
        permissionServiceOverride = new PermissionService(config);
        permissionServiceOverride.setMode(AgentMode.BUILD);
        try {
            final var todoStore = mutableTodoStore(
                    new TodoItem("running step", "in_progress"), new TodoItem("later step", "pending"));
            final var requests = new ArrayList<List<Message>>();
            final var replies = new ArrayDeque<>(List.of(
                    toolCallReply("t1", "noop"),
                    new ScriptedReply("progress 1", "stop", false),
                    toolCallReply("t2", "noop"),
                    new ScriptedReply("progress 2", "stop", false),
                    toolCallReply("t3", "noop"),
                    new ScriptedReply("still not done", "stop", false)));
            final var result =
                    loop(chatClient(replies, requests), todoStore, config).run("do it", null, "s1", null, () -> false);

            assertEquals("still not done", result, "the turn ends once the continuation budget is spent");
            assertEquals(6, requests.size(), "2 continuations, each re-asking the model once");
            final var notices = events.stream()
                    .filter(AgentEvent.Notice.class::isInstance)
                    .map(AgentEvent.Notice.class::cast)
                    .toList();
            assertEquals(3, notices.size(), notices.toString());
            assertEquals(
                    "model ended its turn (finish_reason=stop) with 2/2 todos still pending; continuing automatically (1/2).",
                    notices.get(0).message());
            assertEquals(
                    "model ended its turn (finish_reason=stop) with 2/2 todos still pending; continuing automatically (2/2).",
                    notices.get(1).message());
            assertEquals(AgentEvent.Notice.Level.WARN, notices.get(2).level());
            assertEquals(
                    "model ended its turn (finish_reason=stop) with 2/2 todos still pending - send 'continue' to resume, or review why it stopped",
                    notices.get(2).message());
            assertTrue(
                    requests.get(4).stream()
                            .anyMatch(m -> "user".equals(m.role())
                                    && String.valueOf(m.content()).contains("You already continued several times")),
                    "from the second continuation on the instruction must change strategy: " + requests.get(4));
        } finally {
            permissionServiceOverride = null;
        }
    }

    /**
     * A prompt typed while the loop was running wins over the auto-continuation: the model just
     * answered that message, so its text-only end is not the "stopped mid-implementation" case.
     */
    @Test
    void unfinishedTodosAutoContinueIsSkippedAfterAQueuedPromptWasInjected() {
        final var config = configuration(
                true,
                Map.of(
                        "yca.mode", "build",
                        "yca.permissions.mode", "yolo",
                        "yca.todos.continueOnUnfinished", "true"));
        permissionServiceOverride = new PermissionService(config);
        permissionServiceOverride.setMode(AgentMode.BUILD);
        try {
            final var todoStore = mutableTodoStore(new TodoItem("later step", "pending"));
            final var requests = new ArrayList<List<Message>>();
            final var replies = new ArrayDeque<>(
                    List.of(toolCallReply("t1", "noop"), new ScriptedReply("here is the answer", "stop", false)));
            final var queued = new AtomicBoolean(true);
            final var result = loop(
                            chatClient(replies, requests),
                            todoStore,
                            config,
                            noContextManager(),
                            noSessionStore(),
                            null,
                            todoToolsRegistry(todoStore),
                            directExecutions(),
                            null)
                    .run(
                            "do it",
                            null,
                            "s1",
                            null,
                            () -> false,
                            () -> queued.compareAndSet(true, false) ? "and what does the test cover?" : null);

            assertEquals("here is the answer", result);
            assertEquals(2, requests.size(), "the injected prompt is absorbed by the same turn: " + requests);
            final var notices = events.stream()
                    .filter(AgentEvent.Notice.class::isInstance)
                    .map(AgentEvent.Notice.class::cast)
                    .toList();
            assertEquals(1, notices.size(), "only the warning notice, no continuation: " + notices);
            assertEquals(AgentEvent.Notice.Level.WARN, notices.getFirst().level());
        } finally {
            permissionServiceOverride = null;
        }
    }

    /** An empty completion (no content, no tool call) must be retried, not silently turned into a stop. */
    @Test
    void emptyCompletionIsRetriedUpToTheBudgetThenSucceeds() {
        final var replies = new ArrayDeque<>(
                List.of(new ScriptedReply("", "stop", false), new ScriptedReply("actually done", "stop", false)));
        final var requests = new ArrayList<List<Message>>();
        final var result = loop(
                        chatClient(replies, requests),
                        null,
                        configuration(true, Map.of("yca.llm.emptyCompletionRetriesMax", "2")))
                .run("do it", null, "s1", null, () -> false);

        assertEquals("actually done", result);
        assertEquals(2, requests.size(), "the empty completion must re-ask the model once");
        final var notices =
                events.stream().filter(AgentEvent.Notice.class::isInstance).toList();
        assertEquals(1, notices.size(), notices.toString());
        assertEquals(AgentEvent.Notice.Level.WARN, ((AgentEvent.Notice) notices.getFirst()).level());
        assertTrue(((AgentEvent.Notice) notices.getFirst()).message().contains("empty completion"));
        assertTrue(((AgentEvent.Notice) notices.getFirst()).message().contains("retrying (1/2)"));
        assertTrue(events.getLast() instanceof AgentEvent.TurnDone);
    }

    /** After the configured budget the empty-completion retry gives up with an error notice, not a blank stop. */
    @Test
    void emptyCompletionGivesUpAfterTheBudgetWithAnErrorNotice() {
        final var replies = new ArrayDeque<>(List.of(
                new ScriptedReply("", "stop", false),
                new ScriptedReply("", "stop", false),
                new ScriptedReply("", "stop", false)));
        final var requests = new ArrayList<List<Message>>();
        final var result = loop(
                        chatClient(replies, requests),
                        null,
                        configuration(true, Map.of("yca.llm.emptyCompletionRetriesMax", "2")))
                .run("do it", null, "s1", null, () -> false);

        assertEquals("", result, "the turn ends once the empty-completion budget is spent");
        assertEquals(3, requests.size(), "2 retries then a final call");
        final var notices =
                events.stream().filter(AgentEvent.Notice.class::isInstance).toList();
        assertEquals(3, notices.size(), "2 retry warnings then the give-up error");
        assertEquals(AgentEvent.Notice.Level.WARN, ((AgentEvent.Notice) notices.get(0)).level());
        assertEquals(AgentEvent.Notice.Level.ERROR, ((AgentEvent.Notice) notices.getLast()).level());
        assertTrue(((AgentEvent.Notice) notices.getLast()).message().contains("gave up after 2 retries"));
        assertTrue(events.getLast() instanceof AgentEvent.TurnDone);
    }

    /**
     * An empty-completion give-up with pending todos falls through to the todo auto-continue: a blank
     * reply is a provider hiccup, so when work is unfinished the loop keeps going rather than stopping.
     */
    @Test
    void emptyCompletionGiveUpFallsThroughToTodoAutoContinue() {
        final var config = configuration(
                true,
                Map.of(
                        "yca.mode", "build",
                        "yca.permissions.mode", "yolo",
                        "yca.todos.continueOnUnfinished", "true",
                        "yca.llm.emptyCompletionRetriesMax", "1"));
        permissionServiceOverride = new PermissionService(config);
        permissionServiceOverride.setMode(AgentMode.BUILD);
        try {
            final var todoStore =
                    mutableTodoStore(new TodoItem("done step", "completed"), new TodoItem("later step", "pending"));
            final var requests = new ArrayList<List<Message>>();
            final var replies = new ArrayDeque<>(List.of(
                    toolCallReply("t1", "noop"),
                    new ScriptedReply("", "stop", false),
                    new ScriptedReply("", "stop", false),
                    toolCallReply("t2", "close_todos"),
                    new ScriptedReply("all done", "stop", false)));
            final var result = loop(
                            chatClient(replies, requests),
                            todoStore,
                            config,
                            noContextManager(),
                            noSessionStore(),
                            null,
                            todoToolsRegistry(todoStore),
                            directExecutions(),
                            null)
                    .run("do it", null, "s1", null, () -> false);

            assertEquals("all done", result, "the empty-completion give-up must continue the pending todos");
            final var notices = events.stream()
                    .filter(AgentEvent.Notice.class::isInstance)
                    .map(AgentEvent.Notice.class::cast)
                    .toList();
            assertTrue(
                    notices.stream()
                            .anyMatch(n -> n.level() == AgentEvent.Notice.Level.INFO
                                    && n.message().contains("continuing automatically (1/25)")),
                    "the empty-completion give-up must trigger a todo continuation: " + notices);
            assertTrue(
                    notices.stream()
                            .noneMatch(n -> n.level() == AgentEvent.Notice.Level.ERROR
                                    && n.message().contains("gave up after")),
                    "no empty-completion give-up error when pending todos can continue: " + notices);
        } finally {
            permissionServiceOverride = null;
        }
    }

    /**
     * The empty-completion retry budget is per stuck streak, not per turn: a tool round in between
     * resets it, so a long turn no longer dies after scattered empty replies (the production failure).
     */
    @Test
    void emptyCompletionRetryBudgetResetsAfterToolProgress() {
        final var replies = new ArrayDeque<>(List.of(
                new ScriptedReply("", "stop", false),
                toolCallReply("t1", "noop"),
                new ScriptedReply("", "stop", false),
                new ScriptedReply("actually done", "stop", false)));
        final var requests = new ArrayList<List<Message>>();
        final var result = loop(
                        chatClient(replies, requests),
                        null,
                        configuration(true, Map.of("yca.llm.emptyCompletionRetriesMax", "1")),
                        noContextManager(),
                        noSessionStore(),
                        null,
                        todoToolsRegistry(null),
                        directExecutions(),
                        null)
                .run("do it", null, "s1", null, () -> false);

        assertEquals("actually done", result, "each stuck streak gets its own retry budget");
        assertEquals(4, requests.size(), "empty (retry) -> tool -> empty (retry, budget reset) -> done");
        final var notices = events.stream()
                .filter(AgentEvent.Notice.class::isInstance)
                .map(AgentEvent.Notice.class::cast)
                .toList();
        assertEquals(2, notices.size(), "one retry warning per stuck streak: " + notices);
        assertTrue(
                notices.stream().allMatch(n -> n.level() == AgentEvent.Notice.Level.WARN),
                "both notices are retries, no give-up error: " + notices);
    }

    /** Setting the knob to 0 disables the retry: the error notice is emitted on the first empty completion. */
    @Test
    void emptyCompletionRetryCanBeDisabled() {
        final var replies = new ArrayDeque<>(List.of(new ScriptedReply("", "stop", false)));
        final var requests = new ArrayList<List<Message>>();
        final var result = loop(
                        chatClient(replies, requests),
                        null,
                        configuration(true, Map.of("yca.llm.emptyCompletionRetriesMax", "0")))
                .run("do it", null, "s1", null, () -> false);

        assertEquals("", result);
        assertEquals(1, requests.size(), "no re-ask when the knob is 0");
        final var notices =
                events.stream().filter(AgentEvent.Notice.class::isInstance).toList();
        assertEquals(1, notices.size());
        assertEquals(AgentEvent.Notice.Level.ERROR, ((AgentEvent.Notice) notices.getFirst()).level());
        assertTrue(((AgentEvent.Notice) notices.getFirst()).message().contains("emptyCompletionRetriesMax is 0"));
        assertTrue(events.getLast() instanceof AgentEvent.TurnDone);
    }

    /**
     * The empty-completion retry runs before the todo auto-continue: a blank reply is a provider
     * hiccup, not a deliberate "stopped mid-implementation" stop the auto-continue is made for.
     */
    @Test
    void emptyCompletionRetryWinsOverTodoAutoContinue() {
        final var config = configuration(
                true,
                Map.of(
                        "yca.mode", "build",
                        "yca.permissions.mode", "yolo",
                        "yca.todos.continueOnUnfinished", "true"));
        permissionServiceOverride = new PermissionService(config);
        permissionServiceOverride.setMode(AgentMode.BUILD);
        try {
            final var todoStore =
                    mutableTodoStore(new TodoItem("done step", "completed"), new TodoItem("later step", "pending"));
            final var requests = new ArrayList<List<Message>>();
            final var replies = new ArrayDeque<>(List.of(
                    toolCallReply("t1", "noop"),
                    new ScriptedReply("", "stop", false),
                    toolCallReply("t2", "close_todos"),
                    new ScriptedReply("done", "stop", false)));
            final var result = loop(
                            chatClient(replies, requests),
                            todoStore,
                            config,
                            noContextManager(),
                            noSessionStore(),
                            null,
                            todoToolsRegistry(todoStore),
                            directExecutions(),
                            null)
                    .run("do it", null, "s1", null, () -> false);

            assertEquals("done", result);
            assertEquals(4, requests.size(), "the empty completion goes through the retry, not the auto-continue");
            final var notices = events.stream()
                    .filter(AgentEvent.Notice.class::isInstance)
                    .map(AgentEvent.Notice.class::cast)
                    .toList();
            assertEquals(1, notices.size(), "only the retry warning, no todo auto-continue notice: " + notices);
            assertEquals(AgentEvent.Notice.Level.WARN, notices.getFirst().level());
            assertTrue(
                    notices.getFirst().message().contains("empty completion"),
                    notices.getFirst().message());
            assertTrue(
                    notices.stream().noneMatch(n -> n.message().contains("continuing automatically")),
                    "the empty completion must not be reported as a deliberate todo stop: " + notices);
        } finally {
            permissionServiceOverride = null;
        }
    }

    /**
     * A window that burns its whole iteration budget on tool calls does not end the turn while todos
     * are pending: the todo auto-continue starts a fresh budget, so a large --todos-maxContinues is
     * actually reachable instead of being capped by yca.llm.maxIterations (10 in this test's config).
     */
    @Test
    void iterationBudgetExhaustionRestartsViaTodoAutoContinue() {
        final var config = configuration(
                true,
                Map.of(
                        "yca.mode", "build",
                        "yca.permissions.mode", "yolo",
                        "yca.todos.continueOnUnfinished", "true"));
        permissionServiceOverride = new PermissionService(config);
        permissionServiceOverride.setMode(AgentMode.BUILD);
        try {
            final var todoStore =
                    mutableTodoStore(new TodoItem("done step", "completed"), new TodoItem("later step", "pending"));
            final var requests = new ArrayList<List<Message>>();
            final var replies = new ArrayDeque<ScriptedReply>();
            // maxIterations is 10: exhaust the first window with tool-only rounds, then let the restart close
            for (var i = 0; i < 11; i++) {
                replies.add(toolCallReply("t" + i, "noop"));
            }
            replies.add(toolCallReply("close", "close_todos"));
            replies.add(new ScriptedReply("all done", "stop", false));
            final var result = loop(
                            chatClient(replies, requests),
                            todoStore,
                            config,
                            noContextManager(),
                            noSessionStore(),
                            null,
                            todoToolsRegistry(todoStore),
                            directExecutions(),
                            null)
                    .run("do it", null, "s1", null, () -> false);

            assertEquals("all done", result, "iteration exhaustion must not end the turn with pending todos");
            assertEquals(13, requests.size(), "10 rounds, then a fresh window of 3 more calls");
            final var notices = events.stream()
                    .filter(AgentEvent.Notice.class::isInstance)
                    .map(AgentEvent.Notice.class::cast)
                    .toList();
            assertTrue(
                    notices.stream()
                            .anyMatch(n -> n.level() == AgentEvent.Notice.Level.INFO
                                    && n.message().contains("finish_reason=max_iterations")
                                    && n.message().contains("continuing automatically (1/25)")),
                    "iteration exhaustion must be reported as a todo continuation: " + notices);
            assertTrue(
                    notices.stream().noneMatch(n -> n.message().contains("Maximum iterations reached")),
                    "no hard iteration error while a todo continuation remains: " + notices);
        } finally {
            permissionServiceOverride = null;
        }
    }

    private static ScriptedReply toolCallReply(final String callId, final String tool) {
        return new ScriptedReply(null, "tool_calls", false) {
            @Override
            Message toMessage() {
                return new Message(
                        "assistant",
                        null,
                        null,
                        List.of(new ToolCall(callId, "function", new ToolCall.FunctionCall(tool, "{}"), 0)),
                        null,
                        null);
            }
        };
    }

    private static MutableTodoStore mutableTodoStore(final TodoItem... initial) {
        return new MutableTodoStore(List.of(initial));
    }

    private static final class MutableTodoStore extends TodoStore {
        private List<TodoItem> todos;

        private MutableTodoStore(final List<TodoItem> todos) {
            this.todos = List.copyOf(todos);
        }

        @Override
        public List<TodoItem> all() {
            return todos;
        }

        @Override
        public synchronized List<TodoItem> replace(final List<TodoItem> items) {
            todos = List.copyOf(items);
            return all();
        }
    }

    /** {@code noop} changes nothing, {@code close_todos} completes the whole list like a done write_todo. */
    private static ToolRegistry todoToolsRegistry(final TodoStore todoStore) {
        return new ToolRegistry(null) {
            @Override
            public List<Tool> toWireTools() {
                return List.of();
            }

            @Override
            public EmbeddedTool tool(final String name) {
                if (!"noop".equals(name) && !"close_todos".equals(name)) {
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
                        return ToolCategory.AGENT;
                    }

                    @Override
                    public Map<String, Object> schema() {
                        return Map.of();
                    }

                    @Override
                    public ToolResult execute(final Map<String, Object> args) {
                        if ("close_todos".equals(name)) {
                            todoStore.replace(List.of(new TodoItem("done step", "completed")));
                        }
                        return ToolResult.ok("done");
                    }
                };
            }
        };
    }

    @Test
    void cancellationBeforeToolExecutionSkipsTheTools() {
        final var executions = new AtomicInteger();
        final var calls = new AtomicInteger();
        final var client = new ChatClient() {
            @Override
            public Message stream(
                    final ChatCompletionRequest request,
                    final ChatSettings settings,
                    final Consumer<PauseEvent> pauseListener,
                    final Consumer<LlmStreamEvent> onDelta,
                    final BooleanSupplier cancelled) {
                if (calls.incrementAndGet() == 1) {
                    return Message.assistant(
                            null,
                            List.of(new ToolCall(
                                    "t1",
                                    "function",
                                    new ToolCall.FunctionCall("read_file", "{\"path\":\"/tmp/x\"}"),
                                    0)));
                }
                onDelta.accept(new End("stop", null));
                return Message.assistant("all done", null);
            }
        };
        final var tool = new EmbeddedTool() {
            @Override
            public String name() {
                return "read_file";
            }

            @Override
            public String description() {
                return "counted test tool";
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
                return ToolResult.ok("ran");
            }
        };
        final var result = loop(
                        client,
                        null,
                        configuration(true, Map.of("yca.mode", "build", "yca.permissions.mode", "yolo")),
                        noPruningManager(),
                        noopStore(),
                        null,
                        registryOf(tool),
                        directExecutions(),
                        new VirtualThreadExecutor())
                .run(
                        "do it",
                        null,
                        "s1",
                        null,
                        () -> calls.get() >= 1); // cancelled right after the tool-call reply lands
        assertEquals(0, executions.get(), "the tool must not run when cancelled before its execution: " + result);
    }

    @Test
    void noTodosWarningInPlanMode() {
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
        final var config = configuration(true);
        permissionServiceOverride = new PermissionService(config);
        permissionServiceOverride.setMode(AgentMode.PLAN);
        try {
            loop(chatClient(replies, new ArrayList<>()), todoStore, config).run("do it", null, "s1", null, () -> false);
        } finally {
            permissionServiceOverride = null;
        }

        assertEquals(
                0,
                events.stream().filter(AgentEvent.Notice.class::isInstance).count(),
                "plan mode must not warn about pending todos: that is the goal of the mode");
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
    void noTodosWarningWhenOnlyCancelledItemsRemain() {
        final var todoStore = new TodoStore() {
            private List<TodoItem> todos = List.of(new TodoItem("dropped step", "cancelled"));

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
                new ScriptedReply("adjusted", "stop", false)));
        loop(chatClient(replies, new ArrayList<>()), todoStore, configuration(true))
                .run("do it", null, "s1", null, () -> false);

        assertEquals(
                0,
                events.stream().filter(AgentEvent.Notice.class::isInstance).count(),
                "cancelled todos count as closed: no unfinished-todos warning");
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
            public CompactionResult compact(
                    final List<Message> messages,
                    final boolean force,
                    final String focus,
                    final int keepLastOverride,
                    final java.util.function.BooleanSupplier cancelled) {
                return new CompactionResult(
                        List.of(
                                messages.get(0),
                                Message.user(
                                        "<compacted-context>\ndigest\n</compacted-context>\n\n"
                                                + "When a compacted context is provided, treat it as historical state.\n"
                                                + "Do not reload, reconstruct, or repeat actions already described in it.\n"
                                                + "Continue from the state it represents; only call tools when needed for the current task."),
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
    void usageIsNotClobberedWhenUsageAndFinishShareTheFinalStreamLine() {
        // a single SSE line carrying both usage and a finish reason is decoded by the dialect into a
        // usage End followed by a finish-reason-only End; the trailing End must not reset the
        // captured usage back to empty (regression: tokens reported 0 for every streamed turn)
        recordedUsage.set(null);
        final var usage = new io.yupiik.llm.client.model.Usage(12L, 7L, 19L, 3L, 1L);
        final var replies = new ArrayDeque<>(List.of(new ScriptedReply("done", "stop", false, usage)));
        final var result = loop(chatClient(replies, new ArrayList<>()), null, configuration(true))
                .run("do it", null, "s1", null, () -> false);

        assertEquals("done", result);
        final var recorded = recordedUsage.get();
        assertNotNull(recorded, "usage must be recorded for the finished turn");
        assertEquals(12, recorded.promptTokens(), "prompt tokens must survive the trailing finish End");
        assertEquals(7, recorded.completionTokens(), "completion tokens must survive the trailing finish End");
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
                return compact(messages, false, focus, keepLast);
            }

            @Override
            public CompactionResult compact(
                    final List<Message> messages, final boolean force, final String focus, final int keepLast) {
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
                    final ChatSettings settings,
                    final Consumer<PauseEvent> pauseListener,
                    final Consumer<LlmStreamEvent> onDelta,
                    final BooleanSupplier cancelled) {
                if (calls.incrementAndGet() == 1) {
                    return Message.assistant(
                            null, List.of(new ToolCall("t1", "function", new ToolCall.FunctionCall("bash", "{}"), 0)));
                }
                onDelta.accept(new End("stop", null));
                return Message.assistant("all done", null);
            }
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
    void toolOutputsAreCappedAtIngestionBeforeEnteringTheConversation() {
        final var loop = loop(
                scriptedToolClient(new ArrayList<>(), List.of()),
                null,
                configuration(true, Map.of("yca.pruning.ingestionOutputCapChars", "2000")));

        final var capped = loop.capIngestionOutput("x".repeat(50_000));
        assertTrue(capped.contains("[... output truncated ("), "the oversized output must carry the marker");
        assertTrue(capped.length() <= 2_200, "capped output must stay near the cap: " + capped.length());
        assertTrue(capped.startsWith("xxxx"), "the head must be preserved");
        assertTrue(capped.endsWith("xxxx"), "the tail must be preserved");

        assertEquals("short", loop.capIngestionOutput("short"), "short outputs must stay untouched");

        final var noCap = loop(
                scriptedToolClient(new ArrayList<>(), List.of()),
                null,
                configuration(true, Map.of("yca.pruning.ingestionOutputCapChars", "0")));
        assertEquals("y".repeat(5_000), noCap.capIngestionOutput("y".repeat(5_000)), "cap=0 must disable the cap");
    }

    @Test
    void identicalToolCallsAreDeduplicatedKeepingTheLatest() {
        final var loop = loop(scriptedToolClient(new ArrayList<>(), List.of()), null, configuration(true));
        final var messages = new ArrayList<Message>();
        messages.add(Message.assistant(
                null, List.of(new ToolCall("c1", "function", new ToolCall.FunctionCall("same_tool", "{\"a\":1}"), 0))));
        messages.add(Message.tool("c1", "out-1"));
        messages.add(Message.assistant(
                null, List.of(new ToolCall("c2", "function", new ToolCall.FunctionCall("same_tool", "{\"a\":1}"), 0))));
        messages.add(Message.tool("c2", "out-2"));

        assertEquals(1, loop.deduplicateToolCalls(messages));
        assertTrue(
                String.valueOf(messages.get(1).content()).startsWith("[duplicate tool call pruned"),
                "the older identical result must be replaced by a marker");
        assertEquals("out-2", String.valueOf(messages.get(3).content()), "the latest output must survive");
    }

    @Test
    void protectedToolsAreNotDeduplicated() {
        final var loop = loop(scriptedToolClient(new ArrayList<>(), List.of()), null, configuration(true));
        final var messages = new ArrayList<Message>();
        messages.add(Message.assistant(
                null,
                List.of(new ToolCall(
                        "w1", "function", new ToolCall.FunctionCall("write_file", "{\"path\":\"a\"}"), 0))));
        messages.add(Message.tool("w1", "written"));
        messages.add(Message.assistant(
                null,
                List.of(new ToolCall(
                        "w2", "function", new ToolCall.FunctionCall("write_file", "{\"path\":\"a\"}"), 0))));
        messages.add(Message.tool("w2", "written"));

        assertEquals(0, loop.deduplicateToolCalls(messages), "write_file must be protected from dedup");
    }

    @Test
    void failedToolInputsArePurgedAfterTheConfiguredTurns() {
        final var loop = loop(
                scriptedToolClient(new ArrayList<>(), List.of()),
                null,
                configuration(true, Map.of("yca.pruning.purgeFailedToolInputsTurns", "0")));
        final var messages = new ArrayList<Message>();
        messages.add(Message.assistant(
                null,
                List.of(new ToolCall("t1", "function", new ToolCall.FunctionCall("fail_tool", "{\"big\":\"x\"}"), 0))));
        messages.add(Message.tool("t1", "boom", true));

        assertEquals(1, loop.purgeFailedToolInputs(messages));
        assertEquals("{}", messages.get(0).toolCalls().get(0).function().arguments());
        assertEquals("boom", String.valueOf(messages.get(1).content()), "the error result must survive");
    }

    @Test
    void writesOverwrittenByLaterReadsAreSupersededWhenEnabled() {
        final var loopOn = loop(
                scriptedToolClient(new ArrayList<>(), List.of()),
                null,
                configuration(true, Map.of("yca.pruning.supersedeOverwrittenWrites", "true")));
        final var messages = new ArrayList<Message>();
        messages.add(Message.assistant(
                null,
                List.of(new ToolCall(
                        "w", "function", new ToolCall.FunctionCall("write_file", "{\"path\":\"a.txt\"}"), 0))));
        messages.add(Message.tool("w", "written"));
        messages.add(Message.assistant(
                null,
                List.of(new ToolCall(
                        "r", "function", new ToolCall.FunctionCall("read_file", "{\"path\":\"a.txt\"}"), 0))));
        messages.add(Message.tool("r", "content"));

        assertEquals(1, loopOn.supersedeOverwrittenWrites(messages), "a write overwritten by a read is superseded");
        assertTrue(
                String.valueOf(messages.get(0).toolCalls().get(0).function().arguments())
                        .startsWith("[write superseded"),
                "the write input must be superseded");
        assertEquals("content", String.valueOf(messages.get(3).content()), "the read output must survive");

        // a write with no later read stays untouched
        final var untouched = new ArrayList<Message>();
        untouched.add(Message.assistant(
                null,
                List.of(new ToolCall(
                        "w2", "function", new ToolCall.FunctionCall("write_file", "{\"path\":\"b.txt\"}"), 0))));
        untouched.add(Message.tool("w2", "written"));
        assertEquals(0, loopOn.supersedeOverwrittenWrites(untouched));
        assertEquals(
                "{\"path\":\"b.txt\"}",
                untouched.get(0).toolCalls().get(0).function().arguments());
    }

    @Test
    void softNudgeIsSuppressedWhenThresholdIsZero() {
        final var loop = loop(
                scriptedToolClient(new ArrayList<>(), List.of()),
                null,
                configuration(true, Map.of("yca.pruning.nudgeThresholdPercent", "0")));
        final var messages = new ArrayList<Message>();
        messages.add(Message.user("u"));
        // threshold 0 => no nudge regardless of iterations
        assertEquals("", loop.nudgeBlock(messages, 0));
    }

    @Test
    void softNudgeFiresInTheSoftBandWithUsageAndThresholds() {
        // the loop() helper injects a stub UsageTracker (contextPercent=1, estimateContext=100):
        // threshold 1 fires the nudge, prune threshold 65 keeps it in the soft band
        final var loop = loop(
                scriptedToolClient(new ArrayList<>(), List.of()),
                null,
                configuration(
                        true,
                        Map.of(
                                "yca.pruning.nudgeThresholdPercent", "1",
                                "yca.pruning.nudgeIterationThreshold", "1",
                                "yca.pruning.nudgeMinTokens", "0")));
        final var messages = new ArrayList<Message>();
        messages.add(Message.user("u"));
        messages.add(Message.tool("t1", "some result"));
        final var nudge = loop.nudgeBlock(messages, 0);
        assertTrue(nudge.startsWith("\n<nudge>"), nudge);
        assertTrue(nudge.endsWith("</nudge>"), nudge);
        assertTrue(nudge.contains("~1"), "must carry the usage percent, got " + nudge);
        assertFalse(nudge.contains("Do not start new work"), "usage below the prune threshold must keep the soft band");
    }

    @Test
    void urgentNudgeFiresOnceUsageReachesThePruneThreshold() {
        final var loop = loop(
                scriptedToolClient(new ArrayList<>(), List.of()),
                null,
                configuration(
                        true,
                        Map.of(
                                "yca.pruning.nudgeThresholdPercent", "1",
                                "yca.pruning.nudgeIterationThreshold", "1",
                                "yca.pruning.nudgeMinTokens", "0",
                                // stub percent is 1 -> >= 1 makes the urgent band fire
                                "yca.pruning.thresholdPercent", "1",
                                "yca.tools.manageContext.enabled", "true")));
        final var messages = new ArrayList<Message>();
        messages.add(Message.user("u"));
        messages.add(Message.tool("t1", "some result"));
        final var nudge = loop.nudgeBlock(messages, 0);
        assertTrue(nudge.contains("Do not start new work"), nudge);
        assertTrue(nudge.contains("above the prune threshold"), nudge);
        assertTrue(
                nudge.contains("manage_context with action=prune or action=compact"),
                "the %ACTIONS% placeholder must be resolved to the tool suggestion when enabled, got: " + nudge);
    }

    @Test
    void nudgeActionsFallbackWhenManageContextIsDisabled() {
        final var loop = loop(
                scriptedToolClient(new ArrayList<>(), List.of()),
                null,
                configuration(
                        true,
                        Map.of(
                                "yca.pruning.nudgeThresholdPercent", "1",
                                "yca.pruning.nudgeIterationThreshold", "1",
                                "yca.pruning.nudgeMinTokens", "0",
                                "yca.pruning.thresholdPercent", "1",
                                "yca.tools.manageContext.enabled", "false")));
        final var messages = new ArrayList<Message>();
        messages.add(Message.user("u"));
        messages.add(Message.tool("t1", "some result"));
        final var nudge = loop.nudgeBlock(messages, 0);
        assertTrue(nudge.contains("interrupt with a short summary"), nudge);
        assertFalse(nudge.contains("manage_context"), "must not reference a disabled tool, got: " + nudge);
    }

    @Test
    void nudgeIsSuppressedUntilTheIterationThresholdIsReached() {
        final var loop = loop(
                scriptedToolClient(new ArrayList<>(), List.of()),
                null,
                configuration(
                        true,
                        Map.of(
                                "yca.pruning.nudgeThresholdPercent", "1",
                                "yca.pruning.nudgeIterationThreshold", "3",
                                "yca.pruning.nudgeMinTokens", "0")));
        final var messages = new ArrayList<Message>();
        messages.add(Message.user("u"));
        messages.add(Message.tool("t1", "some result"));
        assertEquals("", loop.nudgeBlock(messages, 0));
    }

    @Test
    void nudgeIsSuppressedBelowTheMinTokenFloor() {
        // stub estimateContext is 100 tokens: the default floor (64000, capped at 90% of the usable
        // window) is far above it, so even with a triggering percent the nudge stays silent
        final var floored = loop(
                scriptedToolClient(new ArrayList<>(), List.of()),
                null,
                configuration(
                        true,
                        Map.of(
                                "yca.pruning.nudgeThresholdPercent", "1",
                                "yca.pruning.nudgeIterationThreshold", "1")));
        final var messages = new ArrayList<Message>();
        messages.add(Message.user("u"));
        messages.add(Message.tool("t1", "some result"));
        assertEquals(
                "", floored.nudgeBlock(messages, 0), "the default floor must suppress the nudge on a small context");

        // a low floor (below the 100-token estimate) lets the percentage alone gate it
        final var lowFloor = loop(
                scriptedToolClient(new ArrayList<>(), List.of()),
                null,
                configuration(
                        true,
                        Map.of(
                                "yca.pruning.nudgeThresholdPercent", "1",
                                "yca.pruning.nudgeIterationThreshold", "1",
                                "yca.pruning.nudgeMinTokens", "50")));
        assertTrue(lowFloor.nudgeBlock(messages, 0).startsWith("\n<nudge>"));
    }

    @Test
    void contextUsageLineIsAlwaysRenderedInTheSessionState() {
        final var loop = loop(
                scriptedToolClient(new ArrayList<>(), List.of()),
                null,
                configuration(
                        true,
                        Map.of(
                                "yca.provider.providers.0.contextWindow", "2000",
                                "yca.pruning.thresholdPercent", "90")));
        final var messages = new ArrayList<Message>();
        messages.add(Message.user("u"));
        messages.add(Message.tool("t1", "x".repeat(2500)));
        final var line = loop.contextUsageLine(messages, 0);
        assertTrue(line.startsWith("Context usage: ~"), line);
        assertTrue(line.contains("(prune at 90%, compaction at 80%)"), line);
        // the line is unconditional: rendered even when the nudge stays silent (iteration guard)
        assertTrue(loop.nudgeBlock(messages, 15).isEmpty());
    }

    @Test
    void appendStateIfChangedKeepsTheContextUsageLineInTheTail() {
        final var requests = new ArrayList<List<Message>>();
        final var loop = loop(
                scriptedToolClient(
                        requests,
                        List.of(new ToolCall("t1", "function", new ToolCall.FunctionCall("inline_read", "{}"), 0))),
                null,
                configuration(
                        true,
                        Map.of(
                                "yca.provider.providers.0.contextWindow", "100000",
                                "yca.pruning.nudgeThresholdPercent", "0")));
        final var result = loop.run("do it", null, "s1", null, () -> false);
        assertEquals("all done", result);
        final var tail = requests.get(1).get(2);
        assertTrue(
                String.valueOf(tail.content()).contains("Context usage: ~"),
                "the volatile tail must always carry the usage line, got: " + tail.content());
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
        assertEquals("done-b", tools.get(1).content(), "the tool result stays intact without any session-state tail");
        assertEquals(6, requests.get(1).size(), "system + user + tail + assistant + both tool results");
        final var tail = requests.get(1).get(2);
        assertEquals("user", tail.role(), "the session state rides its own trailing user message");
        assertTrue(
                String.valueOf(tail.content()).startsWith("<session-state>"),
                "the session state is a standalone trailing message: " + tail.content());
        assertTrue(Boolean.TRUE.equals(tail.others().get("volatileTail")), "the tail is flagged volatile");
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
                    final ChatSettings settings,
                    final Consumer<PauseEvent> pauseListener,
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
                    final ChatSettings settings,
                    final Consumer<PauseEvent> pauseListener,
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
        assertTrue(
                String.valueOf(beforeWrite.content()).startsWith("old content"),
                "the read result must be intact before the write lands");
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
                    final ChatSettings settings,
                    final Consumer<PauseEvent> pauseListener,
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

    private ToolExecutionService directExecutions() {
        return new ToolExecutionService() {
            @Override
            public ToolResult execute(
                    final EmbeddedTool tool, final Map<String, Object> args, final ToolContext context) {
                return tool.execute(args, context);
            }
        };
    }

    @Test
    void sessionStateRidesItsOwnTrailingUserMessage() {
        final var requests = new ArrayList<List<Message>>();
        final var replies = new ArrayDeque<>(List.of(new ScriptedReply("answer", "stop", false)));
        final var client = chatClient(replies, requests);
        loop(client, new TodoStore() {}, configuration(true)).run("my prompt", null, "s1", null, () -> false);
        assertEquals(1, requests.size());
        final var sent = requests.get(0);
        assertEquals(3, sent.size(), "system + the user prompt + the standalone session-state message");
        assertEquals("system prompt", sent.get(0).content());
        assertEquals("user", sent.get(1).role());
        assertEquals("my prompt", sent.get(1).content(), "the user prompt must not be mutated by the tail");
        assertEquals("user", sent.get(2).role());
        assertTrue(
                String.valueOf(sent.get(2).content()).startsWith("<session-state>"),
                "the refreshed session state is its own trailing message: "
                        + sent.get(2).content());
        assertTrue(
                sent.get(2).others() != null
                        && Boolean.TRUE.equals(sent.get(2).others().get("volatileTail")),
                "the session-state message must be flagged volatileTail for cache breakpoint dialects");
    }

    /**
     * Regression for the "this.cancelled is null" NPE: two turns running on the same
     * {@code AgentLoop} instance must not share a mutable cancellation field (the probe is now a
     * per-call parameter threaded down to the tools). The first turn blocks inside a tool while a
     * second {@code run} starts on the same instance; after the first unwinds, the second completes
     * its own tool without a NPE and with its own probe.
     */
    @Test
    void concurrentTurnsOnSameLoopKeepIndependentCancellation() throws Exception {
        final var config = configuration(
                true,
                Map.of(
                        "yca.mode", "build",
                        "yca.permissions.mode", "yolo"));
        final var started = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final var chatCalls = new AtomicInteger();
        final var blockingExecutions = new AtomicInteger();
        final var secondExecutions = new AtomicInteger();
        final var registry = new ToolRegistry(null) {
            @Override
            public List<Tool> toWireTools() {
                return List.of();
            }

            @Override
            public EmbeddedTool tool(final String name) {
                return switch (name) {
                    case "blocking" ->
                        new EmbeddedTool() {
                            @Override
                            public String name() {
                                return "blocking";
                            }

                            @Override
                            public String description() {
                                return "blocks";
                            }

                            @Override
                            public Map<String, Object> schema() {
                                return Map.of();
                            }

                            @Override
                            public ToolCategory category() {
                                return ToolCategory.AGENT;
                            }

                            @Override
                            public ToolResult execute(final Map<String, Object> args) {
                                throw new UnsupportedOperationException();
                            }

                            @Override
                            public ToolResult execute(final Map<String, Object> args, final ToolContext context) {
                                blockingExecutions.incrementAndGet();
                                started.countDown();
                                try {
                                    release.await();
                                } catch (final InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                                return ToolResult.ok("blocking done");
                            }
                        };
                    case "simple" ->
                        new EmbeddedTool() {
                            @Override
                            public String name() {
                                return "simple";
                            }

                            @Override
                            public String description() {
                                return "simple";
                            }

                            @Override
                            public Map<String, Object> schema() {
                                return Map.of();
                            }

                            @Override
                            public ToolCategory category() {
                                return ToolCategory.AGENT;
                            }

                            @Override
                            public ToolResult execute(final Map<String, Object> args) {
                                throw new UnsupportedOperationException();
                            }

                            @Override
                            public ToolResult execute(final Map<String, Object> args, final ToolContext context) {
                                secondExecutions.incrementAndGet();
                                return ToolResult.ok("simple done");
                            }
                        };
                    default -> throw new IllegalArgumentException(name);
                };
            }
        };
        // turn 1 asks for "blocking", turn 2 asks for "simple" (see scripted replies below)
        final var client = new ChatClient() {
            @Override
            public Message stream(
                    final ChatCompletionRequest request,
                    final ChatSettings settings,
                    final Consumer<PauseEvent> pauseListener,
                    final Consumer<LlmStreamEvent> onDelta,
                    final BooleanSupplier cancelled) {
                chatCalls.incrementAndGet();
                final var userMessage = request.messages().get(1) == null
                        ? ""
                        : String.valueOf(request.messages().get(1).content());
                final var firstTurn = userMessage.contains("first");
                // the first LLM call of a turn carries the tool call; later calls (after the tool
                // result lands) return a plain answer so the turn ends
                final var toolCall = firstTurn ? "blocking" : "simple";
                if (request.messages().stream()
                        .noneMatch(m -> "tool".equals(m.role())
                                && String.valueOf(m.content()).contains(toolCall + " done"))) {
                    return new Message(
                            "assistant",
                            null,
                            null,
                            List.of(new ToolCall(
                                    firstTurn ? "1" : "2", "function", new ToolCall.FunctionCall(toolCall, "{}"), 0)),
                            null,
                            null);
                }
                return new Message("assistant", "done", null, null, null, null);
            }
        };
        final var loop = loop(
                client,
                new TodoStore() {},
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
                null,
                registry,
                new ToolExecutionService(
                        config,
                        new Emitter() {
                            @Override
                            public <T> void emit(final T event) {}
                        },
                        List.of()) {
                    @Override
                    public ToolResult execute(
                            final EmbeddedTool tool, final Map<String, Object> args, final ToolContext context) {
                        return tool.execute(args, context);
                    }
                },
                new VirtualThreadExecutor(),
                null);

        final var first = Thread.ofVirtual().start(() -> {
            try {
                loop.run("first", null, "s1", null, () -> false);
            } catch (final Throwable t) {
                throw new RuntimeException(t);
            }
        });
        assertTrue(
                started.await(5, TimeUnit.SECONDS),
                "the first turn must reach its blocking tool; chatCalls=" + chatCalls);

        final var second = Thread.ofVirtual().start(() -> {
            try {
                loop.run("second", null, "s1", null, () -> false);
            } catch (final Throwable t) {
                throw new RuntimeException(t);
            }
        });
        // let the second turn start its own tool while the first is still blocked
        Thread.sleep(100);
        release.countDown();
        first.join(10_000);
        second.join(10_000);

        assertFalse(first.isAlive(), "the first turn must complete");
        assertFalse(second.isAlive(), "the second turn must complete");
        assertEquals(1, blockingExecutions.get(), "the blocking tool must run exactly once");
        assertEquals(1, secondExecutions.get(), "the second turn's tool must run exactly once");
    }

    /**
     * A turn always carries a real cancellation probe: a caller with no interrupt flag passes
     * {@link io.yupiik.ia.agent.core.runtime.Cancellations#NEVER}, and a {@code null} is a caller
     * bug which must fail at the boundary - named, and not swallowed into the mid-turn
     * {@code unexpected failure: java.lang.NullPointerException ... this.cancelled is null}.
     */
    @Test
    void nullCancellationProbeIsRejectedAtTheBoundary() {
        final var executions = new AtomicInteger();
        final var seenContext = new AtomicReference<ToolContext>();
        final var config = configuration(
                true,
                Map.of(
                        "yca.mode", "build",
                        "yca.permissions.mode", "yolo"));
        final var registry = new ToolRegistry(null) {
            @Override
            public List<Tool> toWireTools() {
                return List.of();
            }

            @Override
            public EmbeddedTool tool(final String name) {
                if (!"ping".equals(name)) {
                    throw new IllegalArgumentException(name);
                }
                return new EmbeddedTool() {
                    @Override
                    public String name() {
                        return "ping";
                    }

                    @Override
                    public String description() {
                        return "ping";
                    }

                    @Override
                    public Map<String, Object> schema() {
                        return Map.of();
                    }

                    @Override
                    public ToolCategory category() {
                        return ToolCategory.AGENT;
                    }

                    @Override
                    public ToolResult execute(final Map<String, Object> args) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public ToolResult execute(final Map<String, Object> args, final ToolContext context) {
                        executions.incrementAndGet();
                        seenContext.set(context);
                        return ToolResult.ok("pong");
                    }
                };
            }
        };
        final var client = new ChatClient() {
            @Override
            public Message stream(
                    final ChatCompletionRequest request,
                    final ChatSettings settings,
                    final Consumer<PauseEvent> pauseListener,
                    final Consumer<LlmStreamEvent> onDelta,
                    final BooleanSupplier cancelled) {
                // the probe handed to the client must be usable too, not null
                assertNotNull(cancelled, "the LLM client must receive a usable cancellation probe");
                final var toolAlreadyRan = request.messages().stream().anyMatch(m -> "tool".equals(m.role()));
                return toolAlreadyRan
                        ? new Message("assistant", "all done", null, null, null, null)
                        : new Message(
                                "assistant",
                                null,
                                null,
                                List.of(new ToolCall("1", "function", new ToolCall.FunctionCall("ping", "{}"), 0)),
                                null,
                                null);
            }
        };
        final var loop = loop(
                client,
                new TodoStore() {},
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
                noopStore(),
                null,
                registry,
                new ToolExecutionService() {
                    @Override
                    public ToolResult execute(
                            final EmbeddedTool tool, final Map<String, Object> args, final ToolContext context) {
                        return tool.execute(args, context);
                    }
                },
                new VirtualThreadExecutor());

        final var answer = loop.run("hello", null, "s1", null, Cancellations.NEVER);

        assertEquals("all done", answer, "the turn must run to its end");
        assertEquals(1, executions.get(), "the tool must run exactly once");
        assertNotNull(seenContext.get(), "the tool must receive a context");
        assertFalse(seenContext.get().isCancelled(), "a never-cancelled probe reports false");
        final var errors =
                events.stream().filter(AgentEvent.Error.class::isInstance).toList();
        assertTrue(errors.isEmpty(), "no error event, got: " + errors);

        // a null probe fails fast at the boundary, naming the offending argument: the turn never
        // starts, so nothing is rendered as an "unexpected failure"
        events.clear();
        final var thrown = assertThrows(NullPointerException.class, () -> loop.run("hello", null, "s1", null, null));
        assertTrue(
                thrown.getMessage().contains("cancelled"),
                "the failure must name the probe, got: " + thrown.getMessage());
        assertTrue(
                events.stream().noneMatch(AgentEvent.Error.class::isInstance),
                "a rejected probe must not be reported as a turn failure: " + events);
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
        assertEquals(4, sent.size(), "system + history verbatim + trailing session-state user message");
        assertEquals("system", sent.get(0).role());
        assertEquals("original question", sent.get(1).content());
        assertEquals("partial answer", sent.get(2).content());
        assertEquals("user", sent.get(3).role());
        assertTrue(
                String.valueOf(sent.get(3).content()).startsWith("<session-state>"),
                "the trailing wire message only carries the refreshed session state: "
                        + sent.get(3).content());
        assertTrue(
                sent.get(3).others() != null
                        && Boolean.TRUE.equals(sent.get(3).others().get("volatileTail")),
                "the session-state message must be flagged volatileTail for dialects using cache breakpoints");
        assertTrue(
                appended.stream().noneMatch(e -> "user".equals(e.role())),
                "resume must not persist any new input (only the assistant reply is appended)");
    }

    @Test
    void queuedPromptIsIntegratedMidToolLoop() {
        final var requests = new ArrayList<List<Message>>();
        final var appended = new ArrayList<SessionEntry>();
        final var store = new SessionStore() {
            @Override
            public void append(final String id, final SessionEntry entry) {
                appended.add(entry);
            }
        };
        final var client = scriptedToolClient(
                requests, List.of(new ToolCall("call-1", "function", new ToolCall.FunctionCall("grep", "{}"), null)));
        final var polls = new AtomicInteger();
        final var loop = loop(client, new TodoStore() {}, configuration(true), noPruningManager(), store, null);
        loop.run("do it", null, "s1", null, () -> false, () -> polls.getAndIncrement() == 1 ? "follow up!" : null);
        assertEquals(2, requests.size(), "tool round then final answer carrying the injected prompt");
        final var injected = requests.get(1);
        assertEquals("user", injected.get(injected.size() - 1).role());
        assertEquals("follow up!", injected.get(injected.size() - 1).content());
        assertTrue(
                injected.stream()
                        .anyMatch(m -> m.others() != null
                                && Boolean.TRUE.equals(m.others().get("volatileTail"))),
                "the previous session-state tail is still in the request (state did not change, no duplicate): "
                        + injected);
        assertTrue(
                events.stream().anyMatch(candidate -> candidate instanceof AgentEvent.PromptIntegrated),
                "the queued prompt must surface as a PromptIntegrated event");
        assertTrue(
                appended.stream().anyMatch(e -> "user".equals(e.role()) && "follow up!".equals(e.content())),
                "the injected prompt must be persisted as a user entry");
    }

    @Test
    void queuedPromptAtFinalAnswerKeepsTheTurnRunning() {
        final var requests = new ArrayList<List<Message>>();
        final var replies = new ArrayDeque<>(List.of(
                new ScriptedReply("first answer", "stop", false), new ScriptedReply("second answer", "stop", false)));
        final var client = chatClient(replies, requests);
        final var polls = new AtomicInteger();
        final var loop =
                loop(client, new TodoStore() {}, configuration(true), noPruningManager(), new SessionStore() {}, null);
        loop.run("do it", null, "s1", null, () -> false, () -> polls.getAndIncrement() == 1 ? "one more" : null);
        assertEquals(2, requests.size(), "the queued prompt must trigger a second LLM call");
        assertTrue(
                events.stream().anyMatch(candidate -> candidate instanceof AgentEvent.PromptIntegrated),
                "the queued prompt must surface as a PromptIntegrated event");
        assertEquals(
                1,
                events.stream()
                        .filter(candidate -> candidate instanceof AgentEvent.TurnDone)
                        .count(),
                "TurnDone must be emitted exactly once, when the turn actually ends");
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

    @Test
    void memoryIsNotResentInEveryRoundTail() {
        final var cfg = configuration(true);
        final var memory = new io.yupiik.ia.agent.core.memory.MemoryStore(cfg);
        memory.append(true, List.of("a durable fact"));
        final var requests = new ArrayList<List<Message>>();
        final var client = scriptedToolClient(
                requests,
                List.of(new ToolCall(
                        "c1", "function", new ToolCall.FunctionCall("read_file", "{\"path\":\"a.txt\"}"), 0)));
        final var registry = registryOf(new EmbeddedTool() {
            @Override
            public String name() {
                return "read_file";
            }

            @Override
            public String description() {
                return "read a file";
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
        });
        final var builder = new SystemPromptBuilder(cfg, memory, null, null, null, null, null, null) {
            @Override
            public String build(final String prompt, final AgentConfiguration agent) {
                return "system\n\n" + memorySection(prompt);
            }
        };
        final var loop = loop(
                client,
                null,
                cfg,
                noPruningManager(),
                new SessionStore() {},
                null,
                registry,
                directExecutions(),
                null,
                builder);
        loop.run("do the work", null, "s1", null, () -> false);
        assertEquals(2, requests.size(), "prompt round + 1 tool round: " + requests);
        final var firstSystem = String.valueOf(requests.get(0).get(0).content());
        assertTrue(firstSystem.contains("a durable fact"), "memory must be in the stable prefix: " + firstSystem);
        final var secondTail =
                String.valueOf(requests.get(1).get(requests.get(1).size() - 1).content());
        assertFalse(secondTail.contains("<memory>"), "round 2 must not re-send memory in the tail: " + secondTail);
        assertFalse(secondTail.contains("a durable fact"), "round 2 tail must not carry memory facts: " + secondTail);
    }

    @Test
    void memoryWriteMidTurnRefreshesTheTail() {
        final var cfg = configuration(true);
        final var memory = new io.yupiik.ia.agent.core.memory.MemoryStore(cfg);
        memory.append(true, List.of("fact before"));
        final var requests = new ArrayList<List<Message>>();
        final var client = scriptedToolClient(
                requests,
                List.of(new ToolCall(
                        "c1",
                        "function",
                        new ToolCall.FunctionCall("memory_write", "{\"fact\":\"fact after\",\"scope\":\"global\"}"),
                        0)));
        final var registry = registryOf(new io.yupiik.ia.agent.core.tools.builtin.MemoryWriteTool(memory));
        final var builder = new SystemPromptBuilder(cfg, memory, null, null, null, null, null, null) {
            @Override
            public String build(final String prompt, final AgentConfiguration agent) {
                return "system\n\n" + memorySection(prompt);
            }
        };
        final var loop = loop(
                client,
                null,
                cfg,
                noPruningManager(),
                new SessionStore() {},
                null,
                registry,
                directExecutions(),
                null,
                builder);
        loop.run("work", null, "s1", null, () -> false);
        assertEquals(2, requests.size(), "prompt round + 1 tool round: " + requests);
        final var secondTail =
                String.valueOf(requests.get(1).get(requests.get(1).size() - 1).content());
        assertTrue(
                secondTail.contains("fact after"),
                "a mid-turn memory_write must refresh the volatile tail: " + secondTail);
        assertTrue(secondTail.contains("<memory>"), "the refreshed tail must carry the memory section: " + secondTail);
    }

    /** A registry exposing one wire tool, so a captured payload has a non-empty tools list. */
    private ToolRegistry wireToolsRegistry() {
        return new ToolRegistry(null) {
            @Override
            public List<Tool> toWireTools() {
                return List.of(Tool.function(
                        "read_file",
                        "read a file from disk",
                        Map.of("type", "object", "properties", Map.of("path", Map.of("type", "string")))));
            }

            @Override
            public io.yupiik.ia.agent.core.tools.EmbeddedTool tool(final String name) {
                return null;
            }
        };
    }

    @Test
    void lastLlmPayloadIsCapturedAndThePreviewRebuildsTheRequest() {
        final var replies = new ArrayDeque<>(List.of(new ScriptedReply("done", "stop", false)));
        final var requests = new ArrayList<List<Message>>();
        final var loop = loop(
                chatClient(replies, requests),
                null,
                configuration(true),
                noContextManager(),
                noSessionStore(),
                null,
                wireToolsRegistry(),
                null,
                null);
        assertNull(loop.lastLlmPayload(), "nothing is captured before the first request");

        assertEquals("done", loop.run("do it", null, "s1", null, () -> false));

        final var payload = loop.lastLlmPayload();
        assertNotNull(payload, "a request was sent, the payload must be captured");
        assertEquals("s1", payload.sessionId());
        assertEquals("test-model", payload.model());
        assertEquals("auto", payload.toolChoice());
        assertEquals(1, payload.tools().size());
        assertTrue(payload.toolsTokens() > 0, "the wire tool schemas cost context tokens: " + payload.toolsTokens());
        assertEquals("system", payload.messages().getFirst().role());
        assertEquals(
                "do it",
                payload.messages().stream()
                        .filter(m -> "user".equals(m.role()))
                        .map(m -> String.valueOf(m.content()))
                        .findFirst()
                        .orElseThrow(),
                "the user prompt is part of the snapshot");
        assertEquals(
                requests.getFirst(), payload.messages(), "the snapshot must be the exact list handed to the provider");
        assertTrue(
                payload.messages().stream()
                        .anyMatch(m -> m.others() != null
                                && Boolean.TRUE.equals(m.others().get("volatileTail"))),
                "the volatile tail the store strips must still be visible: " + payload.messages());

        // the preview is rebuilt from the persisted transcript (empty here) plus a fresh tail
        final var preview = loop.previewLlmPayload("s1");
        assertEquals(payload.model(), preview.model());
        assertEquals(payload.tools(), preview.tools());
        assertEquals(payload.toolsTokens(), preview.toolsTokens());
        assertEquals("system", preview.messages().getFirst().role());
        assertTrue(
                preview.messages().stream()
                        .anyMatch(m -> String.valueOf(m.content()).contains("<session-state>")),
                "the preview must carry the volatile tail the store never persists: " + preview.messages());
    }

    private ToolRegistry registryOf(final EmbeddedTool... tools) {
        return new ToolRegistry(null) {
            @Override
            public List<Tool> toWireTools() {
                return List.of();
            }

            @Override
            public EmbeddedTool tool(final String name) {
                for (final var t : tools) {
                    if (t.name().equals(name)) {
                        return t;
                    }
                }
                return null;
            }
        };
    }

    private ChatClient scriptedToolClient(final List<List<Message>> requests, final List<ToolCall> firstReplyCalls) {
        final var calls = new AtomicInteger();
        return new ChatClient() {
            @Override
            public Message stream(
                    final ChatCompletionRequest request,
                    final ChatSettings settings,
                    final Consumer<PauseEvent> pauseListener,
                    final Consumer<LlmStreamEvent> onDelta,
                    final BooleanSupplier cancelled) {
                requests.add(List.copyOf(request.messages()));
                if (calls.incrementAndGet() == 1) {
                    return Message.assistant(null, firstReplyCalls);
                }
                onDelta.accept(new End("stop", null));
                return Message.assistant("all done", null);
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
        entries.put("yca.provider.providers.0.baseUrl", "http://localhost:1/v1/chat/completions");
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
        return loop(client, todoStore, config, noContextManager(), noSessionStore(), null);
    }

    /** A context manager that never prunes nor compacts, for tests that do not exercise context work. */
    private static ContextManager noContextManager() {
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

    /** A session store dropping every persisted entry, for tests that do not assert on the transcript. */
    private static SessionStore noSessionStore() {
        return new SessionStore() {
            @Override
            public void append(final String id, final SessionEntry entry) {}
        };
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
        return loop(
                client,
                todoStore,
                config,
                contextManager,
                sessionStore,
                contextView,
                registry,
                executions,
                executor,
                null);
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
            final VirtualThreadExecutor executor,
            final SystemPromptBuilder promptBuilder) {
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
                permissionServiceOverride != null ? permissionServiceOverride : new PermissionService(config),
                new UsageTracker() {
                    @Override
                    public void record(final io.yupiik.ia.agent.core.llm.Usage usage) {
                        recordedUsage.set(usage);
                    }

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
                promptBuilder == null
                        ? new SystemPromptBuilder() {
                            @Override
                            public String build(final String prompt, final AgentConfiguration agent) {
                                return "system prompt";
                            }
                        }
                        : promptBuilder,
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
                    final ChatSettings settings,
                    final Consumer<PauseEvent> pauseListener,
                    final Consumer<LlmStreamEvent> onDelta,
                    final BooleanSupplier cancelled)
                    throws IOException {
                pauseListenerReference.set(pauseListener);
                final var reply = replies.poll();
                if (reply == null) {
                    throw new IllegalStateException("no scripted reply left");
                }
                requests.add(List.copyOf(request.messages()));
                if (pauseListener != null && reply.firePauses()) {
                    pauseListener.accept(PauseEvent.retry("LLM call failed, retrying in ~2s (attempt 2)", 2000));
                    pauseListener.accept(PauseEvent.throttle("Rate limit reached, waiting ~5s", 5000));
                }
                if (reply.error() != null) {
                    throw reply.error();
                }
                if (reply.ioError() != null) {
                    throw reply.ioError();
                }
                if (reply.usage() != null) {
                    onDelta.accept(new End(null, reply.usage()));
                }
                if (reply.finishReason() != null) {
                    onDelta.accept(new End(reply.finishReason(), null));
                }
                return reply.toMessage();
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
        private final io.yupiik.llm.client.model.Usage usage;

        private ScriptedReply(
                final String content,
                final String finishReason,
                final boolean firePauses,
                final RuntimeException error,
                final IOException ioError,
                final io.yupiik.llm.client.model.Usage usage) {
            this.content = content;
            this.finishReason = finishReason;
            this.firePauses = firePauses;
            this.error = error;
            this.ioError = ioError;
            this.usage = usage;
        }

        ScriptedReply(final String content, final String finishReason, final boolean firePauses) {
            this(content, finishReason, firePauses, null, null, null);
        }

        ScriptedReply(
                final String content,
                final String finishReason,
                final boolean firePauses,
                final io.yupiik.llm.client.model.Usage usage) {
            this(content, finishReason, firePauses, null, null, usage);
        }

        ScriptedReply(final String content, final String finishReason, final RuntimeException error) {
            this(content, finishReason, false, error, null, null);
        }

        ScriptedReply(final IOException ioError) {
            this(null, null, false, null, ioError, null);
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

        io.yupiik.llm.client.model.Usage usage() {
            return usage;
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
