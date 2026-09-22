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
import io.yupiik.fusion.json.internal.JsonMapperImpl;
import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.agent.AgentLoop;
import io.yupiik.ia.agent.core.agent.AgentProvider;
import io.yupiik.ia.agent.core.cli.AskUserAnswersFormatter;
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
import io.yupiik.ia.agent.core.shell.ThinkTagSplitter;
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

    private JsonMapperImpl formatMapper;

    @BeforeEach
    void start() throws IOException {
        formatMapper = new JsonMapperImpl(List.of(), key -> java.util.Optional.empty());
        llm = new MockLlmServer();
        backend = new HeadlessBackend();
    }

    @AfterEach
    void closeFormatMapper() {
        if (formatMapper != null) {
            formatMapper.close();
            formatMapper = null;
        }
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
    void wideStatusMessagesRenderInLastPositionOfStatusStrip() throws Exception {
        // /theme (no arg) produces a wide informational message routed to the trailing
        // notification channel rather than replacing the agent state at the strip head
        final var command = command(testRegistry(), stubAgentLoop());
        uiThread = new Thread(command, "tui-status-notification-test");
        uiThread.setDaemon(true);
        uiThread.start();
        awaitTui();
        awaitText("yca coding agent");

        tuiRef.get().dispatch(new PasteEvent("/theme"));
        tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
        awaitText("ⓘ"); // notification glyph present in the status strip

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
        return command(shellRegistry, agentLoop, showThinking, usageTracker, new TodoStore(configuration(), mapper));
    }

    private TuiCommand command(
            final ShellRegistry shellRegistry,
            final AgentLoop agentLoop,
            final boolean showThinking,
            final UsageTracker usageTracker,
            final TodoStore todoStore) {
        final var mapper = MockLlmServer.mapper();
        final var configuration = configuration();
        final var conf = new TuiCommand.TuiConf(KeyBindingPreset.STANDARD, showThinking, "dark", true);
        final var tuiConfiguration = new TuiConfiguration(
                "#9d7cd8", "#fab283", "#7fd88f", "#f5a742", "#e06c75", "#b896d8", "#eeeeee", "#808080", -1.0, 0.22,
                0.14, 0.85, "#484848", "#606060", "#141414", "#ff5555", 4000L, true, "unicode", "auto", "auto",
                List.of(), null);
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
                new TuiTranscriptModel(new ThinkTagSplitter()),
                factory,
                tuiSlashCommands,
                new SlashRegistry(List.of(tuiSlashCommands, configCommands)),
                Clock.systemUTC(),
                usageTracker,
                new AskUserCardRenderer(new AskUserAnswersFormatter(formatMapper)),
                new ToolOutputFormat(formatMapper));
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
    void thinkingRegionsRenderFencedCodeInline() throws Exception {
        final var shellRegistry = testRegistry();
        final var command = command(shellRegistry, stubAgentLoop(), true, newUsageTracker());
        uiThread = new Thread(command, "tui-thinking-code-test");
        uiThread.setDaemon(true);
        uiThread.start();
        awaitTui();
        awaitText("yca coding agent");

        // reasoning containing a fenced block must survive the split/highlight path and
        // render the code text inline (it used to fall through as plain prose)
        shellRegistry.onEvent(
                new AgentEvent.ThinkingDelta("let's inspect\n```java\npublic int x() {\n return 1;\n}\n```"));
        shellRegistry.onEvent(new AgentEvent.TurnDone("answer"));
        awaitText("public int x()");

        tuiRef.get().dispatch(KeyEvent.ofChar('c', KeyModifiers.CTRL));
        uiThread.join(TIMEOUT_MS);
    }

    @Test
    void collapsedTodosStillRenderTheList() throws Exception {
        final var shellRegistry = testRegistry();
        // a store whose render() yields a real todo so the panel auto-shows (expanded)
        final var todoStore = new TodoStore(configuration(), MockLlmServer.mapper()) {
            @Override
            public String render() {
                return "[> ] fix header padding";
            }
        };
        final var command = command(shellRegistry, stubAgentLoop(), true, newUsageTracker(), todoStore);
        uiThread = new Thread(command, "tui-todos-collapse-test");
        uiThread.setDaemon(true);
        uiThread.start();
        awaitTui();
        awaitText("yca coding agent");
        awaitText("fix header padding");

        // collapsed still renders the list in the narrow column
        tuiRef.get().dispatch(KeyEvent.ofChar('p', KeyModifiers.CTRL));
        awaitText("fix header padding");

        // collapsed -> hidden drops the list
        tuiRef.get().dispatch(KeyEvent.ofChar('p', KeyModifiers.CTRL));
        awaitTextGone("fix header padding");

        // hidden -> expanded brings it back
        tuiRef.get().dispatch(KeyEvent.ofChar('p', KeyModifiers.CTRL));
        awaitText("fix header padding");

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
    void longPromptSoftWrapsInsteadOfClipping() throws Exception {
        final var shellRegistry = testRegistry();
        final var command = command(shellRegistry, stubAgentLoop(), true, newUsageTracker());
        uiThread = new Thread(command, "tui-softwrap-test");
        uiThread.setDaemon(true);
        uiThread.start();
        awaitTui();
        awaitText("yca coding agent");

        final var tail = "--soft-wrap-tail";
        final var longLine = "wrap".repeat(30) + tail;
        tuiRef.get().dispatch(new PasteEvent(longLine));
        // the tail is far beyond one line of the 120-col prompt: it is only visible
        // once the long line wraps onto a subsequent visual row rather than clipping
        awaitText(tail);

        tuiRef.get().dispatch(KeyEvent.ofChar('c', KeyModifiers.CTRL));
        uiThread.join(TIMEOUT_MS);
    }

    @Test
    void promptCursorTrackColumnInsteadOfFixingToZero() throws Exception {
        final var shellRegistry = testRegistry();
        final var command = command(shellRegistry, stubAgentLoop(), true, newUsageTracker());
        uiThread = new Thread(command, "tui-cursor-col-test");
        uiThread.setDaemon(true);
        uiThread.start();
        awaitTui();
        awaitText("yca coding agent");

        tuiRef.get().dispatch(new PasteEvent("hello"));
        awaitText("hello");
        // the prompt interior starts at the column of the first typed char; the
        // cursor used to always be pinned to that start column by the disabled
        // visual-column computation, so it never followed the caret
        final var promptStart = columnOfLineContaining("hello");
        final var atEnd = awaitCursorCell();
        org.junit.jupiter.api.Assertions.assertTrue(
                atEnd[0] > promptStart,
                "cursor must sit past the start column after typing; start=" + promptStart + " cursor=" + atEnd[0]);

        tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.HOME));
        final var atHome = awaitCursorAt(promptStart);
        org.junit.jupiter.api.Assertions.assertEquals(
                promptStart, atHome[0], "HOME must move the cursor to the first character of the line");
        org.junit.jupiter.api.Assertions.assertEquals(atEnd[1], atHome[1], "HOME must not change the cursor row");

        tuiRef.get().dispatch(KeyEvent.ofChar('c', KeyModifiers.CTRL));
        uiThread.join(TIMEOUT_MS);
    }

    @Test
    void promptCursorTracksColumnInsideAsoftWrappedFragment() throws Exception {
        final var shellRegistry = testRegistry();
        final var command = command(shellRegistry, stubAgentLoop(), true, newUsageTracker());
        uiThread = new Thread(command, "tui-cursor-softwrap-test");
        uiThread.setDaemon(true);
        uiThread.start();
        awaitTui();
        awaitText("yca coding agent");

        final var longLine = "wrap".repeat(30);
        tuiRef.get().dispatch(new PasteEvent(longLine));
        awaitText("wrap"); // rendered (wrapped across visual rows) in the prompt
        final var promptStart = columnOfLineContaining("wrap");

        // move to the line start, then a few columns right, so the caret sits in
        // the middle of the first wrapped fragment; it must land beyond the
        // fragment-start column rather than being reset to it
        tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.HOME));
        for (var i = 0; i < 5; i++) {
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.RIGHT));
        }
        final var moved = awaitCursorAt(promptStart + 5);
        org.junit.jupiter.api.Assertions.assertTrue(
                moved[0] > promptStart,
                "cursor must sit inside the wrapped fragment, not pinned to its start; start=" + promptStart
                        + " cursor=" + moved[0]);

        tuiRef.get().dispatch(KeyEvent.ofChar('c', KeyModifiers.CTRL));
        uiThread.join(TIMEOUT_MS);
    }

    @Test
    void ctrlWOnEmptyPromptDoesNotCrash() throws Exception {
        final var command = command(testRegistry(), stubAgentLoop(), true, newUsageTracker());
        uiThread = new Thread(command, "tui-ctrlw-empty-test");
        uiThread.setDaemon(true);
        uiThread.start();
        awaitTui();
        awaitText("yca coding agent");

        // ctrl+w used to throw StringIndexOutOfBoundsException on an empty prompt
        // (previousWord charAt(0) on a length-0 line); it must be a plain no-op.
        tuiRef.get().dispatch(KeyEvent.ofChar('w', KeyModifiers.CTRL));
        awaitText("yca coding agent"); // still rendering -> no crash

        tuiRef.get().dispatch(KeyEvent.ofChar('c', KeyModifiers.CTRL));
        uiThread.join(TIMEOUT_MS);
    }

    @Test
    void ctrlWAtLineStartKillsPreviousLineLastWord() throws Exception {
        final var shellRegistry = testRegistry();
        final var command = command(shellRegistry, stubAgentLoop(), true, newUsageTracker());
        uiThread = new Thread(command, "tui-ctrlw-linejoin-test");
        uiThread.setDaemon(true);
        uiThread.start();
        awaitTui();
        awaitText("yca coding agent");

        // two-line prompt; cursor sits on the last line after pasting
        tuiRef.get().dispatch(new PasteEvent("foo bar\nbaz"));
        awaitText("baz");
        // HOME moves the cursor to the start of the last line (col 0)
        tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.HOME));
        awaitCursorAt(columnOfLineContaining("baz"));
        // ctrl+w at a line start must kill the previous line's last word ("bar")
        tuiRef.get().dispatch(KeyEvent.ofChar('w', KeyModifiers.CTRL));
        awaitTextGone("foo bar");

        tuiRef.get().dispatch(KeyEvent.ofChar('c', KeyModifiers.CTRL));
        uiThread.join(TIMEOUT_MS);
    }

    @Test
    void shortcutOverlayOpensWithCtrlSlash() throws Exception {
        final var command = command(testRegistry(), stubAgentLoop());
        uiThread = new Thread(command, "tui-shortcuts-overlay-test");
        uiThread.setDaemon(true);
        uiThread.start();
        awaitTui();
        awaitText("yca coding agent");

        // plain "?" must type into the prompt, not open the overlay
        tuiRef.get().dispatch(KeyEvent.ofChar('?'));
        awaitText("?");

        // the overlay toggle is reserved to Ctrl+/
        tuiRef.get().dispatch(KeyEvent.ofChar('/', KeyModifiers.CTRL));
        awaitText("Up/Down scroll"); // overlay footer: only rendered while the popup is open
        awaitText("send prompt"); // first binding listed in the Input group

        tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ESCAPE));
        awaitTextGone("Up/Down scroll");

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

    /** Polls until the bottommost reversed-video cell sits at {@code expectedColumn}. */
    private int[] awaitCursorAt(final int expectedColumn) throws InterruptedException {
        var cell = backend.lastReversed();
        while (cell == null || cell[0] != expectedColumn) {
            assertTrue(
                    backend.awaitFrame(1_000),
                    "no frame received while waiting for the cursor at column " + expectedColumn);
            cell = backend.lastReversed();
        }
        return cell;
    }

    /** Bottommost reversed-video cell, which is the prompt input cursor. */
    private int[] awaitCursorCell() throws InterruptedException {
        var cell = backend.lastReversed();
        while (cell == null) {
            assertTrue(backend.awaitFrame(1_000), "no frame received while waiting for the reversed cursor cell");
            cell = backend.lastReversed();
        }
        return cell;
    }

    /** Screen column where {@code token} first appears on the lowest row that contains it. */
    private int columnOfLineContaining(final String token) throws InterruptedException {
        while (true) {
            final var lines = backend.text().split("\n");
            var row = -1;
            for (var y = lines.length - 1; y >= 0; y--) {
                if (lines[y].contains(token)) {
                    row = y;
                    break;
                }
            }
            if (row >= 0) {
                final var idx = lines[row].indexOf(token);
                if (idx >= 0) {
                    return idx;
                }
            }
            assertTrue(backend.awaitFrame(1_000), "no frame received while waiting for '" + token + "'");
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
