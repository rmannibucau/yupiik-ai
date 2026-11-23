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
package io.yupiik.ia.agent.acp;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.agent.AgentLoop;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationLoader;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.llm.ModelsResponse;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.question.UserFeedbackService;
import io.yupiik.ia.agent.core.session.AgentSessionService;
import io.yupiik.ia.agent.core.shell.SessionRuntime;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import java.nio.file.Path;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Predicate;

/** Real configuration/resolution with controlled transport and tiny session loops. */
final class OptionTestSupport implements AutoCloseable {
    final CodingAgentConfiguration configuration;
    final ProviderManager providers;
    final ChatClient chat;
    final AcpSessionOptions options;
    final AcpCommand.Conf commandOptions;

    OptionTestSupport(final JsonMapper mapper, final Function<ChatSettings, CompletableFuture<ModelsResponse>> fetch) {
        this(mapper, fetch, Map.of());
    }

    OptionTestSupport(
            final JsonMapper mapper,
            final Function<ChatSettings, CompletableFuture<ModelsResponse>> fetch,
            final Map<String, String> overrides) {
        final var props = new HashMap<String, String>();
        props.put("yca.provider.providers.length", "2");
        props.put("yca.provider.defaultProvider", "a");
        for (int i = 0; i < 2; i++) {
            final var prefix = "yca.provider.providers." + i;
            props.put(prefix + ".id", i == 0 ? "a" : "default");
            props.put(prefix + ".model", i == 0 ? "a-main" : "b-main");
            props.put(prefix + ".tuning.variants.length", "2");
            props.put(prefix + ".tuning.variants.0.name", "low");
            props.put(prefix + ".tuning.variants.0.reasoningEffort", "low");
            props.put(prefix + ".tuning.variants.1.name", "high");
            props.put(prefix + ".tuning.variants.1.reasoningEffort", "high");
            props.put(prefix + ".tuning.defaultVariant", "high");
            props.put(prefix + ".models.length", "1");
            props.put(prefix + ".models.0.model", "matching-prefix");
        }
        props.put("yca.model.compact.name", "$a/compact-only");
        props.put("yca.model.memory.name", "$a/memory-only");
        props.putAll(overrides);
        commandOptions = ConfigurationLoader.load(AcpCommand.Conf.class, key -> Optional.ofNullable(props.get(key)));
        configuration = ConfigurationLoader.load(
                io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration.class,
                key -> Optional.ofNullable(props.get(key)));
        providers = new ProviderManager(configuration, mapper);
        chat = new ChatClient(configuration, mapper, providers, Clock.systemUTC(), null) {
            @Override
            public CompletableFuture<ModelsResponse> modelsAsync(final ChatSettings settings) {
                return fetch.apply(settings);
            }
        };
        options = new AcpSessionOptions(
                chat,
                providers,
                new AgentSessionService(chat, null, null, null, null, null, null, null, configuration, null, null));
    }

    SessionRuntime runtime(final ChatSettings initial, final Path workdir) {
        return runtime(initial, settings -> true, workdir);
    }

    SessionRuntime runtime(final ChatSettings initial, final Predicate<ChatSettings> accept, final Path workdir) {
        final var loop = new AgentLoop(workdir) {
            private volatile ChatSettings settings = initial;

            @Override
            public ChatSettings chatSettings() {
                return settings;
            }

            @Override
            public void updateChatSettings(final ChatSettings value) {
                if (accept.test(value)) {
                    settings = value;
                }
            }

            @Override
            public boolean iterationLimitReached() {
                return false;
            }

            @Override
            public void close() {}
        };
        return new SessionRuntime(
                loop,
                new PermissionService(configuration, workdir),
                new UserFeedbackService(),
                null,
                null,
                null,
                null,
                null,
                workdir);
    }

    @Override
    public void close() {
        chat.close();
    }
}
