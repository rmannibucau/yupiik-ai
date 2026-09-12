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
package io.yupiik.ia.agent.tui;

import static java.time.Duration.ofMillis;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyModifiers;
import dev.tamboui.tui.event.PasteEvent;
import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.framework.api.RuntimeContainer;
import io.yupiik.fusion.framework.api.configuration.impl.MapConfigSource;
import io.yupiik.fusion.framework.api.container.FusionListener;
import io.yupiik.fusion.framework.api.container.bean.ProvidedInstanceBean;
import io.yupiik.fusion.framework.api.container.configuration.ConfigurationRegistration;
import io.yupiik.fusion.framework.api.main.ArgsConfigSource;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.ia.agent.core.agent.AgentLoop;
import io.yupiik.ia.agent.core.maven.MavenRepositoryKnowledgeService;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Boots the real fusion bean graph through autodiscovery (core + json + tui modules)
 * with a programmatically registered MapConfigSource pointing at {@link MockLlmServer}
 * and drives the TUI headlessly through the production {@link AgentLoop}.
 */
class TuiCommandHeadlessTest {
    private static final long TIMEOUT_MS = Duration.ofSeconds(30).toMillis();

    @TempDir
    Path temp;

    private MockLlmServer llm;
    private HeadlessBackend backend;
    private final AtomicReference<TuiRunner> tuiRef = new AtomicReference<>();
    private Thread uiThread;

    @BeforeEach
    void start() throws IOException {
        llm = new MockLlmServer();
        Files.createDirectories(temp.resolve("home").resolve("sessions"));
        backend = new HeadlessBackend();
    }

    @AfterEach
    void stop() throws InterruptedException {
        final var tui = tuiRef.get();
        if (tui != null && tui.isRunning()) {
            tui.quit();
        }
        if (uiThread != null) {
            uiThread.join(TIMEOUT_MS);
        }
        llm.close();
    }

    @Test
    void wiresTheFullBeanGraph() {
        try (final var container = container()) {
            assertNotNull(container.lookup(TuiCommand.class).instance());
            assertNotNull(container.lookup(RunCommand.class).instance());
            assertNotNull(container.lookup(TuiTranscriptModel.class).instance());
            assertNotNull(container.lookup(TuiRunnerFactory.class).instance());
        }
    }

    @Test
    void answersThroughRealAgentLoop() throws Exception {
        try (final var container = container();
                final var deps = container.lookup(TuiDeps.class)) {
            final var d = deps.instance();
            final var command = new TuiCommand(
                    new TuiCommand.TuiConf(KeyBindingPreset.STANDARD, true, "dark", true),
                    d.tuiConfiguration(),
                    d.configuration(),
                    d.agentLoop(),
                    d.bridge(),
                    d.permissionService(),
                    d.userFeedbackService(),
                    d.sessionStore(),
                    d.todoStore(),
                    d.chatClient(),
                    d.mcpManager(),
                    d.executor(),
                    d.providerManager(),
                    d.agentProvider(),
                    d.agentSessionService(),
                    new MavenRepositoryKnowledgeService(d.configuration(), null, d.executor(), null),
                    d.model(),
                    headlessFactory(),
                    new TuiSlashCommands(),
                    Clock.systemUTC());

            uiThread = new Thread(command::run, "tui-headless-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            tuiRef.get().dispatch(new PasteEvent("say hi to the mock"));
            awaitText("say hi to the mock");
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
            awaitText("hello from mock");
            awaitText("ready");

            final var sessions = temp.resolve("home").resolve("sessions");
            final long sessionDeadline = System.currentTimeMillis() + TIMEOUT_MS;
            var sessionCount = -1L;
            while (System.currentTimeMillis() < sessionDeadline) {
                sessionCount = Files.exists(sessions) ? Files.list(sessions).count() : 0;
                if (sessionCount >= 1) {
                    break;
                }
                Thread.sleep(25);
            }
            assertTrue(sessionCount >= 1, "expected a session file under " + sessions);
            assertTrue(
                    llm.requests().stream().anyMatch(r -> r.contains("say hi to the mock")),
                    "expected the prompt to reach the mock LLM");

            tuiRef.get().dispatch(KeyEvent.ofChar('c', KeyModifiers.CTRL));
            uiThread.join(TIMEOUT_MS);
        }
    }

    private RuntimeContainer container() {
        return ConfiguringContainer.of()
                .register(new ProvidedInstanceBean<>(
                        DefaultScoped.class,
                        ArgsConfigSource.class,
                        () -> new ArgsConfigSource(List.of("--run-prompt", "wiring"))))
                .register(new FusionListener<ConfigurationRegistration>() {
                    @Override
                    public Type eventType() {
                        return ConfigurationRegistration.class;
                    }

                    @Override
                    public void onEvent(
                            final RuntimeContainer container, final ConfigurationRegistration registration) {
                        registration
                                .addSource()
                                .accept(new MapConfigSource(Map.of(
                                        "yca.storageHome", temp.resolve("home").toString(),
                                        "yca.provider.baseUrl", llm.baseUrl(),
                                        "yca.provider.apiKey", "test-key",
                                        "yca.provider.model", "test-model",
                                        "yca.structuralCodeGraph.enabled", "false")));
                    }
                })
                .start();
    }

    private TuiRunnerFactory headlessFactory() {
        return new TuiRunnerFactory() {
            @Override
            public TuiRunner create(final TuiCommand.TuiConf tuiConf) throws Exception {
                final var runner = TuiRunner.create(TuiConfig.builder()
                        .backend(backend)
                        .mouseCapture(false)
                        .rawMode(false)
                        .alternateScreen(false)
                        .bracketedPaste(false)
                        .shutdownHook(false)
                        .tickRate(ofMillis(20))
                        .build());
                tuiRef.set(runner);
                return runner;
            }
        };
    }

    private void awaitTui() throws InterruptedException {
        final long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (tuiRef.get() == null) {
            assertTrue(System.currentTimeMillis() < deadline, "tui runner was not created in time");
            Thread.sleep(20);
        }
    }

    private void awaitText(final String expected) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        String screen = "";
        while (System.currentTimeMillis() < deadline) {
            screen = backend.text();
            if (screen.contains(expected)) {
                return;
            }
            Thread.sleep(25);
        }
        assertTrue(false, "expected screen to contain '" + expected + "' but was:\n" + screen);
    }
}
