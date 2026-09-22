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
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
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
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.MidStreamFailureException;
import io.yupiik.ia.agent.core.llm.PauseEvent;
import io.yupiik.ia.agent.core.llm.ToolCall;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.runtime.StatusReporter;
import io.yupiik.ia.agent.core.runtime.VirtualThreadExecutor;
import io.yupiik.ia.agent.core.session.SessionEntry;
import io.yupiik.ia.agent.core.session.SessionStore;
import io.yupiik.ia.agent.core.snapshot.SnapshotStore;
import io.yupiik.ia.agent.core.todo.TodoStore;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolExecutionService;
import io.yupiik.ia.agent.core.tools.ToolRegistry;
import io.yupiik.ia.agent.core.tools.ToolResult;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import io.yupiik.llm.client.ContextWindowExceededException;
import io.yupiik.llm.client.model.LlmStreamEvent;
import io.yupiik.llm.client.model.LlmStreamEvent.End;
import io.yupiik.llm.client.model.LlmStreamEvent.ReasoningDelta;
import io.yupiik.llm.client.model.LlmStreamEvent.TextDelta;
import io.yupiik.llm.client.model.Usage;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

@ApplicationScoped
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
    private final String subagentId;
    private final String subagentName;

    // Per-turn metrics tracking (reset at start of each doRun)
    private int turnLlmCalls;
    private long turnThinkingMs;
    private long turnToolSumMs;
    private long turnToolWallMs;
    private int turnToolsRun;
    private boolean thinkingActive;
    private long thinkingStartNanos;
    private long turnStartNanos;

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
        this.subagentId = subagentId;
        this.subagentName = subagentName;
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
        this.subagentId = null;
        this.subagentName = null;
    }

    public String run(
            final String prompt,
            final String agentName,
            final String sessionId,
            final List<Message> history,
            final BooleanSupplier cancelled) {
        return doRun(prompt, prompt, agentName, sessionId, history, cancelled);
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
        return doRun(lastUserPrompt(history), null, agentName, sessionId, history, cancelled);
    }

    private String doRun(
            final String promptForBuilding,
            final String userMessage,
            final String agentName,
            final String sessionId,
            final List<Message> history,
            final BooleanSupplier cancelled) {
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
        if (chatClient != null) {
            // surface provider throttles and retry backoffs: without this the UI only shows a
            // spinner while the resilience layer can silently wait up to minutes between attempts
            chatClient.setPauseListener(pause -> emit(
                    pause.kind() == PauseEvent.Pause.THROTTLE
                            ? status(pause.message())
                            : notice(AgentEvent.Notice.Level.INFO, pause.message())));
        }

        try {
            return loop(sessionId, messages, cancelled);
        } catch (final IOException e) {
            emit(error(e.getMessage()));
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
            if (chatClient != null) {
                chatClient.setPauseListener(null);
            }
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
            if (!"user".equals(m.role()) || !(m.content() instanceof String content)) {
                continue;
            }
            if (content.contains("<compacted-context>")
                    || content.contains("<pruned-context>")
                    || content.startsWith("... output truncated")
                    || content.startsWith("Continue from this summary.")) {
                continue;
            }
            return content;
        }
        return null;
    }

    private void emit(final AgentEvent event) {
        emitter.emit(event);
    }

    // Event helpers that include subagent info
    private AgentEvent.TextDelta textDelta(final String delta) {
        return new AgentEvent.TextDelta(delta, subagentId, subagentName);
    }

    private AgentEvent.TextReset textReset() {
        return new AgentEvent.TextReset(subagentId, subagentName);
    }

    private AgentEvent.ThinkingDelta thinkingDelta(final String delta) {
        return new AgentEvent.ThinkingDelta(delta, subagentId, subagentName);
    }

    private AgentEvent.ToolStarted toolStarted(final String name, final String args) {
        return new AgentEvent.ToolStarted(name, args, subagentId, subagentName);
    }

    private AgentEvent.ToolFinished toolFinished(final String name, final String output, final boolean success) {
        return new AgentEvent.ToolFinished(name, output, success, subagentId, subagentName);
    }

    private AgentEvent.ToolStream toolStream(final String name, final String fragment) {
        return new AgentEvent.ToolStream(name, fragment, subagentId, subagentName);
    }

    private AgentEvent.UsageUpdated usageUpdated(
            final io.yupiik.ia.agent.core.llm.Usage usage,
            final int estimatedContextTokens,
            final double contextPercent) {
        return new AgentEvent.UsageUpdated(usage, estimatedContextTokens, contextPercent, subagentId, subagentName);
    }

    private AgentEvent.Compacted compacted(final String kind, final String mode) {
        return new AgentEvent.Compacted(kind, mode, subagentId, subagentName);
    }

    private AgentEvent.Compacted compacted(final String kind) {
        return new AgentEvent.Compacted(kind, "SUMMARY", subagentId, subagentName);
    }

    private AgentEvent.Status status(final String message) {
        return new AgentEvent.Status(message, subagentId, subagentName);
    }

    private AgentEvent.Notice notice(final AgentEvent.Notice.Level level, final String message) {
        return new AgentEvent.Notice(level, message, null, subagentId, subagentName);
    }

    private AgentEvent.Notice notice(
            final AgentEvent.Notice.Level level, final String message, final String finishReason) {
        return new AgentEvent.Notice(level, message, finishReason, subagentId, subagentName);
    }

    private AgentEvent.TurnDone turnDone(final String content) {
        return new AgentEvent.TurnDone(content, subagentId, subagentName);
    }

    private AgentEvent.Error error(final String message) {
        return new AgentEvent.Error(message, subagentId, subagentName);
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
                subagentId,
                subagentName);
    }

    private AgentEvent.ToolTimingEvent toolTimingEvent(
            final String toolName, final long durationMs, final long wallDurationMs, final boolean parallelBatch) {
        return new AgentEvent.ToolTimingEvent(
                toolName, durationMs, wallDurationMs, parallelBatch, subagentId, subagentName);
    }

    private String loop(final String sessionId, final List<Message> messages, final BooleanSupplier cancelled)
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
        var toolsRanThisTurn = false;
        var toolDirectiveApplied = false;
        final var repeats = new ToolRepeats();
        final var staleReads = new StaleReads(readsFromHistory(messages));

        // Reset per-turn metrics
        turnLlmCalls = 0;
        turnThinkingMs = 0L;
        turnToolSumMs = 0L;
        turnToolWallMs = 0L;
        turnToolsRun = 0;
        thinkingActive = false;
        thinkingStartNanos = 0;
        turnStartNanos = System.nanoTime();
        for (var iteration = 0; iteration < maxIterations && !cancelled.getAsBoolean(); iteration++) {
            if (contextView != null) {
                contextView.publish(snapshot(messages, toolsTokens));
            }
            var compactedThisTurn = false;
            if (contextManager.pruneNeeded(messages, toolsTokens)) {
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
                final var compacted = contextManager.compact(messages);
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

            final var request =
                    new ChatCompletionRequest(model, messages, null, null, null, tools, "auto", null, null, null);
            final var usage = new io.yupiik.ia.agent.core.llm.Usage[] {io.yupiik.ia.agent.core.llm.Usage.EMPTY};
            final var finishReason = new String[] {null};
            final Message assistant;
            try {
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
                final var compacted = contextManager.compactForOverflow(messages);
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
            // anchor the hybrid usage on the real prompt tokens of the request we just sent: the
            // estimate at the same moment calibrates later deltas until the next observation
            usageTracker.anchor(usage[0].promptTokens(), usageTracker.estimateContext(messages, toolsTokens));
            messages.add(assistant);
            sessionStore.append(
                    sessionId,
                    SessionEntry.of(
                            clock.millis(), "assistant", str(assistant.content()), assistant.toolCalls(), null));
            emitUsage(messages, toolsTokens);

            if (assistant.toolCalls() == null || assistant.toolCalls().isEmpty()) {
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
                final var todos = unfinishedTodosNotice(toolsRanThisTurn, reason);
                if (todos != null) {
                    emit(todos);
                }
                emitTurnMetrics(content, turnStartNanos);
                return content;
            }
            executeToolCalls(sessionId, messages, assistant.toolCalls(), repeats, staleReads);
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
            emit(error("Cancelled"));
        } else {
            emit(error("Maximum iterations reached (" + maxIterations + ")"));
        }
        emitTurnMetrics(null, turnStartNanos);
        return null;
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
    private void applyToolDirective(
            final String sessionId,
            final List<Message> messages,
            final ContextView.Directive directive,
            final int toolsTokens) {
        final var result = "prune".equals(directive.action())
                ? contextManager.prune(messages)
                : contextManager.compact(messages, directive.focus(), directive.keepLast());
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
                    messages.get(0).role().equals("system") ? messages.subList(1, messages.size()) : messages);
        } catch (final RuntimeException e) {
            LOGGER.log(Level.WARNING, e, () -> "Could not persist the compacted session " + sessionId);
            emit(notice(AgentEvent.Notice.Level.WARN, "Compacted context could not be persisted: " + e.getMessage()));
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
                return chatClient.stream(request, delta -> dispatch(delta, usage, finishReason), cancelled);
            } catch (final MidStreamFailureException e) {
                last = e;
                if (attempt >= maxAttempts) {
                    break;
                }
                emit(textReset());
                emit(notice(
                        AgentEvent.Notice.Level.WARN,
                        "Stream interrupted (" + causeSummary(e) + "); retrying (" + attempt + '/' + maxAttempts
                                + ")."));
                Thread.sleep(streamRetryBackoffMs(attempt));
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

    private static String causeSummary(final MidStreamFailureException e) {
        final var cause = e.getCause();
        final var message = cause == null
                        || cause.getMessage() == null
                        || cause.getMessage().isBlank()
                ? e.getMessage()
                : cause.getMessage();
        return message == null ? "unknown transport failure" : message;
    }

    /**
     * A turn that ran tools but ends text-only while the todo list still has pending items looks
     * finished to the loop while the work is not: surface it so the user can resume explicitly
     * instead of silently losing the remaining steps. The notice is emitted with the normalized
     * {@code finish_reason} so a rich frontend can tell a deliberate, clean stop (the model chose
     * to end its turn despite pending work) apart from a truncation or an error, which are reported
     * by separate events.
     */
    private AgentEvent.Notice unfinishedTodosNotice(final boolean toolsRanThisTurn, final String finishReason) {
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
        if (pending == 0) {
            return null;
        }
        return new AgentEvent.Notice(
                AgentEvent.Notice.Level.WARN,
                "model ended its turn (finish_reason=" + finishReason + ") with " + pending + '/' + todos.size()
                        + " todos still pending - send 'continue' to resume, or review why it stopped",
                finishReason);
    }

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
            final StaleReads staleReads)
            throws InterruptedException {
        var i = 0;
        while (i < calls.size()) {
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
                            futures.add(executor.submit(() -> runTool(call)));
                        }
                    }
                    for (final var future : futures) {
                        appendToolResult(sessionId, messages, awaitTool(future), staleReads);
                    }
                    final var batchWallMs = (System.nanoTime() - batchStartNanos) / 1_000_000;
                    turnToolWallMs += batchWallMs;
                } else {
                    for (var j = i; j < end; j++) {
                        appendToolResult(sessionId, messages, runGuarded(calls.get(j), repeats), staleReads);
                    }
                }
                i = end;
            } else {
                appendToolResult(sessionId, messages, runGuarded(calls.get(i), repeats), staleReads);
                i++;
            }
        }
    }

    private ToolOutcome runGuarded(final ToolCall call, final ToolRepeats repeats) {
        final var repeated = repeats.rejectIfRepeated(call);
        if (repeated != null) {
            emit(toolFinished(repeated.name(), repeated.output(), false));
            return repeated;
        }
        return runTool(call);
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
    private ToolOutcome runTool(final ToolCall call) {
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
        final ToolResult result = toolExecutionService.execute(tool, args);
        final var toolDurationMs = (System.nanoTime() - toolStartNanos) / 1_000_000;
        emit(toolFinished(name, result.output(), result.success()));
        return new ToolOutcome(
                call.id(), name, result.output(), result.success(), true, touchedPaths(name, args), toolDurationMs);
    }

    /** Lands an executed outcome in the conversation and the session store, in call order. */
    private void appendToolResult(
            final String sessionId,
            final List<Message> messages,
            final ToolOutcome outcome,
            final StaleReads staleReads) {
        final var output = outcome.output() == null || outcome.output().isBlank() ? "(no output)" : outcome.output();
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
                        upper.cacheWrite());
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
