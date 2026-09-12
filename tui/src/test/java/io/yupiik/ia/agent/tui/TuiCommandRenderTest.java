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

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyModifiers;
import dev.tamboui.tui.event.PasteEvent;
import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.agent.AgentEventBridge;
import io.yupiik.ia.agent.core.agent.AgentLoop;
import io.yupiik.ia.agent.core.agent.AgentProvider;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.maven.MavenRepositoryKnowledgeService;
import io.yupiik.ia.agent.core.mcp.McpManager;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.question.UserFeedbackService;
import io.yupiik.ia.agent.core.runtime.VirtualThreadExecutor;
import io.yupiik.ia.agent.core.session.AgentSessionService;
import io.yupiik.ia.agent.core.session.SessionStore;
import io.yupiik.ia.agent.core.todo.TodoStore;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Renders the TUI into a {@link HeadlessBackend} and asserts on the visible
 * screen while driving it with dispatched events.
 */
class TuiCommandRenderTest {
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
    void rendersSidebarAndAnswer() throws Exception {
        final var bridge = new AgentEventBridge();
        final var agentLoop = new AgentLoop() {
            @Override
            public String run(
                    final String prompt,
                    final String agentName,
                    final String sessionId,
                    final List<Message> history,
                    final BooleanSupplier cancelled) {
                bridge.onEvent(new AgentEvent.TextDelta("hello from stub"));
                bridge.onEvent(new AgentEvent.TurnDone("hello from stub"));
                return "ok";
            }
        };
        final var command = command(bridge, agentLoop);
        uiThread = new Thread(command::run, "tui-render-test");
        uiThread.setDaemon(true);
        uiThread.start();
        awaitTui();
        awaitText("yca coding agent");
        awaitText("test-model");

        tuiRef.get().dispatch(new PasteEvent("say hi"));
        awaitText("say hi");
        tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
        awaitText("hello from stub");
        awaitText("ready");

        tuiRef.get().dispatch(KeyEvent.ofChar('c', KeyModifiers.CTRL));
        uiThread.join(TIMEOUT_MS);
    }

    @Test
    void slashHelpListsCommandsInTranscript() throws Exception {
        final var command = command(new AgentEventBridge(), stubAgentLoop());
        uiThread = new Thread(command::run, "tui-help-test");
        uiThread.setDaemon(true);
        uiThread.start();
        awaitTui();
        awaitText("yca coding agent");

        tuiRef.get().dispatch(new PasteEvent("/help"));
        tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
        awaitText("show the keyboard shortcuts");

        tuiRef.get().dispatch(KeyEvent.ofChar('c', KeyModifiers.CTRL));
        uiThread.join(TIMEOUT_MS);
    }

    @Test
    void clearRestoresWelcomeScreen() throws Exception {
        final var bridge = new AgentEventBridge();
        final var agentLoop = new AgentLoop() {
            @Override
            public String run(
                    final String prompt,
                    final String agentName,
                    final String sessionId,
                    final List<Message> history,
                    final BooleanSupplier cancelled) {
                bridge.onEvent(new AgentEvent.TurnDone("some answer"));
                return "ok";
            }
        };
        final var command = command(bridge, agentLoop);
        uiThread = new Thread(command::run, "tui-clear-test");
        uiThread.setDaemon(true);
        uiThread.start();
        awaitTui();
        awaitText("yca coding agent");

        tuiRef.get().dispatch(new PasteEvent("hi"));
        tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
        awaitText("some answer");

        tuiRef.get().dispatch(KeyEvent.ofChar('l', KeyModifiers.CTRL));
        awaitText("Type a prompt and press Enter");

        tuiRef.get().dispatch(KeyEvent.ofChar('c', KeyModifiers.CTRL));
        uiThread.join(TIMEOUT_MS);
    }

    private AgentLoop stubAgentLoop() {
        return new AgentLoop() {
            @Override
            public String run(
                    final String prompt,
                    final String agentName,
                    final String sessionId,
                    final List<Message> history,
                    final BooleanSupplier cancelled) {
                return "ok";
            }
        };
    }

    private TuiCommand command(final AgentEventBridge bridge, final AgentLoop agentLoop) {
        final var mapper = MockLlmServer.mapper();
        final var configuration = configuration();
        final var conf = new TuiCommand.TuiConf(KeyBindingPreset.STANDARD, false, "dark", true);
        final var tuiConfiguration = new TuiConfiguration(
                "#9d7cd8", "#fab283", "#7fd88f", "#f5a742", "#e06c75", "#56b6c2", "#eeeeee", "#808080", 0.85, 0.85,
                "#484848", "#606060", "#141414", "#ff5555", 4000L, null);
        final var sessionStore = new SessionStore() {
            @Override
            public String create() {
                return "s-render";
            }

            @Override
            public List<Message> load(final String id) {
                return List.of();
            }

            @Override
            public void delete(final String id) {
                // no-op: render tests never delete a real session
            }
        };
        final var chatClient =
                new ChatClient(configuration, mapper, new ProviderManager(configuration, mapper), Clock.systemUTC());
        final var todoStore = new TodoStore(configuration, mapper);
        final var mcpManager = new McpManager(
                configuration,
                mapper,
                List.of(),
                new io.yupiik.fusion.httpclient.core.ExtendedHttpClient(
                        new io.yupiik.fusion.httpclient.core.ExtendedHttpClientConfiguration()
                                .setDelegate(java.net.http.HttpClient.newHttpClient())));
        final var factory = new TuiRunnerFactory() {
            @Override
            public TuiRunner create(final TuiCommand.TuiConf tuiConf) throws Exception {
                final var runner = TuiRunner.create(TuiConfig.builder()
                        .backend(backend)
                        .mouseCapture(false)
                        .rawMode(false)
                        .alternateScreen(false)
                        .bracketedPaste(false)
                        .shutdownHook(false)
                        .tickRate(Duration.ofMillis(20))
                        .build());
                tuiRef.set(runner);
                return runner;
            }
        };
        return new TuiCommand(
                conf,
                tuiConfiguration,
                configuration,
                agentLoop,
                bridge,
                new PermissionService(configuration),
                new UserFeedbackService(),
                sessionStore,
                todoStore,
                chatClient,
                mcpManager,
                new VirtualThreadExecutor(),
                new ProviderManager(configuration, mapper),
                new AgentProvider(configuration),
                new AgentSessionService(
                        new PermissionService(configuration),
                        chatClient,
                        mcpManager,
                        new AgentProvider(configuration),
                        sessionStore,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        todoStore,
                        configuration,
                        null),
                new MavenRepositoryKnowledgeService(configuration, null, new VirtualThreadExecutor(), null),
                new TuiTranscriptModel(),
                factory,
                new TuiSlashCommands(),
                Clock.systemUTC());
    }

    private CodingAgentConfiguration configuration() {
        return MockLlmServer.configuration(llm.baseUrl(), temp.resolve("home").toString());
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
