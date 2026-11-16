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
package io.yupiik.ia.agent.slack.command;

import io.yupiik.fusion.framework.api.configuration.Configuration;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.cli.Command;
import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClientConfiguration;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.bot.runtime.SlotLease;
import io.yupiik.ia.agent.bot.runtime.admission.HostAdmission;
import io.yupiik.ia.agent.bot.runtime.admission.HostAdmissionConfiguration;
import io.yupiik.ia.agent.slack.configuration.SlackConfiguration;
import io.yupiik.ia.agent.slack.configuration.SlackExecutorConfiguration;
import io.yupiik.ia.agent.slack.configuration.SlackPrompts;
import io.yupiik.ia.agent.slack.configuration.SlackProviderConfiguration;
import io.yupiik.ia.agent.slack.service.SlackAdmission;
import io.yupiik.ia.agent.slack.service.SlackFeedback;
import io.yupiik.ia.agent.slack.service.SlackPromptRenderer;
import io.yupiik.ia.agent.slack.service.SlackSandbox;
import io.yupiik.ia.agent.slack.service.SlackWorker;
import io.yupiik.ia.agent.slack.store.SlackStore;
import io.yupiik.ia.agent.slack.transport.SlackSocketMode;
import io.yupiik.ia.agent.slack.transport.SlackWebApi;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.logging.Logger;

@DefaultScoped
@Command(
        name = {"slack", "bot", "run"},
        description = "Run the outbound Slack Socket Mode controller and bounded workers.")
public class RunCommand implements Runnable {
    private final Options options;
    private final SlackConfiguration configuration;
    private final SlackExecutorConfiguration executor;
    private final SlackProviderConfiguration provider;
    private final Configuration sources;
    private final SlackPrompts prompts;
    private final JsonMapper json;
    private final SlackStore store;
    private final SlackAdmission admission;
    private final SlackPromptRenderer renderer;
    private final HostAdmissionConfiguration hostBudget;

    public RunCommand(
            final Options options,
            final SlackConfiguration configuration,
            final SlackExecutorConfiguration executor,
            final SlackProviderConfiguration provider,
            final Configuration sources,
            final SlackPrompts prompts,
            final JsonMapper json,
            final SlackStore store,
            final SlackAdmission admission,
            final SlackPromptRenderer renderer,
            final HostAdmissionConfiguration hostBudget) {
        this.options = options;
        this.configuration = configuration;
        this.executor = executor;
        this.provider = provider;
        this.sources = sources;
        this.prompts = prompts;
        this.json = json;
        this.store = store;
        this.admission = admission;
        this.hostBudget = hostBudget;
        this.renderer = renderer;
    }

    @RootConfiguration("-")
    public record Options(
            @Property(
                    defaultValue = "1",
                    documentation = "Maximum active agent attempts, independent of Slack HTTP concurrency.")
            int parallelism,

            @Property(
                    defaultValue = "\"https://slack.com/api/\"",
                    documentation = "Trusted Slack API base, normally never overridden.")
            String apiBase,

            @Property(defaultValue = "20", documentation = "Slack HTTP request timeout seconds.")
            int httpTimeoutSeconds,

            @Property(defaultValue = "1048576", documentation = "Maximum Slack envelope/API bytes.")
            int maxTransportBytes,

            @Property(defaultValue = "1", documentation = "Minimum reconnect delay seconds.")
            int reconnectMinSeconds,

            @Property(
                    defaultValue = "30",
                    documentation = "Maximum normal reconnect delay seconds; Retry-After takes precedence.")
            int reconnectMaxSeconds) {
        public Options {
            if (parallelism < 1
                    || parallelism > 16
                    || httpTimeoutSeconds < 1
                    || httpTimeoutSeconds > 120
                    || maxTransportBytes < 1024
                    || maxTransportBytes > 4 * 1024 * 1024
                    || reconnectMinSeconds < 1
                    || reconnectMaxSeconds < reconnectMinSeconds
                    || reconnectMaxSeconds > 300) {
                throw new IllegalArgumentException("Invalid Slack run concurrency or transport bounds");
            }
        }
    }

    @Override
    public void run() {
        configuration.validateInstallation();
        if (configuration.agentConfiguration() == null
                || configuration.agentConfiguration().isBlank()) {
            throw new IllegalArgumentException("Configure slack.agentConfiguration with trusted JSON");
        }
        store.list(); // Validate/create private state before opening any remote connection.
        final var stopped = new CountDownLatch(1);
        final var cleaned = new CountDownLatch(1);
        final var hook = Thread.ofPlatform().unstarted(() -> {
            stopped.countDown();
            boolean interrupted = false;
            while (cleaned.getCount() > 0) {
                try {
                    cleaned.await();
                } catch (final InterruptedException stopping) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        Runtime.getRuntime().addShutdownHook(hook);
        try (final var lease = SlotLease.tryAcquire(configuration.stateRoot().resolve("runner.lock"))
                        .orElseThrow(() -> new IllegalStateException("Another Slack controller owns this state root"));
                final var http = HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .connectTimeout(Duration.ofSeconds(options.httpTimeoutSeconds()))
                        .build();
                final var extended = new ExtendedHttpClient(new ExtendedHttpClientConfiguration().setDelegate(http));
                final var schedule = Executors.newSingleThreadScheduledExecutor(
                        Thread.ofVirtual().factory());
                final var sandbox =
                        new SlackSandbox(executor.shared(), provider, sources, json, configuration.stateRoot());
                final var workers = Executors.newFixedThreadPool(
                        options.parallelism(), Thread.ofVirtual().factory())) {
            final var api = new SlackWebApi(
                    json,
                    extended,
                    new SlackWebApi.Settings(
                            URI.create(options.apiBase()),
                            Duration.ofSeconds(options.httpTimeoutSeconds()),
                            options.maxTransportBytes(),
                            options.maxTransportBytes()),
                    () -> token(configuration.appTokenEnv(), "xapp-"),
                    () -> token(configuration.botTokenEnv(), "xoxb-"));
            token(configuration.appTokenEnv(), "xapp-");
            token(configuration.botTokenEnv(), "xoxb-");
            final var feedback = new SlackFeedback(configuration, prompts, store, api);
            final var hostAdmission = new HostAdmission(hostBudget);
            hostAdmission.validateDisjoint(configuration.stateRoot(), Path.of(executor.attemptRoot()));
            final var worker = new SlackWorker(
                    configuration, executor.shared(), json, store, renderer, sandbox, feedback, api, hostAdmission);
            worker.recover();
            try (final var socket = new SlackSocketMode(
                    json,
                    api,
                    http,
                    schedule,
                    new SlackSocketMode.Settings(
                            Duration.ofSeconds(options.httpTimeoutSeconds()),
                            Duration.ofSeconds(options.reconnectMinSeconds()),
                            Duration.ofSeconds(options.reconnectMaxSeconds()),
                            options.maxTransportBytes()),
                    envelope -> {
                        if (envelope.get("payload") instanceof Map<?, ?> raw) {
                            @SuppressWarnings("unchecked")
                            final var payload = (Map<String, Object>) raw;
                            if ("events_api".equals(envelope.get("type"))) {
                                admission.admit(payload);
                            } else if ("interactive".equals(envelope.get("type"))) {
                                return feedback.interaction(payload);
                            }
                        }
                        return Optional.empty();
                    },
                    () -> ThreadLocalRandom.current().nextDouble())) {
                for (int index = 0; index < options.parallelism(); index++) {
                    workers.submit(() -> {
                        try {
                            while (stopped.getCount() > 0
                                    && !Thread.currentThread().isInterrupted()) {
                                if (!worker.runOne()) {
                                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(100));
                                }
                            }
                        } catch (final RuntimeException failure) {
                            Logger.getLogger(RunCommand.class.getName())
                                    .severe(
                                            "Slack worker stopped; inspect private durable task state before restarting");
                            stopped.countDown();
                        }
                    });
                }
                socket.start();
                try {
                    stopped.await();
                } finally {
                    workers.shutdownNow();
                    // Do not release runtime ownership while any admitted worker may still be stopping a sandbox.
                    while (!workers.awaitTermination(1, TimeUnit.SECONDS)) {
                        // Confirmation, not a guessed grace interval, controls ownership release.
                    }
                }
            }
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (final IOException failure) {
            throw new IllegalStateException("Cannot acquire private Slack controller ownership");
        } finally {
            cleaned.countDown();
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (final IllegalStateException stopping) {
                // VM shutdown is already in progress.
            }
        }
    }

    private String token(final String environment, final String prefix) {
        final var value = sources.get(environment).orElse(null);
        if (value == null || !value.startsWith(prefix) || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(
                    "Missing or invalid Slack credential in configured environment variable");
        }
        return value;
    }
}
