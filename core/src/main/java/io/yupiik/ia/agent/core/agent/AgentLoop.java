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
import io.yupiik.ia.agent.core.configuration.PlateauFeedbackMode;
import io.yupiik.ia.agent.core.context.ContextManager;
import io.yupiik.ia.agent.core.context.ContextView;
import io.yupiik.ia.agent.core.context.TokenEstimator;
import io.yupiik.ia.agent.core.context.UsageTracker;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatCompletionRequest;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.PauseEvent;
import io.yupiik.ia.agent.core.llm.Tool;
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
import java.util.HashMap;
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

    /** P2: after this many consecutive tool rounds with no new information, the turn is stopped. */
    private static final int NO_PROGRESS_ROUNDS_LIMIT = 5;

    /** Exploration-plateau markers used in the per-round coverage set (see {@link #appendToolResult}). */
    private static final String WRITE_MARKER = "\u0000write";

    private static final String REDUNDANT_MARKER = "\u0000seen:";

    /**
     * One-shot nudge injected before the exploration-plateau hard stop (plateauFeedback nudge_1 /
     * nudge_2): the model keeps re-reading already-seen files/results with novel signatures, so it
     * is told to change strategy instead of silently losing the turn at the limit.
     */
    static final String PLATEAU_NUDGE_PROMPT =
            "Your recent tool rounds re-read only files/results already present in this conversation."
                    + " Stop re-reading: use the information you already collected, batch any remaining"
                    + " independent reads or searches into a single reply, and if you are stuck state"
                    + " exactly what information is still missing instead of probing the same targets.";

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
     * Retry nudge for an empty completion (no content, no tool call) that followed a request which
     * produced reasoning tokens: the model spent its whole output budget thinking, so asking it to
     * "retry" the same context would re-derive the same thoughts and hit the same wall. The model is
     * asked to bound its reasoning (or answer without further deliberation) instead of re-thinking.
     */
    private static final String EMPTY_REASONING_RETRY_PROMPT =
            "Your previous try produced no answer at all: it looks like the output budget was consumed by the"
                    + " reasoning/thinking phase. Do not re-derive the same reasoning: it will hit the same limit."
                    + " Answer directly now - keep the thinking minimal, or skip it - and use the tools to make"
                    + " concrete progress (or reply with the final answer if the work is done).";

    /**
     * Nudge sent when the turn ended with todos still open and {@code yca.todos.continueOnUnfinished}
     * is enabled: weaker model families (qwen and friends) tend to end their turn after a couple of
     * steps with a progress report, so the instruction is explicit about acting rather than narrating.
     */
    private static final String UNFINISHED_TODO_CONTINUE_PROMPT =
            "You ended your turn while the todo list still has unfinished items (pending or in_progress), so the work you were"
                    + " asked to do is not finished. Do not summarize your progress and do not wait for a new"
                    + " instruction: re-read the todo list in the <session-state> block and continue with the next"
                    + " unfinished item right now, using tools - finish or cancel the in_progress todos you already"
                    + " started before opening new ones. End your turn only when every todo is completed or"
                    + " explicitly cancelled, or when you are genuinely blocked - in that case state exactly what"
                    + " blocks you and stop.";

    /**
     * Variant used from the second continuation on: a model repeating itself keeps answering with the
     * same progress report, so it is asked to state the blocker instead of trying again.
     */
    private static final String UNFINISHED_TODO_BLOCKED_PROMPT =
            " You already continued several times without closing the remaining todos, so continuing the same way"
                    + " is not working: do not repeat your progress report. Either complete or cancel the unfinished"
                    + " todo with a tool call right now, finish the in_progress todos you started instead of leaving"
                    + " them open, or explicitly tell the user what exactly blocks you (missing information, a"
                    + " failing command, a decision to make) and stop.";

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
    /** Effective agent of the current turn (set at {@code doRun} start), used for identity capture. */
    private volatile String currentAgentName;
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

    /**
     * Immutable snapshot of the last request this loop handed to the provider: what the
     * model really receives, which is not what the session store keeps (the leading
     * system message and the volatile session-state tail are never persisted, and the
     * list is rewritten by prune/compaction before every call). Captured for
     * {@code /context llm-payload}. {@code protected} so tests can seed a loop without
     * running a turn.
     *
     * @param sessionId session the request was built for
     * @param model model the request was sent with
     * @param messages the exact message list sent (system prompt included)
     * @param tools the wire tool schemas sent
     * @param toolChoice the tool choice sent
     * @param toolsTokens estimated tokens of {@code tools}, used to report the total footprint
     */
    public record LlmPayload(
            String sessionId,
            String model,
            List<Message> messages,
            List<Tool> tools,
            String toolChoice,
            int toolsTokens) {}

    /** Last captured payload, {@code null} until this session sent its first request. */
    protected volatile LlmPayload lastLlmPayload;

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
    // todo auto-continuation budget consumed by the current turn (bounded by yca.todos.maxContinues)
    private int unfinishedTodoContinues;
    /**
     * Whether the last completed LLM request produced reasoning tokens (a {@code ReasoningDelta}
     * was observed before its {@code End}): an empty or truncated completion after reasoning means
     * the model spent its whole output budget thinking, and re-asking the same context would only
     * re-derive the same reasoning. Reset before each request, flipped by {@link #dispatch}, read
     * by the truncation/empty-completion branches.
     */
    private volatile boolean lastRequestProducedReasoning;

    /**
     * Doom-loop breaker state for the current turn: counts repeated tool calls (normalized args)
     * and remembers the last output per signature for the no-progress termination. Created fresh
     * per turn, mutated on the loop thread only.
     */
    private ToolRepeats repeats = new ToolRepeats();

    /** Consecutive tool rounds with no new information (reset on any informative round), see P2. */
    private int noProgressRounds;

    /**
     * Exploration-plateau breaker state for the current turn: tracks which normalized paths (and
     * bash command fingerprints) the turn already covered with read-only tools, so a model that
     * keeps re-reading the same files/results with novel-but-redundant calls can be stopped instead
     * of burning the iteration window (see the plateauRounds cascade).
     */
    private final java.util.Set<String> seenFiles = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Consecutive read-only rounds re-covering only already-seen files (reset on any informative round or write). */
    private int plateauRounds;

    /** Whether the plateau nudge (plateauFeedback nudge_1/nudge_2) was already injected this turn. */
    private boolean plateauNudged;

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

    /**
     * The last request sent to the provider for this session, {@code null} when none was
     * sent yet. Backed by {@code /context llm-payload}; the snapshot is immutable so a
     * later prune/compaction of the live list cannot change it.
     */
    public LlmPayload lastLlmPayload() {
        return lastLlmPayload;
    }

    /**
     * Rebuilds - without calling the provider - the request the next round would carry right now:
     * the same system prefix {@code doRun} assembles, the persisted transcript and a freshly
     * computed volatile tail. It is a preview and not a history: the memory refresh, the context
     * usage line and the nudge block depend on the state at this instant and the persisted
     * transcript has none of them, so {@code /context next-request} says so while
     * {@link #lastLlmPayload()} stays the exact bytes of the last call.
     */
    public LlmPayload previewLlmPayload(final String sessionId) {
        final var tools = toolRegistry.toWireTools();
        final var toolsTokens = toolTokens(tools);
        final var history = sessionStore.load(sessionId);
        final var prompt = lastUserPrompt(history);
        final var messages = new ArrayList<Message>();
        messages.add(Message.system(systemPromptBuilder.build(prompt, null) + snapshotBlock(sessionId)));
        messages.addAll(history);
        appendStateIfChanged(sessionId, messages, prompt, memoryVersionAtStart(), toolsTokens);
        return new LlmPayload(
                sessionId,
                providerManager.resolveDefault().model(),
                List.copyOf(messages),
                List.copyOf(tools),
                "auto",
                toolsTokens);
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

    private void emitResumeAfterCapNotice(final List<Message> history, final String sessionId) {
        if (history == null || history.isEmpty() || currentSessionId == null) {
            return;
        }
        final var last = history.get(history.size() - 1);
        if ("system".equals(last.role())
                && last.content() instanceof final String content
                && content.startsWith("Maximum iterations reached")) {
            emit(notice(
                    AgentEvent.Notice.Level.INFO,
                    "resuming after an iteration-cap stop: this turn starts with a fresh window."));
        }
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
        currentAgentName = agentName;
        final var agent = (agentName == null || agentName.isBlank() || agentProvider == null)
                ? null
                : agentProvider.byName(agentName);
        structuralCodeGraph.ensureReadyAsync();
        // fresh per-turn loop-guard state: repeat counts must not leak across turns
        repeats = new ToolRepeats();
        noProgressRounds = 0;
        seenFiles.clear();
        plateauRounds = 0;
        plateauNudged = false;
        final var messages = new ArrayList<Message>();
        messages.add(Message.system(systemPromptBuilder.build(promptForBuilding == null ? "" : promptForBuilding, agent)
                + snapshotBlock(sessionId)));
        if (history != null) {
            // persisted session notices (role "system", e.g. an iteration-cap stop) must never reach
            // the LLM wire: they are display-only markers for the transcript. Everything else
            // (system prompt, user/assistant/tool turns) is replayed verbatim.
            for (final var m : history) {
                if (!"system".equals(m.role())) {
                    messages.add(m);
                }
            }
        }
        if (userMessage != null) {
            messages.add(Message.user(userMessage));
            sessionStore.append(
                    sessionId,
                    SessionEntry.of(clock.millis(), "user", userMessage, null, null, null, identityOthers(agentName)));
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
        // a resume right after an iteration-cap stop is a fresh window: make it visible instead of
        // looking like one seamless turn in the transcript
        if (userMessage == null && history != null) {
            emitResumeAfterCapNotice(history, sessionId);
        }

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
        // iteration window cap cascade: per-model behavior > per-provider tuning > global default
        final var resolved = providerManager.resolveDefault();
        final var maxIterations = resolvedMaxIterations(resolved);
        final var plateauLimit = resolvedPlateauRounds(resolved);
        final var plateauFeedback = resolvedPlateauFeedback(resolved);
        final var maxConsecutiveCompactions = MAX_CONSECUTIVE_COMPACTIONS;
        final var model = resolved.model();
        final var tools = toolRegistry.toWireTools();
        final var toolsTokens = toolTokens(tools);
        var overflowRetried = false;
        var consecutiveCompactions = 0;
        var autoNoticeEmitted = false;
        var truncationContinues = 0;
        var emptyCompletionRetries = 0;
        unfinishedTodoContinues = 0;
        var toolsRanThisTurn = false;
        var toolDirectiveApplied = false;
        // a prompt typed while the loop was running was injected in this turn: the model answered
        // it, so its text-only end answers that message and must not be treated as "stopped early"
        var userInjected = false;
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
        // A "window" is one bounded run of the round loop. Every todo auto-continuation starts a fresh
        // window with a new maxIterations budget - yca.todos.maxContinues bounds the number of windows -
        // while empty-completion and truncation retries keep sharing the current window's budget. Without
        // this one long turn would be capped at maxIterations (250 by default) regardless of how many
        // todo continuations remain, making large --todos-maxContinues values unreachable.
        windowLoop:
        while (!cancelled.getAsBoolean()) {
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
                        emit(
                                notice(
                                        AgentEvent.Notice.Level.ERROR,
                                        "Context keeps filling up right after compaction after "
                                                + consecutiveCompactions
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
                appendStateIfChanged(sessionId, messages, prompt, memoryVersionAtStart, toolsTokens);
                // snapshot of what goes on the wire, for /context llm-payload: taken here so it
                // carries the pruned/compacted list and the volatile tail, and copied because the
                // live list keeps growing (messages/tools are immutable, so a shallow copy is enough)
                lastLlmPayload = new LlmPayload(
                        sessionId, model, List.copyOf(messages), List.copyOf(tools), "auto", toolsTokens);
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
                                clock.millis(),
                                "assistant",
                                str(assistant.content()),
                                assistant.toolCalls(),
                                null,
                                null,
                                identityOthers(currentAgentName)));
                emitUsage(messages, toolsTokens);

                if (integrateQueuedPrompt(sessionId, messages, queuedPrompt)) {
                    userInjected = true;
                    continue;
                }

                if (assistant.toolCalls() == null || assistant.toolCalls().isEmpty()) {
                    // an interrupt pressed while a continuation (truncation or overflow retry) was
                    // about to re-ask the model must stop here, not spawn another request
                    if (cancelled.getAsBoolean()) {
                        // rush the terminal event out so the shells reset their busy state and flush
                        // the buffered thinking/answer: the other cancel exits (window budget, outer
                        // loop) emit TurnCancelled before returning, without it the transcript stays
                        // busy forever and the live thinking tail keeps animating after the interrupt
                        emit(new AgentEvent.TurnCancelled(currentSessionId, subagentId, subagentName));
                        emitTurnMetrics(null, turnStartNanos);
                        return str(assistant.content());
                    }
                    final var content = str(assistant.content());
                    final var reason = finishReason[0];
                    if (isTruncation(reason)) {
                        final var maxTruncationContinues = configuration.llm().truncationContinuesMax();
                        if (configuration.llm().continueOnTruncation()
                                && truncationContinues < maxTruncationContinues) {
                            truncationContinues++;
                            emit(notice(
                                    AgentEvent.Notice.Level.WARN,
                                    "Response truncated by the output token limit (finish_reason="
                                            + reason + "); asking the model to continue (" + truncationContinues + '/'
                                            + maxTruncationContinues + ")."));
                            // When the model produced reasoning before the truncation, its whole output
                            // budget was spent thinking: "continue exactly where you stopped" would make
                            // it re-derive the same reasoning from the same context (the thoughts are not
                            // part of the request), hitting the same wall. Jump straight to the
                            // change-strategy prompt instead of the verbatim continue. Without reasoning
                            // the usual progressive escalation (continue, then split) still applies.
                            final var changeStrategy = lastRequestProducedReasoning || truncationContinues >= 2;
                            messages.add(Message.user(
                                    changeStrategy ? TRUNCATION_SPLIT_PROMPT : TRUNCATION_CONTINUE_PROMPT));
                            // the reasoning flag was consumed: a retry of this request must not re-trigger
                            // the "spent thinking" branch again on the same generation
                            lastRequestProducedReasoning = false;
                            continue;
                        }
                        if (tryTodoAutoContinue(messages, reason, toolsRanThisTurn, userInjected)) {
                            continue windowLoop;
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
                    if (content.isBlank()) {
                        final var maxRetries = configuration.llm().emptyCompletionRetriesMax();
                        if (maxRetries > 0 && emptyCompletionRetries < maxRetries) {
                            emptyCompletionRetries++;
                            emit(notice(
                                    AgentEvent.Notice.Level.WARN,
                                    "model returned an empty completion (finish_reason=" + reason + "); retrying ("
                                            + emptyCompletionRetries + '/' + maxRetries + ')'));
                            // An empty completion after reasoning means the model burned its whole
                            // output budget thinking (reasoning tokens are not part of the request):
                            // re-asking the identical context would re-derive the same thoughts and
                            // hit the same wall. Ask for a strategy change instead of a blind retry.
                            if (lastRequestProducedReasoning) {
                                lastRequestProducedReasoning = false;
                                messages.add(Message.user(EMPTY_REASONING_RETRY_PROMPT));
                            }
                            continue;
                        }
                        if (tryTodoAutoContinue(messages, reason, toolsRanThisTurn, userInjected)) {
                            continue windowLoop;
                        }
                        emit(notice(
                                AgentEvent.Notice.Level.ERROR,
                                "model returned an empty completion (finish_reason=" + reason + ')'
                                        + (maxRetries <= 0
                                                ? " (yca.llm.emptyCompletionRetriesMax is 0, no retry)"
                                                : "; gave up after " + emptyCompletionRetries + " retries")));
                        emitTurnMetrics(content, turnStartNanos);
                        return content;
                    }
                    // a real, non-truncated text reply is progress: empty-completion and truncation retries must
                    // not accumulate across productive rounds, only across consecutive stuck replies
                    emptyCompletionRetries = 0;
                    truncationContinues = 0;
                    if (tryTodoAutoContinue(messages, reason, toolsRanThisTurn, userInjected)) {
                        continue windowLoop;
                    }
                    final var pending = unfinishedTodos(toolsRanThisTurn);
                    if (pending != null) {
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
                final var roundInformative = new boolean[] {false};
                final var roundCoverage = new java.util.HashSet<String>();
                executeToolCalls(
                        sessionId,
                        messages,
                        assistant.toolCalls(),
                        repeats,
                        staleReads,
                        toolsTokens,
                        cancelled,
                        roundInformative,
                        roundCoverage);
                toolsRanThisTurn = true;
                // a tool round is progress too: a later empty/truncated reply starts its retry budget fresh
                emptyCompletionRetries = 0;
                truncationContinues = 0;
                // no-progress termination (P2): 5 consecutive tool rounds that brought no new
                // information (same calls, same outputs, no write) mean the model is spinning
                if (roundInformative[0]) {
                    noProgressRounds = 0;
                } else {
                    // open todos keep the turn going: the todo auto-continue (and the model's own
                    // next round) is the intended escape hatch there, not a hard no-progress stop
                    if (unfinishedTodos(toolsRanThisTurn) != null) {
                        noProgressRounds = 0;
                    } else {
                        noProgressRounds++;
                        if (noProgressRounds >= NO_PROGRESS_ROUNDS_LIMIT) {
                            emit(error("No progress: the model made " + NO_PROGRESS_ROUNDS_LIMIT
                                    + " consecutive tool calls returning the same results. Stopping the turn."));
                            emitTurnMetrics(null, turnStartNanos);
                            return null;
                        }
                    }
                }
                // exploration-plateau termination (P2): a read-only round re-covering only already-seen
                // files/results (novel call signatures, same targets) is not progress for flash-class
                // models that explore one call per round: stop after the configured plateau rounds,
                // keeping the todo auto-continue as the escape hatch when todos are open
                if (plateauLimit > 0 && !roundCoverage.isEmpty() && isPlateauRound(roundCoverage)) {
                    if (unfinishedTodos(toolsRanThisTurn) != null) {
                        plateauRounds = 0;
                    } else {
                        plateauRounds++;
                        // one-shot strategy-change nudge (plateauFeedback nudge_1 / nudge_2): before the
                        // hard stop the model gets a chance to adapt instead of losing the whole turn
                        final var nudgeAt = plateauFeedbackNudgeAt(plateauFeedback, plateauLimit);
                        if (nudgeAt > 0 && !plateauNudged && plateauRounds >= nudgeAt) {
                            plateauNudged = true;
                            emit(notice(
                                    AgentEvent.Notice.Level.INFO,
                                    "exploration-plateau nudge sent to the model (round " + plateauRounds + '/'
                                            + plateauLimit + ")"));
                            messages.add(Message.user(PLATEAU_NUDGE_PROMPT));
                            continue windowLoop;
                        }
                        if (plateauRounds >= plateauLimit) {
                            persistStopNotice(
                                    sessionId,
                                    "Exploration plateau: the model made " + plateauLimit
                                            + " consecutive read-only tool rounds re-covering only already-seen files/results.");
                            emit(error("Exploration plateau: the model made " + plateauLimit
                                    + " consecutive read-only tool rounds re-covering only already-seen files/results."
                                    + " Stopping the turn."));
                            emitTurnMetrics(null, turnStartNanos);
                            return null;
                        }
                    }
                } else {
                    plateauRounds = 0;
                }
                if (!toolDirectiveApplied && contextView != null) {
                    final var directive = contextView.poll();
                    if (directive != null) {
                        toolDirectiveApplied = true;
                        applyToolDirective(sessionId, messages, directive, toolsTokens);
                    }
                }
            }
            // the window's iteration budget ran out while the turn was still running: when todos are
            // pending and auto-continuation is allowed, start a fresh window instead of stopping, so a
            // model that keeps calling tools is not capped by the per-window budget alone
            if (cancelled.getAsBoolean()) {
                emit(new AgentEvent.TurnCancelled(currentSessionId, subagentId, subagentName));
                emitTurnMetrics(null, turnStartNanos);
                return null;
            }
            if (tryTodoAutoContinue(messages, "max_iterations", toolsRanThisTurn, userInjected)) {
                continue windowLoop;
            }
            persistStopNotice(
                    sessionId,
                    "Maximum iterations reached (" + maxIterations + "): turn stopped; resume starts a fresh window.");
            emit(error("Maximum iterations reached (" + maxIterations + ")"));
            emitTurnMetrics(null, turnStartNanos);
            return null;
        }
        emit(new AgentEvent.TurnCancelled(currentSessionId, subagentId, subagentName));
        emitTurnMetrics(null, turnStartNanos);
        return null;
    }

    /**
     * Appends the volatile session state (date, todos, memory refresh, context usage, nudge and the
     * compaction snapshot) as its own trailing user message in the working conversation, deduplicated
     * against the newest state tail already present: when the rendered state is byte-identical, nothing
     * is appended. Because the tail is part of the working list and is never moved nor rewritten once
     * sent, every request is the previous request's exact bytes plus the assistant/tool messages
     * and state changes appended since - so provider-side prefix caches (llama.cpp slot LCP, token
     * gateways) keep matching with f_keep ~ 1.0 like opencode instead of diverging mid-conversation.
     */
    private void appendStateIfChanged(
            final String sessionId,
            final List<Message> messages,
            final String prompt,
            final int memoryVersionAtTurnStart,
            final int toolsTokens) {
        final var state = "<session-state>\n"
                + systemPromptBuilder.sessionState(prompt)
                + memoryRefresh(prompt, memoryVersionAtTurnStart)
                + contextUsageLine(messages, toolsTokens)
                + nudgeBlock(messages, toolsTokens)
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
        sessionStore.append(
                sessionId,
                SessionEntry.of(clock.millis(), "user", text, null, null, null, identityOthers(currentAgentName)));
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
    /**
     * Builds the {@code others} map carrying the producer identity (provider, effective model,
     * agent) of the block about to be persisted. The identity is captured at write time so a
     * replay or a template change can re-render the block's speaker label from what actually
     * produced it. Never sent to the LLM (stripped at wire boundaries).
     */
    private Map<String, Object> identityOthers(final String agentName) {
        try {
            if (providerManager == null || chatSettings == null) {
                // no provider wiring (unit tests, bootstrapping): no identity to capture
                return null;
            }
            final var provider = providerManager.currentProviderId();
            String model = null;
            if (chatSettings.model() != null && !chatSettings.model().isBlank()) {
                model = chatSettings.model();
            } else {
                final var current = providerManager.current();
                if (current != null
                        && current.model() != null
                        && !current.model().isBlank()) {
                    model = current.model();
                }
            }
            final var identity = new java.util.LinkedHashMap<String, Object>();
            if (provider != null && !provider.isBlank()) {
                identity.put("provider", provider);
            }
            if (model != null && !model.isBlank()) {
                identity.put("model", model);
            }
            final var agent = agentName == null || agentName.isBlank() ? null : agentName;
            if (agent != null) {
                identity.put("agent", agent);
            }
            if (identity.isEmpty()) {
                return null;
            }
            return java.util.Map.of(Message.IDENTITY_KEY, identity);
        } catch (final RuntimeException e) {
            // identity capture is best-effort: a provider manager without real wiring
            // (unit tests, bootstrapping) must never break a turn
            return null;
        }
    }

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
        // a fresh generation starts here: whether it produces reasoning (and may burn its whole
        // output budget thinking) is observed per-attempt by dispatch()
        lastRequestProducedReasoning = false;
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
    /**
     * A round is a plateau round when every coverage key it contributed was already seen this turn
     * (marked {@link #REDUNDANT_MARKER}) and no write happened (a write contributes
     * {@link #WRITE_MARKER}). Writes and first-time files/results reset the plateau counter in the
     * caller because such rounds fall in the {@code else} branch.
     */
    private static boolean isPlateauRound(final java.util.Set<String> roundCoverage) {
        for (final var key : roundCoverage) {
            if (!key.startsWith(REDUNDANT_MARKER)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Iteration window cap cascade: per-model behavior.maxIterations > per-provider tuning.maxIterations
     * > the global llm.maxIterations default. The provider/model come from {@code resolved} (the
     * default provider resolved at loop start).
     */
    private int resolvedMaxIterations(final ProviderManager.ResolvedModel resolved) {
        final var override = resolved == null || providerManager == null
                ? 0
                : providerManager.maxIterationsOverride(resolved.provider(), resolved.model());
        return override > 0 ? override : configuration.llm().maxIterations();
    }

    /**
     * Exploration-plateau limit cascade: per-model behavior.plateauRounds > per-provider
     * tuning.plateauRounds > the global llm.plateauRounds default (8). 0 disables the guard.
     */
    private int resolvedPlateauRounds(final ProviderManager.ResolvedModel resolved) {
        final var override = resolved == null || providerManager == null
                ? 0
                : providerManager.plateauRounds(resolved.provider(), resolved.model());
        return override > 0 ? override : configuration.llm().plateauRounds();
    }

    /**
     * Exploration-plateau feedback cascade: per-model behavior.plateauFeedback > per-provider
     * tuning.plateauFeedback > the global llm.plateauFeedback default (nudge_2).
     */
    private PlateauFeedbackMode resolvedPlateauFeedback(final ProviderManager.ResolvedModel resolved) {
        final var override = resolved == null || providerManager == null
                ? null
                : providerManager.plateauFeedback(resolved.provider(), resolved.model());
        return override == null ? configuration.llm().plateauFeedback() : override;
    }

    /**
     * Round at which the one-shot plateau nudge fires for a mode: {@code NUDGE_2} at {@code limit-2},
     * {@code NUDGE_1} at {@code limit-1}, {@code NONE} or a limit too small for a meaningful early
     * nudge (&le; 2) returns 0 (no nudge, hard stop only).
     */
    private static int plateauFeedbackNudgeAt(final PlateauFeedbackMode mode, final int limit) {
        return switch (mode) {
            case NUDGE_2 -> limit > 2 ? limit - 2 : 0;
            case NUDGE_1 -> limit > 2 ? limit - 1 : 0;
            case NONE -> 0;
        };
    }

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
        final var pending = todos.stream()
                .filter(t -> !"completed".equals(t.status()) && !"cancelled".equals(t.status()))
                .count();
        return pending == 0 ? null : new PendingTodos((int) pending, todos.size());
    }

    private AgentEvent.Notice unfinishedTodosNotice(final PendingTodos pending, final String finishReason) {
        return new AgentEvent.Notice(
                AgentEvent.Notice.Level.WARN,
                "model ended its turn (finish_reason=" + finishReason + ") with " + pending.pending() + '/'
                        + pending.total()
                        + " todos still unfinished - send 'continue' to resume, or review why it stopped",
                finishReason,
                currentSessionId,
                subagentId,
                subagentName);
    }

    /**
     * Persists a turn-stopped notice (role "system") so the transcript can render it on replay and
     * a later resume can detect that the previous turn did not end naturally. These entries are
     * display-only: the loop filters them out of the working message list (see {@code doRun}).
     */
    private void persistStopNotice(final String sessionId, final String message) {
        sessionStore.append(sessionId, SessionEntry.of(clock.millis(), "system", message, null, null, "notice"));
    }

    /** Continuations allowed per turn ({@code yca.todos.maxContinues}), zero when not configured. */
    private int todoMaxContinues() {
        return configuration.todos() == null ? 0 : configuration.todos().maxContinues();
    }

    /**
     * Turns a stop the model did not really choose - an empty completion, a truncation, a window whose
     * iteration budget ran out, or a plain text-only end - into a todo auto-continuation when pending
     * todos and {@code yca.todos.continueOnUnfinished} hold. Shared by every stop path so the
     * continuation budget ({@code yca.todos.maxContinues}) and the strategy-change prompt are honored
     * uniformly. Returns whether the caller must start a fresh iteration window ({@code continue} on
     * the labelled {@code windowLoop}).
     */
    private boolean tryTodoAutoContinue(
            final List<Message> messages,
            final String finishReason,
            final boolean toolsRanThisTurn,
            final boolean userInjected) {
        final var pending = unfinishedTodos(toolsRanThisTurn);
        final var maxContinues = todoMaxContinues();
        if (pending == null || !canAutoContinueUnfinished(userInjected) || unfinishedTodoContinues >= maxContinues) {
            return false;
        }
        unfinishedTodoContinues++;
        emit(notice(
                AgentEvent.Notice.Level.INFO,
                "model ended its turn (finish_reason=" + finishReason + ") with " + pending.pending() + '/'
                        + pending.total() + " todos still unfinished; continuing automatically ("
                        + unfinishedTodoContinues + '/' + maxContinues + ")."));
        messages.add(Message.user(unfinishedTodoContinuePrompt(unfinishedTodoContinues)));
        return true;
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
            long durationMs,
            boolean informative,
            List<String> coverage) {

        ToolOutcome(
                final String callId,
                final String name,
                final String output,
                final boolean success,
                final boolean persist,
                final List<String> paths,
                final long durationMs,
                final boolean informative) {
            this(callId, name, output, success, persist, paths, durationMs, informative, List.of());
        }

        ToolOutcome(
                final String callId,
                final String name,
                final String output,
                final boolean success,
                final boolean persist,
                final List<String> paths,
                final long durationMs) {
            this(callId, name, output, success, persist, paths, durationMs, true, List.of());
        }

        ToolOutcome(
                final String callId,
                final String name,
                final String output,
                final boolean success,
                final boolean persist,
                final List<String> paths) {
            this(callId, name, output, success, persist, paths, 0L, true);
        }

        ToolOutcome(
                final String callId,
                final String name,
                final String output,
                final boolean success,
                final boolean persist) {
            this(callId, name, output, success, persist, List.of(), 0L, true);
        }
    }

    /**
     * Executes the tool calls of one model reply. Maximal runs of consecutive read-only calls run
     * concurrently on virtual threads (they cannot invalidate each other's inputs), while
     * state-changing tools stay sequential: a read never overtakes a preceding edit issued in the
     * same reply. Message and session order always follow the model's call order.
     */
    private boolean executeToolCalls(
            final String sessionId,
            final List<Message> messages,
            final List<ToolCall> calls,
            final ToolRepeats repeats,
            final StaleReads staleReads,
            final int toolsTokens,
            final BooleanSupplier cancelled,
            final boolean[] roundInformative,
            final java.util.Set<String> roundCoverage)
            throws InterruptedException {
        var i = 0;
        while (i < calls.size()) {
            if (cancelled.getAsBoolean()) {
                // a cancel pressed mid-batch: abort the remaining calls (the running tool stops
                // cooperatively through its own probe); the loop sees the flag on its next
                // iteration and unwinds the turn
                return roundInformative[0];
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
                        final var repeated = repeats.rejectIfRepeated(call, this::normalizedArguments);
                        if (repeated != null) {
                            emit(toolFinished(repeated.name(), repeated.output(), false));
                            futures.add(executor.submit(() -> repeated));
                        } else {
                            futures.add(executor.submit(() -> runTool(call, cancelled)));
                        }
                    }
                    for (final var future : futures) {
                        appendToolResult(
                                sessionId, messages, awaitTool(future), staleReads, roundInformative, roundCoverage);
                        checkRoundBudget(sessionId, messages, toolsTokens);
                    }
                    final var batchWallMs = (System.nanoTime() - batchStartNanos) / 1_000_000;
                    turnToolWallMs += batchWallMs;
                } else {
                    for (var j = i; j < end; j++) {
                        appendToolResult(
                                sessionId,
                                messages,
                                runGuarded(calls.get(j), repeats, cancelled),
                                staleReads,
                                roundInformative,
                                roundCoverage);
                        checkRoundBudget(sessionId, messages, toolsTokens);
                    }
                }
                i = end;
            } else {
                appendToolResult(
                        sessionId,
                        messages,
                        runGuarded(calls.get(i), repeats, cancelled),
                        staleReads,
                        roundInformative,
                        roundCoverage);
                checkRoundBudget(sessionId, messages, toolsTokens);
                i++;
            }
        }
        return roundInformative[0];
    }

    private ToolOutcome runGuarded(final ToolCall call, final ToolRepeats repeats, final BooleanSupplier cancelled) {
        final var repeated = repeats.rejectIfRepeated(call, this::normalizedArguments);
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
     * Doom-loop breaker: counts executions of the same tool with the same normalized arguments across
     * the whole turn. The third identical call is refused with guidance instead of burning another
     * model round-trip on the exact same output (mirrors opencode's doom-loop detection). Arguments are
     * normalized (JSON keys sorted) so cosmetic spelling variants do not bypass the guard, and the
     * count is per signature (not merely consecutive): {@code read A, read B, read A, read A} refuses
     * the third occurrence of {@code read A}. Read-only calls below the threshold are never refused:
     * legitimate polling/exploration stays legal.
     */
    private static final class ToolRepeats {
        private static final int THRESHOLD = 3;

        /** Signature (name + normalized args) -> number of occurrences seen this turn. */
        private final Map<String, Integer> counts = new HashMap<>();
        /** callId -> signature key of the call, so a result can be matched back to its call args. */
        private final Map<String, String> callKeyByCallId = new HashMap<>();
        /** Signature -> last output seen this turn (for same-call-same-result detection). */
        private final Map<String, String> lastOutputs = new HashMap<>();

        /**
         * Records one tool result {@code output} for the call {@code callId} and reports whether it
         * brought new information: {@code false} when the same signature already produced
         * byte-identical output this turn. The no-progress counting itself is done by the loop
         * (one decision per round, aggregating all results of a round).
         */
        synchronized boolean isNovelResult(final String callId, final String name, final String output) {
            final var key = callKeyByCallId.get(callId);
            if (key == null) {
                return true; // never saw the call: always informative
            }
            final var previous = lastOutputs.get(key);
            final var value = output == null ? "" : output;
            final var novel = previous == null || !previous.equals(value);
            lastOutputs.put(key, value);
            return novel;
        }

        /** The normalized arguments are computed by the owning loop (JSON key sorting lives there). */
        ToolOutcome rejectIfRepeated(final ToolCall call) {
            return rejectIfRepeated(call, null);
        }

        ToolOutcome rejectIfRepeated(
                final ToolCall call, final java.util.function.Function<String, String> normalizer) {
            final var fn = call.function();
            final var name = fn == null || fn.name() == null ? "" : fn.name();
            final var args = fn == null || fn.arguments() == null ? "{}" : fn.arguments();
            final var normalized = normalizer == null ? args : normalizer.apply(args);
            final var key = name + '\n' + normalized;
            final var count = counts.merge(key, 1, Integer::sum);
            callKeyByCallId.put(call.id(), key);
            if (count < THRESHOLD) {
                return null;
            }
            final var diagnostic = "Tool " + name + " was called " + count + " times with the same arguments: "
                    + "repeating it adds no information. Change strategy - narrow or widen the query, "
                    + "use another tool, or answer from what you already collected.";
            return new ToolOutcome(call.id(), name, diagnostic, false, false, List.of(), 0L, false);
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
                call.id(),
                name,
                result.output(),
                result.success(),
                true,
                touchedPaths(name, args),
                toolDurationMs,
                true,
                coverageKeys(name, args));
    }

    /**
     * Coverage keys of a successful read-only tool call, used by the exploration-plateau detector:
     * a normalized absolute path for file tools (page-aware for read_file, pattern-aware for grep),
     * and for bash the configured bash-rules prefix + target from {@link PermissionService} - so the
     * recognized read-only families come from the config, never from a hardcoded command list.
     * Returns an empty list when the call does not map to a recognisable coverage target (writes,
     * todo updates, unrecognized commands): such calls are never plateau signals.
     */
    private List<String> coverageKeys(final String name, final Map<String, Object> args) {
        if (FILE_READ_TOOLS.contains(name)) {
            final var path = args.get("path") instanceof final String p && !p.isBlank() ? p : "";
            if (path.isBlank()) {
                return List.of();
            }
            // page-aware key: re-reading the same 2k window is redundant, advancing through the file
            // (offset buckets) is novel - so legitimate paging never trips the plateau detector
            final var offset = args.get("offset") instanceof final Number n ? n.intValue() : 0;
            return List.of(normalizeCoveragePath(path) + "@" + (offset / 2048) + "k");
        }
        if ("bash".equals(name) && args.get("command") instanceof final String command && !command.isBlank()) {
            return permissionService == null ? List.of() : permissionService.bashCoverage(command);
        }
        if ("grep".equals(name)) {
            final var pattern = args.get("pattern") instanceof final String p ? p : "";
            final var path = args.get("path") instanceof final String p && !p.isBlank() ? p : "";
            // pattern-aware: a new search over the same root is novel, the same pattern over the
            // same root is redundant
            return path.isBlank()
                    ? (pattern.isBlank() ? List.of() : List.of("grep:" + pattern))
                    : List.of("grep:" + pattern + ":" + normalizeCoveragePath(path));
        }
        if (("glob".equals(name) || "list_dir".equals(name))
                && args.get("path") instanceof final String path
                && !path.isBlank()) {
            return List.of(normalizeCoveragePath(path));
        }
        return List.of();
    }

    private static String normalizeCoveragePath(final String raw) {
        final var trimmed = raw.strip();
        if (trimmed.isEmpty()) {
            return trimmed;
        }
        try {
            return Path.of(trimmed).toAbsolutePath().normalize().toString();
        } catch (final RuntimeException e) {
            return trimmed;
        }
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
            final StaleReads staleReads,
            final boolean[] roundInformative,
            final java.util.Set<String> roundCoverage) {
        final var raw = outcome.output() == null || outcome.output().isBlank() ? "(no output)" : outcome.output();
        final var output = capIngestionOutput(raw);
        messages.add(Message.tool(outcome.callId(), output, !outcome.success()));
        // a round is informative when at least one result is new (same call, different output) or
        // a write succeeded: used by the no-progress termination (P2). Refused doom-loop calls are
        // explicitly non-informative (their diagnostic output would otherwise look "new").
        if (outcome.informative() && outcome.success() && FILE_WRITE_TOOLS.contains(outcome.name())) {
            roundInformative[0] = true;
        } else if (roundInformative != null && !roundInformative[0]) {
            roundInformative[0] =
                    outcome.informative() && repeats.isNovelResult(outcome.callId(), outcome.name(), output);
        }
        // exploration-plateau tracking: successful read-only calls contribute their coverage keys
        // (already-seen files flagged as such); a write is a hard reset signal for the round.
        if (outcome.success() && roundCoverage != null) {
            if (FILE_WRITE_TOOLS.contains(outcome.name())) {
                roundCoverage.add(WRITE_MARKER);
            } else {
                for (final var key : outcome.coverage()) {
                    final var seen = seenFiles.add(key);
                    roundCoverage.add(seen ? key : REDUNDANT_MARKER + key);
                }
            }
        }
        if (outcome.persist()) {
            sessionStore.append(
                    sessionId,
                    SessionEntry.of(
                            clock.millis(),
                            "tool",
                            output,
                            null,
                            outcome.callId(),
                            outcome.name(),
                            outcome.success() ? null : Map.of("is_error", true)));
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
            // record that this request produced reasoning: later, a truncation or an empty
            // completion after reasoning means the output budget was spent thinking, and a
            // context-identical retry would only re-derive the same thoughts (P3)
            lastRequestProducedReasoning = true;
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
     * Unconditional context-usage line rendered in every volatile session-state tail: the model always
     * sees the current usage estimate and the prune/compaction thresholds, whether or not the nudge
     * fires. Kept out of the stable system prefix so prompt caching is unaffected.
     */
    String contextUsageLine(final List<Message> messages, final int toolsTokens) {
        final var percent =
                Math.round(usageTracker.contextPercent(usageTracker.effectiveContext(messages, toolsTokens)) / 5d) * 5;
        return "Context usage: ~" + (int) percent + "% of the usable window (prune at "
                + configuration.pruning().thresholdPercent() + "%, compaction at "
                + configuration.compaction().thresholdPercent() + "%).\n";
    }

    /**
     * Soft context-usage nudge appended to the volatile session state (never to the stable prefix, so
     * prompt caching is unaffected). Only fires when {@code nudgeThresholdPercent} is non-zero, the
     * estimated usage is above it, the estimate is large enough (see {@code nudgeMinTokens}) and at
     * least {@code nudgeIterationThreshold} iterations ran since the last user message. The wording
     * escalates once usage reaches the prune threshold.
     */
    String nudgeBlock(final List<Message> messages, final int toolsTokens) {
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
        final var tokens = usageTracker.effectiveContext(messages, toolsTokens);
        if (tokens < minNudgeTokens()) {
            return "";
        }
        final var percent = (int) Math.round(usageTracker.contextPercent(tokens));
        if (percent < threshold) {
            return "";
        }
        final var urgent = percent >= p.thresholdPercent();
        final var template = urgent ? p.nudgeUrgentTemplate() : p.nudgeTemplate();
        final var resolved = template == null || template.isBlank()
                ? urgent
                        ? "Context is at ~%USAGE%% and above the prune threshold (%PRUNE%%). Do not start new work - %ACTIONS% now to avoid the hard compaction wall."
                        : "Context is at ~%USAGE%% of the usable window. Prefer wrapping this step up, or compact soon."
                : template;
        return "\n<nudge>"
                + resolved.replace("%USAGE%", String.valueOf(percent))
                        .replace("%PRUNE%", String.valueOf(p.thresholdPercent()))
                        .replace(
                                "%COMPACT%",
                                String.valueOf(configuration.compaction().thresholdPercent()))
                        .replace("%ACTIONS%", manageContextActions())
                + "</nudge>";
    }

    /**
     * Effective absolute-token floor below which the nudge is suppressed: the smaller of 90% of the
     * usable window and {@code nudgeMinTokens}, so small-window providers are only nudged close to the
     * compaction wall while large ones get an absolute cap; 0 disables the floor (the percentage then
     * remains the only usage gate).
     */
    private int minNudgeTokens() {
        final var configured = configuration.pruning().nudgeMinTokens();
        if (configured <= 0) {
            return 0;
        }
        return (int) Math.min(usageTracker.usableWindow() * 0.9, configured);
    }

    private String manageContextActions() {
        return configuration.tools().manageContext() != null
                        && configuration.tools().manageContext().enabled()
                ? "run manage_context with action=prune or action=compact"
                : "interrupt with a short summary of the remaining steps";
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
