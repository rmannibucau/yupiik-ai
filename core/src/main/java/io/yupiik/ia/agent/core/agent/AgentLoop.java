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
import io.yupiik.ia.agent.core.configuration.ModelVariantConfiguration;
import io.yupiik.ia.agent.core.configuration.PlateauFeedbackMode;
import io.yupiik.ia.agent.core.context.CompactionResult;
import io.yupiik.ia.agent.core.context.ContextManager;
import io.yupiik.ia.agent.core.context.ContextView;
import io.yupiik.ia.agent.core.context.TokenEstimator;
import io.yupiik.ia.agent.core.context.UsageTracker;
import io.yupiik.ia.agent.core.events.HookResult;
import io.yupiik.ia.agent.core.events.ModelSwitchedEvent;
import io.yupiik.ia.agent.core.events.ModelSwitchingEvent;
import io.yupiik.ia.agent.core.events.SessionEndedEvent;
import io.yupiik.ia.agent.core.events.ToolUsePreEvent;
import io.yupiik.ia.agent.core.events.UserPromptSubmitEvent;
import io.yupiik.ia.agent.core.events.hooks.EventContextBuilder;
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
import io.yupiik.ia.agent.core.security.OutboundLlmRequest;
import io.yupiik.ia.agent.core.session.SessionEntry;
import io.yupiik.ia.agent.core.session.SessionStore;
import io.yupiik.ia.agent.core.shell.ThinkTagSplitter;
import io.yupiik.ia.agent.core.snapshot.SnapshotStore;
import io.yupiik.ia.agent.core.todo.TodoStore;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.tools.ToolExecutionService;
import io.yupiik.ia.agent.core.tools.ToolRegistry;
import io.yupiik.ia.agent.core.tools.ToolResult;
import io.yupiik.ia.agent.core.wiring.PrecisionDurationMarkerProvider;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import io.yupiik.ia.agent.core.wiring.SessionAwareLogRecord;
import io.yupiik.llm.client.ContextWindowExceededException;
import io.yupiik.llm.client.LlmHttpFailureException;
import io.yupiik.llm.client.model.LlmStreamEvent;
import io.yupiik.llm.client.model.LlmStreamEvent.End;
import io.yupiik.llm.client.model.LlmStreamEvent.ReasoningDelta;
import io.yupiik.llm.client.model.LlmStreamEvent.ReasoningItem;
import io.yupiik.llm.client.model.LlmStreamEvent.TextDelta;
import io.yupiik.llm.client.model.Usage;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

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
     * Upper bound on the assistant tool call ids a single block can carry before {@link
     * #repairInterruptedToolCalls} stops tracking them: beyond this a corrupted/oversized assistant block
     * is impossible to heal anyway, so the repair conservatively leaves it untouched rather than blowing up.
     */
    private static final int MAX_REPAIR_PENDING_TOOL_IDS = 256;

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

    /** One-shot nudge sent when the doom-loop guard refused a repeated state-changing/bash call. */
    static final String DOOM_LOOP_NUDGE_PROMPT =
            "You just repeated the same bash/state-changing call with the same arguments, and it returned"
                    + " the same already-known result again. Do not re-issue it: it adds no information."
                    + " Change approach - use a different command or tool, narrow or widen the query, or"
                    + " state exactly what is still missing instead of repeating the call.";

    /**
     * One-shot nudge injected when the model has spent llm.explorationNudgeRounds consecutive
     * informative read-only rounds exploring without answering. Complements the plateau guard
     * (which only catches re-reading already-seen targets): a spiral of novel reads is
     * informative every round, so only a wrap-up directive stops it.
     */
    static final String EXPLORATION_NUDGE_PROMPT =
            "You have spent several consecutive rounds exploring the codebase with read-only tools."
                    + " You have enough context: stop expanding the survey now and deliver the answer."
                    + " Synthesize what you already collected; only call another tool if a single,"
                    + " decisive piece of information is strictly missing.";

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
    private final ToolCallCoordinator toolCallCoordinator;
    private final ModelRoundProcessor modelRoundProcessor;
    private final CodingAgentConfiguration configuration;
    private final PermissionService permissionService;
    private final UsageTracker usageTracker;
    private final io.yupiik.ia.agent.core.usage.QuotaSnapshotStore quotaStore;
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
    private final PrecisionDurationMarkerProvider precisionDurationMarker;
    private final TurnState turnState = new TurnState();
    private final SnapshotStore snapshotStore;
    private final TodoStore todoStore;
    private final ExecutorService executor;
    private final UserFeedbackService userFeedbackService;
    private final String subagentId;
    private final String subagentName;
    /** Null means this is a regular agent turn; a non-null set is a subagent execution ceiling. */
    private volatile Set<String> allowedTools;

    private volatile Integer maxIterationsCap;
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
     * The session's resolved working/project directory, set once by
     * {@code SessionRuntimeFactory}. Null means the process default
     * ({@code configuration.resolveProjectDir()}). Injected into every per-turn
     * {@link ToolContext} so file-rooted tools resolve paths against the session's
     * root. A mid-session change rebuilds the runtime rather than mutating this field.
     */
    private volatile java.nio.file.Path sessionWorkdir;
    /** Set when the loop/session is closed so {@link #close()} fires the SessionEnded hook only once. */
    private volatile boolean closed;
    /** Effective agent of the current turn (set at {@code doRun} start), used for identity capture. */
    private volatile String currentAgentName;
    /**
     * Per-session model selection (model/variant/sampling). Explicitly passed to every
     * {@link ChatClient} call made by this loop's turns so concurrent tabs never share
     * a selection. Plain holder - no scope, no thread-local.
     */
    private volatile ChatSettings chatSettings = ChatSettings.EMPTY;

    /**
     * Profile-level model tuning installed by {@code SubagentRunner} when it launches a subagent on a
     * dedicated loop, applied to the wire request alongside the run settings. {@code skipConfiguredAgentModel}
     * tells {@code doRun} not to let the profile's own model replace the already-resolved subagent
     * selection (the profile only contributes its tuning, not its model, on the isolated child loop).
     */
    private volatile ModelVariantConfiguration agentModelTuning;

    private volatile boolean skipConfiguredAgentModel;
    /**
     * Per-turn pause listener surfacing provider throttles and retry backoffs to the owning
     * tab. It is handed to each {@link ChatClient} call instead of being installed globally:
     * without it the UI would only show a spinner while the resilience layer can silently
     * wait up to minutes between attempts.
     */
    private volatile Consumer<PauseEvent> pauseListener;
    /** Per-tool-call cancellation handles, keyed by the tool-call ordinal (the TUI card #N). */
    private final ConcurrentHashMap<Long, AtomicBoolean> runningToolCancels = new ConcurrentHashMap<>();
    /** Next ordinal handed to a started tool call, shared with ConversationTranscript's numbering. */
    private final AtomicLong toolCallOrdinal = new AtomicLong();

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

    /**
     * Whether the last completed LLM request produced reasoning tokens (a {@code ReasoningDelta}
     * was observed before its {@code End}): an empty or truncated completion after reasoning means
     * the model spent its whole output budget thinking, and re-asking the same context would only
     * re-derive the same reasoning. Reset before each request, flipped by {@link #dispatch}, read
     * by the truncation/empty-completion branches.
     */

    /**
     * Streaming splitter for models that inline their reasoning in the text channel (GLM/Qwen /
     * deepseek-flash served with {@code think} tags inside {@code content} rather than a dedicated
     * {@code reasoning_content}). Every wire {@code TextDelta} is fed through it and emitted as
     * pre-classified {@link AgentEvent.ThinkingDelta} / {@link AgentEvent.TextDelta} events, so ALL
     * frontends (TUI, web SSE, subagent transcripts) receive the same classification from this one
     * shared parser - the web frontend never has to re-parse. Reset per stream attempt (a retry is a
     * fresh generation). Null-safe no-op for providers that stream reasoning through the dedicated
     * channel (a clean {@code content} passes straight through as visible).
     */
    private ThinkTagSplitter.Parser inlineSplitter;

    /**
     * Reserved {@code others} key on a persisted assistant {@link Message} carrying the reasoning
     * text produced alongside the answer (dedicated channel or inline think-tags). Stored only for
     * replay rendering ({@code WebReplay} reconstructs {@code thinking-delta} events from it); never
     * sent to the LLM - {@code others} is stripped at every wire boundary.
     */
    static final String THINKING_KEY = "thinking";

    /**
     * Reserved {@code others} key on a persisted assistant {@link Message} carrying the opaque reasoning
     * items ({@code id}, {@code encrypted_content}, {@code summary}) for multi-turn reasoning replay.
     * The blob is decrypted only server-side; it lives here so it survives the session store round-trip
     * and is re-emitted as a {@code type:reasoning} input item on the next request. Never sent to the
     * LLM as visible content, and never displayed - {@code others} is stripped at every wire boundary.
     */
    static final String REASONING_ITEMS_KEY = "reasoningItems";

    /** Reasoning text accumulated for the current LLM request, attached to the persisted message. */
    private final StringBuilder accumulatedThinking = new StringBuilder();

    /**
     * Opaque reasoning items ({@code id} + {@code encryptedContent}) emitted for the current LLM request,
     * captured for multi-turn replay (the blob is decrypted only server-side; it is never displayed).
     * Reset per request (a fresh generation drops the previous request's blobs).
     */
    private final List<ReasoningItem> accumulatedReasoningItems = new ArrayList<>();

    /** Resets the inline-reasoning splitter for a fresh stream attempt (a retry is a new generation). */
    private void resetInlineSplitter() {
        inlineSplitter = new ThinkTagSplitter().create();
        accumulatedThinking.setLength(0);
        accumulatedReasoningItems.clear();
    }

    /**
     * Feeds a wire {@code TextDelta} through the inline-reasoning splitter and emits the classified
     * segments as separate {@link AgentEvent.ThinkingDelta} / {@link AgentEvent.TextDelta} events.
     * When the model streams reasoning through a dedicated channel the splitter sees a clean
     * {@code content} and emits a single visible {@code TextDelta} - a no-op aside from the one
     * extra parser pass.
     */
    private void dispatchTextDelta(final String text) {
        final var parsed = inlineSplitter == null ? null : inlineSplitter.feed(text);
        // an empty return only happens when the whole delta was absorbed into the parser's bounded
        // hold-tail (a candidate delimiter fragment): emit nothing now, the content surfaces when the
        // parser emits it as part of a later feed/flush. Emitting the raw text here would both leak a
        // partial think-tag AND duplicate the tail once the parser drains it.
        if (parsed == null || parsed.isEmpty()) {
            return;
        }
        emitInlineSegments(parsed);
    }

    /** Flushes the bounded hold-tail of the inline splitter at stream end so no answer tail is lost. */
    private void dispatchInlineSplitterFlush() {
        if (inlineSplitter == null) {
            return;
        }
        emitInlineSegments(inlineSplitter.flush());
    }

    /**
     * Emits the classified segments from the inline-reasoning splitter as {@code ThinkingDelta} /
     * {@code TextDelta} events, accumulating thinking text. A null element (a defensive guard against an
     * unexpected splitter edge case) is skipped rather than NPE-ing mid-stream, so it can never be
     * misdiagnosed as a transport failure and retried pointlessly.
     */
    private void emitInlineSegments(final List<ThinkTagSplitter.Segment> segments) {
        for (final var segment : segments) {
            if (segment == null) {
                continue;
            }
            if (segment.channel() == ThinkTagSplitter.Channel.THINKING) {
                accumulatedThinking.append(segment.text());
                emit(thinkingDelta(segment.text()));
            } else {
                emit(textDelta(segment.text()));
            }
        }
    }

    /**
     * Whether the turn just ended on a real, non-empty model reply (a {@code TurnDone} with
     * content was emitted). Set by {@link #emitTurnMetrics} when content is non-null and
     * non-blank; every other terminal path (cancel, interrupt, hard stop, empty completion,
     * error) leaves it false. Gates the end-of-turn memory extraction in {@code doRun}'s
     * finally: only a turn the user actually saw an answer from is worth mining.
     */
    private volatile boolean lastTurnEndedWithContent;

    /**
     * Per-session count of completed user turns (incremented in {@code doRun}'s finally when
     * the turn had a real user prompt). Drives the {@code memory.extractEveryTurns} cadence:
     * extraction runs when ({@code turnCount % extractEveryTurns == 0} with
     * {@code extractEveryTurns > 0}) or when a todo completed this turn. Resumed/initial turns
     * without a user message do not advance the counter.
     */
    private int turnCount;

    /**
     * Snapshot of the finished-todo set (content of items in completed/cancelled status) at the
     * end of the previous user turn, used to detect that at least one todo transitioned into
     * finished during the current turn (membership diff - mid-turn todo additions cannot fake a
     * completion). Reset to empty on a new session/tab.
     */
    private Set<String> lastFinishedTodos = Set.of();

    /**
     * Doom-loop breaker state for the current turn: counts repeated tool calls (normalized args)
     * and remembers the last output per signature for the no-progress termination. Created fresh
     * per turn, mutated on the loop thread only.
     */
    private ToolRepeats repeats = new ToolRepeats(0);

    /** Consecutive tool rounds with no new information (reset on any informative round), see P2. */
    private int noProgressRounds;

    /**
     * Exploration-plateau breaker state for the current turn: tracks which normalized paths (and
     * bash command fingerprints) the turn already covered with read-only tools, so a model that
     * keeps re-reading the same files/results with novel-but-redundant calls can be stopped instead
     * of burning the iteration window (see the plateauRounds cascade).
     */
    private final Set<String> seenFiles = ConcurrentHashMap.newKeySet();

    /** Consecutive read-only rounds re-covering only already-seen files (reset on any informative round or write). */
    private int plateauRounds;

    /** Whether the plateau nudge (plateauFeedback nudge_1/nudge_2) was already injected this turn. */
    private boolean plateauNudged;

    /** Whether the one-shot exploration nudge (EXPLORATION_NUDGE_PROMPT) was already injected this turn. */
    private boolean explorationNudged;

    /** Consecutive informative read-only rounds this turn without an answer (drives the exploration nudge). */
    private int explorationRounds;

    /** Whether the one-shot doom-loop nudge (DOOM_LOOP_NUDGE_PROMPT) was already injected this turn. */
    private boolean doomLoopNudged;

    /**
     * The last {@code estimatedContextTokens} a {@link AgentEvent.UsageUpdated} carried, so a
     * quota-only push at provider switch or {@code /usage} preserves the live context gauge instead
     * of resetting it to zero (the push has no message list to recompute context from).
     */
    private volatile int lastUsageContextTokens = -1;

    /**
     * The last {@code contextPercent} a {@link AgentEvent.UsageUpdated} carried (see
     * {@link #lastUsageContextTokens}).
     */
    private volatile double lastUsageContextPercent = -1;

    /**
     * Names of the tools the registry serves in build mode (the unfiltered {@code toWireTools()} list),
     * frozen once per {@code loop}. Plan mode hides EDIT tools from that list; a call to an EDIT tool that
     * IS served (present here) is plan-hidden and rejected at dispatch instead of reaching the approval gate.
     * Tools never served (empty/bare test registries) are not treated as plan-hidden, so they keep executing.
     */
    private volatile Set<String> servedToolNames;

    public AgentLoop(
            final ChatClient chatClient,
            final ToolRegistry toolRegistry,
            final ToolExecutionService toolExecutionService,
            final CodingAgentConfiguration configuration,
            final PermissionService permissionService,
            final UsageTracker usageTracker,
            final io.yupiik.ia.agent.core.usage.QuotaSnapshotStore quotaStore,
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
            final ExecutorService executor) {
        this(
                chatClient,
                toolRegistry,
                toolExecutionService,
                configuration,
                permissionService,
                usageTracker,
                quotaStore,
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
                null,
                new PrecisionDurationMarkerProvider());
    }

    AgentLoop(
            final ChatClient chatClient,
            final ToolRegistry toolRegistry,
            final ToolExecutionService toolExecutionService,
            final CodingAgentConfiguration configuration,
            final PermissionService permissionService,
            final UsageTracker usageTracker,
            final io.yupiik.ia.agent.core.usage.QuotaSnapshotStore quotaStore,
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
            final ExecutorService executor,
            final String subagentId,
            final String subagentName) {
        this(
                chatClient,
                toolRegistry,
                toolExecutionService,
                configuration,
                permissionService,
                usageTracker,
                quotaStore,
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
                null,
                new PrecisionDurationMarkerProvider());
    }

    protected AgentLoop() {
        this(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null);
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
            final io.yupiik.ia.agent.core.usage.QuotaSnapshotStore quotaStore,
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
            final ExecutorService executor,
            final String sessionId,
            final String subagentId,
            final String subagentName,
            final UserFeedbackService userFeedbackService,
            final PrecisionDurationMarkerProvider precisionDurationMarker) {
        this.chatClient = chatClient;
        this.toolRegistry = toolRegistry;
        this.toolExecutionService = toolExecutionService;
        this.toolCallCoordinator = new ToolCallCoordinator();
        this.modelRoundProcessor = new ModelRoundProcessor(chatClient, configuration, precisionDurationMarker);
        this.configuration = configuration;
        this.permissionService = permissionService;
        this.usageTracker = usageTracker;
        this.quotaStore = quotaStore;
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
        this.precisionDurationMarker = precisionDurationMarker;
        this.snapshotStore = snapshotStore;
        this.todoStore = todoStore;
        this.executor = executor;
        this.sessionId = sessionId;
        this.subagentId = subagentId;
        this.subagentName = subagentName;
        this.userFeedbackService = userFeedbackService;
    }

    /**
     * This loop's per-session model selection. The session runtime hands it to the slash
     * commands and the UI so {@code /model}, {@code /variant} and the sampling overrides
     * mutate only this tab.
     */
    public ChatSettings chatSettings() {
        return chatSettings;
    }

    /** Applies a new selection value (mid-turn changes take effect from the next round/tool call). */
    public void updateChatSettings(final ChatSettings value) {
        final var alt = value == null ? ChatSettings.EMPTY : value;
        final var current = chatSettings;
        // the model-switch hook is emitted before the swap so a forbid vetoes the switch (the turn keeps the old model)
        if (emitter != null) {
            final var hookResult = new HookResult();
            emitter.emit(new ModelSwitchingEvent(
                    new EventContextBuilder(configuration)
                            .build(permissionService, current, currentSessionId, subagentId, subagentName, null),
                    current == null || current.model() == null ? null : current.model(),
                    alt.model(),
                    alt.variant(),
                    hookResult));
            if (hookResult.forbidden()) {
                emit(AgentEvent.Notice.warn(
                        "Model switch rejected" + (hookResult.reason() == null ? "" : ": " + hookResult.reason()),
                        currentSessionId));
                return;
            }
        }
        this.chatSettings = alt;
        if (emitter != null) {
            emitter.emit(new ModelSwitchedEvent(
                    new EventContextBuilder(configuration)
                            .build(permissionService, alt, currentSessionId, subagentId, subagentName, null),
                    current == null || current.model() == null ? null : current.model(),
                    alt.model(),
                    alt.variant()));
        }
        // a provider switch must re-attach this session's quota snapshot immediately so the frontend
        // gauge (freshly refreshed at switch, then TTL-governed) shows the *new* provider's usage at
        // switch time instead of keeping the old provider's cached quota until the next turn, and a
        // fully-reached quota is visible. Only a provider change pushes a quota event: a model-only
        // switch reuses the already-attached snapshot and must not clobber the live context gauge.
        if (providerChanged(current, alt)) {
            if (quotaStore != null) {
                quotaStore.attach(currentSessionId, alt);
            }
            pushUsageQuota();
        }
    }

    /** True when the resolved provider id differs between two selections (blank = process default). */
    private static boolean providerChanged(final ChatSettings before, final ChatSettings after) {
        final var a = before == null ? "" : normalizeProvider(before.providerId());
        final var b = after == null ? "" : normalizeProvider(after.providerId());
        return !a.equals(b);
    }

    private static String normalizeProvider(final String id) {
        return id == null || id.isBlank() ? "" : id.strip().toLowerCase(Locale.ROOT);
    }

    /** Sets optional profile-level model tuning for this loop (used by isolated subagent loops). */
    public void agentModelTuning(final ModelVariantConfiguration value) {
        this.agentModelTuning = value;
    }

    /** Prevents a subagent profile from replacing a model explicitly selected for this run. */
    public void skipConfiguredAgentModel(final boolean value) {
        this.skipConfiguredAgentModel = value;
    }

    /**
     * Releases the session and fires the {@code yca.hooks.SessionEnded} hook once. Idempotent: the hook fires for the
     * first call only (a TUI may dispose a tab and later re-close, or the CLI and process shutdown may overlap).
     */
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (emitter != null) {
            final var id = currentSessionId != null ? currentSessionId : this.sessionId;
            emitter.emit(new SessionEndedEvent(
                    new EventContextBuilder(configuration)
                            .build(permissionService, chatSettings(), id, subagentId, subagentName, null),
                    id,
                    null));
        }
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
     * Background (auxiliary) LLM spend: calls and tokens that never appear as conversation turns
     * (memory extraction, compaction summarizer, sub-agent fold-in). The middleware measures these
     * too, so exposing them lets the app self-report match the middleware and makes background cost
     * visible instead of hidden. Returns all zeros when no usage tracker is wired.
     */
    public AuxiliaryUsage auxiliaryUsage() {
        if (usageTracker == null) {
            return AuxiliaryUsage.ZERO;
        }
        return new AuxiliaryUsage(
                usageTracker.auxiliaryRequests(),
                usageTracker.auxiliaryPromptTokens(),
                usageTracker.auxiliaryCompletionTokens(),
                usageTracker.auxiliaryCacheReadTokens(),
                usageTracker.auxiliaryFreshTokens());
    }

    /** Background (auxiliary) LLM spend counters, captured read-only for metrics/reporting. */
    public record AuxiliaryUsage(
            long calls, long promptTokens, long completionTokens, long cacheReadTokens, long freshTokens) {
        static final AuxiliaryUsage ZERO = new AuxiliaryUsage(0, 0, 0, 0, 0);
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
        final var tools = wireTools();
        final var toolsTokens = toolTokens(tools);
        final var history = sessionStore.load(sessionId);
        final var prompt = lastUserPrompt(history);
        final var messages = new ArrayList<Message>();
        messages.add(Message.system(systemPromptBuilder.build(prompt, null) + snapshotBlock(sessionId)));
        messages.addAll(history);
        appendStateIfChanged(sessionId, messages, prompt, toolsTokens);
        final var resolved = resolvedModel();
        return new LlmPayload(
                sessionId,
                resolved == null ? "" : resolved.model(),
                List.copyOf(messages),
                List.copyOf(tools),
                "auto",
                toolsTokens);
    }

    /**
     * Synchronous forced memory extraction of the persisted transcript, used when the session
     * stops (tab close, headless run end). The end-of-turn hook already covered the delta
     * asynchronously while the app was idle; this is normally a watermark no-op and stays as
     * the deterministic last-resort for sessions closed before their first turn ever finished.
     *
     * @return {@code true} when an extraction pass ran, {@code false} when there was nothing new.
     */
    public boolean forceExtractNow(final List<Message> messages, final ChatSettings settings) {
        return memoryExtractor != null && memoryExtractor.forceExtractNow(messages, settings);
    }

    /**
     * Builds the wire tool list for the current call, hiding the file-mutating
     * tools (EDIT category) while the agent runs in PLAN mode: the model then
     * simply cannot call them - the strongest form of "plan must not build".
     */
    private List<Tool> wireTools() {
        final var toolCeiling = effectiveAllowedTools();
        final var tools = toolCeiling == null
                ? toolRegistry.toWireTools()
                : toolRegistry.toWireTools(tool -> toolCeiling.contains(tool.name()));
        if (permissionService == null || permissionService.mode() != AgentMode.PLAN) {
            return tools;
        }
        return tools.stream()
                .filter(t -> {
                    final var embedded = toolRegistry.tool(t.function().name());
                    return embedded == null || embedded.category() != ToolCategory.EDIT;
                })
                .toList();
    }

    private Set<String> effectiveAllowedTools() {
        final var profileTools = agentProvider == null ? null : agentProvider.allowedTools(currentAgentName);
        final var runTools = allowedTools;
        if (profileTools == null) {
            return runTools;
        }
        if (runTools == null) {
            return profileTools;
        }
        final var intersection = new java.util.HashSet<>(profileTools);
        intersection.retainAll(runTools);
        return Set.copyOf(intersection);
    }

    /** Installs a child run's tool ceiling before its first request. Null retains the registry's full set. */
    public void allowedTools(final Set<String> names) {
        this.allowedTools = names == null ? null : Set.copyOf(names);
    }

    /** Sets a positive upper bound for this agent run; model and provider caps may still lower it. */
    public void maxIterationsCap(final int cap) {
        if (cap < 1) {
            throw new IllegalArgumentException("maxIterationsCap must be positive");
        }
        this.maxIterationsCap = cap;
    }

    /** Whether the most recent turn stopped at its resolved iteration cap. */
    public boolean iterationLimitReached() {
        return turnState.iterationLimitReached;
    }

    public String run(
            final String prompt,
            final String agentName,
            final String sessionId,
            final List<Message> history,
            final BooleanSupplier cancelled) {
        return run(prompt, agentName, sessionId, history, cancelled, null, null);
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
        return run(prompt, agentName, sessionId, history, cancelled, queuedPrompt, null);
    }

    public String run(
            final String prompt,
            final String agentName,
            final String sessionId,
            final List<Message> history,
            final BooleanSupplier cancelled,
            final Supplier<String> queuedPrompt,
            final String explicitModel) {
        return doRun(prompt, prompt, agentName, sessionId, history, cancelled, queuedPrompt, explicitModel);
    }

    /** The per-session provider/model selection rides the session's {@link ChatSettings}; make the tracker see it. */
    private void attachSessionSettings(final ChatSettings settings) {
        if (usageTracker != null) {
            usageTracker.attach(settings);
        }
        if (quotaStore != null) {
            quotaStore.attach(currentSessionId, settings);
        }
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
        return doRun(lastUserPrompt(history), null, agentName, sessionId, history, cancelled, queuedPrompt, null);
    }

    public String resume(
            final String agentName,
            final String sessionId,
            final List<Message> history,
            final BooleanSupplier cancelled,
            final Supplier<String> queuedPrompt,
            final String explicitModel) {
        return doRun(
                lastUserPrompt(history), null, agentName, sessionId, history, cancelled, queuedPrompt, explicitModel);
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
            final Supplier<String> queuedPrompt,
            final String explicitModel) {
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
        final var contextualSettings = chatSettings;
        final var contextualAgentTuning = agentModelTuning;
        final var configuredAgentModel = agent == null || agent.model() == null
                ? null
                : AgentModelRouting.refName(agent.model().name());
        final var useProfileModel = configuredAgentModel != null;
        final var effectiveModel = EffectiveAgentModel.resolve(
                providerManager != null && contextualSettings.providerId() != null ? providerManager : null,
                contextualSettings,
                skipConfiguredAgentModel ? null : agent,
                explicitModel,
                null);
        final var runSettings =
                !skipConfiguredAgentModel && useProfileModel ? effectiveModel.settings() : contextualSettings;
        final var runTuning =
                !skipConfiguredAgentModel && useProfileModel && (explicitModel == null || explicitModel.isBlank())
                        ? agent.model()
                        : contextualAgentTuning;
        structuralCodeGraph.ensureReadyAsync();
        // fresh per-turn loop-guard state: repeat counts must not leak across turns
        // (the threshold cascade is resolved here, once per turn, like the plateau guards)
        repeats = new ToolRepeats(resolvedRepeatSuccessThreshold(resolvedModel(runSettings)));
        noProgressRounds = 0;
        seenFiles.clear();
        plateauRounds = 0;
        plateauNudged = false;
        explorationRounds = 0;
        explorationNudged = false;
        doomLoopNudged = false;
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
        if (userMessage != null && acceptInitialPrompt(userMessage, sessionId)) {
            messages.add(Message.user(userMessage));
            sessionStore.append(
                    sessionId,
                    SessionEntry.of(clock.millis(), "user", userMessage, null, null, null, identityOthers(agentName)));
        }
        statusReporter.setSink(message -> emitter.emit(status(message)));
        // surface provider throttles and retry backoffs: without this the UI only shows a
        // spinner while the resilience layer can silently wait up to minutes between attempts.
        // Pauses are transient feedback rendered in the status-strip notification slot (right
        // side) without touching the leading agent state; the wait is also accumulated so the
        // throughput metric ignores it.
        this.pauseListener = pause -> {
            turnState.llmPausedMs += Math.max(0L, pause.durationMs());
            emit(new AgentEvent.Pause(
                    pause.kind() == PauseEvent.Pause.THROTTLE
                            ? AgentEvent.Pause.Kind.THROTTLE
                            : AgentEvent.Pause.Kind.RETRY,
                    pause.message(),
                    pause.durationMs(),
                    currentSessionId,
                    subagentId,
                    subagentName));
        };
        // a resume right after an iteration-cap stop is a fresh window: make it visible instead of
        // looking like one seamless turn in the transcript
        if (userMessage == null && history != null) {
            emitResumeAfterCapNotice(history, sessionId);
        }

        try {
            return loop(
                    sessionId,
                    messages,
                    promptForBuilding,
                    cancelled,
                    queuedPrompt,
                    useProfileModel,
                    runSettings,
                    runTuning);
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
            LOGGER.log(SessionAwareLogRecord.of(LOGGER, currentSessionId, Level.SEVERE, "agent turn failed", e));
            emit(error("unexpected failure: " + e));
            return null;
        } finally {
            pauseListener = null;
            statusReporter.setSink(null);
            // End-of-turn memory: best-effort, asynchronous, only when the turn ended where the
            // user sees it - a real (non-empty) reply. Empty completions, hard stops (no progress,
            // plateau, max iterations), interrupts and uncaught failures all leave
            // lastTurnEndedWithContent false: there is nothing durable to mine (the excerpt gate
            // would filter empty deltas anyway), and extraction while the app is already idle
            // waiting for the next input means the close paths never need to block on it.
            if (memoryExtractor != null && lastTurnEndedWithContent) {
                // Mode-driven extraction cadence. The prompt prefix is built once per session and
                // never re-read mid-session, so the memory.mode governs only the durability cadence,
                // not what the current turn sees. LAZY extracts only at a natural milestone (all
                // todos done) or session close; FULL keeps the every-N-turns cadence plus the
                // todo-completion flush; AUTO picks LAZY when the memory model is the main model
                // (extraction is then expensive), FULL when it routes to a distinct/cheaper model.
                final var mode = configuration.memory().mode();
                final var regimeLazy =
                        switch (mode) {
                            case FULL -> false;
                            case LAZY -> true;
                            case AUTO -> providerManager != null && providerManager.memoryUsesMainModel(runSettings);
                        };
                final boolean eligible;
                if (regimeLazy) {
                    // LAZY: extract only when all todos are done (empty of pending items;
                    // completed/cancelled allowed). A resume counts as a user turn here too.
                    eligible = userMessage != null && !hasPendingTodos();
                } else {
                    // FULL/AUTO-full: cadence + todo-completion gating kept as-is (extract every
                    // extractEveryTurns user turns, or immediately when a todo just finished).
                    if (userMessage != null) {
                        turnCount++;
                        final var cfg = configuration.memory();
                        final var every = cfg.extractEveryTurns();
                        final var finishedNow = finishedTodos();
                        final var todoFlush = cfg.extractOnTodosDone()
                                && !finishedNow.isEmpty()
                                && !lastFinishedTodos.containsAll(finishedNow);
                        lastFinishedTodos = finishedNow;
                        final var cadence = every > 0 && turnCount % every == 0;
                        eligible = cadence || todoFlush;
                    } else {
                        // no user message at all (empty replay): keep the legacy every-turn behavior
                        // so the pending delta is still mined when eligible
                        eligible = true;
                    }
                }
                memoryExtractor.asyncExtractNow(messages, runSettings, eligible);
            }
        }
    }

    /**
     * Whether the todo store still has at least one pending item (not completed/cancelled). The
     * LAZY memory regime extracts only when this is false - a natural milestone with no open task.
     * An empty or absent todo list (or a store that throws) reports no pending items.
     */
    private boolean hasPendingTodos() {
        if (todoStore == null) {
            return false;
        }
        try {
            for (final var item : todoStore.all()) {
                if (!"completed".equals(item.status()) && !"cancelled".equals(item.status())) {
                    return true;
                }
            }
            return false;
        } catch (final RuntimeException e) {
            return false;
        }
    }

    /**
     * Content of the finished-todo set for the memory cadence: contents of items whose status is
     * completed or cancelled (membership-based so mid-turn todo additions cannot fake a flush).
     */
    private Set<String> finishedTodos() {
        if (todoStore == null) {
            return Set.of();
        }
        try {
            final var done = new HashSet<String>();
            for (final var item : todoStore.all()) {
                if ("completed".equals(item.status()) || "cancelled".equals(item.status())) {
                    done.add(item.content());
                }
            }
            return done;
        } catch (final RuntimeException e) {
            return Set.of();
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
    private ToolContext toolContext(
            final BooleanSupplier cancelled,
            final BooleanSupplier callCancelled,
            final long ordinal,
            final ChatSettings settings) {
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
                cancelled,
                settings,
                callCancelled,
                sessionWorkdir,
                ordinal,
                effectiveAllowedTools());
    }

    /**
     * The session's resolved working/project directory, frozen once when the session runtime is
     * created. Null means "use the process default ({@code configuration.resolveProjectDir()})".
     * It is injected into every per-turn {@link ToolContext} so file-rooted tools resolve paths
     * against the session's root. Set once by {@code SessionRuntimeFactory}; a mid-session change
     * (web {@code session.setProject}) rebuilds the runtime rather than mutating this field.
     */
    public void sessionWorkdir(final java.nio.file.Path dir) {
        this.sessionWorkdir = dir;
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

    /** Builds a ToolStarted event with the given pre-assigned ordinal (the TUI card #). */
    private AgentEvent.ToolStarted toolStarted(final String name, final String args, final long ordinal) {
        return new AgentEvent.ToolStarted(name, args, ordinal, currentSessionId, subagentId, subagentName);
    }

    /**
     * Cancels the tool call with the given ordinal (the TUI's #{@code n} card): flips its per-call
     * probe so the running tool aborts on its next cancellation poll. Does nothing when the call
     * already finished. The turn itself keeps running - the aborting tool result is fed back to the
     * model like any other failure.
     */
    public void cancelTool(final long ordinal) {
        final var handle = runningToolCancels.get(ordinal);
        if (handle != null) {
            handle.set(true);
        }
    }

    /** Whether the tool call with the given ordinal is still running. */
    public boolean isToolRunning(final long ordinal) {
        return runningToolCancels.containsKey(ordinal);
    }

    private void registerRunningTool(final long ordinal) {
        runningToolCancels.put(ordinal, new AtomicBoolean());
    }

    private void registerRunningTool(final long ordinal, final AtomicBoolean cancel) {
        runningToolCancels.put(ordinal, cancel);
    }

    private void unregisterRunningTool(final long ordinal) {
        if (ordinal > 0) {
            runningToolCancels.remove(ordinal);
        }
    }

    /** Builds a ToolFinished event; the ordinal registration was already cleaned up at execution end. */
    private AgentEvent.ToolFinished toolFinished(
            final String name, final String output, final boolean success, final boolean pending, final long ordinal) {
        return new AgentEvent.ToolFinished(
                name, output, success, pending, ordinal, currentSessionId, subagentId, subagentName);
    }

    private AgentEvent.UsageUpdated usageUpdated(
            final io.yupiik.ia.agent.core.llm.Usage usage,
            final int estimatedContextTokens,
            final double contextPercent,
            final int lastRealPromptTokens,
            final double calibrationRatio,
            final String model,
            final AgentEvent.UsageUpdated.Quota quota) {
        return new AgentEvent.UsageUpdated(
                usage,
                estimatedContextTokens,
                contextPercent,
                lastRealPromptTokens,
                calibrationRatio,
                model,
                currentSessionId,
                subagentId,
                subagentName,
                quota);
    }

    private AgentEvent.Compacted compacted(final String kind, final String mode) {
        return compacted(List.of(kind), mode);
    }

    private AgentEvent.Compacted compacted(final List<String> kinds, final String mode) {
        return new AgentEvent.Compacted(kinds, mode, currentSessionId, subagentId, subagentName);
    }

    /**
     * Picks the most informative mode among the reductions merged into one checkpoint event:
     * {@code SUMMARY} (LLM summarizer ran) {@code > SHRUNK > TRUNCATE > CLEARED}. All the
     * zero-LLM strategies report {@code CLEARED}, so a prune/zero-LLM-only round stays
     * {@code CLEARED} while an LLM compaction makes the event carry {@code SUMMARY}.
     * The enum declaration order is NOT used (NONE then SUMMARY then TRUNCATE then CLEARED
     * then SHRUNK would rank SHRUNK above SUMMARY): the ranking is explicit here.
     */
    private static final Map<CompactionResult.Mode, Integer> MODE_RANK = Map.of(
            CompactionResult.Mode.CLEARED, 0,
            CompactionResult.Mode.TRUNCATE, 1,
            CompactionResult.Mode.SHRUNK, 2,
            CompactionResult.Mode.SUMMARY, 3);

    private static String bestMode(final List<CompactionResult.Mode> modes) {
        return modes.stream()
                .max(Comparator.comparingInt(m -> MODE_RANK.getOrDefault(m, -1)))
                .orElse(CompactionResult.Mode.CLEARED)
                .name();
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

    private AgentEvent.Notice notice(
            final AgentEvent.Notice.Level level,
            final String message,
            final String finishReason,
            final Throwable cause) {
        return new AgentEvent.Notice(level, message, finishReason, currentSessionId, subagentId, subagentName, cause);
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
                new AgentEvent.Throughput(
                        turnState.outputTokens,
                        turnState.reasoningTokens,
                        turnState.llmActiveMs,
                        turnState.llmReportedMs),
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
            final boolean useProfileModel,
            final ChatSettings runSettings,
            final ModelVariantConfiguration runTuning)
            throws IOException, InterruptedException {
        final var retryOnOverflow = configuration.llm().retryOnContextOverflow();
        // iteration window cap cascade: per-model behavior > per-provider tuning > global default;
        // the provider is the session's effective one (per-tab override or process default); only
        // these window caps are computed once here - the model is re-resolved for every request
        // below so a mid-turn /model or /provider switch applies from the next round.
        final var resolved = resolvedModel(runSettings);
        final var maxIterations = resolvedMaxIterations(resolved);
        final var plateauLimit = resolvedPlateauRounds(resolved);
        final var plateauFeedback = resolvedPlateauFeedback(resolved);
        final var explorationNudgeRounds = resolvedModelExplorationNudgeRounds(resolved);
        final var turnContinuesOnTextEnd = resolved == null || providerManager == null
                ? false
                : providerManager.turnContinuesOnTextEnd(resolved.provider(), resolved.model());
        final var maxConsecutiveCompactions = MAX_CONSECUTIVE_COMPACTIONS;
        final var tools = wireTools();
        final var toolsTokens = toolTokens(tools);
        // snapshot which tools the registry actually serves (unfiltered by mode): plan mode may hide an EDIT
        // tool from this turn's wire list, so a call to a served EDIT tool in plan mode is a hidden-tool call
        // to auto-reject (never gate). Bare/empty test registries simply serve nothing and stay unaffected.
        servedToolNames = toolRegistry.toWireTools().stream()
                .map(t -> t.function().name())
                .collect(java.util.stream.Collectors.toSet());
        var overflowRetried = false;
        // turns the pair old assistant tool-call/result history was healed at most once per turn, so a
        // tool-pairing 400 (Azure/litellm "tool_calls must be followed by tool messages") can be repaired
        // and retried once without risking an endless retry loop on a non-tool 400.
        var toolPairingRepaired = false;
        var consecutiveCompactions = 0;
        var autoNoticeEmitted = false;
        var truncationContinues = 0;
        var emptyCompletionRetries = 0;
        var toolsRanThisTurn = false;
        var toolDirectiveApplied = false;
        // a prompt typed while the loop was running was injected in this turn: the model answered
        // it, so its text-only end answers that message and must not be treated as "stopped early"
        var userInjected = false;
        final var staleReads = new StaleReads(readsFromHistory(messages));
        // heal interrupted/cancelled history before the first request: an assistant tool_use whose
        // result never landed (turn ended mid-tool via cancel/interrupt/error) must be answered by a
        // lost-result marker so Anthropic's tool_use -> tool_result contiguity rule holds on the wire.
        repairInterruptedToolCalls(sessionId, messages);

        // Reset turn-local counters and reasoning flags before starting this run.
        turnState.reset();
        final var turnStartNanos = precisionDurationMarker.now();
        lastTurnEndedWithContent = false;
        // A "window" is one bounded run of the round loop. Every todo auto-continuation starts a fresh
        // window with a new maxIterations budget - yca.todos.maxContinues bounds the number of windows -
        // while empty-completion and truncation retries keep sharing the current window's budget. Without
        // this one long turn would be capped at maxIterations (250 by default) regardless of how many
        // todo continuations remain, making large --todos-maxContinues values unreachable.
        windowLoop:
        while (!cancelled.getAsBoolean()) {
            // An unbounded window (maxIterations < 0) has no iteration budget: it runs until the turn
            // genuinely ends. A positive maxIterations bounds this window; every todo auto-continuation
            // starts a fresh window with a new maxIterations budget.
            for (var iteration = 0;
                    (maxIterations < 0 || iteration < maxIterations) && !cancelled.getAsBoolean();
                    iteration++) {
                if (contextView != null) {
                    contextView.publish(snapshot(messages, toolsTokens));
                }
                if (integrateQueuedPrompt(sessionId, messages, queuedPrompt)) {
                    userInjected = true;
                    continue;
                }
                // a finished sub-agent's result is pushed into this (parent) turn's context here, at the
                // round boundary, so the model's next round sees the child's completion inline.
                integrateSubagentDeliveries(sessionId, messages);
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
                // All reductions of this checkpoint (zero-LLM + prune + LLM compact) are reported as a
                // single merged Compacted event carrying the applied kinds, so the transcript shows one
                // "context <kinds> applied (<mode>)" line instead of one line per strategy.
                var compactedThisTurn = false;
                final var appliedKinds = new ArrayList<String>(4);
                var appliedModes = new ArrayList<CompactionResult.Mode>(2);
                if (contextManager.pruneNeeded(messages, toolsTokens)) {
                    appliedKinds.addAll(applyZeroLlmStrategies(messages));
                    if (!appliedKinds.isEmpty()) {
                        persistContext(sessionId, messages);
                        usageTracker.clearAnchor();
                        appliedModes.add(CompactionResult.Mode.CLEARED);
                    }
                    final var pruned = contextManager.prune(messages);
                    if (pruned.changed()) {
                        messages.clear();
                        messages.addAll(pruned.messages());
                        appliedKinds.add("prune");
                        appliedModes.add(pruned.mode());
                        persistContext(sessionId, messages);
                        compactedThisTurn = true;
                    }
                }
                if (contextManager.compactNeeded(messages, toolsTokens)) {
                    final var compacted = contextManager.compact(messages, false, null, 0, cancelled, runSettings);
                    if (compacted.changed()) {
                        messages.clear();
                        messages.addAll(compacted.messages());
                        appliedKinds.add("compact");
                        appliedModes.add(compacted.mode());
                        persistContext(sessionId, messages);
                        compactedThisTurn = true;
                    }
                }
                if (!appliedKinds.isEmpty()) {
                    emit(compacted(appliedKinds, bestMode(appliedModes)));
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
                appendStateIfChanged(sessionId, messages, prompt, toolsTokens);
                // snapshot of what goes on the wire, for /context llm-payload: taken here so it
                // carries the pruned/compacted list and the volatile tail, and copied because the
                // live list keeps growing (messages/tools are immutable, so a shallow copy is enough)
                // resolved (and tracked) per request: the window caps above stay the turn's ones but
                // the selection the provider sees is the session's current one, so a /model run
                // while busy lands on this round instead of only on the next turn
                final var requestSettings = useProfileModel && !skipConfiguredAgentModel ? runSettings : chatSettings;
                attachSessionSettings(requestSettings);
                final var model = requestModel(resolved, requestSettings);
                // secret redaction: let observer(s) (the default SecretRedactor, or custom
                // extensions) transform the outgoing messages before they reach the provider
                final var outbound = new OutboundLlmRequest(messages, requestSettings, sessionId);
                emitter.emit(outbound);
                final var wireMessages = outbound.messages();
                lastLlmPayload = new LlmPayload(
                        sessionId, model, List.copyOf(wireMessages), List.copyOf(tools), "auto", toolsTokens);
                final var request = new ChatCompletionRequest(
                                model, wireMessages, null, null, null, tools, "auto", null, null, null)
                        .withSessionId(sessionId)
                        .withSettings(requestSettings)
                        .withTuning(runTuning);
                final var usage = new io.yupiik.ia.agent.core.llm.Usage[] {io.yupiik.ia.agent.core.llm.Usage.EMPTY};
                final var finishReason = new String[] {null};
                Message assistant;
                final long llmStartNanos;
                final long pausedBeforeMs = turnState.llmPausedMs;
                try {
                    llmStartNanos = precisionDurationMarker.now();
                    assistant = modelRoundProcessor.stream(
                            request,
                            usage,
                            cancelled,
                            pauseListener,
                            delta -> dispatch(delta, usage, finishReason),
                            new ModelRoundProcessor.Observer() {
                                @Override
                                public void begin() {
                                    turnState.lastRequestProducedReasoning = false;
                                    resetInlineSplitter();
                                }

                                @Override
                                public void resetText() {
                                    // a retry is a fresh generation: rebuild the inline splitter (dropping the
                                    // failed attempt's stale, possibly half-consumed carry) and drop the stale
                                    // partial reasoning text + opaque blobs so nothing leaks into the persisted
                                    // message's thinking / reasoningItems
                                    resetInlineSplitter();
                                    emit(textReset());
                                }

                                @Override
                                public void retrying(final String message, final Throwable cause) {
                                    emit(notice(AgentEvent.Notice.Level.WARN, message, null, cause));
                                }

                                @Override
                                public void malformedToolArguments() {
                                    emit(
                                            notice(
                                                    AgentEvent.Notice.Level.WARN,
                                                    "Server rejected the request: malformed tool call arguments (parse_error.101). "
                                                            + "The arguments were kept as-is (repair failed or disabled); remove the "
                                                            + "corrupted call from the transcript or retry with the repair enabled."));
                                }

                                @Override
                                public void toolCallPairingRejected() {
                                    // Signalled by ModelRoundProcessor when it short-circuits a tool-pairing 400
                                    // (instead of wasting the generic retry budget on the same broken history). The
                                    // notice and the actual history repair are emitted/done by the catch(IOException)
                                    // below, which runs on the rethrown 400 and stays the single emission point.
                                }

                                @Override
                                public void paused(final long millis) {
                                    turnState.llmPausedMs += millis;
                                }
                            });
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
                    final var compacted = contextManager.compactForOverflow(messages, cancelled, runSettings);
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
                } catch (final IOException e) {
                    // Azure / litellm strict validators reject an assistant message whose tool_calls are
                    // not answered by a tool message carrying each tool_call_id ("An assistant message with
                    // 'tool_calls' must be followed by tool messages responding to each 'tool_call_id'. The
                    // following tool_call_ids did not have response messages: ..."), a hard 400 that hits
                    // "sometimes" when an interrupted/cancelled tool round left its result unlanded. Heal the
                    // in-loop history (insert synthetic lost-result markers, exactly like the loop-entry
                    // repair) and retry once; the model then sees an honest interrupted note instead of the
                    // turn dying. Bounded to one repair per turn so a non-tool 400 can never spin. The
                    // signature check is owned by ModelRoundProcessor (single source of truth) and, for the
                    // streaming path, already short-circuits the generic retry budget before it reaches here.
                    if (!toolPairingRepaired && isToolPairingRejection(e)) {
                        toolPairingRepaired = true;
                        emit(
                                notice(
                                        AgentEvent.Notice.Level.INFO,
                                        "Provider rejected the request: an assistant tool call had no matching tool result "
                                                + "(interrupted or cancelled tool round). Repairing the history and retrying once."));
                        repairInterruptedToolCalls(sessionId, messages);
                        continue;
                    }
                    throw e;
                }
                usageTracker.record(usage[0]);
                // inbound restore: replace provenance fakes in the assistant reply with the real
                // values (only what we emitted outbound; model-invented token shapes stay as-is)
                final var inbound = new io.yupiik.ia.agent.core.security.InboundLlmResponse(
                        assistant.content() instanceof String s ? s : str(assistant.content()), sessionId);
                emitter.emit(inbound);
                final var restored = inbound.restored();
                if (!restored.equals(str(assistant.content()))) {
                    assistant = new Message(
                            assistant.role(),
                            restored,
                            assistant.name(),
                            assistant.toolCalls(),
                            assistant.toolCallId(),
                            assistant.others());
                }
                // throughput: time spent generating, idle time excluded. A request refused because the
                // context window is too small contributes nothing (it is a preflight, not a generation).
                recordThroughput(
                        assistant,
                        usage[0],
                        (precisionDurationMarker.now() - llmStartNanos) / 1_000_000,
                        pausedBeforeMs);
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
                                identityAndThinking(currentAgentName)));
                emitUsage(messages, toolsTokens);

                // When the reply carries tool calls, do NOT absorb a queued prompt here (before the
                // tools run): injecting a plain user text message between the assistant tool_use and
                // its tool_result blocks would break Anthropic's strict pairing rule ("tool_use ids
                // were found without tool_result blocks immediately after"), a hard 400 on
                // Anthropic/Azure AI Gateway. Fall through to executeToolCalls first so the tool_result
                // blocks stay contiguous; the queued prompt is then absorbed at the next top-of-iteration
                // check (integrateQueuedPrompt at the loop head) once the tool round has landed.
                if ((assistant.toolCalls() == null || assistant.toolCalls().isEmpty())
                        && integrateQueuedPrompt(sessionId, messages, queuedPrompt)) {
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
                            final var changeStrategy =
                                    turnState.lastRequestProducedReasoning || truncationContinues >= 2;
                            messages.add(Message.user(
                                    changeStrategy ? TRUNCATION_SPLIT_PROMPT : TRUNCATION_CONTINUE_PROMPT));
                            // the reasoning flag was consumed: a retry of this request must not re-trigger
                            // the "spent thinking" branch again on the same generation
                            turnState.lastRequestProducedReasoning = false;
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
                            if (turnState.lastRequestProducedReasoning) {
                                turnState.lastRequestProducedReasoning = false;
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
                    // Codex-style round gate: models opted in via behavior.turnContinuesOnTextEnd=true
                    // keep driving a turn that ends text-only while the todo list is unfinished, instead
                    // of ending the turn and waiting for the user to send 'continue'. The model decides
                    // the turn is genuinely over by producing a plain text end once todos are done (or by
                    // hitting the iteration cap / a plateau hard-stop); nothing consumes the
                    // yca.todos.maxContinues budget because this is bounded by the iteration window, not
                    // by todo continuations. Only skipped when a prompt typed while running was injected
                    // (the model just answered that message).
                    if (turnContinuesOnTextEnd && pending != null && !userInjected) {
                        emit(
                                notice(
                                        AgentEvent.Notice.Level.INFO,
                                        "model ended its turn (finish_reason=" + reason + ") with " + pending.pending()
                                                + '/' + pending.total()
                                                + " todos still unfinished; continuing the round automatically (behavior.turnContinuesOnTextEnd)."));
                        messages.add(Message.user(unfinishedTodoContinuePrompt(1)));
                        continue windowLoop;
                    }
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
                final var roundRefused = new boolean[] {false};
                final var roundCoverage = new HashSet<String>();
                final var roundMutatingBash = new boolean[] {false};
                executeToolCalls(
                        sessionId,
                        messages,
                        assistant.toolCalls(),
                        repeats,
                        staleReads,
                        toolsTokens,
                        cancelled,
                        requestSettings,
                        roundInformative,
                        roundRefused,
                        roundCoverage,
                        roundMutatingBash);
                toolsRanThisTurn = true;
                // a doom-loop refusal is surfaced to the model as ONE strategy-change nudge (never as a
                // failed tool result): after the refused round completes, inject it and let the model adapt.
                if (roundRefused[0] && !doomLoopNudged) {
                    doomLoopNudged = true;
                    emit(notice(
                            AgentEvent.Notice.Level.INFO,
                            "doom-loop refusal sent to the model: a repeated state-changing/bash call was withheld."));
                    messages.add(Message.user(DOOM_LOOP_NUDGE_PROMPT));
                    continue windowLoop;
                }
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
                if (plateauLimit != 0 && !roundCoverage.isEmpty() && isPlateauRound(roundCoverage)) {
                    if (unfinishedTodos(toolsRanThisTurn) != null) {
                        plateauRounds = 0;
                    } else {
                        plateauRounds++;
                        final var plateauCap = Math.abs(plateauLimit);
                        final var plateauStops = plateauLimit > 0;
                        // one-shot strategy-change nudge (plateauFeedback nudge_1 / nudge_2): before the
                        // hard stop the model gets a chance to adapt instead of losing the whole turn.
                        // In nudge-only mode (plateauLimit < 0) the guard never hard-stops, so the nudge
                        // fires when the streak reaches the cap (abs value) to remind without killing the turn.
                        final int nudgeAt =
                                plateauStops ? plateauFeedbackNudgeAt(plateauFeedback, plateauCap) : plateauCap;
                        if (nudgeAt > 0 && !plateauNudged && plateauRounds >= nudgeAt) {
                            plateauNudged = true;
                            emit(notice(
                                    AgentEvent.Notice.Level.INFO,
                                    "exploration-plateau nudge sent to the model (round " + plateauRounds + '/'
                                            + plateauCap + (plateauStops ? "" : ",nudge-only") + ")"));
                            messages.add(Message.user(PLATEAU_NUDGE_PROMPT));
                            continue windowLoop;
                        }
                        if (plateauStops && plateauRounds >= plateauCap) {
                            persistStopNotice(
                                    sessionId,
                                    "Exploration plateau: the model made " + plateauCap
                                            + " consecutive read-only tool rounds re-covering only already-seen files/results.");
                            emit(error("Exploration plateau: the model made " + plateauCap
                                    + " consecutive read-only tool rounds re-covering only already-seen files/results."
                                    + " Stopping the turn."));
                            emitTurnMetrics(null, turnStartNanos);
                            return null;
                        }
                    }
                } else {
                    plateauRounds = 0;
                }
                // exploration nudge (novel-spiral complement to the plateau guard): plateau/doom-loop
                // only catch redundant work; a spiral of NOVEL reads/globs is informative every round
                // and slips through. After llm.explorationNudgeRounds such consecutive rounds without
                // an answer, inject a one-shot wrap-up directive (no hard stop: a genuinely deep task
                // can still continue gathering - the model decides).
                final var readOnlyRound =
                        !roundCoverage.contains(WRITE_MARKER) && !roundCoverage.isEmpty() && !roundMutatingBash[0];
                if (readOnlyRound && roundInformative[0]) {
                    explorationRounds++;
                    if (explorationNudgeRounds > 0
                            && !explorationNudged
                            && explorationRounds >= explorationNudgeRounds) {
                        explorationNudged = true;
                        emit(notice(
                                AgentEvent.Notice.Level.INFO,
                                "exploration nudge sent to the model (round " + explorationRounds + ')'));
                        messages.add(Message.user(EXPLORATION_NUDGE_PROMPT));
                        continue windowLoop;
                    }
                } else {
                    explorationRounds = 0;
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
            // an unbounded window never exhausts its budget: reaching here means the loop was broken
            // out of (eg. context kept filling up), so there is no "iteration cap" to report
            final var capReached = maxIterations > 0;
            persistStopNotice(
                    sessionId,
                    capReached
                            ? "Maximum iterations reached (" + maxIterations
                                    + "): turn stopped; resume starts a fresh window."
                            : "Turn ended without an iteration cap; resume continues the remaining work.");
            turnState.iterationLimitReached = capReached;
            emit(error(
                    capReached
                            ? "Maximum iterations reached (" + maxIterations + ")"
                            : "Turn ended before completion"));
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
            final String sessionId, final List<Message> messages, final String prompt, final int toolsTokens) {
        // The tail keeps a stable compact shape across every request so the prefix (system +
        // user + tail) stays byte-identical and provider prompt caches keep hitting; only the
        // todo list (delta of what changed) and the usage line move between requests.
        final var state = "<session-state>\n"
                + systemPromptBuilder.sessionStateDelta()
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

    private void emitTurnMetrics(final String content, final long turnStartNanos) {
        final var turnDurationMs = (precisionDurationMarker.now() - turnStartNanos) / 1_000_000;
        if (turnState.thinkingActive) {
            turnState.thinkingMs += (precisionDurationMarker.now() - turnState.thinkingStartNanos) / 1_000_000;
        }
        emit(turnMetrics(
                turnState.llmCalls,
                turnState.thinkingMs,
                turnState.toolSumMs,
                turnState.toolWallMs,
                turnState.toolsRun,
                turnDurationMs));
        if (content != null) {
            lastTurnEndedWithContent = !content.isBlank();
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
        final var pausedMs = Math.max(0L, turnState.llmPausedMs - pausedBeforeMs);
        turnState.llmActiveMs += Math.max(0L, wallMs - pausedMs);
        if (usage != null && usage.completionTokens() > 0) {
            turnState.outputTokens += usage.completionTokens();
            if (usage.reasoningTokens() > 0) {
                turnState.reasoningTokens += usage.reasoningTokens();
            }
            if (usage.generationTimeMs() > 0) {
                turnState.llmReportedMs += usage.generationTimeMs();
            }
        } else {
            // no usage from the provider: estimate the produced tokens from what came back
            turnState.outputTokens += estimateOutputTokens(assistant);
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
     * Drains the {@link SubagentDeliveryBus} for this parent session and injects each finished
     * sub-agent's result as a {@code user}-role {@code <subagent ...>...</subagent>} message, so the
     * parent model's next round sees the child's completion. Only the parent loop (the one with a null
     * {@code subagentId}) drains: a child AgentLoop shares the same session id but must never receive
     * its own siblings' results. The injected message rides in the in-loop {@code messages} list and is
     * persisted through the same {@code sessionStore} so it survives compaction/pruning and is pruned by
     * {@link #purgeDeliveredSubagents(List)} once old.
     */
    private void integrateSubagentDeliveries(final String sessionId, final List<Message> messages) {
        if (subagentId != null) {
            return; // a nested sub-agent never consumes its parent's deliveries
        }
        final var deliveries = SubagentDeliveryBus.get().drain(sessionId);
        if (deliveries.isEmpty()) {
            return;
        }
        for (final var delivery : deliveries) {
            final var text = delivery.message();
            messages.add(Message.user(text));
            sessionStore.append(
                    sessionId,
                    SessionEntry.of(clock.millis(), "user", text, null, null, null, identityOthers(currentAgentName)));
            emit(new AgentEvent.SubagentDelivered(
                    delivery.summary(),
                    delivery.success(),
                    delivery.cancelled(),
                    sessionId,
                    delivery.subagentId(),
                    delivery.name()));
        }
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
                usageTracker.contextWindowTokenCount().value(),
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
                : contextManager.compact(
                        messages,
                        directive.force(),
                        directive.focus(),
                        directive.keepLast(),
                        () -> false,
                        chatSettings);
        if (!result.changed()) {
            emit(notice(
                    AgentEvent.Notice.Level.INFO,
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
                        + result.mode().name().toLowerCase(Locale.ROOT)
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
            final var effectiveProvider = chatSettings.providerId() == null
                    ? providerManager.current()
                    : providerManager.provider(chatSettings.providerId());
            final var provider = effectiveProvider != null && effectiveProvider.id() != null
                    ? effectiveProvider.id()
                    : providerManager.currentProviderId();
            String model = null;
            if (chatSettings.model() != null && !chatSettings.model().isBlank()) {
                model = chatSettings.model();
            } else {
                final var current = effectiveProvider != null ? effectiveProvider : providerManager.current();
                if (current != null
                        && current.model() != null
                        && !current.model().isBlank()) {
                    model = current.model();
                }
            }
            final var identity = new LinkedHashMap<String, Object>();
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
            return Map.of(Message.IDENTITY_KEY, identity);
        } catch (final RuntimeException e) {
            // identity capture is best-effort: a provider manager without real wiring
            // (unit tests, bootstrapping) must never break a turn
            return null;
        }
    }

    /**
     * The {@code others} map persisted with an assistant message: the producer identity (as
     * {@link #identityOthers}) merged with the accumulated reasoning text (under {@link #THINKING_KEY}),
     * so a later replay can re-render the thought blocks. The identity members are captured at write
     * time; the thinking is whatever streamed for this request. Never sent to the LLM ({@code others}
     * is stripped at every wire boundary). Returns {@code null} when there is nothing to persist.
     */
    private Map<String, Object> identityAndThinking(final String agentName) {
        final var identity = identityOthers(agentName);
        final var thinking = accumulatedThinking.toString().strip();
        final var reasoningItems = toReasoningItemsPayload(accumulatedReasoningItems);
        if (identity == null && (thinking.isEmpty() || thinking.isBlank()) && reasoningItems.isEmpty()) {
            return null;
        }
        final var others = new LinkedHashMap<String, Object>();
        if (identity != null) {
            others.putAll(identity);
        }
        if (thinking != null && !thinking.isEmpty() && !thinking.isBlank()) {
            others.put(THINKING_KEY, thinking);
        }
        if (!reasoningItems.isEmpty()) {
            others.put(REASONING_ITEMS_KEY, reasoningItems);
        }
        return others;
    }

    /**
     * Converts the captured reasoning items into a JSON-serializable payload for the session store
     * (each entry carries {@code id}, {@code summary} and {@code encrypted_content}). Returns an empty
     * list when none were captured.
     */
    private static List<Map<String, Object>> toReasoningItemsPayload(final List<ReasoningItem> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        final var out = new ArrayList<Map<String, Object>>(items.size());
        for (final var item : items) {
            final var map = new LinkedHashMap<String, Object>();
            if (item.id() != null && !item.id().isEmpty()) {
                map.put("id", item.id());
            }
            if (item.summary() != null && !item.summary().isEmpty()) {
                map.put("summary", item.summary());
            }
            if (item.encryptedContent() != null && !item.encryptedContent().isEmpty()) {
                map.put("encrypted_content", item.encryptedContent());
            }
            if (!map.isEmpty()) {
                out.add(map);
            }
        }
        return out;
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
            LOGGER.log(SessionAwareLogRecord.of(
                    LOGGER, sessionId, Level.WARNING, "Could not persist the compacted session " + sessionId, e));
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
     * Each applied strategy reports its {@code Compacted} kind through the returned list (the caller
     * merges it with the automatic prune/compact of the same checkpoint into a single event). The
     * conversation list is mutated in place because the loop owns it and keeps the call/result
     * pairing intact.
     *
     * @return the kinds of the strategies that changed the history, in execution order
     */
    private List<String> applyZeroLlmStrategies(final List<Message> messages) {
        final var p = configuration.pruning();
        final var applied = new ArrayList<String>(4);
        if (p.deduplicateToolCalls() && deduplicateToolCalls(messages) > 0) {
            applied.add("dedup");
        }
        if (p.supersedeOverwrittenWrites() && supersedeOverwrittenWrites(messages) > 0) {
            applied.add("supersede");
        }
        if (p.purgeFailedToolInputs() && purgeFailedToolInputs(messages) > 0) {
            applied.add("purge-errors");
        }
        if (p.purgeDeliveredSubagents() && purgeDeliveredSubagents(messages) > 0) {
            applied.add("purge-subagents");
        }
        return applied;
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
            final var json = mapper.read(Map.class, new StringReader(arguments));
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
                if ("read".equals(name) || "grep".equals(name) || "glob".equals(name)) {
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
            final var json =
                    mapper.read(Map.class, new StringReader(call.function().arguments()));
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
     * Truncates old delivered <subagent> blocks to their first line (the sessionID/state/description
     * metadata), keeping the "what just finished" signal without retaining the whole summary. The
     * {@code last two} deliveries are always kept verbatim so the model still sees the most recent
     * completed sub-agent's full outcome. Returns the number of blocks truncated.
     */
    int purgeDeliveredSubagents(final List<Message> messages) {
        if (!configuration.pruning().purgeDeliveredSubagents()) {
            return 0;
        }
        final var turns = configuration.pruning().purgeDeliveredSubagentsTurns();
        // locate delivered blocks (user-role messages opened by the <subagent marker) and index them
        final var delivered = new java.util.ArrayList<Integer>();
        for (int i = 0; i < messages.size(); i++) {
            final var m = messages.get(i);
            final var content = m.content();
            if (m.role() != null
                    && "user".equals(m.role())
                    && content instanceof String s
                    && s.startsWith("<subagent ")) {
                delivered.add(i);
            }
        }
        if (delivered.size() <= 2) {
            return 0; // always keep the last two verbatim
        }
        var purged = 0;
        final int keepLast = 2;
        for (var k = 0; k < delivered.size() - keepLast; k++) {
            final var idx = delivered.get(k);
            final var message = messages.get(idx);
            // count user turns strictly after this block
            var userTurnsAfter = 0;
            for (int j = idx + 1; j < messages.size(); j++) {
                if ("user".equals(messages.get(j).role())) {
                    userTurnsAfter++;
                }
            }
            if (turns > 0 && userTurnsAfter < turns) {
                continue;
            }
            final var content = message.content() instanceof String s2 ? s2 : "";
            final var nl = content.indexOf('\n');
            if (nl <= 0) {
                continue; // nothing to truncate (single-line already)
            }
            final var metadataLine = content.substring(0, nl);
            final var compact = metadataLine + "\n[delivered sub-agent content pruned; see /subagents "
                    + Integer.toHexString(content.hashCode()) + "]";
            // Message is immutable: swap in a rebuilt message with the compacted body, keeping every
            // other field (role user) unchanged so the block is still a plain delivered message.
            messages.set(
                    idx,
                    new Message(
                            message.role(),
                            compact,
                            message.name(),
                            message.toolCalls(),
                            message.toolCallId(),
                            message.others()));
            purged++;
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
        final var appliedKinds = new ArrayList<String>(2);
        var appliedModes = new ArrayList<CompactionResult.Mode>(2);
        if (contextManager.pruneNeeded(messages, toolsTokens)) {
            final var pruned = contextManager.prune(messages);
            if (pruned.changed()) {
                messages.clear();
                messages.addAll(pruned.messages());
                appliedKinds.add("round-prune");
                appliedModes.add(pruned.mode());
                persistContext(sessionId, messages);
                usageTracker.clearAnchor();
            }
        }
        if (contextManager.compactNeeded(messages, toolsTokens)) {
            final var compacted = contextManager.compact(messages, false, null, 0, () -> false, chatSettings);
            if (compacted.changed()) {
                messages.clear();
                messages.addAll(compacted.messages());
                appliedKinds.add("round-compact");
                appliedModes.add(compacted.mode());
                persistContext(sessionId, messages);
                usageTracker.clearAnchor();
            }
        }
        if (!appliedKinds.isEmpty()) {
            emit(compacted(appliedKinds, bestMode(appliedModes)));
        }
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
    private static boolean isPlateauRound(final Set<String> roundCoverage) {
        for (final var key : roundCoverage) {
            if (!key.startsWith(REDUNDANT_MARKER)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The session's effective provider + model: the per-tab provider/model selection when set,
     * else the process default one. The model override applies even without a per-tab provider -
     * a plain {@code /model} (no {@code /provider}) must change what goes on the wire, not only
     * the name the UI reads from {@code chatClient.model(settings)}.
     */
    private ProviderManager.ResolvedModel resolvedModel() {
        return resolvedModel(chatSettings);
    }

    private ProviderManager.ResolvedModel resolvedModel(final ChatSettings settings) {
        if (providerManager == null) {
            return null;
        }
        final var overrideModel = settings.model() == null || settings.model().isBlank() ? null : settings.model();
        if (settings.providerId() != null) {
            final var provider = providerManager.provider(settings.providerId());
            if (provider != null) {
                return new ProviderManager.ResolvedModel(
                        provider, overrideModel != null ? overrideModel : provider.model());
            }
        }
        final var defaultModel = providerManager.resolveDefault();
        if (defaultModel != null) {
            return overrideModel == null
                    ? defaultModel
                    : new ProviderManager.ResolvedModel(defaultModel.provider(), overrideModel);
        }
        final var configModel = configuration == null || configuration.provider() == null
                ? null
                : configuration.provider().model();
        return new ProviderManager.ResolvedModel(
                providerManager.current(), overrideModel != null ? overrideModel : configModel);
    }

    /**
     * The model the next request must carry: the session's selection resolved right now (a
     * mid-turn {@code /model} applies from the next round, not only from the next turn), falling
     * back to the model resolved at window start when this loop has no provider wiring.
     */
    private String requestModel(final ProviderManager.ResolvedModel windowModel) {
        final var current = resolvedModel();
        return current == null ? (windowModel == null ? null : windowModel.model()) : current.model();
    }

    private String requestModel(final ProviderManager.ResolvedModel windowModel, final ChatSettings settings) {
        final var current = resolvedModel(settings);
        return current == null ? (windowModel == null ? null : windowModel.model()) : current.model();
    }

    /**
     * Iteration window cap cascade: per-model behavior.maxIterations > per-provider tuning.maxIterations
     * > the global llm.maxIterations default. A negative override (< 0) means an UNBOUNDED window: the
     * turn runs until it genuinely ends (the model finishes, a plateau/no-progress guard stops it,
     * context overflows, or the turn is cancelled). A positive {@code maxIterationsCap} (always set for
     * sub-agents) bounds the window regardless, so unbounded applies only to top-level turns with no cap.
     * The provider/model come from {@code resolved} (the default provider resolved at loop start).
     */
    private int resolvedMaxIterations(final ProviderManager.ResolvedModel resolved) {
        final var cap = maxIterationsCap;
        if (cap != null) {
            // sub-agent / hard window cap always bounds; unbounded requires no cap AND a negative override
            return cap;
        }
        final var override = resolved == null || providerManager == null
                ? 0
                : providerManager.maxIterationsOverride(resolved.provider(), resolved.model());
        return override != 0 ? override : configuration.llm().maxIterations();
    }

    /**
     * Exploration-plateau limit cascade: per-model behavior.plateauRounds > per-provider
     * tuning.plateauRounds > the global llm.plateauRounds default (8). A non-zero resolved value wins
     * verbatim: a negative one means nudge-only (the plateau nudges but never hard-stops the turn), a
     * positive one is the nudge + hard-stop limit, and 0 (only reachable via an explicit global
     * llm.plateauRounds=0, since a per-model/provider 0 falls through the chain) disables the guard.
     */
    private int resolvedPlateauRounds(final ProviderManager.ResolvedModel resolved) {
        final var override = resolved == null || providerManager == null
                ? 0
                : providerManager.plateauRounds(resolved.provider(), resolved.model());
        return override != 0 ? override : configuration.llm().plateauRounds();
    }

    /**
     * Exploration-nudge cascade: per-model behavior.explorationNudgeRounds > per-provider
     * tuning.explorationNudgeRounds > the global llm.explorationNudgeRounds default (20). 0 disables
     * the nudge (or falls back to the provider/global when only set to 0 higher up the chain).
     */
    private int resolvedModelExplorationNudgeRounds(final ProviderManager.ResolvedModel resolved) {
        final var override = resolved == null || providerManager == null
                ? 0
                : providerManager.explorationNudgeRounds(resolved.provider(), resolved.model());
        return override > 0 ? override : configuration.llm().explorationNudgeRounds();
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
     * Doom-loop success threshold cascade: per-model behavior.repeatSuccessThreshold > per-provider
     * tuning.repeatSuccessThreshold > the global yca.tools.repeatSuccessThreshold default (3).
     * 0 at the global level disables the guard entirely.
     */
    private int resolvedRepeatSuccessThreshold(final ProviderManager.ResolvedModel resolved) {
        final var override = resolved == null || providerManager == null
                ? 0
                : providerManager.repeatSuccessThreshold(resolved.provider(), resolved.model());
        return override > 0 ? override : configuration.tools().repeatSuccessThreshold();
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
        if (pending == null
                || !canAutoContinueUnfinished(userInjected)
                || turnState.unfinishedTodoContinues >= maxContinues) {
            return false;
        }
        turnState.unfinishedTodoContinues++;
        emit(notice(
                AgentEvent.Notice.Level.INFO,
                "model ended its turn (finish_reason=" + finishReason + ") with " + pending.pending() + '/'
                        + pending.total() + " todos still unfinished; continuing automatically ("
                        + turnState.unfinishedTodoContinues + '/' + maxContinues + ")."));
        messages.add(Message.user(unfinishedTodoContinuePrompt(turnState.unfinishedTodoContinues)));
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
    record ToolOutcome(
            String callId,
            String name,
            String output,
            boolean success,
            boolean pending,
            boolean persist,
            List<String> paths,
            long durationMs,
            boolean informative,
            List<String> coverage,
            boolean refused) {

        ToolOutcome(
                final String callId,
                final String name,
                final String output,
                final boolean success,
                final boolean pending,
                final boolean persist,
                final List<String> paths,
                final long durationMs,
                final boolean informative,
                final List<String> coverage) {
            this(callId, name, output, success, pending, persist, paths, durationMs, informative, coverage, false);
        }

        ToolOutcome(
                final String callId,
                final String name,
                final String output,
                final boolean success,
                final boolean persist,
                final List<String> paths,
                final long durationMs,
                final boolean informative,
                final List<String> coverage) {
            this(callId, name, output, success, false, persist, paths, durationMs, informative, coverage);
        }

        // refused-carrying 10-arg form (the model-loop doom-guard refusal path); pending is false
        ToolOutcome(
                final String callId,
                final String name,
                final String output,
                final boolean success,
                final boolean persist,
                final List<String> paths,
                final long durationMs,
                final boolean informative,
                final List<String> coverage,
                final boolean refused) {
            this(callId, name, output, success, false, persist, paths, durationMs, informative, coverage, refused);
        }

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
            final ChatSettings settings,
            final boolean[] roundInformative,
            final boolean[] roundRefused,
            final Set<String> roundCoverage,
            final boolean[] roundMutatingBash)
            throws InterruptedException {
        return toolCallCoordinator.execute(
                calls,
                cancelled,
                this::isParallelizable,
                executor != null && parallelReadonlyEnabled(),
                call -> runGuarded(call, repeats, cancelled, settings),
                call -> {
                    // Workers never touch ToolRepeats: register the signature on this loop thread first.
                    repeats.register(call, this::normalizedArguments);
                    return executor.submit(() -> runTool(call, cancelled, settings));
                },
                outcome -> appendToolResult(
                        sessionId,
                        messages,
                        outcome,
                        staleReads,
                        roundInformative,
                        roundRefused,
                        roundCoverage,
                        roundMutatingBash),
                this::awaitTool,
                () -> checkRoundBudget(sessionId, messages, toolsTokens),
                precisionDurationMarker::now,
                elapsedMs -> turnState.toolWallMs += elapsedMs,
                () -> roundInformative[0]);
    }

    private ToolOutcome runGuarded(
            final ToolCall call,
            final ToolRepeats repeats,
            final BooleanSupplier cancelled,
            final ChatSettings settings) {
        // register the callId -> signature mapping on the loop thread (before any dispatch) so record()
        // can match the result back for reads too, feeding the per-signature no-progress decision.
        repeats.register(call, this::normalizedArguments);
        // the doom-loop guard targets state-changing/bash calls only: read-only rounds are owned by
        // the exploration-plateau guard (semantic re-coverage), so a read is never signature-refused here.
        if (isReadOnly(call)) {
            return runTool(call, cancelled, settings);
        }
        final var repeated = repeats.rejectIfRepeated(call, this::normalizedArguments);
        if (repeated != null) {
            emit(toolFinished(repeated.name(), repeated.output(), false, false, -1));
            return repeated;
        }
        return runTool(call, cancelled, settings);
    }

    /** File tools whose results participate in stale-read invalidation. */
    private static final Set<String> FILE_READ_TOOLS = Set.of("read");

    private static final Set<String> FILE_WRITE_TOOLS = Set.of("write_file", "edit_file", "apply_patch");

    /** Stable prefix every stale-read note carries; used to skip already-noted reads (see {@link StaleReads}). */
    private static final String STALE_READ_MARKER = "[stale read cleared:";

    /**
     * Tracks which {@code read} results sit in the conversation and by which path, so a later
     * successful write to the same path can flag them as superseded instead of letting the model
     * reason over stale file snapshots until prune/compaction eventually drops them. The flag rides
     * inside the write tool's own result message (brand-new content that turn), so nothing is ever
     * rewritten and no extra message is injected mid-conversation. All mutation happens on the loop
     * thread (worker threads only produce outcomes), so no synchronization is needed.
     */
    static final class StaleReads {
        private final Map<String, Path> readByCallId;

        StaleReads(final Map<String, Path> readByCallId) {
            this.readByCallId = new LinkedHashMap<>(readByCallId);
        }

        /**
         * Records a {@code read} (by call id), or computes which earlier read paths a
         * successful write invalidated. Never mutates {@code messages}: the caller folds the
         * returned paths into the write tool's own result message, which is appended right after
         * this call (zero prefix-cache cost — that message is new content this round anyway).
         *
         * @param messages conversation so far, WITHOUT the write result message (not yet appended)
         * @return the stale paths a successful write superseded, sorted; empty otherwise
         */
        List<Path> onLanded(final List<Message> messages, final ToolOutcome outcome) {
            if (outcome.paths().isEmpty()) {
                return List.of();
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
                return List.of();
            }
            if (!FILE_WRITE_TOOLS.contains(outcome.name()) || !outcome.success()) {
                return List.of();
            }
            final var written = Set.copyOf(touched);
            final var stale = new LinkedHashSet<Path>();
            for (final var message : messages) {
                if (!"tool".equals(message.role())) {
                    continue;
                }
                final var read = readByCallId.get(message.toolCallId());
                if (read == null || !written.contains(read)) {
                    continue;
                }
                if (String.valueOf(message.content()).contains(STALE_READ_MARKER)) {
                    continue; // already noted
                }
                stale.add(read);
            }
            if (stale.isEmpty()) {
                return List.of();
            }
            final var paths = new ArrayList<>(stale);
            paths.sort(Comparator.naturalOrder());
            return paths;
        }
    }

    /** Rebuilds the {@code callId -> path} index of past {@code read} calls from the conversation. */
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
                if (fn == null || !"read".equals(fn.name()) || fn.arguments() == null) {
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
     * Doom-loop breaker: refuses repeated identical tool calls that keep returning the SAME (already-known)
     * successful result, instead of burning another model round-trip on duplicate information. Each signature
     * (tool name + normalized arguments) tracks its own CONSECUTIVE success streak. The FIRST occurrence of a
     * signature is reserved (it only primes comparison state, never counts), so the configured threshold T means
     * "T identical-successful-output calls in a row, refuse the next one". A call counts toward the streak only
     * when it succeeded AND its output carried no new information (identical output, ignoring embedded ISO-8601
     * timestamps). A failing call (legitimate retry) or a same-signature call returning fresh content
     * (polling/progress) resets the streak to zero. A refused call never runs and never resets the streak, so the
     * diagnostic stays stable ("called T times") instead of escalating. Arguments are normalized (JSON keys
     * sorted) so cosmetic spelling variants do not bypass the guard.
     */
    private static final class ToolRepeats {
        /** ISO-8601 dates/times, clock times and build-tool durations; deliberately no bare digit runs. */
        private static final Pattern DATE_LIKE = Pattern.compile(
                // ISO-8601 date, optionally with time and zone (2026-10-13T18:00:00+02:00)
                "\\d{4}-\\d{2}-\\d{2}(?:[T ]\\d{1,2}:\\d{2}(?::\\d{2}(?:\\.\\d{1,9})?)?(?:Z|[+-]\\d{2}:?\\d{2})?)?"
                        // standalone clock (18:00, 18:00:00, 18:00:00.123) - log prefixes and timestamps
                        + "|\\b\\d{1,2}:\\d{2}(?::\\d{2}(?:\\.\\d{1,9})?)?\\b"
                        // build-tool durations: "in 5s", "Total time: 1.234 s", "0.5ms", "[1.2s]"
                        + "|\\b\\d{1,4}(?:\\.\\d{1,3})?\\s*(?:ms|s)\\b");

        /** Success streak length that triggers a refusal; {@code <= 0} disables the guard entirely. */
        private final int threshold;

        /** callId -> signature key of the call, so a result can be matched back to its call args. */
        private final Map<String, String> callKeyByCallId = new HashMap<>();
        /** Signature -> per-signature repeat state (last normalized output + consecutive success streak). */
        private final Map<String, State> states = new HashMap<>();
        /** Turn-level tombstone of already-seen normalized outputs (across all signatures/tools). */
        private final Set<String> seenOutputs = new HashSet<>();

        private static final class State {
            String lastOutput; // normalized output, null until the first occurrence is primed
            int streak;
            boolean seen;
        }

        ToolRepeats(final int threshold) {
            this.threshold = threshold;
        }

        /** Replaces embedded ISO-8601 dates/times so two otherwise-identical outputs compare equal. */
        static String normalizeForComparison(final String raw) {
            if (raw == null || raw.isEmpty()) {
                return "";
            }
            return DATE_LIKE.matcher(raw).replaceAll("\u0000");
        }

        /**
         * Atomically records one executed tool result and updates its signature state. Returns whether the
         * result carried NEW information for the round ({@code false} when its normalized output was already
         * produced earlier this turn by any tool - the turn-level tombstone). Per-signature: the success streak
         * used by the doom-loop refusal is bumped for a repeated identical-output success and reset on failure
         * or fresh content; the first successful occurrence seeds it (T means T). Only reached for executed
         * calls: refused/never-run calls skip it so the streak stays stable across refusals.
         */
        boolean record(final String callId, final boolean success, final String output) {
            final var key = callKeyByCallId.remove(callId);
            if (key == null) {
                return true; // never saw the call: always informative
            }
            final var value = normalizeForComparison(output);
            final var state = states.computeIfAbsent(key, k -> new State());
            // per-signature novelty drives the doom-loop success streak (refusal); turn-wide novelty
            // (tombstone across all tools/signatures) drives the no-progress/plateau "informative" signal.
            final boolean signatureNovel = state.seen && !value.equals(state.lastOutput);
            state.lastOutput = value;
            if (!state.seen) {
                // first occurrence: prime the baseline. A success counts as the first identical-output
                // success in the streak (T means T), a first failure only primes and never starts one.
                state.streak = success ? 1 : 0;
            } else if (success && !signatureNovel) {
                state.streak++;
            } else {
                state.streak = 0;
            }
            state.seen = true;
            // tombstone: an output already produced this turn by ANY prior call is not (turn-wise) novel.
            final var turnNovel = seenOutputs.add(value);
            return turnNovel;
        }

        /** The normalized arguments are computed by the owning loop (JSON key sorting lives there). */
        ToolOutcome rejectIfRepeated(final ToolCall call) {
            return rejectIfRepeated(call, null);
        }

        /**
         * Registers the callId -> signature-key mapping for {@code record()} to match the result back.
         * Called for EVERY executed call (read and non-read alike) so the per-signature output/streak
         * tracking in {@link #record(String, boolean, String)} works for reads too (it feeds the
         * no-progress decision). Idempotent: the key is deterministic for a given call.
         */
        void register(final ToolCall call, final Function<String, String> normalizer) {
            final var fn = call.function();
            if (fn == null) {
                return;
            }
            final var name = fn.name() == null ? "" : fn.name();
            final var args = fn.arguments() == null ? "{}" : fn.arguments();
            callKeyByCallId.put(call.id(), name + '\n' + (normalizer == null ? args : normalizer.apply(args)));
        }

        ToolOutcome rejectIfRepeated(final ToolCall call, final Function<String, String> normalizer) {
            if (threshold <= 0) {
                return null; // guard disabled
            }
            register(call, normalizer); // ensure the callId mapping exists for the streak lookup
            final var fn = call.function();
            final var name = fn == null || fn.name() == null ? "" : fn.name();
            final var key = callKeyByCallId.get(call.id());
            final var state = states.get(key);
            if (state == null || state.streak < threshold) {
                return null;
            }
            // a refused call never runs, so record() is never called for it and would leave its
            // callId -> key mapping behind: drop it here to keep ToolRepeats bounded within the turn.
            callKeyByCallId.remove(call.id());
            final var diagnostic = "Tool " + name + " was called " + threshold
                    + " times in a row with the same arguments, each time returning the same already-known result."
                    + " Repeating it adds no information. Change strategy - narrow or widen the query,"
                    + " use a different command, or answer from what you already collected.";
            return new ToolOutcome(call.id(), name, diagnostic, false, false, List.of(), 0L, false, List.of(), true);
        }
    }

    /** Whether a call targets a read-only tool (never state-changing). Reads are owned by the plateau guard. */
    private boolean isReadOnly(final ToolCall call) {
        if (toolRegistry == null) {
            return false;
        }
        final var fn = call.function();
        final EmbeddedTool tool = toolRegistry.tool(fn == null || fn.name() == null ? "" : fn.name());
        return tool != null && (tool.readonly() || tool.category() == ToolCategory.READ);
    }

    /** A call can join a parallel run only if its tool is known and cannot mutate state. */
    private boolean isParallelizable(final ToolCall call) {
        return isReadOnly(call);
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
    private ToolOutcome runTool(final ToolCall call, final BooleanSupplier cancelled, final ChatSettings settings) {
        final var name = call.function() == null || call.function().name() == null
                ? ""
                : call.function().name();
        final var argsJson = call.function() == null || call.function().arguments() == null
                ? "{}"
                : call.function().arguments();
        final var callCancel = new AtomicBoolean();
        final var ordinal = toolCallOrdinal.incrementAndGet();
        registerRunningTool(ordinal, callCancel);
        final EmbeddedTool tool = toolRegistry.tool(name);
        if (tool == null) {
            final var diagnostic = "Unknown tool: " + name;
            unregisterRunningTool(ordinal);
            emit(toolFinished(name, unknownToolDiagnostic(name), false, false, ordinal));
            return new ToolOutcome(call.id(), name, diagnostic, false, false);
        }
        final var childToolCeiling = effectiveAllowedTools();
        if (childToolCeiling != null && !childToolCeiling.contains(name)) {
            final var diagnostic = "Tool is not allowed for this agent run: " + name;
            unregisterRunningTool(ordinal);
            emit(toolFinished(name, diagnostic, false, false, ordinal));
            return new ToolOutcome(call.id(), name, diagnostic, false, false);
        }
        // a tool that is served but was NOT offered in the current request's wire list must not be executed:
        // plan mode removes the EDIT tools from the schema, so a model reaching for one (e.g. carried over
        // from a prior build-mode turn in the append-only transcript) is auto-rejected here with a mode-aware
        // diagnostic - the approval gate is never consulted for a tool that was hidden. Only tools the registry
        // actually serves are subject to being plan-hidden; bare/empty test registries are unaffected.
        final var served = servedToolNames;
        if (permissionService != null
                && permissionService.mode() == AgentMode.PLAN
                && tool.category() == ToolCategory.EDIT
                && served != null
                && served.contains(name)) {
            final var modeDenial = hiddenToolDiagnostic(name);
            unregisterRunningTool(ordinal);
            emit(toolFinished(name, modeDenial, false, false, ordinal));
            return new ToolOutcome(call.id(), name, modeDenial, false, false);
        }
        final Map<String, Object> args;
        try {
            final var parsed = mapper.fromString(Object.class, argsJson.isBlank() ? "{}" : argsJson);
            args = parsed instanceof Map<?, ?> m ? cast(m) : new LinkedHashMap<>();
        } catch (final RuntimeException e) {
            final var diagnostic = "Invalid tool arguments JSON: " + argsJson;
            unregisterRunningTool(ordinal);
            emit(toolFinished(name, diagnostic, false, false, ordinal));
            return new ToolOutcome(call.id(), name, diagnostic, false, false);
        }
        emit(toolStarted(name, argsJson, ordinal));
        final var gateDenial = gateTool(name, tool, args, call.id());
        if (gateDenial != null) {
            unregisterRunningTool(ordinal);
            emit(toolFinished(name, gateDenial, false, false, ordinal));
            return new ToolOutcome(call.id(), name, gateDenial, false, false);
        }
        final var toolStartNanos = precisionDurationMarker.now();
        final ToolResult result;
        try {
            result = toolExecutionService.execute(
                    tool, args, toolContext(cancelled, callCancel::get, ordinal, settings));
        } finally {
            unregisterRunningTool(ordinal);
            // the turn keeps running after a per-call cancel: only the tool result is marked
        }
        final var toolDurationMs = (precisionDurationMarker.now() - toolStartNanos) / 1_000_000;
        emit(toolFinished(name, result.output(), result.success(), result.pending(), ordinal));
        return new ToolOutcome(
                call.id(),
                name,
                result.output(),
                result.success(),
                result.pending(),
                true,
                touchedPaths(name, args),
                toolDurationMs,
                // a pending result is not "new informative work" nor a failure: the model must not
                // count a still-growing subagent wait as an exploration round or a signal to retry
                !result.pending(),
                coverageKeys(name, args));
    }

    /**
     * Runs the unified pre-tool gate for a tool call: the event is emitted synchronously (so the built-in
     * {@link ToolUsePermissionObserver} runs the session's permission rules and the {@link ToolUseCallbackObserver}
     * runs the configured {@code yca.hooks.ToolUsePreEvent} callbacks, in that order). Returns the denial reason
     * when the call must be blocked, or {@code null} to let it run. This is the single authoritative denial point
     * for every tool, bash included.
     */
    private String gateTool(
            final String name, final EmbeddedTool tool, final Map<String, Object> args, final String toolUseId) {
        final var result = new HookResult();
        final var workdir = args.get("workdir") instanceof final String w && !w.isBlank() ? w : null;
        final var context = new EventContextBuilder(configuration)
                .build(permissionService, chatSettings(), currentSessionId, subagentId, subagentName, workdir);
        emitter.emit(new ToolUsePreEvent(
                context, name, args, toolUseId, result, permissionService, tool.category(), tool.readonly()));
        if (!result.forbidden()) {
            return null;
        }
        return "Permission denied by user" + (result.reason() == null ? "" : ": " + result.reason());
    }

    /**
     * Gates the initial user prompt of a turn through the {@code yca.hooks.UserPromptSubmit} callbacks. Returns
     * {@code false} when a callback forbids it (the prompt is dropped and the turn does not run); {@code true}
     * otherwise. Queued mid-turn prompts are not gated here so forbidding one cannot kill the running turn.
     */
    private boolean acceptInitialPrompt(final String userMessage, final String sessionId) {
        final var result = new HookResult();
        final var context = new EventContextBuilder(configuration)
                .build(permissionService, chatSettings(), sessionId, subagentId, subagentName, null);
        emitter.emit(new UserPromptSubmitEvent(context, userMessage, "user", result));
        if (result.forbidden()) {
            emit(AgentEvent.Notice.warn(
                    "Prompt rejected" + (result.reason() == null ? "" : ": " + result.reason()), currentSessionId));
            return false;
        }
        return true;
    }

    /**
     * Coverage keys of a successful read-only tool call, used by the exploration-plateau detector:
     * a normalized absolute path for file tools (page-aware for read, pattern-aware for grep),
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
        if ("glob".equals(name) && args.get("path") instanceof final String path && !path.isBlank()) {
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
            final boolean[] roundRefused,
            final Set<String> roundCoverage,
            final boolean[] roundMutatingBash) {
        // a refused doom-loop call is NOT delivered as a failed tool result (that shape reads as "the
        // tool errored, retry it" and feeds the very loop we are stopping): it only nudges the model at
        // the round boundary and surfaces a UI event. So skip the conversation/session mutation entirely.
        if (outcome.refused()) {
            if (roundRefused != null) {
                roundRefused[0] = true;
            }
            return;
        }
        final var raw = outcome.output() == null || outcome.output().isBlank() ? "(no output)" : outcome.output();
        // Stale-read invalidation rides INSIDE this tool result (new content this round, so the
        // provider prefix cache is untouched — no extra message is injected mid-conversation).
        // Computed before the message is appended: onLanded scans messages WITHOUT this result.
        final var stalePaths = staleReads.onLanded(messages, outcome);
        final var output = capIngestionOutput(stalePaths.isEmpty() ? raw : raw + staleNote(stalePaths));
        final var isError = !outcome.success() && !outcome.pending();
        messages.add(Message.tool(outcome.callId(), output, isError));
        // a round is informative when at least one result is new (same call, different output) or
        // a write succeeded: used by the no-progress termination (P2). Refused doom-loop calls are
        // explicitly non-informative (their diagnostic output would otherwise look "new"), and never
        // reach the repeat-streak tracking so the refusal stays stable.
        if (outcome.informative()) {
            // novelty is computed ONCE by the atomic record() and drives both the round-informative
            // decision and the doom-loop success streak update (a non-novel success bumps the streak,
            // failure or fresh content resets it). Only executed calls land here, hence on the loop thread.
            final var novel = repeats.record(outcome.callId(), outcome.success(), output);
            if (outcome.success() && FILE_WRITE_TOOLS.contains(outcome.name())) {
                roundInformative[0] = true;
            } else if (roundInformative != null && !roundInformative[0]) {
                roundInformative[0] = novel;
            }
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
        // mutating-bash detection for the exploration-nudge spiral: a bash command that is NOT a recognized
        // read-only family (empty coverage - eg. builds, mvn, npm, git commit, rm) is real work, so it
        // resets the novel-spiral counter instead of counting as exploration. This applies whether it
        // succeeded or failed (a failing build is still the intended work, not exploration). Read-only
        // bash (git log, cat, ls, grep -r) produces coverage keys and is NOT flagged here, so it keeps
        // counting toward the spiral in both build and plan mode.
        if ("bash".equals(outcome.name())
                && roundMutatingBash != null
                && outcome.coverage().isEmpty()) {
            roundMutatingBash[0] = true;
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
                            // a pending tool is not a failure: the ephemeral "still running" result must
                            // not be pruned as a failed tool input nor flagged as an error
                            (!outcome.success() && !outcome.pending()) ? Map.of("is_error", true) : null));
        }
        if (!stalePaths.isEmpty()) {
            emit(status("invalidated " + stalePaths.size() + " stale read(s) of modified files"));
        }
        turnState.toolsRun++;
        turnState.toolSumMs += outcome.durationMs();
    }

    /**
     * Whether a request failure is a strict tool-pairing rejection (Azure / litellm "An assistant message
     * with 'tool_calls' must be followed by tool messages responding to each 'tool_call_id'. The following
     * tool_call_ids did not have response messages: ..."). This is the caller-side (history-owner) companion
     * to {@link ModelRoundProcessor#isToolCallPairingRejected(LlmHttpFailureException)}, which owns the
     * retry-policy decision. Keep the {@code LLM request failed (400)} prefix gate on the message so a
     * non-400 transport failure is never misclassified, then delegate the signature narrowing to the shared
     * matcher for the http-failure body, falling back to a message-only check when the failure does not carry
     * a body.
     *
     * @param failure the thrown low-level request failure (the 400 propagates to the loop as an
     *     {@link IOException}, possibly a {@link LlmHttpFailureException} carrying the provider body).
     * @return whether the failure is a tool-call pairing rejection that the interrupted-tool repair can fix.
     */
    private static boolean isToolPairingRejection(final Throwable failure) {
        final var message = failure == null ? null : failure.getMessage();
        if (message == null || !message.startsWith("LLM request failed (400)")) {
            return false;
        }
        if (failure instanceof LlmHttpFailureException httpFailure
                && ModelRoundProcessor.isToolCallPairingRejected(httpFailure)) {
            return true;
        }
        final var lowerTxt = message.toLowerCase(Locale.ROOT);
        return lowerTxt.contains("tool_calls")
                && (lowerTxt.contains("must be followed")
                        || lowerTxt.contains("no response message")
                        || lowerTxt.contains("did not have")
                        || lowerTxt.contains("without tool messages"));
    }

    /**
     * Heals history where an assistant {@code tool_use} never got a CONTIGUOUS answer (the turn ended
     * mid-tool via cancel/interrupt/error, or a non-tool message - a user prompt, notice, compaction
     * marker - was interposed between the assistant call and its result). Anthropic requires every
     * {@code tool_use} to be immediately answered by a {@code tool_result}, and Azure / litellm strict
     * validators reject an assistant {@code tool_calls} with no contiguous response ("an assistant message
     * with 'tool_calls' must be followed by tool messages responding to each 'tool_call_id'"). A synthetic
     * lost-result marker is inserted IN-MEMORY immediately after each affected assistant message - so
     * whatever follows (a freshly appended user prompt, later turns) never sits between the {@code tool_use}
     * and its result on the wire - and persisted via the append-only session store.
     *
     * <p>The heal is per-ASSISTANT-OCCURRENCE: each {@code tool_calls} block is checked against the
     * contiguous run of {@code tool} messages immediately following it, and a marker is placed right after
     * the block for every one of ITS OWN calls not answered by that run. A stray/orphan marker (or result)
     * that exists elsewhere for the same id does NOT suppress the heal - only a contiguously-following
     * result of that very block does. This is idempotent: a healed block (its calls already answered by the
     * immediately-following run, including a previously-inserted marker) is never re-healed.
     *
     * @param sessionId the session being resumed.
     * @param messages the working conversation, rebuilt per {@code loop()}, repaired in place.
     */
    private void repairInterruptedToolCalls(final String sessionId, final List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        // An assistant tool call is only "answered" when its result arrives CONTIGUOUSLY (the very next
        // tool messages, before any non-tool message) - the exact rule the OpenAI/Azure and Anthropic wire
        // backstops and their strict validators enforce. A response (or a lost-result marker) that landed
        // ANYWHERE in history but is separated from its assistant call by an interposed assistant/user/
        // notice message does NOT satisfy the contiguity rule: the assistant call gets a synthetic
        // lost-result marker placed IMMEDIATELY after its block, and the stray response is dropped.
        // Azure enforces BOTH directions of this adjacency ("an assistant message with 'tool_calls' must be
        // followed by tool messages responding to each 'tool_call_id'" AND "messages with role 'tool' must
        // be a response to a preceding message with 'tool_calls'"), so the healed history must carry
        // exactly one response per call id, in the contiguous position.
        //
        // The heal is per-OCCURRENCE and streaming: each assistant block opens a pending set of its own
        // call ids; the contiguous run of tool messages immediately following it claims those ids; any
        // tool message that does NOT claim a pending id is an orphan (a stray marker or a result whose
        // assistant block was interposed away) and is dropped; ids still pending when a non-tool message
        // (or the end of the history) is reached get their marker inserted right there, before whatever
        // interposes. Stray markers elsewhere no longer suppress the heal.
        final var persistedIds = new LinkedHashSet<String>();
        // ids that already carried a response somewhere in the pre-repair history: the marker(s) they
        // carried may be strays (dropped by this pass), but their session entry already exists, so the
        // healed id must NOT be persisted again - appending would add a new orphan marker at the tail of
        // the append-only session on every resume.
        final var persisted = new HashSet<String>();
        for (final var message : messages) {
            if ("tool".equals(message.role()) && message.toolCallId() != null) {
                persisted.add(message.toolCallId());
            }
        }
        final var repaired = new ArrayList<Message>(messages.size() + 8);
        final var text = "[tool result lost: the model's tool call was interrupted before it could complete this turn]";
        var droppedOrphans = 0;
        var healed = 0;
        var changed = false;
        var pending = new LinkedHashSet<String>();
        for (final var message : messages) {
            if (!"tool".equals(message.role())) {
                // a non-tool message (a newer assistant reply, a user prompt, a notice, the end of the
                // history) closes the previous assistant block's contiguous run: ids still awaiting a
                // result there are healed with a marker right before whatever now follows.
                if (!pending.isEmpty()) {
                    for (final var id : pending) {
                        repaired.add(Message.tool(id, text, true));
                        changed = true;
                        if (persisted.add(id)) {
                            healed++;
                            sessionStore.append(
                                    sessionId,
                                    SessionEntry.of(
                                            clock.millis(), "tool", text, null, id, null, Map.of("is_error", true)));
                        }
                    }
                    pending = new LinkedHashSet<>();
                }
                repaired.add(message);
                if ("assistant".equals(message.role()) && message.toolCalls() != null) {
                    for (final var call : message.toolCalls()) {
                        if (call != null && call.id() != null && pending.size() < MAX_REPAIR_PENDING_TOOL_IDS) {
                            pending.add(call.id());
                        }
                    }
                }
                continue;
            }
            if (pending.remove(message.toolCallId())) {
                repaired.add(message);
            } else {
                // orphan response: no contiguous assistant tool_calls claim it - Azure/litellm reject
                // "messages with role 'tool' must be a response to a tool call", so it must not reach
                // the wire (a marker for the same id, if needed, is inserted at its assistant block).
                droppedOrphans++;
                changed = true;
            }
        }
        for (final var id : pending) {
            repaired.add(Message.tool(id, text, true));
            changed = true;
            if (persisted.add(id)) {
                healed++;
                sessionStore.append(
                        sessionId,
                        SessionEntry.of(clock.millis(), "tool", text, null, id, null, Map.of("is_error", true)));
            }
        }
        if (!changed) {
            return;
        }
        messages.clear();
        messages.addAll(repaired);
        // only a TRUE first-time heal (the id carried no response anywhere before this repair) persists a
        // marker: an id whose stray marker existed already and was just dropped must not get a fresh tail
        // entry, or every resume would append another orphan marker at the end of the append-only session.
        emit(status("repaired " + healed + " interrupted tool call(s) whose result never landed"
                + (droppedOrphans == 0 ? "" : ", dropped " + droppedOrphans + " orphan tool response(s)")));
    }

    /**
     * Compact stale-read suffix appended to a successful write tool's own result message.
     * Directs the model to NOT re-read now (the write just applied and the model knows what it
     * wrote), but TO re-read before any later edit of the same file — so a future edit is never
     * based on a stale snapshot. The explicit negative+conditional phrasing matters for small
     * models that act on in-band instructions literally.
     */
    private static String staleNote(final List<Path> paths) {
        return "\n[stale read cleared: "
                + String.join(", ", paths.stream().map(Path::toString).toList())
                + " - don't re-read now; re-read only before a future edit]";
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

    /**
     * Diagnostic surfaced back to the model when it calls a tool that exists but was hidden from the current
     * request's wire list by the mode (e.g. EDIT tools in plan mode). Read-only tools (READ/AGENT categories)
     * are never hidden, so the tool is state-changing; the message says so and re-anchors the model to the
     * mode's rules instead of letting the call reach the approval gate.
     */
    private String hiddenToolDiagnostic(final String name) {
        final var plan = permissionService != null && permissionService.mode() == AgentMode.PLAN;
        if (plan) {
            return "\"" + name + "\" is not available in plan mode - file-mutating tools (edit_file, write_file,"
                    + " apply_patch) are only offered in build mode. If this is part of the plan, describe it and"
                    + " record it with write_todo; execution happens when the user switches to build mode.";
        }
        final var mode = permissionService == null
                ? "this"
                : permissionService.mode().name().toLowerCase(Locale.ROOT);
        return "\"" + name + "\" was not offered in this request's tools field and cannot be called in " + mode
                + " mode. Describe what you intended instead and wait for an appropriate mode.";
    }

    private void dispatch(
            final LlmStreamEvent delta, final io.yupiik.ia.agent.core.llm.Usage[] usage, final String[] finishReason) {
        if (delta instanceof TextDelta(final String text)) {
            if (turnState.thinkingActive) {
                turnState.thinkingMs += (precisionDurationMarker.now() - turnState.thinkingStartNanos) / 1_000_000;
                turnState.thinkingActive = false;
            }
            dispatchTextDelta(text);
        } else if (delta instanceof ReasoningDelta(final String text)) {
            if (!turnState.thinkingActive) {
                turnState.thinkingActive = true;
                turnState.thinkingStartNanos = precisionDurationMarker.now();
            }
            // record that this request produced reasoning: later, a truncation or an empty
            // completion after reasoning means the output budget was spent thinking, and a
            // context-identical retry would only re-derive the same thoughts (P3)
            turnState.lastRequestProducedReasoning = true;
            accumulatedThinking.append(text);
            emit(thinkingDelta(text));
        } else if (delta instanceof ReasoningItem item) {
            // capture the opaque encrypted_content blob for multi-turn reasoning replay; it never
            // produces visible output (the summary deltas did) and is not re-emitted as a delta
            accumulatedReasoningItems.add(item);
        } else if (delta instanceof End(final String reason, final Usage upper)) {
            // drain whatever the inline splitter still held for this generation (a trailing
            // inline-code backtick, a fence tail or an unclosed think delimiter split across the
            // last chunks), so no partial answer tail is lost at stream end
            dispatchInlineSplitterFlush();
            if (turnState.thinkingActive) {
                turnState.thinkingMs += (precisionDurationMarker.now() - turnState.thinkingStartNanos) / 1_000_000;
                turnState.thinkingActive = false;
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
                        Math.toIntExact(upper.reasoning()),
                        upper.generationTime());
            }
            turnState.llmCalls++;
            // usage is recorded once per request after the stream completes (single record point,
            // otherwise requests and tokens are double counted)
        }
    }

    private void emitUsage(final List<Message> messages, final int toolsTokens) {
        final var effective = usageTracker.effectiveContext(messages, toolsTokens);
        lastUsageContextTokens = effective;
        lastUsageContextPercent = usageTracker.contextPercent(effective);
        emit(usageUpdated(
                currentUsage(),
                effective,
                lastUsageContextPercent,
                usageTracker.lastRealPromptTokens(),
                usageTracker.calibrationRatio(),
                requestModel(resolvedModel()),
                currentQuota()));
    }

    /**
     * Emits a quota-only {@link UsageUpdated} (the frontends' single source for the sidebar gauge)
     * with the session's current token metrics. Used at provider/model switch so the TUI and web
     * reflect the new provider's account usage immediately, and by {@code /usage} so a forced
     * refresh reaches the sidebar instead of only the command's table. The context fields are the
     * last values a real turn published (never recomputed from an empty list), so the push refreshes
     * the quota without clobbering the live context gauge.
     */
    public void pushUsageQuota() {
        if (emitter == null) {
            return;
        }
        final var effective = lastUsageContextTokens >= 0 ? lastUsageContextTokens : 0;
        emit(usageUpdated(
                currentUsage(),
                effective,
                lastUsageContextPercent >= 0 ? lastUsageContextPercent : 0,
                usageTracker == null ? 0 : usageTracker.lastRealPromptTokens(),
                usageTracker == null ? -1 : usageTracker.calibrationRatio(),
                requestModel(resolvedModel()),
                currentQuota()));
    }

    private io.yupiik.ia.agent.core.llm.Usage currentUsage() {
        if (usageTracker == null || usageTracker.promptTokens() <= 0) {
            return io.yupiik.ia.agent.core.llm.Usage.EMPTY;
        }
        return new io.yupiik.ia.agent.core.llm.Usage(
                (int) usageTracker.promptTokens(),
                (int) usageTracker.completionTokens(),
                (int) (usageTracker.promptTokens() + usageTracker.completionTokens()),
                usageTracker.cacheReadTokens(),
                0,
                (int) usageTracker.reasoningTokens(),
                0);
    }

    /** Reads the per-session cached account quota without causing a refresh. */
    public AgentEvent.UsageUpdated.Quota cachedQuotaSnapshot() {
        if (quotaStore == null) {
            return AgentEvent.UsageUpdated.Quota.NONE;
        }
        // currentSessionId is only set once a turn actually runs; before the first turn it is null, and
        // feeding a null key into the quota store's ConcurrentHashMap.get would NPE (surfacing on the
        // web snapshot path as a silently-hung session restore). Prefer the live id, else the stable one.
        final var id = currentSessionId != null ? currentSessionId : this.sessionId;
        final var view = quotaStore.cachedSnapshot(id);
        return AgentEvent.UsageUpdated.Quota.of(view.enabled(), view.supported(), view.snapshot());
    }

    /** The current account quota for this session, refreshed at most once per {@code QuotaSnapshotStore}
     * TTL. Returns {@link AgentEvent.UsageUpdated.Quota#NONE} when the current provider has no usage
     * capability (so UIs hide the gauge).
     */
    private AgentEvent.UsageUpdated.Quota currentQuota() {
        if (quotaStore == null) {
            return AgentEvent.UsageUpdated.Quota.NONE;
        }
        final var view = quotaStore.snapshot(currentSessionId, false);
        return AgentEvent.UsageUpdated.Quota.of(view.enabled(), view.supported(), view.snapshot());
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
