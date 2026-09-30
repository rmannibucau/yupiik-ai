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

import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.codegraph.StructuralCodeGraph;
import io.yupiik.ia.agent.core.configuration.AgentMode;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.context.ContextManager;
import io.yupiik.ia.agent.core.context.ContextView;
import io.yupiik.ia.agent.core.context.TokenEstimator;
import io.yupiik.ia.agent.core.context.UsageTracker;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatCompletionRequest;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.PauseEvent;
import io.yupiik.ia.agent.core.llm.ToolCall;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.question.UserFeedbackService;
import io.yupiik.ia.agent.core.runtime.StatusReporter;
import io.yupiik.ia.agent.core.runtime.VirtualThreadExecutor;
import io.yupiik.ia.agent.core.session.SessionEntry;
import io.yupiik.ia.agent.core.session.SessionStore;
import io.yupiik.ia.agent.core.snapshot.SnapshotStore;
import io.yupiik.ia.agent.core.todo.TodoStore;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.tools.ToolExecutionService;
import io.yupiik.ia.agent.core.tools.ToolRegistry;
import io.yupiik.ia.agent.core.tools.ToolResult;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import io.yupiik.llm.client.ContextWindowExceededException;
import io.yupiik.llm.client.LlmHttpFailureException;
import io.yupiik.llm.client.model.LlmStreamEvent;
import io.yupiik.llm.client.model.LlmStreamEvent.End;
import io.yupiik.llm.client.model.LlmStreamEvent.ReasoningDelta;
import io.yupiik.llm.client.model.LlmStreamEvent.TextDelta;
import io.yupiik.llm.client.model.Usage;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

public class AgentLoop {
    private static final Logger LOGGER = Logger.getLogger(AgentLoop.class.getName());

    /** Anti-thrash: stop auto-compacting when the context refills right after compaction this many turns in a row. */
    private static final int MAX_CONSECUTIVE_COMPACTIONS = 5;

    /** Nudge sent to the model after a truncated reply so it resumes instead of restarting. */
    private static final String TRUNCATION_CONTINUE_PROMPT =
            "Your previous reply was cut off by the output token limit before you could finish."
                    + " Continue exactly where you stopped; do not repeat content you already produced.";

    /**
     * Nudge used from the second continuation on: repeating the same verbatim continuation hits the
     * same output budget again, so the model is asked to switch strategy instead.
     */
    private static final String TRUNCATION_SPLIT_PROMPT =
            "Your previous reply was cut off by the output token limit again. Do not continue the same output verbatim,"
                    + " it will hit the limit a second time. Change strategy: break the remaining work into smaller"
                    + " steps, use tools to write files or produce results in chunks, keep prose minimal and summarize"
                    + " instead of dumping full content.";

    /**
     * Nudge sent when the turn ended with todos still open and {@code yca.todos.continueOnUnfinished}
     * is enabled: weaker model families (qwen and friends) tend to end their turn after a couple of
     * steps with a progress report, so the instruction is explicit about acting rather than narrating.
     */
    private static final String UNFINISHED_TODO_CONTINUE_PROMPT =
            "You ended your turn while the todo list still has pending or in_progress items, so the work you were"
                    + " asked to do is not finished. Do not summarize your progress and do not wait for a new"
                    + " instruction: re-read the todo list in the <session-state> block and continue with the next"
                    + " pending item right now, using tools. End your turn only when every todo is completed or"
                    + " explicitly cancelled, or when you are genuinely blocked - in that case state exactly what"
                    + " blocks you and stop.";

    /**
     * Variant used from the second continuation on: a model repeating itself keeps answering with the
     * same progress report, so it is asked to state the blocker instead of trying again.
     */
    private static final String UNFINISHED_TODO_BLOCKED_PROMPT =
            " You already continued several times without closing the remaining todos, so continuing the same way"
                    + " is not working: do not repeat your progress report. Either complete the next pending todo"
                    + " with a tool call right now, or explicitly tell the user what exactly blocks you (missing"
                    + " information, a failing command, a decision to make) and stop.";

    private final ChatClient chatClient;
    private final ToolRegistry toolRegistry;
    private final ToolExecutionService toolExecutionService;
    private final CodingAgentConfiguration configuration;
    private final PermissionService permissionService;
    private final UsageTracker usageTracker;
    private final ContextManager contextManager;
    private final ContextView contextView;
    private final SessionStore sessionStore;
    private final SystemPromptBuilder systemPromptBuilder;
    private final MemoryExtractor memoryExtractor;
    private final StatusReporter statusReporter;
    private final Emitter emitter;
    private final JsonMapper mapper;
    private final StructuralCodeGraph structuralCodeGraph;
    private final ProviderManager providerManager;
    private final AgentProvider agentProvider;
    private final Clock clock;
    private final SnapshotStore snapshotStore;
    private final TodoStore todoStore;
    private final VirtualThreadExecutor executor;
    private final UserFeedbackService userFeedbackService;
    private final String subagentId;
    private final String subagentName;
    /**
     * Session this loop instance belongs to. Events are tagged with it so the
     * shell registry can route each stream to the tab that owns it. A loop
     * created without a session (CLI, legacy wiring) falls back to the
     * sessionId passed to run/resume.
     */
    private final String sessionId;
    /**
     * Effective session for the current turn. Equal to {@link #sessionId} for
     * per-session instances; for legacy shared instances (CLI) it is set from
     * the run argument. Event helpers tag with this value.
     */
    private volatile String currentSessionId;
    /**
     * Per-session model selection (model/variant/sampling). Explicitly passed to every
     * {@link ChatClient} call made by this loop's turns so concurrent tabs never share
     * a selection. Plain holder - no scope, no thread-local.
     */
    private final ChatSettings chatSettings = new ChatSettings();
    /**
     * Per-turn pause listener surfacing provider throttles and retry backoffs to the owning
     * tab. It is handed to each {@link ChatClient} call instead of being installed globally:
     * without it the UI would only show a spinner while the resilience layer can silently
     * wait up to minutes between attempts.
     */
    private volatile Consumer<PauseEvent> pauseListener;

    // Per-turn metrics tracking (reset at start of each doRun)
    private int turnLlmCalls;
    private long turnThinkingMs;
    private long turnToolSumMs;
    private long turnToolWallMs;
    private int turnToolsRun;
    private boolean thinkingActive;
    private long thinkingStartNanos;
    private long turnStartNanos;
    // output throughput: tokens produced vs. time actually spent producing them
    private long turnLlmActiveMs;
    private long turnLlmPausedMs;
    private int turnOutputTokens;
    private long turnLlmReportedMs;

    public AgentLoop(
            final ChatClient chatClient,
            final ToolRegistry toolRegistry,
            final ToolExecutionService toolExecutionService,
            final CodingAgentConfiguration configuration,
            final PermissionService permissionService,
            final UsageTracker usageTracker,
            final ContextManager contextManager,
            final ContextView contextView,
            final SessionStore sessionStore,
            final SystemPromptBuilder systemPromptBuilder,
            final MemoryExtractor memoryExtractor,
            final StatusReporter statusReporter,
            final Emitter emitter,
            final JsonMapper mapper,
            final StructuralCodeGraph structuralCodeGraph,
            final ProviderManager providerManager,
            final AgentProvider agentProvider,
            final Clock clock,
            final SnapshotStore snapshotStore,
            final TodoStore todoStore,
            final VirtualThreadExecutor executor) {
        this(
                chatClient,
                toolRegistry,
                toolExecutionService,
                configuration,
                permissionService,
                usageTracker,
                contextManager,
                contextView,
                sessionStore,
                systemPromptBuilder,
                memoryExtractor,
                statusReporter,
                emitter,
                mapper,
                structuralCodeGraph,
                providerManager,
                agentProvider,
                clock,
                snapshotStore,
                todoStore,
                executor,
                null,
                null,
                null,
                null);
    }

    AgentLoop(
            final ChatClient chatClient,
            final ToolRegistry toolRegistry,
            final ToolExecutionService toolExecutionService,
            final CodingAgentConfiguration configuration,
            final PermissionService permissionService,
            final UsageTracker usageTracker,
            final ContextManager contextManager,
            final ContextView contextView,
            final SessionStore sessionStore,
            final SystemPromptBuilder systemPromptBuilder,
            final MemoryExtractor memoryExtractor,
            final StatusReporter statusReporter,
            final Emitter emitter,
            final JsonMapper mapper,
            final StructuralCodeGraph structuralCodeGraph,
            final ProviderManager providerManager,
            final AgentProvider agentProvider,
            final Clock clock,
            final SnapshotStore snapshotStore,
            final TodoStore todoStore,
            final VirtualThreadExecutor executor,
            final String subagentId,
            final String subagentName) {
        this(
                chatClient,
                toolRegistry,
                toolExecutionService,
                configuration,
                permissionService,
                usageTracker,
                contextManager,
                contextView,
                sessionStore,
                systemPromptBuilder,
                memoryExtractor,
                statusReporter,
                emitter,
                mapper,
                structuralCodeGraph,
                providerManager,
                agentProvider,
                clock,
                snapshotStore,
                todoStore,
                executor,
                null,
                subagentId,
                subagentName,
                null);
    }

    /**
     * Full wiring for a session (tab) instance: the per-session services
     * ({@code permissionService}, {@code usageTracker}, {@code statusReporter},
     * {@code todoStore}, {@code userFeedbackService}) belong to exactly one
     * session and the loop tags every event it emits with {@code sessionId} so
     * the shell registry routes the stream to the owning tab.
     */
    public AgentLoop(
            final ChatClient chatClient,
            final ToolRegistry toolRegistry,
            final ToolExecutionService toolExecutionService,
            final CodingAgentConfiguration configuration,
            final PermissionService permissionService,
            final UsageTracker usageTracker,
            final ContextManager contextManager,
            final ContextView contextView,
            final SessionStore sessionStore,
            final SystemPromptBuilder systemPromptBuilder,
            final MemoryExtractor memoryExtractor,
            final StatusReporter statusReporter,
            final Emitter emitter,
            final JsonMapper mapper,
            final StructuralCodeGraph structuralCodeGraph,
            final ProviderManager providerManager,
            final AgentProvider agentProvider,
            final Clock clock,
            final SnapshotStore snapshotStore,
            final TodoStore todoStore,
            final VirtualThreadExecutor executor,
            final String sessionId,
            final String subagentId,
            final String subagentName,
            final UserFeedbackService userFeedbackService) {
        this.chatClient = chatClient;
        this.toolRegistry = toolRegistry;
        this.toolExecutionService = toolExecutionService;
        this.configuration = configuration;
        this.permissionService = permissionService;
        this.usageTracker = usageTracker;
        this.contextManager = contextManager;
        this.contextView = contextView;
        this.sessionStore = sessionStore;
        this.systemPromptBuilder = systemPromptBuilder;
        this.memoryExtractor = memoryExtractor;
        this.statusReporter = statusReporter;
        this.emitter = emitter;
        this.mapper = mapper;
        this.structuralCodeGraph = structuralCodeGraph;
        this.providerManager = providerManager;
        this.agentProvider = agentProvider;
        this.clock = clock;
        this.snapshotStore = snapshotStore;
        this.todoStore = todoStore;
        this.executor = executor;
        this.sessionId = sessionId;
        this.subagentId = subagentId;
        this.subagentName = subagentName;
        this.userFeedbackService = userFeedbackService;
    }

    protected AgentLoop() {
        this.chatClient = null;
        this.toolRegistry = null;
        this.toolExecutionService = null;
        this.configuration = null;
        this.permissionService = null;
        this.usageTracker = null;
        this.contextManager = null;
        this.contextView = null;
        this.sessionStore = null;
        this.systemPromptBuilder = null;
        this.memoryExtractor = null;
        this.statusReporter = null;
        this.emitter = null;
        this.mapper = null;
        this.structuralCodeGraph = null;
        this.providerManager = null;
        this.agentProvider = null;
        this.clock = null;
        this.snapshotStore = null;
        this.todoStore = null;
        this.executor = null;
        this.sessionId = null;
        this.subagentId = null;
        this.subagentName = null;
        this.userFeedbackService = null;
    }

    /**
     * This loop's per-session model selection. The session runtime hands it to the slash
     * commands and the UI so {@code /model}, {@code /variant} and the sampling overrides
     * mutate only this tab.
     */
    public ChatSettings chatSettings() {
        return chatSettings;
    }

    public String run(
            final String prompt,
            final String agentName,
            final String sessionId,
            final List<Message> history,
            final BooleanSupplier cancelled) {
        return run(prompt, agentName, sessionId, history, cancelled, null);
    }

    /**
     * @param queuedPrompt source of a prompt queued while this turn runs; when
     *                      non-null the loop polls it between iterations and after
     *                      each assistant reply, appending it as the next user
     *                      message so the queued prompt is handled by the very
     *                      same turn instead of waiting for it to finish.
     */
    public String run(
            final String prompt,
            final String agentName,
            final String sessionId,
            final List<Message> history,
            final BooleanSupplier cancelled,
            final Supplier<String> queuedPrompt) {
        return doRun(prompt, prompt, agentName, sessionId, history, cancelled, queuedPrompt);
    }

    /**
     * Re-runs the agent loop on the existing history without adding any user message: nothing is
     * appended to the conversation and nothing is persisted as input, so resuming costs zero extra
     * prompt tokens. The last real user message (when found) still drives prompt building (memory
     * ranking, todo hints) so the rebuilt system prompt stays relevant to the ongoing task.
     */
    public String resume(
            final String agentName,
            final String sessionId,
            final List<Message> history,
            final BooleanSupplier cancelled) {
        return resume(agentName, sessionId, history, cancelled, null);
    }

    /**
     * Same as {@link #resume(String, String, List, BooleanSupplier)} but polls
     * {@code queuedPrompt} so prompts typed while the resume runs are injected.
     */
    public String resume(
            final String agentName,
            final String sessionId,
            final List<Message> history,
            final BooleanSupplier cancelled,
            final Supplier<String> queuedPrompt) {
        return doRun(lastUserPrompt(history), null, agentName, sessionId, history, cancelled, queuedPrompt);
    }

    private String doRun(
            final String promptForBuilding,
            final String userMessage,
            final String agentName,
            final String sessionId,
            final List<Message> history,
            final BooleanSupplier cancelled,
            final Supplier<String> queuedPrompt) {
        // a probe is always a real supplier: every downstream use (loop iterations, compaction,
        // streaming, the tool context) dereferences it. A caller holding no interrupt flag passes
        // Cancellations.NEVER; a null is a caller bug and fails here, at the boundary, rather than
        // mid-turn as an "unexpected failure" NPE.
        Objects.requireNonNull(cancelled, "cancelled: a turn must provide a cancellation probe");
        currentSessionId = sessionId == null || sessionId.isBlank() ? this.sessionId : sessionId;
        final var agent = (agentName == null || agentName.isBlank() || agentProvider == null)
                ? null
                : agentProvider.byName(agentName);
        structuralCodeGraph.ensureReadyAsync();
        final var messages = new ArrayList<Message>();
        messages.add(Message.system(systemPromptBuilder.build(promptForBuilding == null ? "" : promptForBuilding, agent)
                + snapshotBlock(sessionId)));
        if (history != null) {
            messages.addAll(history);
        }
        if (userMessage != null) {
            messages.add(Message.user(userMessage));
            sessionStore.append(sessionId, SessionEntry.of(clock.millis(), "user", userMessage, null, null));
        }
        statusReporter.setSink(message -> emitter.emit(status(message)));
        // surface provider throttles and retry backoffs: without this the UI only shows a
        // spinner while the resilience layer can silently wait up to minutes between attempts.
        // the wait is also accumulated so the throughput metric ignores it.
        this.pauseListener = pause -> {
            turnLlmPausedMs += Math.max(0L, pause.durationMs());
            emit(
                    pause.kind() == PauseEvent.Pause.THROTTLE
                            ? status(pause.message())
                            : notice(AgentEvent.Notice.Level.INFO, pause.message()));
        };

        try {
            return loop(sessionId, messages, promptForBuilding, cancelled, queuedPrompt, memoryVersionAtStart());
        } catch (final IOException e) {
            if ("Cancelled".equals(e.getMessage())) {
                // user interrupt surfaced through the stream/backoff paths: a normal end,
                // not a failure - the transcript must not render it as an error
                emit(new AgentEvent.TurnCancelled(currentSessionId, subagentId, subagentName));
            } else {
                emit(error(e.getMessage()));
            }
            return null;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            emit(error("Interrupted"));
            return null;
        } catch (final RuntimeException e) {
            LOGGER.log(Level.SEVERE, "agent turn failed", e);
            emit(error("unexpected failure: " + e));
            return null;
        } finally {
            pauseListener = null;
            statusReporter.setSink(null);
            memoryExtractor.maybeExtract(messages);
        }
    }

    /**
     * Content of the last real user message in the history, skipping compaction digests and
     * truncation nudges; {@code null} when the history carries none.
     */
    private String lastUserPrompt(final List<Message> history) {
        if (history == null) {
            return null;
        }
        for (var i = history.size() - 1; i >= 0; i--) {
            final var m = history.get(i);
            if (isVolatileTail(m) || !"user".equals(m.role()) || !(m.content() instanceof String content)) {
                continue;
            }
            if (content.contains("<compacted-context>")
                    || content.contains("<pruned-context>")
                    || content.startsWith("... output truncated")
                    || content.startsWith("When a compacted context is provided")) {
                continue;
            }
            return content;
        }
        return null;
    }

    private void emit(final AgentEvent event) {
        emitter.emit(event);
    }

    /**
     * The context handed to every tool of this turn: it carries this session's
     * own feedback gate, todo list and status reporter so a tool asking a
     * question or writing a todo always touches the state of the tab that
     * started the turn, never another concurrent session's.
     */
    private ToolContext toolContext(final BooleanSupplier cancelled) {
        return new ToolContext(
                currentSessionId,
                subagentId,
                subagentName,
                permissionService,
                userFeedbackService,
                todoStore,
                statusReporter,
                usageTracker,
                contextView,
                cancelled);
    }

    // Event helpers that include the session and subagent info
    private AgentEvent.TextDelta textDelta(final String delta) {
        return new AgentEvent.TextDelta(delta, currentSessionId, subagentId, subagentName);
    }

    private AgentEvent.TextReset textReset() {
        return new AgentEvent.TextReset(currentSessionId, subagentId, subagentName);
    }

    private AgentEvent.ThinkingDelta thinkingDelta(final String delta) {
        return new AgentEvent.ThinkingDelta(delta, currentSessionId, subagentId, subagentName);
    }

    private AgentEvent.ToolStarted toolStarted(final String name, final String args) {
        return new AgentEvent.ToolStarted(name, args, currentSessionId, subagentId, subagentName);
    }

    private AgentEvent.ToolFinished toolFinished(final String name, final String output, final boolean success) {
        return new AgentEvent.ToolFinished(name, output, success, currentSessionId, subagentId, subagentName);
    }

    private AgentEvent.ToolStream toolStream(final String name, final String fragment) {
        return new AgentEvent.ToolStream(name, fragment, currentSessionId, subagentId, subagentName);
    }

    private AgentEvent.UsageUpdated usageUpdated(
            final io.yupiik.ia.agent.core.llm.Usage usage,
            final int estimatedContextTokens,
            final double contextPercent) {
        return new AgentEvent.UsageUpdated(
                usage, estimatedContextTokens, contextPercent, currentSessionId, subagentId, subagentName);
    }

    private AgentEvent.Compacted compacted(final String kind, final String mode) {
        return new AgentEvent.Compacted(kind, mode, currentSessionId, subagentId, subagentName);
    }

    private AgentEvent.Compacted compacted(final String kind) {
        return new AgentEvent.Compacted(kind, "SUMMARY", currentSessionId, subagentId, subagentName);
    }

    private AgentEvent.Status status(final String message) {
        return new AgentEvent.Status(message, currentSessionId, subagentId, subagentName);
    }

    private AgentEvent.Notice notice(final AgentEvent.Notice.Level level, final String message) {
        return new AgentEvent.Notice(level, message, null, currentSessionId, subagentId, subagentName);
    }

    private AgentEvent.Notice notice(
            final AgentEvent.Notice.Level level, final String message, final String finishReason) {
        return new AgentEvent.Notice(level, message, finishReason, currentSessionId, subagentId, subagentName);
    }

    private AgentEvent.TurnDone turnDone(final String content) {
        return new AgentEvent.TurnDone(content, currentSessionId, subagentId, subagentName);
    }

    private AgentEvent.Error error(final String message) {
        return new AgentEvent.Error(message, currentSessionId, subagentId, subagentName);
    }

    private AgentEvent.PromptIntegrated promptIntegrated(final String text) {
        return new AgentEvent.PromptIntegrated(text, currentSessionId, subagentId, subagentName);
    }

    private AgentEvent.TurnMetrics turnMetrics(
            final int llmCalls,
            final long thinkingDurationMs,
            final long toolDurationSumMs,
            final long toolDurationWallMs,
            final int toolsRun,
            final long turnDurationMs) {
        return new AgentEvent.TurnMetrics(
                llmCalls,
                thinkingDurationMs,
                toolDurationSumMs,
                toolDurationWallMs,
                toolsRun,
                turnDurationMs,
                new AgentEvent.Throughput(turnOutputTokens, turnLlmActiveMs, turnLlmReportedMs),
                currentSessionId,
                subagentId,
                subagentName);
    }

    private AgentEvent.ToolTimingEvent toolTimingEvent(
            final String toolName, final long durationMs, final long wallDurationMs, final boolean parallelBatch) {
        return new AgentEvent.ToolTimingEvent(
                toolName, durationMs, wallDurationMs, parallelBatch, currentSessionId, subagentId, subagentName);
    }

    private String loop(
            final String sessionId,
            final List<Message> messages,
            final String prompt,
            final BooleanSupplier cancelled,
            final Supplier<String> queuedPrompt,
            final int memoryVersionAtStart)
            throws IOException, InterruptedException {
        final var retryOnOverflow = configuration.llm().retryOnContextOverflow();
        final var maxIterations = configuration.llm().maxIterations();
        final var maxConsecutiveCompactions = MAX_CONSECUTIVE_COMPACTIONS;
        final var model = providerManager.resolveDefault().model();
        final var tools = toolRegistry.toWireTools();
        final var toolsTokens = toolTokens(tools);
        var overflowRetried = false;
        var consecutiveCompactions = 0;
        var autoNoticeEmitted = false;
        var truncationContinues = 0;
        var unfinishedTodoContinues = 0;
        var toolsRanThisTurn = false;
        var toolDirectiveApplied = false;
        // a prompt typed while the loop was running was injected in this turn: the model answered
        // it, so its text-only end answers that message and must not be treated as "stopped early"
        var userInjected = false;
        final var repeats = new ToolRepeats();
        final var staleReads = new StaleReads(readsFromHistory(messages));

        // Reset per-turn metrics
        turnLlmCalls = 0;
        turnThinkingMs = 0L;
        turnToolSumMs = 0L;
        turnToolWallMs = 0L;
        turnToolsRun = 0;
        turnLlmActiveMs = 0L;
        turnLlmPausedMs = 0L;
        turnOutputTokens = 0;
        turnLlmReportedMs = 0L;
        thinkingActive = false;
        thinkingStartNanos = 0;
        turnStartNanos = System.nanoTime();
        for (var iteration = 0; iteration < maxIterations && !cancelled.getAsBoolean(); iteration++) {
            if (contextView != null) {
                contextView.publish(snapshot(messages, toolsTokens));
            }
            if (integrateQueuedPrompt(sessionId, messages, queuedPrompt)) {
                userInjected = true;
                continue;
            }
            // user/tool-requested context operations (manage_context, /compact while busy) are
            // applied at the first safe checkpoint of the turn, at most one per turn
            if (!toolDirectiveApplied && contextView != null) {
                final var directive = contextView.poll();
                if (directive != null) {
                    toolDirectiveApplied = true;
                    applyToolDirective(sessionId, messages, directive, toolsTokens);
                }
            }
            // zero-LLM DCP-style strategies (dedup, write superseding, error-input purge) run only at
            // the automatic-prune check point: like the prune, they mutate already-sent messages, so
            // running them on every iteration would invalidate the prompt prefix cache for no gain.
            // When they change something the result is persisted and the usage anchor cleared.
            var compactedThisTurn = false;
            if (contextManager.pruneNeeded(messages, toolsTokens)) {
                final var zeroLlmChanged = applyZeroLlmStrategies(messages);
                if (zeroLlmChanged) {
                    persistContext(sessionId, messages);
                    usageTracker.clearAnchor();
                }
                final var pruned = contextManager.prune(messages);
                if (pruned.changed()) {
                    messages.clear();
                    messages.addAll(pruned.messages());
                    emit(compacted("prune", pruned.mode().name()));
                    persistContext(sessionId, messages);
                    compactedThisTurn = true;
                }
            }
            if (contextManager.compactNeeded(messages, toolsTokens)) {
                final var compacted = contextManager.compact(messages, false, null, 0, cancelled);
                if (compacted.changed()) {
                    messages.clear();
                    messages.addAll(compacted.messages());
                    emit(compacted("compact", compacted.mode().name()));
                    persistContext(sessionId, messages);
                    compactedThisTurn = true;
                }
            }
            if (compactedThisTurn) {
                consecutiveCompactions++;
                if (consecutiveCompactions >= maxConsecutiveCompactions
                        && usageTracker.contextPercent(usageTracker.effectiveContext(messages, toolsTokens))
                                >= configuration.compaction().thresholdPercent()) {
                    emit(notice(
                            AgentEvent.Notice.Level.ERROR,
                            "Context keeps filling up right after compaction after " + consecutiveCompactions
                                    + " attempts. Consider /compact manually, a larger model, or splitting the task."));
                    break;
                }
            } else {
                consecutiveCompactions = 0;
            }

            // append-only volatile session state: the tail is its own trailing user message in the
            // working list (deduplicated when unchanged), so every request is the previous request's
            // exact bytes plus the tokens appended since. Nothing previously sent is ever moved or
            // rewritten, so provider-side prefix caches (llama.cpp slot LCP with f_keep ~ 1.0,
            // token-level gateways) keep matching instead of diverging mid-conversation.
            appendStateIfChanged(sessionId, messages, prompt, memoryVersionAtStart);
            final var request =
                    new ChatCompletionRequest(model, messages, null, null, null, tools, "auto", null, null, null);
            final var usage = new io.yupiik.ia.agent.core.llm.Usage[] {io.yupiik.ia.agent.core.llm.Usage.EMPTY};
            final var finishReason = new String[] {null};
            final Message assistant;
            final long llmStartNanos;
            final long pausedBeforeMs = turnLlmPausedMs;
            try {
                llmStartNanos = System.nanoTime();
                assistant = streamWithRetry(request, usage, finishReason, cancelled);
            } catch (final ContextWindowExceededException e) {
                if (!retryOnOverflow || overflowRetried) {
                    throw e;
                }
                if (usageTracker.lastResolutionWasAuto() && !autoNoticeEmitted) {
                    emit(
                            notice(
                                    AgentEvent.Notice.Level.INFO,
                                    "Context window auto-detected: no exact figure was available, using a conservative default and learning on overflow."));
                    autoNoticeEmitted = true;
                }
                final var shrunken = usageTracker.rememberShrinkOnOverflow();
                emit(notice(
                        AgentEvent.Notice.Level.INFO,
                        "Context window exceeded; compacting and retrying (assumed window reduced to " + shrunken
                                + " tokens)."));
                final var compacted = contextManager.compactForOverflow(messages, cancelled);
                if (compacted.changed() && !compacted.messages().isEmpty()) {
                    messages.clear();
                    messages.addAll(compacted.messages());
                    emit(compacted("compact-retry", compacted.mode().name()));
                    persistContext(sessionId, messages);
                    overflowRetried = true;
                    continue;
                }
                emit(notice(
                        AgentEvent.Notice.Level.ERROR,
                        "Context window exceeded and compacting did not free enough context: " + e.getMessage()));
                throw e;
            }
            usageTracker.record(usage[0]);
            // throughput: time spent generating, idle time excluded. A request refused because the
            // context window is too small contributes nothing (it is a preflight, not a generation).
            recordThroughput(assistant, usage[0], (System.nanoTime() - llmStartNanos) / 1_000_000, pausedBeforeMs);
            // anchor the hybrid usage on the real prompt tokens of the request we just sent: the
            // estimate at the same moment calibrates later deltas until the next observation
            usageTracker.anchor(usage[0].promptTokens(), usageTracker.estimateContext(messages, toolsTokens));
            messages.add(assistant);
            sessionStore.append(
                    sessionId,
                    SessionEntry.of(
                            clock.millis(), "assistant", str(assistant.content()), assistant.toolCalls(), null));
            emitUsage(messages, toolsTokens);

            if (integrateQueuedPrompt(sessionId, messages, queuedPrompt)) {
                userInjected = true;
                continue;
            }

            if (assistant.toolCalls() == null || assistant.toolCalls().isEmpty()) {
                // an interrupt pressed while a continuation (truncation or overflow retry) was
                // about to re-ask the model must stop here, not spawn another request
                if (cancelled.getAsBoolean()) {
                    return str(assistant.content());
                }
                final var content = str(assistant.content());
                final var reason = finishReason[0];
                if (isTruncation(reason)) {
                    final var maxTruncationContinues = configuration.llm().truncationContinuesMax();
                    if (configuration.llm().continueOnTruncation() && truncationContinues < maxTruncationContinues) {
                        truncationContinues++;
                        emit(notice(
                                AgentEvent.Notice.Level.WARN,
                                "Response truncated by the output token limit (finish_reason="
                                        + reason + "); asking the model to continue (" + truncationContinues + '/'
                                        + maxTruncationContinues + ")."));
                        messages.add(Message.user(
                                truncationContinues >= 2 ? TRUNCATION_SPLIT_PROMPT : TRUNCATION_CONTINUE_PROMPT));
                        continue;
                    }
                    emit(notice(
                            AgentEvent.Notice.Level.ERROR,
                            "Response truncated by the output token limit (finish_reason="
                                    + reason + "); the reply may be incomplete."
                                    + (configuration.llm().continueOnTruncation()
                                            ? " Gave up after " + truncationContinues + " continuation"
                                                    + (truncationContinues == 1 ? "" : "s") + "."
                                            : " Enable yca.llm.continueOnTruncation to auto-continue.")));
                    emitTurnMetrics(content, turnStartNanos);
                    return content;
                }
                final var pending = unfinishedTodos(toolsRanThisTurn);
                if (pending != null) {
                    final var maxContinues = todoMaxContinues();
                    if (canAutoContinueUnfinished(userInjected) && unfinishedTodoContinues < maxContinues) {
                        unfinishedTodoContinues++;
                        emit(notice(
                                AgentEvent.Notice.Level.INFO,
                                "model ended its turn (finish_reason=" + reason + ") with " + pending.pending() + '/'
                                        + pending.total() + " todos still pending; continuing automatically ("
                                        + unfinishedTodoContinues + '/' + maxContinues + ")."));
                        messages.add(Message.user(unfinishedTodoContinuePrompt(unfinishedTodoContinues)));
                        continue;
                    }
                    emit(unfinishedTodosNotice(pending, reason));
                }
                emitTurnMetrics(content, turnStartNanos);
                return content;
            }
            // an interrupt pressed while the model was generating must stop here: the flag is
            // only polled at iteration boundaries, so without this check the whole tool batch
            // would still run after the cancellation
            if (cancelled.getAsBoolean()) {
                break;
            }
            executeToolCalls(sessionId, messages, assistant.toolCalls(), repeats, staleReads, toolsTokens, cancelled);
            toolsRanThisTurn = true;
            if (!toolDirectiveApplied && contextView != null) {
                final var directive = contextView.poll();
                if (directive != null) {
                    toolDirectiveApplied = true;
                    applyToolDirective(sessionId, messages, directive, toolsTokens);
                }
            }
        }
        if (cancelled.getAsBoolean()) {
            emit(new AgentEvent.TurnCancelled(currentSessionId, subagentId, subagentName));
        } else {
            emit(error("Maximum iterations reached (" + maxIterations + ")"));
        }
        emitTurnMetrics(null, turnStartNanos);
        return null;
    }

    /**
     * Appends the volatile session state (date, todos, memory refresh, nudge and the compaction
     * snapshot) as its own trailing user message in the working conversation, deduplicated against
     * the newest state tail already present: when the rendered state is byte-identical, nothing is
     * appended. Because the tail is part of the working list and is never moved nor rewritten once
     * sent, every request is the previous request's exact bytes plus the assistant/tool messages
     * and state changes appended since - so provider-side prefix caches (llama.cpp slot LCP, token
     * gateways) keep matching with f_keep ~ 1.0 like opencode instead of diverging mid-conversation.
     */
    private void appendStateIfChanged(
            final String sessionId,
            final List<Message> messages,
            final String prompt,
            final int memoryVersionAtTurnStart) {
        final var state = "<session-state>\n"
                + systemPromptBuilder.sessionState(prompt)
                + memoryRefresh(prompt, memoryVersionAtTurnStart)
                + nudgeBlock(messages)
                + snapshotBlock(sessionId)
                + "</session-state>";
        // dedup: skip when the newest state tail already carries exactly this state
        for (var i = messages.size() - 1; i >= 0; i--) {
            final var m = messages.get(i);
            if (isVolatileTail(m)) {
                if (state.equals(String.valueOf(m.content()))) {
                    return; // nothing changed since the last tail: keep the prefix byte-stable
                }
                break;
            }
        }
        messages.add(new Message("user", state, null, null, null, Map.of("volatileTail", true)));
    }

    private static boolean isVolatileTail(final Message message) {
        return message.others() != null && Boolean.TRUE.equals(message.others().get("volatileTail"));
    }

    /**
     * Memory version captured once at turn start (see {@code doRun}): the durable memory already
     * sits in the stable system prefix built by {@code SystemPromptBuilder.build}, so the volatile
     * tail only re-appends the memory section when a mid-turn {@code memory_write} changed it.
     */
    private int memoryVersionAtStart() {
        return systemPromptBuilder.memoryVersion();
    }

    /**
     * The memory section to append to the volatile tail: empty when memory did not change since the
     * turn started (it is already in the stable prefix), the fresh section otherwise (a mid-turn
     * {@code memory_write} must be visible to the next rounds).
     */
    private String memoryRefresh(final String prompt, final int memoryVersionAtTurnStart) {
        if (systemPromptBuilder.memoryVersion() == memoryVersionAtTurnStart) {
            return "";
        }
        final var section = systemPromptBuilder.memorySection(prompt);
        return section.isBlank() ? "" : "\n" + section + "\n";
    }

    private void emitTurnMetrics(final String content, final long turnStartNanos) {
        final var turnDurationMs = (System.nanoTime() - turnStartNanos) / 1_000_000;
        if (thinkingActive) {
            turnThinkingMs += (System.nanoTime() - thinkingStartNanos) / 1_000_000;
        }
        emit(turnMetrics(turnLlmCalls, turnThinkingMs, turnToolSumMs, turnToolWallMs, turnToolsRun, turnDurationMs));
        if (content != null) {
            emit(turnDone(content));
        }
    }

    /**
     * Accumulates one LLM request in the turn output throughput: the time it really spent generating
     * (wall time minus the throttles and retry backoffs it waited for) and the tokens it produced.
     *
     * @param assistant the message produced by the request.
     * @param usage the usage reported by the provider, {@link io.yupiik.ia.agent.core.llm.Usage#EMPTY} when none.
     * @param wallMs wall time of the request, throttles and backoffs included.
     * @param pausedBeforeMs the turn pause counter before the request started.
     */
    private void recordThroughput(
            final Message assistant,
            final io.yupiik.ia.agent.core.llm.Usage usage,
            final long wallMs,
            final long pausedBeforeMs) {
        final var pausedMs = Math.max(0L, turnLlmPausedMs - pausedBeforeMs);
        turnLlmActiveMs += Math.max(0L, wallMs - pausedMs);
        if (usage != null && usage.completionTokens() > 0) {
            turnOutputTokens += usage.completionTokens();
            if (usage.generationTimeMs() > 0) {
                turnLlmReportedMs += usage.generationTimeMs();
            }
        } else {
            // no usage from the provider: estimate the produced tokens from what came back
            turnOutputTokens += estimateOutputTokens(assistant);
        }
    }

    /**
     * Estimates the tokens a response produced when the provider reports no usage: the visible
     * content plus the arguments of every tool call.
     *
     * @param assistant the produced message, may be {@code null}.
     * @return the estimated token count, {@code 0} when there is nothing to estimate.
     */
    private int estimateOutputTokens(final Message assistant) {
        if (assistant == null) {
            return 0;
        }
        var tokens = TokenEstimator.estimate(str(assistant.content()));
        if (assistant.toolCalls() != null) {
            for (final var call : assistant.toolCalls()) {
                if (call != null && call.function() != null) {
                    tokens += TokenEstimator.estimate(call.function().arguments());
                }
            }
        }
        return tokens;
    }

    /**
     * Polls the queued-prompt source (when set) and, on a non-blank prompt,
     * appends it as the next user message so the running turn absorbs it.
     *
     * @return {@code true} when a prompt was integrated and the iteration must restart
     */
    private boolean integrateQueuedPrompt(
            final String sessionId, final List<Message> messages, final Supplier<String> queuedPrompt) {
        if (queuedPrompt == null) {
            return false;
        }
        final var prompt = queuedPrompt.get();
        if (prompt == null || prompt.isBlank()) {
            return false;
        }
        final var text = prompt.strip();
        messages.add(Message.user(text));
        sessionStore.append(sessionId, SessionEntry.of(clock.millis(), "user", text, null, null));
        emit(promptIntegrated(text));
        return true;
    }

    /**
     * Whether a normalized stream finish reason means the reply was cut by the output token budget:
     * the dialects map Anthropic {@code max_tokens} and OpenAI {@code length} to {@code length}.
     */
    private static boolean isTruncation(final String finishReason) {
        return "length".equalsIgnoreCase(finishReason) || "max_tokens".equalsIgnoreCase(finishReason);
    }

    /** Live usage snapshot published to {@link ContextView} consumers (eg. the manage_context tool). */
    private ContextView.Snapshot snapshot(final List<Message> messages, final int toolsTokens) {
        final var effective = usageTracker.effectiveContext(messages, toolsTokens);
        return new ContextView.Snapshot(
                messages.size(),
                effective,
                usageTracker.contextWindow(),
                usageTracker.usableWindow(),
                usageTracker.contextPercent(effective),
                configuration.pruning().enabled() ? configuration.pruning().thresholdPercent() : Integer.MAX_VALUE,
                configuration.compaction().enabled()
                        ? configuration.compaction().thresholdPercent()
                        : Integer.MAX_VALUE);
    }

    /**
     * Applies a model-requested context operation (manage_context tool) to the live messages:
     * at most one per turn (enforced by the caller), results persisted and surfaced as a
     * {@link AgentEvent.Compacted} event with the {@code tool} kind.
     */
    /**
     * Shell-side entry point for a user-triggered context operation during a turn
     * (e.g. {@code /compact} or {@code /context prune} while the shell is busy): the operation is
     * queued as a directive and applied by the loop at the next safe checkpoint, at most once per
     * turn. Returns {@code false} when no consumer can accept the request (no context view) or when
     * an operation is already pending this turn.
     */
    public boolean requestUserContextOperation(
            final String action, final String focus, final int keepLast, final boolean force) {
        if (contextView == null) {
            return false;
        }
        return contextView.request(new ContextView.Directive(action, focus, keepLast, "user", force));
    }

    private void applyToolDirective(
            final String sessionId,
            final List<Message> messages,
            final ContextView.Directive directive,
            final int toolsTokens) {
        final var result = "prune".equals(directive.action())
                ? contextManager.prune(messages)
                : contextManager.compact(messages, directive.force(), directive.focus(), directive.keepLast());
        if (!result.changed()) {
            emit(notice(
                    AgentEvent.Notice.Level.WARN,
                    "manage_context " + directive.action() + " had nothing to reclaim; context left untouched."));
            return;
        }
        messages.clear();
        messages.addAll(result.messages());
        emit(compacted("tool", result.mode().name()));
        persistContext(sessionId, messages);
        emit(notice(
                AgentEvent.Notice.Level.INFO,
                "Context " + directive.action()
                        + " requested by the model applied ("
                        + result.mode().name().toLowerCase(java.util.Locale.ROOT)
                        + ")."));
        usageTracker.clearAnchor(); // the anchor predates the reduction: fall back to estimates until the next call
    }

    /**
     * Persists an in-loop context reduction to the session store (without the leading system
     * prompt, which is regenerated every turn) so the next turn reloads the compacted history
     * instead of re-compacting the same full transcript. Failures never break the turn.
     */
    private void persistContext(final String sessionId, final List<Message> messages) {
        if (sessionStore == null || sessionId == null || messages.isEmpty()) {
            return;
        }
        try {
            sessionStore.replace(
                    sessionId,
                    (messages.get(0).role().equals("system") ? messages.subList(1, messages.size()) : messages)
                            .stream().filter(m -> !isVolatileTail(m)).toList());
        } catch (final RuntimeException e) {
            LOGGER.log(Level.WARNING, e, () -> "Could not persist the compacted session " + sessionId);
            emit(notice(AgentEvent.Notice.Level.WARN, "Compacted context could not be persisted: " + e.getMessage()));
        }
    }

    /**
     * Zero-LLM context strategies run at the same point as the automatic prune (start of an
     * iteration), in a single non-incremental transform:
     * <ol>
     *   <li>deduplicate identical tool calls (same tool, same normalized arguments) keeping the latest,
     *       protected tools excluded;</li>
     *   <li>supersede write inputs overwritten by a later read of the same file (opt-in);</li>
     *   <li>purge the (potentially large) inputs of failed tool calls after a number of turns.</li>
     * </ol>
     * Each strategy reports a single {@code Compacted} event when it changed the history. The
     * conversation list is mutated in place because the loop owns it and keeps the call/result
     * pairing intact.
     */
    private boolean applyZeroLlmStrategies(final List<Message> messages) {
        final var p = configuration.pruning();
        var deduped = 0;
        if (p.deduplicateToolCalls()) {
            deduped = deduplicateToolCalls(messages);
        }
        var superseded = 0;
        if (p.supersedeOverwrittenWrites()) {
            superseded = supersedeOverwrittenWrites(messages);
        }
        var purged = 0;
        if (p.purgeFailedToolInputs()) {
            purged = purgeFailedToolInputs(messages);
        }
        if (deduped > 0) {
            emit(compacted("dedup", "CLEARED"));
        }
        if (superseded > 0) {
            emit(compacted("supersede", "CLEARED"));
        }
        if (purged > 0) {
            emit(compacted("purge-errors", "CLEARED"));
        }
        return deduped > 0 || superseded > 0 || purged > 0;
    }

    /** Tools whose calls/results are never deduplicated, superseded or input-purged (state/decisions). */
    private static final Set<String> PROTECTED_CONTEXT_TOOLS =
            Set.of("write_file", "edit_file", "apply_patch", "todo_write", "todo_read", "todo_update", "memory_write");

    /** Write tools whose inputs can be superseded by a later read of the same path. */
    private static final Set<String> WRITE_CONTEXT_TOOLS = Set.of("write_file", "edit_file", "apply_patch");

    private String normalizedArguments(final String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return "";
        }
        try {
            final var json = mapper.read(Map.class, new java.io.StringReader(arguments));
            final var sorted = new TreeMap<String, Object>();
            if (json != null) {
                json.forEach((k, v) -> sorted.put(String.valueOf(k), v));
            }
            return String.valueOf(sorted);
        } catch (final RuntimeException e) {
            // not JSON (eg. a freeform text argument): keep the raw form, dedup still works on exact matches
            return arguments.strip();
        }
    }

    /** Deduplicates identical tool calls keeping the latest occurrence. Returns the number of replacements. */
    int deduplicateToolCalls(final List<Message> messages) {
        // last index per signature (excluding protected tools); signatures are resolved across the
        // whole list so identical calls far apart collapse too
        final var lastBySignature = new LinkedHashMap<String, Integer>();
        for (var i = 0; i < messages.size(); i++) {
            final var message = messages.get(i);
            if (!"assistant".equals(message.role()) || message.toolCalls() == null) {
                continue;
            }
            for (final var call : message.toolCalls()) {
                if (call == null
                        || call.function() == null
                        || PROTECTED_CONTEXT_TOOLS.contains(call.function().name())) {
                    continue;
                }
                final var signature = call.function().name()
                        + '\n'
                        + normalizedArguments(call.function().arguments());
                lastBySignature.put(signature, i);
            }
        }
        var replaced = 0;
        // second pass: replace the tool result of any assistant call whose signature was seen later
        for (var i = 0; i < messages.size(); i++) {
            final var message = messages.get(i);
            if (!"assistant".equals(message.role()) || message.toolCalls() == null) {
                continue;
            }
            for (final var call : message.toolCalls()) {
                if (call == null
                        || call.function() == null
                        || PROTECTED_CONTEXT_TOOLS.contains(call.function().name())) {
                    continue;
                }
                final var signature = call.function().name()
                        + '\n'
                        + normalizedArguments(call.function().arguments());
                final var last = lastBySignature.get(signature);
                if (last != null && last != i) {
                    // replace the matching tool result (same call id) with a marker; the assistant
                    // call itself is left in place to keep the call/result pairing unambiguous
                    for (var j = i + 1; j < messages.size(); j++) {
                        final var candidate = messages.get(j);
                        if ("tool".equals(candidate.role()) && call.id().equals(candidate.toolCallId())) {
                            messages.set(
                                    j,
                                    Message.tool(
                                            call.id(),
                                            "[duplicate tool call pruned - same tool and arguments as the last call, keeping the latest output]"));
                            replaced++;
                            break;
                        }
                    }
                }
            }
        }
        return replaced;
    }

    /** Replaces write inputs overwritten by a later read of the same file, keeping the write confirmation. */
    int supersedeOverwrittenWrites(final List<Message> messages) {
        // track read paths seen after this position; a write whose path is read later is superseded
        final var readsAfter = new HashSet<String>();
        var replaced = 0;
        for (var i = messages.size() - 1; i >= 0; i--) {
            final var message = messages.get(i);
            if (!"assistant".equals(message.role()) || message.toolCalls() == null) {
                continue;
            }
            for (final var call : message.toolCalls()) {
                if (call == null || call.function() == null) {
                    continue;
                }
                final var name = call.function().name();
                if ("read_file".equals(name) || "grep".equals(name) || "glob".equals(name)) {
                    final var path = toolFilePath(call);
                    if (path != null) {
                        readsAfter.add(path);
                    }
                } else if (WRITE_CONTEXT_TOOLS.contains(name)
                        && toolFilePath(call) != null
                        && readsAfter.contains(toolFilePath(call))) {
                    // only the input is superseded; the (small) confirmation output is kept
                    final var newCall = new ToolCall(
                            call.id(),
                            call.type(),
                            new ToolCall.FunctionCall(
                                    call.function().name(),
                                    "[write superseded by later read - file content already captured]"),
                            call.index());
                    // re-read the current message: an earlier call of the same message may already
                    // have been superseded
                    messages.set(i, replaceToolCall(messages.get(i), call.id(), newCall));
                    replaced++;
                }
            }
        }
        return replaced;
    }

    /** Returns a copy of {@code message} with the tool call of {@code callId} replaced by {@code replacement}. */
    private static Message replaceToolCall(final Message message, final String callId, final ToolCall replacement) {
        return new Message(
                message.role(),
                message.content(),
                message.name(),
                message.toolCalls().stream()
                        .map(c -> callId.equals(c == null ? null : c.id()) ? replacement : c)
                        .toList(),
                message.toolCallId(),
                message.others());
    }

    private String toolFilePath(final ToolCall call) {
        if (call == null || call.function() == null || call.function().arguments() == null) {
            return null;
        }
        try {
            final var json = mapper.read(
                    Map.class, new java.io.StringReader(call.function().arguments()));
            final var path = json == null ? null : json.get("path");
            return path == null ? null : String.valueOf(path);
        } catch (final RuntimeException e) {
            return null;
        }
    }

    /**
     * Purges the (potentially large) input of failed tool calls after {@code purgeFailedToolInputsTurns}
     * user turns, keeping the error message. Returns the number of purged inputs.
     */
    int purgeFailedToolInputs(final List<Message> messages) {
        final var turns = configuration.pruning().purgeFailedToolInputsTurns();
        final var failedCallIds = new HashSet<String>();
        for (final var message : messages) {
            if ("tool".equals(message.role())
                    && message.others() != null
                    && Boolean.TRUE.equals(message.others().get("is_error"))) {
                failedCallIds.add(message.toolCallId());
            }
        }
        if (failedCallIds.isEmpty()) {
            return 0;
        }
        var purged = 0;
        for (var i = 0; i < messages.size(); i++) {
            final var message = messages.get(i);
            if (!"assistant".equals(message.role()) || message.toolCalls() == null) {
                continue;
            }
            // count user turns between this call and the end
            var userTurnsAfter = 0;
            for (var j = i + 1; j < messages.size(); j++) {
                if ("user".equals(messages.get(j).role())) {
                    userTurnsAfter++;
                }
            }
            if (turns > 0 && userTurnsAfter < turns) {
                continue;
            }
            for (final var call : message.toolCalls()) {
                if (call == null || call.function() == null || !failedCallIds.contains(call.id())) {
                    continue;
                }
                if (call.function().arguments() == null
                        || call.function().arguments().isBlank()
                        || call.function().arguments().equals("{}")) {
                    continue;
                }
                final var newCall = new ToolCall(
                        call.id(),
                        call.type(),
                        new ToolCall.FunctionCall(call.function().name(), "{}"),
                        call.index());
                messages.set(i, replaceToolCall(messages.get(i), call.id(), newCall));
                purged++;
            }
        }
        return purged;
    }

    /**
     * Round-level budget guard: after each tool result lands, if the estimated context is above the
     * pruning threshold, prune locally first (zero LLM cost); only if it remains above the
     * compaction threshold, run the LLM compaction. The round continues: the already-landed results
     * stay in the verbatim tail protected by the compaction's keep budget. Each reduction is
     * persisted and clears the usage anchor like any other context reduction.
     */
    private void checkRoundBudget(final String sessionId, final List<Message> messages, final int toolsTokens) {
        if (contextManager == null) {
            return;
        }
        if (contextManager.pruneNeeded(messages, toolsTokens)) {
            final var pruned = contextManager.prune(messages);
            if (pruned.changed()) {
                messages.clear();
                messages.addAll(pruned.messages());
                emit(compacted("round-prune", pruned.mode().name()));
                persistContext(sessionId, messages);
                usageTracker.clearAnchor();
            }
        }
        if (contextManager.compactNeeded(messages, toolsTokens)) {
            final var compacted = contextManager.compact(messages);
            if (compacted.changed()) {
                messages.clear();
                messages.addAll(compacted.messages());
                emit(compacted("round-compact", compacted.mode().name()));
                persistContext(sessionId, messages);
                usageTracker.clearAnchor();
            }
        }
    }

    /**
     * Streams the request, retrying whole requests whose transport broke mid-stream after partial
     * content (eg. a chunked connection cut): the history is untouched until the stream returns so a
     * plain retry is safe. Each retry discards the partial rendering ({@link AgentEvent.TextReset})
     * and backs off exponentially before resending. Failures before any fragment and exhausted
     * retries propagate to the caller.
     */
    private Message streamWithRetry(
            final ChatCompletionRequest request,
            final io.yupiik.ia.agent.core.llm.Usage[] usage,
            final String[] finishReason,
            final BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        final var maxAttempts = 1 + Math.max(0, configuration.llm().streamRetriesMaxAttempts());
        IOException last = null;
        for (var attempt = 1; attempt <= maxAttempts; attempt++) {
            if (cancelled.getAsBoolean()) {
                throw new IOException("Cancelled");
            }
            try {
                return chatClient.stream(
                        request, chatSettings, pauseListener, delta -> dispatch(delta, usage, finishReason), cancelled);
            } catch (final LlmHttpFailureException e) {
                // llama.cpp / ollama style backends reject the whole turn with a 500 when the
                // tool-call arguments they just produced are malformed (unescaped control chars or
                // a structurally corrupt tail): the retry loop below would burn the same failing
                // payload again, so surface the cause explicitly instead of a bare "falling back"
                // silence. The repairer normally fixes these payloads; when it cannot (or the
                // arguments were already stored by an earlier turn), the user must see why.
                if (isToolCallArgumentsRejected(e)) {
                    emit(notice(
                            AgentEvent.Notice.Level.WARN,
                            "Server rejected the request: malformed tool call arguments (parse_error.101). "
                                    + "The arguments were kept as-is (repair failed or disabled); remove the "
                                    + "corrupted call from the transcript or retry with the repair enabled."));
                }
                last = e;
                if (attempt >= maxAttempts) {
                    break;
                }
                emit(textReset());
                emit(notice(
                        AgentEvent.Notice.Level.WARN,
                        "Stream interrupted (" + causeSummary(e) + "); retrying (" + attempt + '/' + maxAttempts
                                + ")."));
                // the backoff is idle time: it must not be counted as generation time
                final var backoffMs = streamRetryBackoffMs(attempt);
                turnLlmPausedMs += backoffMs;
                // an interrupt pressed during the backoff must abort the retry immediately:
                // the sleep is the only gap between two stream attempts, so check around it
                if (cancelled.getAsBoolean() || !interruptibleSleep(backoffMs)) {
                    throw new IOException("Cancelled");
                }
            } catch (final IOException e) {
                last = e;
                if (attempt >= maxAttempts) {
                    break;
                }
                emit(textReset());
                emit(notice(
                        AgentEvent.Notice.Level.WARN,
                        "Stream interrupted (" + causeSummary(e) + "); retrying (" + attempt + '/' + maxAttempts
                                + ")."));
                // the backoff is idle time: it must not be counted as generation time
                final var backoffMs = streamRetryBackoffMs(attempt);
                turnLlmPausedMs += backoffMs;
                // an interrupt pressed during the backoff must abort the retry immediately:
                // the sleep is the only gap between two stream attempts, so check around it
                if (cancelled.getAsBoolean() || !interruptibleSleep(backoffMs)) {
                    throw new IOException("Cancelled");
                }
            }
        }
        throw last;
    }

    /** Exponential backoff between mid-stream retries: base * 2^(attempt-1), capped by retryAfterMaxWaitMs. */
    private long streamRetryBackoffMs(final int attempt) {
        final var base = Math.max(0, configuration.llm().retriesBaseDelayMs());
        final var cap = Math.max(base, configuration.llm().retryAfterMaxWaitMs());
        var delay = base;
        for (var i = 1; i < attempt && delay < cap; i++) {
            delay *= 2;
        }
        return Math.min(delay, cap);
    }

    /**
     * Sleeps in small slices so a turn cancellation is noticed promptly (a single
     * {@code Thread.sleep} could park the loop through the whole backoff).
     *
     * @return {@code false} when the current thread was interrupted (caller aborts the retry).
     */
    private boolean interruptibleSleep(final long millis) {
        final var deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (true) {
            final var remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return true;
            }
            try {
                Thread.sleep(Math.min(100, Math.max(1, remaining / 1_000_000)));
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    private static String causeSummary(final Throwable e) {
        final var cause = e.getCause();
        final var message = cause == null
                        || cause.getMessage() == null
                        || cause.getMessage().isBlank()
                ? e.getMessage()
                : cause.getMessage();
        return message == null ? "unknown transport failure" : message;
    }

    /**
     * Whether a provider rejection is the llama.cpp / ollama style "the tool call arguments this
     * model just produced are not parseable JSON" HTTP failure. The arguments are repaired before
     * being sent (see {@code ToolCallArgumentsRepairer}); when this marker still shows up the
     * payload was already corrupt at generation time and the repair could not recover it (or is
     * disabled for the model). Surfaced explicitly because retrying the same turn cannot fix it.
     */
    private static boolean isToolCallArgumentsRejected(final LlmHttpFailureException e) {
        final var body = e.body();
        return body != null && body.contains("parse_error.101");
    }

    /**
     * A turn that ran tools but ends text-only while the todo list still has pending items looks
     * finished to the loop while the work is not: the same predicate drives both the optional
     * auto-continuation (see {@code yca.todos.continueOnUnfinished}) and this notice, so the user
     * can still resume explicitly - or review why the model stopped - once auto-continuation is
     * exhausted. The notice is emitted with the normalized {@code finish_reason} so a rich frontend
     * can tell a deliberate, clean stop (the model chose to end its turn despite pending work) apart
     * from a truncation or an error, which are reported by separate events.
     *
     * @return the pending/total counts, or {@code null} when the todo list does not hold this turn open.
     */
    private PendingTodos unfinishedTodos(final boolean toolsRanThisTurn) {
        if (!toolsRanThisTurn || todoStore == null) {
            return null;
        }
        // in plan mode pending todos are the expected end state: the whole point is to
        // record the plan as todos, so no resume reminder is needed
        if (permissionService != null && permissionService.mode() == AgentMode.PLAN) {
            return null;
        }
        final var todos = todoStore.all();
        if (todos.isEmpty()) {
            return null;
        }
        final var pending =
                todos.stream().filter(t -> !"completed".equals(t.status())).count();
        return pending == 0 ? null : new PendingTodos((int) pending, todos.size());
    }

    private AgentEvent.Notice unfinishedTodosNotice(final PendingTodos pending, final String finishReason) {
        return new AgentEvent.Notice(
                AgentEvent.Notice.Level.WARN,
                "model ended its turn (finish_reason=" + finishReason + ") with " + pending.pending() + '/'
                        + pending.total()
                        + " todos still pending - send 'continue' to resume, or review why it stopped",
                finishReason,
                currentSessionId,
                subagentId,
                subagentName);
    }

    /** Continuations allowed per turn ({@code yca.todos.maxContinues}), zero when not configured. */
    private int todoMaxContinues() {
        return configuration.todos() == null ? 0 : configuration.todos().maxContinues();
    }

    /**
     * Whether a text-only end with pending todos may be turned into a continuation. Opt-in through
     * {@code yca.todos.continueOnUnfinished} and deliberately conservative, because the model ending
     * its turn is legitimate in three cases: a sub-agent reporting back to its parent (its todo list
     * is its own), a turn carrying a prompt the user typed while the loop was running (the model just
     * answered that message) and a positive continuation budget that must be honored as a stop.
     */
    private boolean canAutoContinueUnfinished(final boolean userInjected) {
        return !userInjected
                && subagentId == null
                && configuration.todos() != null
                && configuration.todos().continueOnUnfinished()
                && todoMaxContinues() > 0;
    }

    /**
     * The instruction sent to the model to continue instead of ending the turn, from the second
     * continuation on enriched with a strategy-change clause: a model that already repeated itself
     * keeps answering with the same progress report, so it is asked for the blocker instead.
     */
    private String unfinishedTodoContinuePrompt(final int continuations) {
        final var configured = configuration.todos() == null
                ? null
                : configuration.todos().resolveContinuePrompt(UNFINISHED_TODO_CONTINUE_PROMPT);
        final var base = configured == null || configured.isBlank() ? UNFINISHED_TODO_CONTINUE_PROMPT : configured;
        return continuations >= 2 ? base + UNFINISHED_TODO_BLOCKED_PROMPT : base;
    }

    /** How many todos of the list are not completed yet, and how many the list holds. */
    private record PendingTodos(int pending, int total) {}

    /**
     * Outcome of a single tool call, decoupled from message/session mutation so calls can run
     * concurrently while results still land in the conversation in the model's call order.
     * {@code paths} carries the file paths a read or write tool touched, for stale-read invalidation.
     * {@code durationMs} is the individual tool execution time, used for per-turn metrics.
     */
    private record ToolOutcome(
            String callId,
            String name,
            String output,
            boolean success,
            boolean persist,
            List<String> paths,
            long durationMs) {

        ToolOutcome(
                final String callId,
                final String name,
                final String output,
                final boolean success,
                final boolean persist,
                final List<String> paths) {
            this(callId, name, output, success, persist, paths, 0L);
        }

        ToolOutcome(
                final String callId,
                final String name,
                final String output,
                final boolean success,
                final boolean persist) {
            this(callId, name, output, success, persist, List.of(), 0L);
        }
    }

    /**
     * Executes the tool calls of one model reply. Maximal runs of consecutive read-only calls run
     * concurrently on virtual threads (they cannot invalidate each other's inputs), while
     * state-changing tools stay sequential: a read never overtakes a preceding edit issued in the
     * same reply. Message and session order always follow the model's call order.
     */
    private void executeToolCalls(
            final String sessionId,
            final List<Message> messages,
            final List<ToolCall> calls,
            final ToolRepeats repeats,
            final StaleReads staleReads,
            final int toolsTokens,
            final BooleanSupplier cancelled)
            throws InterruptedException {
        var i = 0;
        while (i < calls.size()) {
            if (cancelled.getAsBoolean()) {
                // a cancel pressed mid-batch: abort the remaining calls (the running tool stops
                // cooperatively through its own probe); the loop sees the flag on its next
                // iteration and unwinds the turn
                return;
            }
            if (isParallelizable(calls.get(i))) {
                var end = i + 1;
                while (end < calls.size() && isParallelizable(calls.get(end))) {
                    end++;
                }
                if (end - i > 1 && executor != null && parallelReadonlyEnabled()) {
                    // tool events (started/finished) are emitted from the worker threads as they
                    // happen: bridges tolerate that, results order is still enforced below
                    final var batchStartNanos = System.nanoTime();
                    final var futures = new ArrayList<Future<ToolOutcome>>(end - i);
                    for (var j = i; j < end; j++) {
                        final var call = calls.get(j);
                        // doom-loop check happens in call order, before the worker starts
                        final var repeated = repeats.rejectIfRepeated(call);
                        if (repeated != null) {
                            emit(toolFinished(repeated.name(), repeated.output(), false));
                            futures.add(executor.submit(() -> repeated));
                        } else {
                            futures.add(executor.submit(() -> runTool(call, cancelled)));
                        }
                    }
                    for (final var future : futures) {
                        appendToolResult(sessionId, messages, awaitTool(future), staleReads);
                        checkRoundBudget(sessionId, messages, toolsTokens);
                    }
                    final var batchWallMs = (System.nanoTime() - batchStartNanos) / 1_000_000;
                    turnToolWallMs += batchWallMs;
                } else {
                    for (var j = i; j < end; j++) {
                        appendToolResult(sessionId, messages, runGuarded(calls.get(j), repeats, cancelled), staleReads);
                        checkRoundBudget(sessionId, messages, toolsTokens);
                    }
                }
                i = end;
            } else {
                appendToolResult(sessionId, messages, runGuarded(calls.get(i), repeats, cancelled), staleReads);
                checkRoundBudget(sessionId, messages, toolsTokens);
                i++;
            }
        }
    }

    private ToolOutcome runGuarded(final ToolCall call, final ToolRepeats repeats, final BooleanSupplier cancelled) {
        final var repeated = repeats.rejectIfRepeated(call);
        if (repeated != null) {
            emit(toolFinished(repeated.name(), repeated.output(), false));
            return repeated;
        }
        return runTool(call, cancelled);
    }

    /** File tools whose results participate in stale-read invalidation. */
    private static final Set<String> FILE_READ_TOOLS = Set.of("read_file");

    private static final Set<String> FILE_WRITE_TOOLS = Set.of("write_file", "edit_file", "apply_patch");

    private static final String STALE_READ_MARKER =
            "[stale read cleared: the file changed after this read; re-read it to get the current content]";

    /**
     * Tracks which {@code read_file} results sit in the conversation and by which path, so a later
     * successful write to the same path can replace them with a short marker instead of letting the
     * model reason over superseded file snapshots until prune/compaction eventually drops them.
     * All mutation happens on the loop thread (worker threads only produce outcomes), so no
     * synchronization is needed.
     */
    private static final class StaleReads {
        private final Map<String, Path> readByCallId;

        StaleReads(final Map<String, Path> readByCallId) {
            this.readByCallId = new LinkedHashMap<>(readByCallId);
        }

        /** Returns how many older read results were invalidated by this outcome (0 when none). */
        int onLanded(final List<Message> messages, final ToolOutcome outcome) {
            if (outcome.paths().isEmpty()) {
                return 0;
            }
            final var touched = new ArrayList<Path>(outcome.paths().size());
            for (final var raw : outcome.paths()) {
                touched.add(Path.of(raw).toAbsolutePath().normalize());
            }
            if (FILE_READ_TOOLS.contains(outcome.name())) {
                if (outcome.success()) {
                    for (final var path : touched) {
                        readByCallId.put(outcome.callId(), path);
                    }
                }
                return 0;
            }
            if (!FILE_WRITE_TOOLS.contains(outcome.name()) || !outcome.success()) {
                return 0;
            }
            final var written = Set.copyOf(touched);
            var invalidated = 0;
            for (var i = 0; i < messages.size() - 1; i++) { // last message is the write result itself
                final var message = messages.get(i);
                if (!"tool".equals(message.role())) {
                    continue;
                }
                final var read = readByCallId.get(message.toolCallId());
                if (read == null || !written.contains(read)) {
                    continue;
                }
                if (String.valueOf(message.content()).contains(STALE_READ_MARKER)) {
                    continue; // already invalidated
                }
                messages.set(i, Message.tool(message.toolCallId(), STALE_READ_MARKER));
                invalidated++;
            }
            return invalidated;
        }
    }

    /** Rebuilds the {@code callId -> path} index of past {@code read_file} calls from the conversation. */
    private Map<String, Path> readsFromHistory(final List<Message> messages) {
        if (messages.isEmpty()) {
            return Map.of();
        }
        Map<String, Path> out = null;
        for (final var message : messages) {
            if (message.toolCalls() == null || message.toolCalls().isEmpty()) {
                continue;
            }
            for (final var call : message.toolCalls()) {
                final var fn = call.function();
                if (fn == null || !"read_file".equals(fn.name()) || fn.arguments() == null) {
                    continue;
                }
                try {
                    final var parsed = mapper.fromString(Object.class, fn.arguments());
                    if (parsed instanceof final Map<?, ?> m
                            && m.get("path") instanceof final String p
                            && !p.isBlank()) {
                        if (out == null) {
                            out = new LinkedHashMap<>();
                        }
                        out.put(call.id(), Path.of(p).toAbsolutePath().normalize());
                    }
                } catch (final RuntimeException ignored) {
                    // unreadable arguments in history are simply not indexed
                }
            }
        }
        return out == null ? Map.of() : out;
    }

    /** Extracts the file paths a file tool touched: the {@code path} arg, or {@code +++} headers for patches. */
    private static List<String> touchedPaths(final String name, final Map<String, Object> args) {
        if ("apply_patch".equals(name)) {
            if (!(args.get("patch") instanceof final String patch)) {
                return List.of();
            }
            final var out = new ArrayList<String>();
            for (final var line : patch.split("\n", -1)) {
                final var stripped = line.strip();
                if (!stripped.startsWith("+++ ")) {
                    continue;
                }
                var file = stripped.substring(4).strip();
                final var tab = file.indexOf('\t');
                if (tab >= 0) {
                    file = file.substring(0, tab).strip();
                }
                if (file.startsWith("b/")) {
                    file = file.substring(2);
                }
                if (!file.isBlank() && !"/dev/null".equals(file)) {
                    out.add(file);
                }
            }
            return out;
        }
        if ((FILE_READ_TOOLS.contains(name) || FILE_WRITE_TOOLS.contains(name))
                && args.get("path") instanceof final String path
                && !path.isBlank()) {
            return List.of(path);
        }
        return List.of();
    }

    /**
     * Doom-loop breaker: counts consecutive executions of the same tool with identical arguments across the whole
     * turn. The third identical call in a row is refused with guidance instead of burning another model round-trip
     * on the exact same output (mirrors opencode's doom-loop detection).
     */
    private static final class ToolRepeats {
        private static final int THRESHOLD = 3;

        private String lastKey;
        private int count;

        ToolOutcome rejectIfRepeated(final ToolCall call) {
            final var fn = call.function();
            final var name = fn == null || fn.name() == null ? "" : fn.name();
            final var args = fn == null || fn.arguments() == null ? "{}" : fn.arguments();
            final var key = name + '\n' + args;
            if (key.equals(lastKey)) {
                count++;
            } else {
                lastKey = key;
                count = 1;
            }
            if (count < THRESHOLD) {
                return null;
            }
            final var diagnostic = "Tool " + name + " was called " + count + " times in a row with exactly the same "
                    + "arguments: repeating it adds no information. Change strategy - narrow or widen the query, "
                    + "use another tool, or answer from what you already collected.";
            return new ToolOutcome(call.id(), name, diagnostic, false, false);
        }
    }

    /** A call can join a parallel run only if its tool is known and cannot mutate state. */
    private boolean isParallelizable(final ToolCall call) {
        if (toolRegistry == null) {
            return false;
        }
        final var fn = call.function();
        final EmbeddedTool tool = toolRegistry.tool(fn == null || fn.name() == null ? "" : fn.name());
        return tool != null && (tool.readonly() || tool.category() == ToolCategory.READ);
    }

    private boolean parallelReadonlyEnabled() {
        if (configuration == null || configuration.tools() == null) {
            return true; // conservative default: the feature is on unless explicitly disabled
        }
        return configuration.tools().parallelReadonly();
    }

    private ToolOutcome awaitTool(final Future<ToolOutcome> future) throws InterruptedException {
        try {
            return future.get();
        } catch (final ExecutionException e) {
            final var cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("parallel tool execution failed", cause);
        }
    }

    /** Runs one tool call (lookup, arguments parsing, permission gate, execution) without touching the conversation. */
    private ToolOutcome runTool(final ToolCall call, final BooleanSupplier cancelled) {
        final var name = call.function() == null || call.function().name() == null
                ? ""
                : call.function().name();
        final var argsJson = call.function() == null || call.function().arguments() == null
                ? "{}"
                : call.function().arguments();
        final EmbeddedTool tool = toolRegistry.tool(name);
        if (tool == null) {
            final var diagnostic = "Unknown tool: " + name;
            emit(toolFinished(name, unknownToolDiagnostic(name), false));
            return new ToolOutcome(call.id(), name, diagnostic, false, false);
        }
        final Map<String, Object> args;
        try {
            final var parsed = mapper.fromString(Object.class, argsJson.isBlank() ? "{}" : argsJson);
            args = parsed instanceof Map<?, ?> m ? cast(m) : new LinkedHashMap<>();
        } catch (final RuntimeException e) {
            final var diagnostic = "Invalid tool arguments JSON: " + argsJson;
            emit(toolFinished(name, diagnostic, false));
            return new ToolOutcome(call.id(), name, diagnostic, false, false);
        }
        emit(toolStarted(name, argsJson));
        final PermissionService.Decision decision =
                permissionService.check(name, tool.category(), args, tool.readonly());
        if (!decision.allowed()) {
            final var denial =
                    "Permission denied by user" + (decision.reason() == null ? "" : ": " + decision.reason());
            emit(toolFinished(name, denial, false));
            return new ToolOutcome(call.id(), name, denial, false, false);
        }
        final var toolStartNanos = System.nanoTime();
        final ToolResult result = toolExecutionService.execute(tool, args, toolContext(cancelled));
        final var toolDurationMs = (System.nanoTime() - toolStartNanos) / 1_000_000;
        emit(toolFinished(name, result.output(), result.success()));
        return new ToolOutcome(
                call.id(), name, result.output(), result.success(), true, touchedPaths(name, args), toolDurationMs);
    }

    /**
     * Lands an executed outcome in the conversation and the session store, in call order. The output
     * is capped at ingestion time (head+tail, deterministic) so a single oversized result can never
     * blow the context budget: the capped version becomes the canonical session content, keeping the
     * prompt prefix stable for caching (no retroactive mutation of already-sent messages).
     */
    private void appendToolResult(
            final String sessionId,
            final List<Message> messages,
            final ToolOutcome outcome,
            final StaleReads staleReads) {
        final var raw = outcome.output() == null || outcome.output().isBlank() ? "(no output)" : outcome.output();
        final var output = capIngestionOutput(raw);
        messages.add(Message.tool(outcome.callId(), output, !outcome.success()));
        if (outcome.persist()) {
            sessionStore.append(
                    sessionId, SessionEntry.of(clock.millis(), "tool", output, null, outcome.callId(), outcome.name()));
        }
        final var invalidated = staleReads.onLanded(messages, outcome);
        if (invalidated > 0) {
            emit(status("invalidated " + invalidated + " stale read(s) of modified files"));
        }
        turnToolsRun++;
        turnToolSumMs += outcome.durationMs();
    }

    /** Head+tail ingestion cap applied to tool outputs before they enter the conversation. */
    String capIngestionOutput(final String output) {
        final var cap = configuration.pruning().ingestionOutputCapChars();
        if (cap <= 0 || output == null || output.length() <= cap) {
            return output;
        }
        final var half = cap / 2;
        return output.substring(0, half)
                + "\n[... output truncated (" + output.length() + " chars), kept head and tail ...]\n"
                + output.substring(output.length() - half);
    }

    private static String unknownToolDiagnostic(final String name) {
        return "Unknown tool \"" + name + "\": not in this session's toolset (built-in or enabled MCP)."
                + " Run /tools to list the valid tools; only exact names from the request's tools field are accepted.";
    }

    private void dispatch(
            final LlmStreamEvent delta, final io.yupiik.ia.agent.core.llm.Usage[] usage, final String[] finishReason) {
        if (delta instanceof TextDelta(final String text)) {
            if (thinkingActive) {
                turnThinkingMs += (System.nanoTime() - thinkingStartNanos) / 1_000_000;
                thinkingActive = false;
            }
            emit(textDelta(text));
        } else if (delta instanceof ReasoningDelta(final String text)) {
            if (!thinkingActive) {
                thinkingActive = true;
                thinkingStartNanos = System.nanoTime();
            }
            emit(thinkingDelta(text));
        } else if (delta instanceof End(final String reason, final Usage upper)) {
            if (thinkingActive) {
                turnThinkingMs += (System.nanoTime() - thinkingStartNanos) / 1_000_000;
                thinkingActive = false;
            }
            if (reason != null) {
                finishReason[0] = reason;
            }
            // a chunk may carry usage and a choice finish reason on the same stream line, which
            // the dialect splits into two End events; only overwrite usage when this End actually
            // carries a usage payload, otherwise a trailing finish-reason-only End would reset the
            // captured counters back to empty
            if (upper != null) {
                usage[0] = new io.yupiik.ia.agent.core.llm.Usage(
                        Math.toIntExact(upper.promptTokens()),
                        Math.toIntExact(upper.completionTokens()),
                        Math.toIntExact(upper.totalTokens()),
                        upper.cacheRead(),
                        upper.cacheWrite(),
                        upper.generationTime());
            }
            turnLlmCalls++;
            // usage is recorded once per request after the stream completes (single record point,
            // otherwise requests and tokens are double counted)
        }
    }

    private void emitUsage(final List<Message> messages, final int toolsTokens) {
        final var effective = usageTracker.effectiveContext(messages, toolsTokens);
        emit(usageUpdated(
                usageTracker.promptTokens() > 0
                        ? new io.yupiik.ia.agent.core.llm.Usage(
                                (int) usageTracker.promptTokens(),
                                (int) usageTracker.completionTokens(),
                                (int) (usageTracker.promptTokens() + usageTracker.completionTokens()),
                                usageTracker.cacheReadTokens(),
                                0)
                        : io.yupiik.ia.agent.core.llm.Usage.EMPTY,
                effective,
                usageTracker.contextPercent(effective)));
    }

    /**
     * Estimates the token contribution of the wire tool schemas injected into every request,
     * which are never part of the message list but consume context window. Falls back to a rough
     * per-tool cost when the JSON mapper is unavailable.
     */
    private int toolTokens(final List<?> tools) {
        if (tools == null || tools.isEmpty()) {
            return 0;
        }
        if (mapper != null) {
            try {
                return TokenEstimator.estimate(mapper.toString(tools));
            } catch (final RuntimeException ignored) {
                // fall through to the per-tool approximation
            }
        }
        int total = 0;
        for (final var ignored : tools) {
            total += 300;
        }
        return total;
    }

    private static String str(final Object content) {
        return content instanceof String s ? s : content == null ? "" : content.toString();
    }

    /**
     * Appends the per-session snapshot block (active checkpoint label plus retained summaries that
     * must survive compaction) to the system prompt, when any exist.
     */
    /**
     * Soft context-usage nudge appended to the volatile session state (never to the stable prefix, so
     * prompt caching is unaffected). Only fires when {@code nudgeThresholdPercent} is non-zero, the
     * estimated usage is above it and at least {@code nudgeIterationThreshold} iterations ran since
     * the last user message: the model is invited to finish or to compress instead of waiting for the
     * hard compaction wall.
     */
    String nudgeBlock(final List<Message> messages) {
        final var p = configuration.pruning();
        final var threshold = p.nudgeThresholdPercent();
        if (threshold <= 0) {
            return "";
        }
        var sinceLastUser = 0;
        for (var i = messages.size() - 1; i >= 0; i--) {
            if (isVolatileTail(messages.get(i))) {
                continue; // synthetic session-state tail: not a user turn and not a real iteration
            }
            if ("user".equals(messages.get(i).role())) {
                break;
            }
            sinceLastUser++;
        }
        if (sinceLastUser < p.nudgeIterationThreshold()) {
            return "";
        }
        final var percent = (int) Math.round(usageTracker.contextPercent(usageTracker.effectiveContext(messages, 0)));
        if (percent < threshold) {
            return "";
        }
        final var template = p.nudgeTemplate() == null || p.nudgeTemplate().isBlank()
                ? "Context is at ~%USAGE%% of the usable window. Consider finishing the task soon, or running manage_context with action=compact or action=prune."
                : p.nudgeTemplate();
        return "\n<nudge>" + template.replace("%USAGE%", String.valueOf(percent)) + "</nudge>";
    }

    private String snapshotBlock(final String sessionId) {
        if (snapshotStore == null || sessionId == null) {
            return "";
        }
        final var sb = new StringBuilder();
        final var label = snapshotStore.checkpointLabel(sessionId);
        if (label != null && !label.isBlank()) {
            sb.append("\n<snapshot checkpoint=\"").append(label).append("\">\n");
        } else {
            sb.append("\n<snapshot>\n");
        }
        final var retained = snapshotStore.retained(sessionId);
        if (retained == null || retained.isEmpty()) {
            sb.append("checkpoint: ")
                    .append(label == null || label.isEmpty() ? "none" : label)
                    .append('\n');
        } else {
            for (final var r : retained) {
                final var summary = r.summary();
                sb.append("- ")
                        .append(summary == null ? "" : summary.replace("\n", " "))
                        .append('\n');
            }
        }
        sb.append("</snapshot>\n");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(final Map<?, ?> m) {
        return (Map<String, Object>) m;
    }
}
