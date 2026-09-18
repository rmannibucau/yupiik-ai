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
import io.yupiik.ia.agent.core.session.SessionEntry;
import io.yupiik.ia.agent.core.session.SessionStore;
import io.yupiik.ia.agent.core.snapshot.SnapshotStore;
import io.yupiik.ia.agent.core.todo.TodoStore;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
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
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
            final TodoStore todoStore) {
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
    }

    public String run(
            final String prompt,
            final String agentName,
            final String sessionId,
            final List<Message> history,
            final BooleanSupplier cancelled) {
        final var agent = (agentName == null || agentName.isBlank() || agentProvider == null)
                ? null
                : agentProvider.byName(agentName);
        structuralCodeGraph.ensureReadyAsync();
        final var messages = new ArrayList<Message>();
        messages.add(Message.system(systemPromptBuilder.build(prompt, agent) + snapshotBlock(sessionId)));
        if (history != null) {
            messages.addAll(history);
        }
        messages.add(Message.user(prompt));
        sessionStore.append(sessionId, SessionEntry.of(clock.millis(), "user", prompt, null, null));
        statusReporter.setSink(message -> emitter.emit(new AgentEvent.Status(message)));
        if (chatClient != null) {
            // surface provider throttles and retry backoffs: without this the UI only shows a
            // spinner while the resilience layer can silently wait up to minutes between attempts
            chatClient.setPauseListener(pause -> emit(
                    pause.kind() == PauseEvent.Pause.THROTTLE
                            ? new AgentEvent.Status(pause.message())
                            : AgentEvent.Notice.info(pause.message())));
        }

        try {
            return loop(sessionId, messages, cancelled);
        } catch (final IOException e) {
            emit(new AgentEvent.Error(e.getMessage()));
            return null;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            emit(new AgentEvent.Error("Interrupted"));
            return null;
        } catch (final RuntimeException e) {
            LOGGER.log(Level.SEVERE, "agent turn failed", e);
            emit(new AgentEvent.Error("unexpected failure: " + e));
            return null;
        } finally {
            if (chatClient != null) {
                chatClient.setPauseListener(null);
            }
            statusReporter.setSink(null);
            memoryExtractor.maybeExtract(messages);
        }
    }

    private void emit(final AgentEvent event) {
        emitter.emit(event);
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
                    emit(new AgentEvent.Compacted("prune", pruned.mode().name()));
                    persistContext(sessionId, messages);
                    compactedThisTurn = true;
                }
            }
            if (contextManager.compactNeeded(messages, toolsTokens)) {
                final var compacted = contextManager.compact(messages);
                if (compacted.changed()) {
                    messages.clear();
                    messages.addAll(compacted.messages());
                    emit(new AgentEvent.Compacted("compact", compacted.mode().name()));
                    persistContext(sessionId, messages);
                    compactedThisTurn = true;
                }
            }
            if (compactedThisTurn) {
                consecutiveCompactions++;
                if (consecutiveCompactions >= maxConsecutiveCompactions
                        && usageTracker.contextPercent(usageTracker.effectiveContext(messages, toolsTokens))
                                >= configuration.compaction().thresholdPercent()) {
                    emit(new AgentEvent.Notice(
                            AgentEvent.Notice.Level.ERROR,
                            "Context keeps filling up right after compaction after " + consecutiveCompactions
                                    + " attempts. Consider /compact manually, a larger model, or splitting the task."));
                    break;
                }
            } else {
                consecutiveCompactions = 0;
            }

            final var request = new ChatCompletionRequest(model, messages, null, null, tools, "auto", null, null, null);
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
                            new AgentEvent.Notice(
                                    AgentEvent.Notice.Level.INFO,
                                    "Context window auto-detected: no exact figure was available, using a conservative default and learning on overflow."));
                    autoNoticeEmitted = true;
                }
                final var shrunken = usageTracker.rememberShrinkOnOverflow();
                emit(new AgentEvent.Notice(
                        AgentEvent.Notice.Level.INFO,
                        "Context window exceeded; compacting and retrying (assumed window reduced to " + shrunken
                                + " tokens)."));
                final var compacted = contextManager.compactForOverflow(messages);
                if (compacted.changed() && !compacted.messages().isEmpty()) {
                    messages.clear();
                    messages.addAll(compacted.messages());
                    emit(new AgentEvent.Compacted(
                            "compact-retry", compacted.mode().name()));
                    persistContext(sessionId, messages);
                    overflowRetried = true;
                    continue;
                }
                emit(new AgentEvent.Notice(
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
                        emit(AgentEvent.Notice.warn("Response truncated by the output token limit (finish_reason="
                                + reason + "); asking the model to continue (" + truncationContinues + '/'
                                + maxTruncationContinues + ")."));
                        messages.add(Message.user(
                                truncationContinues >= 2 ? TRUNCATION_SPLIT_PROMPT : TRUNCATION_CONTINUE_PROMPT));
                        continue;
                    }
                    emit(AgentEvent.Notice.error("Response truncated by the output token limit (finish_reason="
                            + reason + "); the reply may be incomplete."
                            + (configuration.llm().continueOnTruncation()
                                    ? " Gave up after " + truncationContinues + " continuation"
                                            + (truncationContinues == 1 ? "" : "s") + "."
                                    : " Enable yca.llm.continueOnTruncation to auto-continue.")));
                    emit(new AgentEvent.TurnDone(content));
                    return content;
                }
                final var todos = unfinishedTodosNotice(toolsRanThisTurn, reason);
                if (todos != null) {
                    emit(todos);
                }
                emit(new AgentEvent.TurnDone(content));
                return content;
            }
            for (final var call : assistant.toolCalls()) {
                executeTool(sessionId, messages, call);
                toolsRanThisTurn = true;
            }
            if (!toolDirectiveApplied && contextView != null) {
                final var directive = contextView.poll();
                if (directive != null) {
                    toolDirectiveApplied = true;
                    applyToolDirective(sessionId, messages, directive, toolsTokens);
                }
            }
        }
        if (cancelled.getAsBoolean()) {
            emit(new AgentEvent.Error("Cancelled"));
        } else {
            emit(new AgentEvent.Error("Maximum iterations reached (" + maxIterations + ")"));
        }
        return null;
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
            emit(AgentEvent.Notice.warn(
                    "manage_context " + directive.action() + " had nothing to reclaim; context left untouched."));
            return;
        }
        messages.clear();
        messages.addAll(result.messages());
        emit(new AgentEvent.Compacted("tool", result.mode().name()));
        persistContext(sessionId, messages);
        emit(new AgentEvent.Notice(
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
            emit(AgentEvent.Notice.warn("Compacted context could not be persisted: " + e.getMessage()));
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
                emit(new AgentEvent.TextReset());
                emit(AgentEvent.Notice.warn("Stream interrupted (" + causeSummary(e) + "); retrying (" + attempt + '/'
                        + maxAttempts + ")."));
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

    private void executeTool(final String sessionId, final List<Message> messages, final ToolCall call) {
        final var name = call.function() == null || call.function().name() == null
                ? ""
                : call.function().name();
        final var argsJson = call.function() == null || call.function().arguments() == null
                ? "{}"
                : call.function().arguments();
        final EmbeddedTool tool = toolRegistry.tool(name);
        if (tool == null) {
            messages.add(Message.tool(call.id(), "Unknown tool: " + name));
            emit(new AgentEvent.ToolFinished(name, unknownToolDiagnostic(name), false));
            return;
        }
        final Map<String, Object> args;
        try {
            final var parsed = mapper.fromString(Object.class, argsJson.isBlank() ? "{}" : argsJson);
            args = parsed instanceof Map<?, ?> m ? cast(m) : new LinkedHashMap<>();
        } catch (final RuntimeException e) {
            messages.add(Message.tool(call.id(), "Invalid tool arguments JSON: " + argsJson));
            emit(new AgentEvent.ToolFinished(name, "invalid arguments", false));
            return;
        }
        emit(new AgentEvent.ToolStarted(name, argsJson));
        final PermissionService.Decision decision =
                permissionService.check(name, tool.category(), args, tool.readonly());
        if (!decision.allowed()) {
            final var denial =
                    "Permission denied by user" + (decision.reason() == null ? "" : ": " + decision.reason());
            messages.add(Message.tool(call.id(), denial));
            emit(new AgentEvent.ToolFinished(name, denial, false));
            return;
        }
        final ToolResult result = toolExecutionService.execute(tool, args);
        messages.add(Message.tool(
                call.id(), result.output() == null || result.output().isBlank() ? "(no output)" : result.output()));
        sessionStore.append(sessionId, SessionEntry.of(clock.millis(), "tool", result.output(), null, name));
        emit(new AgentEvent.ToolFinished(name, result.output(), result.success()));
    }

    private static String unknownToolDiagnostic(final String name) {
        return "Unknown tool \"" + name + "\": not in this session's toolset (built-in or enabled MCP)."
                + " Run /tools to list the valid tools; only exact names from the request's tools field are accepted.";
    }

    private void dispatch(
            final LlmStreamEvent delta, final io.yupiik.ia.agent.core.llm.Usage[] usage, final String[] finishReason) {
        if (delta instanceof TextDelta(final String text)) {
            emit(new AgentEvent.TextDelta(text));
        } else if (delta instanceof ReasoningDelta(final String text)) {
            emit(new AgentEvent.ThinkingDelta(text));
        } else if (delta instanceof End(final String reason, final Usage upper)) {
            if (reason != null) {
                finishReason[0] = reason;
            }
            final var converted = upper == null
                    ? io.yupiik.ia.agent.core.llm.Usage.EMPTY
                    : new io.yupiik.ia.agent.core.llm.Usage(
                            Math.toIntExact(upper.promptTokens()),
                            Math.toIntExact(upper.completionTokens()),
                            Math.toIntExact(upper.totalTokens()));
            usage[0] = converted;
            usageTracker.record(converted);
        }
    }

    private void emitUsage(final List<Message> messages, final int toolsTokens) {
        final var effective = usageTracker.effectiveContext(messages, toolsTokens);
        emit(new AgentEvent.UsageUpdated(
                usageTracker.promptTokens() > 0
                        ? new io.yupiik.ia.agent.core.llm.Usage(
                                (int) usageTracker.promptTokens(), (int) usageTracker.completionTokens(), (int)
                                        (usageTracker.promptTokens() + usageTracker.completionTokens()))
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
