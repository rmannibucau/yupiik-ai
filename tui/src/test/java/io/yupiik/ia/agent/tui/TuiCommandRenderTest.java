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
import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.agent.AgentLoop;
import io.yupiik.ia.agent.core.agent.AgentProvider;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfigurations;
import io.yupiik.ia.agent.core.context.UsageTracker;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.Usage;
import io.yupiik.ia.agent.core.maven.MavenRepositoryKnowledgeService;
import io.yupiik.ia.agent.core.mcp.McpManager;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.question.UserFeedbackService;
import io.yupiik.ia.agent.core.runtime.VirtualThreadExecutor;
import io.yupiik.ia.agent.core.session.AgentSessionService;
import io.yupiik.ia.agent.core.session.SessionStore;
import io.yupiik.ia.agent.core.shell.ShellRegistry;
import io.yupiik.ia.agent.core.shell.TurnRunner;
import io.yupiik.ia.agent.core.shell.slash.ConfigCommands;
import io.yupiik.ia.agent.core.shell.slash.SlashRegistry;
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
    private static final long TIMEOUT_MS = Duration.ofSeconds(5).toMillis();

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
        final var shellRegistry = testRegistry();
        final var agentLoop = new AgentLoop() {
            @Override
            public String run(
                    final String prompt,
                    final String agentName,
                    final String sessionId,
                    final List<Message> history,
                    final BooleanSupplier cancelled) {
                shellRegistry.onEvent(new AgentEvent.TextDelta("hello from stub"));
                shellRegistry.onEvent(new AgentEvent.TurnDone("hello from stub"));
                return "ok";
            }
        };
        final var command = command(shellRegistry, agentLoop);
        uiThread = new Thread(command, "tui-render-test");
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
        final var command = command(testRegistry(), stubAgentLoop());
        uiThread = new Thread(command, "tui-help-test");
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
        final var shellRegistry = testRegistry();
        final var agentLoop = new AgentLoop() {
            @Override
            public String run(
                    final String prompt,
                    final String agentName,
                    final String sessionId,
                    final List<Message> history,
                    final BooleanSupplier cancelled) {
                shellRegistry.onEvent(new AgentEvent.TurnDone("some answer"));
                return "ok";
            }
        };
        final var command = command(shellRegistry, agentLoop);
        uiThread = new Thread(command, "tui-clear-test");
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

    private TuiCommand command(final ShellRegistry shellRegistry, final AgentLoop agentLoop) {
        return command(shellRegistry, agentLoop, false, new UsageTracker(null, null, null, Clock.systemUTC()));
    }

    private TuiCommand command(
            final ShellRegistry shellRegistry,
            final AgentLoop agentLoop,
            final boolean showThinking,
            final UsageTracker usageTracker) {
        final var mapper = MockLlmServer.mapper();
        final var configuration = configuration();
        final var conf = new TuiCommand.TuiConf(KeyBindingPreset.STANDARD, showThinking, "dark", true);
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
                                .setDelegate(java.net.http.HttpClient.newBuilder()
                                        .version(java.net.http.HttpClient.Version.HTTP_1_1)
                                        .build())));
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
        final var tuiSlashCommands = new TuiSlashCommands();
        final var providerManager = new ProviderManager(configuration, mapper);
        // one shared permission service like in production: the TUI and the slash
        // commands must observe the same mode for the gutter to follow /mode
        final var permissionService = new PermissionService(configuration);
        final var agentSessionService = new AgentSessionService(
                permissionService,
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
                null);
        final var configCommands =
                new ConfigCommands(agentSessionService, chatClient, providerManager, new AgentProvider(configuration));
        return new TuiCommand(
                conf,
                tuiConfiguration,
                configuration,
                agentLoop,
                shellRegistry,
                permissionService,
                new UserFeedbackService(),
                sessionStore,
                todoStore,
                chatClient,
                new io.yupiik.ia.agent.core.style.ModelStyleService(configuration, chatClient),
                mcpManager,
                new VirtualThreadExecutor(),
                providerManager,
                new AgentProvider(configuration),
                agentSessionService,
                new MavenRepositoryKnowledgeService(configuration, null, new VirtualThreadExecutor(), null),
                new TurnRunner(
                        agentLoop,
                        new MavenRepositoryKnowledgeService(configuration, null, new VirtualThreadExecutor(), null),
                        new VirtualThreadExecutor()),
                new TuiTranscriptModel(),
                factory,
                tuiSlashCommands,
                new SlashRegistry(List.of(tuiSlashCommands, configCommands)),
                Clock.systemUTC(),
                usageTracker);
    }

    @Test
    void thinkingStreamsInlineAndCtrlTTogglesItBack() throws Exception {
        final var shellRegistry = testRegistry();
        final var command = command(shellRegistry, stubAgentLoop(), true, newUsageTracker());
        uiThread = new Thread(command, "tui-thinking-test");
        uiThread.setDaemon(true);
        uiThread.start();
        awaitTui();
        awaitText("yca coding agent");

        // live thinking renders inline in the conversation panel (no dedicated panel)
        shellRegistry.onEvent(new AgentEvent.ThinkingDelta("pondering"));
        awaitText("pondering");

        tuiRef.get().dispatch(KeyEvent.ofChar('t', KeyModifiers.CTRL));
        awaitTextGone("pondering");
        tuiRef.get().dispatch(KeyEvent.ofChar('t', KeyModifiers.CTRL));
        awaitText("pondering");

        tuiRef.get().dispatch(KeyEvent.ofChar('c', KeyModifiers.CTRL));
        uiThread.join(TIMEOUT_MS);
    }

    @Test
    void thinkingSlashCommandTogglesFlushedThoughts() throws Exception {
        final var shellRegistry = testRegistry();
        final var command = command(shellRegistry, stubAgentLoop(), true, newUsageTracker());
        uiThread = new Thread(command, "tui-thinking-slash-test");
        uiThread.setDaemon(true);
        uiThread.start();
        awaitTui();
        awaitText("yca coding agent");

        // a flushed thought region sits in the transcript
        shellRegistry.onEvent(new AgentEvent.ThinkingDelta("secret reasoning"));
        shellRegistry.onEvent(new AgentEvent.TurnDone("answer"));
        awaitText("secret reasoning");

        tuiRef.get().dispatch(new PasteEvent("/thinking hide"));
        tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
        awaitTextGone("secret reasoning");

        tuiRef.get().dispatch(new PasteEvent("/thinking show"));
        tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
        awaitText("secret reasoning");

        tuiRef.get().dispatch(KeyEvent.ofChar('c', KeyModifiers.CTRL));
        uiThread.join(TIMEOUT_MS);
    }

    @Test
    void toolOutputHideKeepsDimLineAndRestoresCards() throws Exception {
        final var shellRegistry = testRegistry();
        final var command = command(shellRegistry, stubAgentLoop(), true, newUsageTracker());
        uiThread = new Thread(command, "tui-tool-output-test");
        uiThread.setDaemon(true);
        uiThread.start();
        awaitTui();
        awaitText("yca coding agent");

        shellRegistry.onEvent(new AgentEvent.ToolStarted("bash", "{\"command\":\"ls -la\"}"));
        shellRegistry.onEvent(new AgentEvent.ToolStream("bash", "file.txt"));
        shellRegistry.onEvent(new AgentEvent.ToolFinished("bash", "file.txt", true));
        awaitText("ls -la"); // full card with args + output

        tuiRef.get().dispatch(KeyEvent.ofChar('y', KeyModifiers.CTRL));
        awaitTextGone("ls -la"); // args and output hidden
        awaitText("✓ bash"); // dim one-liner remains (tool finished in <1ms: no duration)

        tuiRef.get().dispatch(KeyEvent.ofChar('y', KeyModifiers.CTRL));
        awaitText("ls -la"); // full card restored

        tuiRef.get().dispatch(KeyEvent.ofChar('c', KeyModifiers.CTRL));
        uiThread.join(TIMEOUT_MS);
    }

    @Test
    void runningToolStreamIsHiddenWithToolBlocks() throws Exception {
        final var shellRegistry = testRegistry();
        final var command = command(shellRegistry, stubAgentLoop(), true, newUsageTracker());
        uiThread = new Thread(command, "tui-running-tool-test");
        uiThread.setDaemon(true);
        uiThread.start();
        awaitTui();
        awaitText("yca coding agent");

        shellRegistry.onEvent(new AgentEvent.ToolStarted("bash", "{\"command\":\"tail -f log\"}"));
        shellRegistry.onEvent(new AgentEvent.ToolStream("bash", "streamed line"));
        awaitText("streamed line"); // live tool stream visible

        tuiRef.get().dispatch(KeyEvent.ofChar('y', KeyModifiers.CTRL));
        awaitTextGone("streamed line");
        awaitText("tail -f log"); // dim one-liner with the command
        awaitText("running...");

        tuiRef.get().dispatch(KeyEvent.ofChar('c', KeyModifiers.CTRL));
        uiThread.join(TIMEOUT_MS);
    }

    @Test
    void turnAndSessionMetricsShowInStatusBar() throws Exception {
        final var shellRegistry = testRegistry();
        final var usageTracker = newUsageTracker();
        usageTracker.record(new Usage(10, 5, 15, 0, 0));
        final var command = command(shellRegistry, stubAgentLoop(), true, usageTracker);
        uiThread = new Thread(command, "tui-metrics-test");
        uiThread.setDaemon(true);
        uiThread.start();
        awaitTui();
        awaitText("yca coding agent");

        shellRegistry.onEvent(new AgentEvent.TurnMetrics(3, 120, 400, 500, 2, 1500));
        awaitText("3 LLM");
        awaitText("1.5s");
        awaitText("session: 1 LLM calls");

        tuiRef.get().dispatch(KeyEvent.ofChar('c', KeyModifiers.CTRL));
        uiThread.join(TIMEOUT_MS);
    }

    @Test
    void multilinePasteStaysInPromptUntilSubmitted() throws Exception {
        final var shellRegistry = testRegistry();
        final var received = new AtomicReference<String>();
        final var agentLoop = new AgentLoop() {
            @Override
            public String run(
                    final String prompt,
                    final String agentName,
                    final String sessionId,
                    final List<Message> history,
                    final BooleanSupplier cancelled) {
                received.set(prompt);
                shellRegistry.onEvent(
                        new AgentEvent.TurnDone("lines:" + prompt.lines().count()));
                return "ok";
            }
        };
        final var command = command(shellRegistry, agentLoop, true, newUsageTracker());
        uiThread = new Thread(command, "tui-multiline-paste-test");
        uiThread.setDaemon(true);
        uiThread.start();
        awaitTui();
        awaitText("yca coding agent");

        tuiRef.get().dispatch(new PasteEvent("line one\nline two"));
        awaitText("line two"); // both lines visible in the grown prompt

        tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
        awaitText("lines:2"); // submitted as a single prompt
        org.junit.jupiter.api.Assertions.assertEquals("line one\nline two", received.get());

        tuiRef.get().dispatch(KeyEvent.ofChar('c', KeyModifiers.CTRL));
        uiThread.join(TIMEOUT_MS);
    }

    @Test
    void guttersFollowAgentMode() throws Exception {
        final var shellRegistry = testRegistry();
        final var command = command(shellRegistry, stubAgentLoop(), true, newUsageTracker());
        uiThread = new Thread(command, "tui-mode-gutter-test");
        uiThread.setDaemon(true);
        uiThread.start();
        awaitTui();
        awaitText("yca coding agent");
        // PLAN is the default mode: the global gutter (screen column 0) is the thin bar
        awaitNoRowStartingWith("┃");

        tuiRef.get().dispatch(new PasteEvent("/mode build"));
        tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
        awaitText("build");
        awaitRowStartingWith("┃"); // heavy bar on the prompt + global gutters

        tuiRef.get().dispatch(new PasteEvent("/mode plan"));
        tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
        awaitText("plan");
        awaitNoRowStartingWith("┃");

        tuiRef.get().dispatch(KeyEvent.ofChar('c', KeyModifiers.CTRL));
        uiThread.join(TIMEOUT_MS);
    }

    @Test
    void shiftTabTogglesAgentMode() throws Exception {
        final var shellRegistry = testRegistry();
        final var command = command(shellRegistry, stubAgentLoop(), true, newUsageTracker());
        uiThread = new Thread(command, "tui-shift-tab-mode-test");
        uiThread.setDaemon(true);
        uiThread.start();
        awaitTui();
        awaitText("yca coding agent");
        awaitNoRowStartingWith("┃"); // plan by default

        tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.TAB, KeyModifiers.SHIFT));
        awaitRowStartingWith("┃"); // build

        tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.TAB, KeyModifiers.SHIFT));
        awaitNoRowStartingWith("┃"); // back to plan

        tuiRef.get().dispatch(KeyEvent.ofChar('c', KeyModifiers.CTRL));
        uiThread.join(TIMEOUT_MS);
    }

    private boolean anyRowStartsWith(final String prefix) {
        for (final var line : backend.text().split("\n")) {
            if (line.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private void awaitRowStartingWith(final String prefix) throws InterruptedException {
        while (!anyRowStartsWith(prefix)) {
            assertTrue(
                    backend.awaitFrame(1_000),
                    "no frame received while waiting for a row starting with '" + prefix + "'");
        }
    }

    private void awaitNoRowStartingWith(final String prefix) throws InterruptedException {
        while (anyRowStartsWith(prefix)) {
            assertTrue(
                    backend.awaitFrame(1_000),
                    "no frame received while waiting for the '" + prefix + "' gutter to clear");
        }
    }

    private static UsageTracker newUsageTracker() {
        return new UsageTracker(null, null, null, Clock.systemUTC());
    }

    private CodingAgentConfiguration configuration() {
        return CodingAgentConfigurations.builder()
                .baseUrl(llm.baseUrl())
                .storageHome(temp.resolve("home").toString())
                .build();
    }

    private void awaitTui() throws InterruptedException {
        while (tuiRef.get() == null) {
            assertTrue(backend.awaitFrame(1_000), "tui runner was not created in time");
        }
    }

    private void awaitText(final String expected) throws InterruptedException {
        var screen = backend.text();
        while (!screen.contains(expected)) {
            assertTrue(backend.awaitFrame(1_000), "no frame received while waiting for '" + expected + "'");
            screen = backend.text();
        }
    }

    private void awaitTextGone(final String expected) throws InterruptedException {
        var screen = backend.text();
        while (screen.contains(expected)) {
            assertTrue(backend.awaitFrame(1_000), "no frame received while waiting for '" + expected + "' to clear");
            screen = backend.text();
        }
    }

    private static ShellRegistry testRegistry() {
        return new ShellRegistry(new Emitter() {
            @Override
            public <T> void emit(final T event) {
                // no-op: events are dispatched through ShellRegistry.onEvent in tests
            }
        });
    }
}
