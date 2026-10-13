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
package io.yupiik.ia.agent.core.shell.btw;

import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.lifecycle.Destroy;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.configuration.AgentMode;
import io.yupiik.ia.agent.core.configuration.BtwConfiguration;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.PermissionMode;
import io.yupiik.ia.agent.core.context.UsageTracker;
import io.yupiik.ia.agent.core.events.EventContext;
import io.yupiik.ia.agent.core.events.HookResult;
import io.yupiik.ia.agent.core.events.ToolUsePreEvent;
import io.yupiik.ia.agent.core.events.hooks.EventContextBuilder;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatCompletionRequest;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.Tool;
import io.yupiik.ia.agent.core.llm.ToolCall;
import io.yupiik.ia.agent.core.llm.ToolCallMarkupParser;
import io.yupiik.ia.agent.core.llm.ToolCallMarkupStripper;
import io.yupiik.ia.agent.core.permissions.ApprovalGate;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.session.SessionStore;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.tools.ToolExecutionService;
import io.yupiik.ia.agent.core.tools.ToolRegistry;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.function.BooleanSupplier;

/**
 * Answers /btw side questions out of band: a bounded read-only tool loop over the inherited
 * session history, gated by the plan-mode permissions and the session approval gate. Tools
 * are limited to the read-only category plus the bash tool evaluated against the plan-mode
 * command rules (never inheriting the session permission mode). Nothing is written to the session store,
 * so the main conversation and its persistence are never affected.
 */
@ApplicationScoped
public class BtwAnswerer {
    static final int MAX_RECALL_EXCHANGES = 20;

    /** Bound on the model round-trips of a single /btw answer, tool round-trips included. */
    static final int MAX_TOOL_ROUNDS = 6;

    static final String SYSTEM_PROMPT =
            "You answer quick side questions ('by the way') asked while a coding agent session is running. "
                    + "The session conversation is provided for context; the reply being streamed, if any, is not part of it. "
                    + "You can use the declared read-only tools (file/command readers, searches, and the read-only shell "
                    + "commands allowed in plan mode) to inspect the project when it helps answering, exactly like plan mode. "
                    + "You cannot modify anything: write/edit tools are never available and shell commands are restricted to "
                    + "the plan-mode read-only allow-list - a refused or failed tool call just means it is not allowed, so "
                    + "fall back to what you can read or to your own knowledge. Answer concisely and in markdown, and never "
                    + "claim to have executed anything you did not.";

    private final ChatClient chatClient;
    private final SessionStore sessionStore;
    private final CodingAgentConfiguration configuration;
    private final ExecutorService executor;
    private final ToolCallMarkupStripper toolCallMarkup;
    private final ToolCallMarkupParser toolCallMarkupParser;
    private final ToolRegistry toolRegistry;
    private final ToolExecutionService toolExecutionService;
    private final JsonMapper mapper;
    private final ProviderManager providerManager;
    private final Clock clock;
    private final Emitter emitter;
    private final EventContextBuilder eventContextBuilder;
    private final Deque<BtwExchange> exchanges = new ConcurrentLinkedDeque<>();
    private volatile ChatClient dedicatedClient;
    private volatile ChatSettings dedicatedSettings;

    public BtwAnswerer(
            final ChatClient chatClient,
            final SessionStore sessionStore,
            final CodingAgentConfiguration configuration,
            final ExecutorService executor,
            final ToolCallMarkupStripper toolCallMarkup,
            final ToolCallMarkupParser toolCallMarkupParser,
            final ToolRegistry toolRegistry,
            final ToolExecutionService toolExecutionService,
            final JsonMapper mapper,
            final ProviderManager providerManager,
            final Clock clock,
            final Emitter emitter,
            final EventContextBuilder eventContextBuilder) {
        this.chatClient = chatClient;
        this.sessionStore = sessionStore;
        this.configuration = configuration;
        this.executor = executor;
        this.toolCallMarkup = toolCallMarkup;
        this.toolCallMarkupParser = toolCallMarkupParser;
        this.toolRegistry = toolRegistry;
        this.toolExecutionService = toolExecutionService;
        this.mapper = mapper;
        this.providerManager = providerManager;
        this.clock = clock;
        this.emitter = emitter;
        this.eventContextBuilder = eventContextBuilder;
    }

    public record BtwExchange(String question, String answer) {}

    public CompletionStage<String> ask(
            final String question,
            final String sessionId,
            final UsageTracker usage,
            final BooleanSupplier cancelled,
            final ApprovalGate sessionGate) {
        return ask(question, sessionId, usage, cancelled, sessionGate, null);
    }

    /** @param sessionSettings the requesting tab's selection; null means the process default provider/model */
    public CompletionStage<String> ask(
            final String question,
            final String sessionId,
            final UsageTracker usage,
            final BooleanSupplier cancelled,
            final ApprovalGate sessionGate,
            final ChatSettings sessionSettings) {
        final var future = new CompletableFuture<String>();
        executor.execute(() -> {
            try {
                future.complete(doAsk(question, sessionId, usage, cancelled, sessionGate, sessionSettings));
            } catch (final IOException e) {
                future.completeExceptionally(e);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                future.completeExceptionally(e);
            } catch (final RuntimeException e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    String doAsk(
            final String question,
            final String sessionId,
            final UsageTracker usage,
            final BooleanSupplier cancelled,
            final ApprovalGate sessionGate,
            final ChatSettings sessionSettings)
            throws IOException, InterruptedException {
        final var conf = configuration();
        final var bounded = tail(sessionStore.load(sessionId), conf.maxHistoryMessages());
        final var messages = new ArrayList<Message>(bounded.size() + 2);
        messages.add(Message.system(SYSTEM_PROMPT));
        messages.addAll(bounded);
        messages.add(Message.user(userPrompt(conf, question)));
        final var model = conf.model() == null || conf.model().isBlank() ? null : conf.model();
        final var target = client(model);
        // /btw never inherits the session permission mode: plan-mode rules only, and the caller's
        // approval gate (the tabs share it, so an approval pops above the btw modal) decides the rest.
        final var permissions = new PermissionService(configuration, emitter);
        permissions.setMode(AgentMode.PLAN);
        permissions.setPermissionMode(PermissionMode.ASK);
        permissions.setGate(sessionGate);
        final var tools = toolRegistry == null
                ? List.<Tool>of()
                : toolRegistry.toWireTools(t -> t.category() == ToolCategory.READ || t.category() == ToolCategory.BASH);
        var retried = false;
        String raw = "";
        for (var round = 0; round < MAX_TOOL_ROUNDS; round++) {
            if (cancelled.getAsBoolean()) {
                throw new InterruptedException("cancelled");
            }
            var requestSettings = target.settings();
            if (sessionSettings != null) {
                requestSettings =
                        requestSettings == null ? sessionSettings : requestSettings.inheritFrom(sessionSettings);
            }
            final var res = target.client()
                    .chat(
                            new ChatCompletionRequest(
                                            model,
                                            messages,
                                            null,
                                            null,
                                            false,
                                            tools,
                                            "auto",
                                            conf.maxTokens() > 0 ? conf.maxTokens() : null,
                                            null,
                                            null)
                                    .withSettings(requestSettings),
                            cancelled);
            if (usage != null) {
                usage.recordAuxiliary(res.usage());
            }
            final var content = res.message() == null ? null : res.message().content();
            raw = content == null ? "" : String.valueOf(content);
            final var nativeCalls = res.message() == null ? null : res.message().toolCalls();
            final var calls =
                    nativeCalls != null && !nativeCalls.isEmpty() ? nativeCalls : toolCallMarkupParser.parse(raw);
            if (calls.isEmpty()) {
                final var answer = toolCallMarkup.strip(raw);
                if (!answer.isBlank()) {
                    remember(conf, new BtwExchange(question, answer));
                    return answer;
                }
                // truncated-while-thinking signature: tokens were spent but nothing came out.
                // Retry once, then fall back to a note instead of an exception.
                if (!retried && res.usage() != null && res.usage().completionTokens() > 0) {
                    retried = true;
                    continue;
                }
                return "The model returned an empty answer. Check the output budget or retry the side question.";
            }
            final var prose = toolCallMarkup.strip(raw);
            messages.add(Message.assistant(prose.isBlank() ? null : prose, calls));
            for (final var call : calls) {
                final var outcome = runTool(target, call, sessionId, usage, cancelled, permissions);
                messages.add(Message.tool(call.id(), outcome.output(), !outcome.success()));
            }
        }
        // round cap reached while the model kept calling tools: surface the glitch.
        return glitchNote(raw);
    }

    /** Executes a single /btw tool call, mirroring the main loop's authorisation and bounding. */
    private ToolOutcome runTool(
            final Target target,
            final ToolCall call,
            final String sessionId,
            final UsageTracker usage,
            final BooleanSupplier cancelled,
            final PermissionService permissions) {
        final var function = call.function();
        final var name = function == null || function.name() == null ? "" : function.name();
        final var argsJson = function == null || function.arguments() == null ? "{}" : function.arguments();
        final var tool = toolRegistry.tool(name);
        if (tool == null) {
            return new ToolOutcome(call.id(), "unknown tool", false);
        }
        final Map<String, Object> args;
        try {
            // mirror the main loop's parsing: never trust the model, garbage in -> invalid args
            final var parsed = mapper.fromString(Object.class, argsJson.isBlank() ? "{}" : argsJson);
            args = parsed instanceof final Map<?, ?> map ? castArgs(map) : new LinkedHashMap<>();
        } catch (final RuntimeException invalid) {
            return new ToolOutcome(call.id(), "(invalid arguments: " + invalid.getMessage() + ")", false);
        }
        final var inline = permissions.check(name, tool.category(), args, tool.readonly());
        if (!inline.allowed()) {
            return new ToolOutcome(
                    call.id(),
                    "Permission denied by user" + (inline.reason() == null ? "" : ": " + inline.reason()),
                    false);
        }
        // the built-in plan-mode check is authoritative inline (so a /btw denial is never dependent on the global
        // observer wiring); the ToolUsePreEvent is still emitted so configured yca.hooks.ToolUsePreEvent callbacks
        // run for allowed calls. The built-in observer re-checking the same permissionService is a harmless fast path.
        final var toolUseResult = new HookResult();
        final var workdir = args.get("workdir") instanceof final String w && !w.isBlank() ? w : null;
        final var eventContext = eventContextBuilder == null
                ? new EventContext(sessionId, null, null, null, null, null, null, null)
                : eventContextBuilder.build(permissions, target.settings(), sessionId, null, null, workdir);
        emitter.emit(new ToolUsePreEvent(
                eventContext, name, args, call.id(), toolUseResult, permissions, tool.category(), tool.readonly()));
        if (toolUseResult.forbidden()) {
            return new ToolOutcome(
                    call.id(),
                    "Permission denied by user" + (toolUseResult.reason() == null ? "" : ": " + toolUseResult.reason()),
                    false);
        }
        final var context = new ToolContext(
                sessionId,
                null,
                null,
                permissions,
                null,
                null,
                null,
                usage,
                null,
                cancelled,
                target.settings(),
                null,
                null);
        final var result = toolExecutionService.execute(tool, args, context);
        final var output = result.output() == null || result.output().isBlank() ? "(no output)" : result.output();
        return new ToolOutcome(call.id(), capIngestionOutput(output), result.success());
    }

    private record ToolOutcome(String callId, String output, boolean success) {}

    @SuppressWarnings("unchecked")
    private Map<String, Object> castArgs(final Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private String capIngestionOutput(final String output) {
        final var cap = configuration.pruning().ingestionOutputCapChars();
        if (cap <= 0 || output == null || output.length() <= cap) {
            return output;
        }
        final var half = cap / 2;
        return output.substring(0, half)
                + "\n[... output truncated (" + output.length() + " chars), kept head and tail ...]\n"
                + output.substring(output.length() - half);
    }

    /** Renders a tool-call-only answer as a note plus the raw output, in markdown. */
    private String glitchNote(final String raw) {
        return "> The model kept calling tools instead of answering this side question. Raw output:\n\n"
                + "```\n"
                + raw
                + "\n```";
    }

    /** The client to use plus the settings riding the call: the shared one with no override, or a dedicated one. */
    private record Target(ChatClient client, ChatSettings settings) {}

    private Target client(final String model) {
        if (model == null) {
            return new Target(chatClient, null);
        }
        final var dedicated = dedicatedClient;
        if (dedicated != null) {
            return new Target(dedicated, dedicatedSettings);
        }
        synchronized (this) {
            if (dedicatedClient == null) {
                final var created = new ChatClient(configuration, mapper, providerManager, clock);
                final var settings = ChatSettings.EMPTY.withModel(model);
                dedicatedClient = created;
                dedicatedSettings = settings;
            }
            return new Target(dedicatedClient, dedicatedSettings);
        }
    }

    @Destroy
    public void release() {
        if (dedicatedClient != null) {
            dedicatedClient.close();
        }
    }

    private BtwConfiguration configuration() {
        final var slashcommands = configuration.slashcommands();
        return slashcommands == null || slashcommands.btw() == null ? BtwConfiguration.DEFAULT : slashcommands.btw();
    }

    private List<Message> tail(final List<Message> history, final int max) {
        if (max <= 0 || history.size() <= max) {
            return history;
        }
        return history.subList(history.size() - max, history.size());
    }

    private String userPrompt(final BtwConfiguration conf, final String question) {
        final var sb = new StringBuilder();
        final List<BtwExchange> recent = recent(conf.recall());
        if (!recent.isEmpty()) {
            sb.append("Earlier side questions in this session (context only):\n\n");
            for (final var exchange : recent) {
                sb.append("Q: ").append(exchange.question()).append('\n');
                sb.append("A: ").append(exchange.answer()).append("\n\n");
            }
        }
        sb.append("Side question: ").append(question);
        return sb.toString();
    }

    private List<BtwExchange> recent(final int recall) {
        synchronized (exchanges) {
            final var limit = Math.min(recall, MAX_RECALL_EXCHANGES);
            if (limit <= 0 || exchanges.isEmpty()) {
                return List.of();
            }
            return exchanges.stream()
                    .skip(Math.max(0, exchanges.size() - limit))
                    .toList();
        }
    }

    private void remember(final BtwConfiguration conf, final BtwExchange exchange) {
        final var limit = Math.min(conf.recall(), MAX_RECALL_EXCHANGES);
        if (limit <= 0) {
            return;
        }
        synchronized (exchanges) {
            exchanges.addLast(exchange);
            while (exchanges.size() > limit) {
                exchanges.pollFirst();
            }
        }
    }
}
