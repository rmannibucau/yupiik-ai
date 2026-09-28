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

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.lifecycle.Destroy;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.configuration.BtwConfiguration;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.context.UsageTracker;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatCompletionRequest;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.ToolCallMarkupStripper;
import io.yupiik.ia.agent.core.runtime.VirtualThreadExecutor;
import io.yupiik.ia.agent.core.session.SessionStore;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.BooleanSupplier;

/**
 * Answers /btw side questions out of band: a single tool-less LLM round-trip over the
 * inherited session history. Nothing is written to the session store, so the main
 * conversation and its persistence are never affected.
 */
@ApplicationScoped
public class BtwAnswerer {
    static final int MAX_RECALL_EXCHANGES = 20;

    static final String SYSTEM_PROMPT =
            "You answer quick side questions ('by the way') asked while a coding agent session is running. "
                    + "The session conversation is provided for context; the reply being streamed, if any, is not part of it. "
                    + "You have no tools: answer from the provided context and your own knowledge only, and never claim "
                    + "to have executed anything. Never emit tool calls or tool-call markup (DSML or similar), even though "
                    + "the context shows past tool usage. Keep the answer concise and in markdown.";

    private final ChatClient chatClient;
    private final SessionStore sessionStore;
    private final CodingAgentConfiguration configuration;
    private final VirtualThreadExecutor executor;
    private final ToolCallMarkupStripper toolCallMarkup;
    private final JsonMapper mapper;
    private final ProviderManager providerManager;
    private final Clock clock;
    private final Deque<BtwExchange> exchanges = new ConcurrentLinkedDeque<>();
    private volatile ChatClient dedicatedClient;
    private volatile ChatSettings dedicatedSettings;

    public BtwAnswerer(
            final ChatClient chatClient,
            final SessionStore sessionStore,
            final CodingAgentConfiguration configuration,
            final VirtualThreadExecutor executor,
            final ToolCallMarkupStripper toolCallMarkup,
            final JsonMapper mapper,
            final ProviderManager providerManager,
            final Clock clock) {
        this.chatClient = chatClient;
        this.sessionStore = sessionStore;
        this.configuration = configuration;
        this.executor = executor;
        this.toolCallMarkup = toolCallMarkup;
        this.mapper = mapper;
        this.providerManager = providerManager;
        this.clock = clock;
    }

    public record BtwExchange(String question, String answer) {}

    public CompletionStage<String> ask(
            final String question, final String sessionId, final UsageTracker usage, final BooleanSupplier cancelled) {
        final var future = new CompletableFuture<String>();
        executor.execute(() -> {
            try {
                future.complete(doAsk(question, sessionId, usage, cancelled));
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
            final String question, final String sessionId, final UsageTracker usage, final BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        final var conf = configuration();
        final var bounded = tail(sessionStore.load(sessionId), conf.maxHistoryMessages());
        final var messages = new ArrayList<Message>(bounded.size() + 2);
        messages.add(Message.system(SYSTEM_PROMPT));
        messages.addAll(bounded);
        messages.add(Message.user(userPrompt(conf, question)));
        final var model = conf.model() == null || conf.model().isBlank() ? null : conf.model();
        final var target = client(model);
        final var res = target.client()
                .chat(
                        new ChatCompletionRequest(
                                null, messages, null, null, false, null, null, conf.maxTokens(), null, null),
                        target.settings(),
                        cancelled);
        if (usage != null) {
            usage.recordAuxiliary(res.usage());
        }
        final var content = res.message() == null ? null : res.message().content();
        final var raw = content == null ? "" : String.valueOf(content);
        final var answer = toolCallMarkup.strip(raw);
        if (answer.isBlank()) {
            if (raw.isBlank()) {
                throw new IOException("btw got an empty answer");
            }
            // The model emitted tool-call markup with no prose at all: surface the glitch
            // instead of an empty modal, and do not replay it into the next side questions.
            return glitchNote(raw);
        }
        remember(conf, new BtwExchange(question, answer));
        return answer;
    }

    /** Renders a tool-call-only answer as a note plus the raw output, in markdown. */
    private String glitchNote(final String raw) {
        return "> The model tried to call a tool instead of answering this side question. Raw output:\n\n"
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
                final var settings = new ChatSettings();
                settings.setModel(model);
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
