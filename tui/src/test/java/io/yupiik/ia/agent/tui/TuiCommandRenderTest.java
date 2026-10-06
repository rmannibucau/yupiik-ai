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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyModifiers;
import dev.tamboui.tui.event.MouseButton;
import dev.tamboui.tui.event.MouseEvent;
import dev.tamboui.tui.event.PasteEvent;
import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.agent.AgentLoop;
import io.yupiik.ia.agent.core.agent.AgentProvider;
import io.yupiik.ia.agent.core.agent.SubagentRegistry;
import io.yupiik.ia.agent.core.cli.AskUserAnswersFormatter;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration$FusionConfigurationFactory;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfigurations;
import io.yupiik.ia.agent.core.context.UsageTracker;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.ToolCallMarkupParser;
import io.yupiik.ia.agent.core.llm.ToolCallMarkupStripper;
import io.yupiik.ia.agent.core.llm.Usage;
import io.yupiik.ia.agent.core.maven.MavenRepositoryKnowledgeService;
import io.yupiik.ia.agent.core.mcp.McpManager;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.question.UserFeedbackService;
import io.yupiik.ia.agent.core.runtime.VirtualThreadExecutor;
import io.yupiik.ia.agent.core.session.AgentSessionService;
import io.yupiik.ia.agent.core.session.SessionEntry;
import io.yupiik.ia.agent.core.session.SessionStore;
import io.yupiik.ia.agent.core.shell.SessionLifecycle;
import io.yupiik.ia.agent.core.shell.SessionRuntime;
import io.yupiik.ia.agent.core.shell.SessionRuntimeFactory;
import io.yupiik.ia.agent.core.shell.ShellRegistry;
import io.yupiik.ia.agent.core.shell.SpeakerLabels;
import io.yupiik.ia.agent.core.shell.ThinkTagSplitter;
import io.yupiik.ia.agent.core.shell.TurnRunner;
import io.yupiik.ia.agent.core.shell.btw.BtwAnswerer;
import io.yupiik.ia.agent.core.shell.slash.ConfigCommands;
import io.yupiik.ia.agent.core.shell.slash.SlashRegistry;
import io.yupiik.ia.agent.core.todo.TodoStore;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import io.yupiik.ia.agent.test.TestJsonMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * Renders the TUI into a {@link HeadlessBackend} and asserts on the visible
 * screen while driving it with dispatched events.
 */
// shares the "io.yupiik-jul-handler" lock with TuiCommandHeadlessTest: both boot a real
// TuiCommand, which hijacks the JVM-wide "io.yupiik" java.util.logging logger and gives up when
// another handler is already installed. See TuiCommand.redirectLoggerToUi.
@ResourceLock("io.yupiik-jul-handler")
@ExtendWith(TestJsonMapper.class)
class TuiCommandRenderTest {
    private static final long TIMEOUT_MS = Duration.ofSeconds(5).toMillis();
    private static final Duration TICK_RATE = Duration.ofMillis(2);
    private static final Duration POLL_TIMEOUT = Duration.ofMillis(2);

    @TempDir
    Path temp;

    private MockLlmServer llm;
    private HeadlessBackend backend;
    private final AtomicReference<TuiRunner> tuiRef = new AtomicReference<>();
    private Thread uiThread;

    private JsonMapper formatMapper;
    private final List<String> sessionAppends = new ArrayList<>();

    @BeforeEach
    void start(final JsonMapper mapper) throws IOException {
        formatMapper = mapper;
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

    private static String sid(final ShellRegistry registry) {
        return registry.sessions().iterator().next();
    }

    @Test
    void rendersTurnHeadersWithRule() throws Exception {
        final var shellRegistry = testRegistry();
        final var agentLoop = new AgentLoop() {
            @Override
            public String run(
                    final String prompt,
                    final String agentName,
                    final String sessionId,
                    final List<Message> history,
                    final BooleanSupplier cancelled) {
                shellRegistry.onEvent(new AgentEvent.TextDelta("hello from stub", sessionId));
                shellRegistry.onEvent(new AgentEvent.TurnDone("hello from stub", sessionId));
                return "ok";
            }

            @Override
            public String run(
                    final String prompt,
                    final String agentName,
                    final String sessionId,
                    final List<Message> history,
                    final BooleanSupplier cancelled,
                    final Supplier<String> queuedPrompt) {
                return run(prompt, agentName, sessionId, history, cancelled);
            }
        };
        final var command = command(shellRegistry, agentLoop);
        uiThread = new Thread(command, "tui-render-test");
        uiThread.setDaemon(true);
        uiThread.start();
        awaitTui();
        awaitText("yca coding agent");

        tuiRef.get().dispatch(new PasteEvent("say hi"));
        awaitText("say hi");
        tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
        awaitText("hello from stub");
        awaitText("ready");

        // a labelled turn opens with a role-colored label and a rule running to the panel
        // edge; this is what keeps the user/assistant boundary readable while scrolling.
        // the default user label is the OS user name ("You" when unavailable)
        final var userName = System.getProperty("user.name");
        final var userLabel = userName == null || userName.isBlank() ? "You" : userName;
        assertTrue(
                anyRowContaining("\u258c " + userLabel + " \u2500"),
                "the user turn header and rule should be rendered:\n" + backend.text());
        assertTrue(
                anyRowContaining("\u258c default::test-model \u2500"),
                "the assistant turn header and rule should be rendered:\n" + backend.text());

        quit();
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
                shellRegistry.onEvent(new AgentEvent.TextDelta("hello from stub", sessionId));
                shellRegistry.onEvent(new AgentEvent.TurnDone("hello from stub", sessionId));
                return "ok";
            }

            @Override
            public String run(
                    final String prompt,
                    final String agentName,
                    final String sessionId,
                    final List<Message> history,
                    final BooleanSupplier cancelled,
                    final Supplier<String> queuedPrompt) {
                return run(prompt, agentName, sessionId, history, cancelled);
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

        quit();
    }

    @Test
    void sidebarReflectsModelChangedViaSlashCommand() throws Exception {
        final var shellRegistry = testRegistry();
        final var command = command(shellRegistry, stubAgentLoop());
        uiThread = new Thread(command, "tui-sidebar-model-test");
        uiThread.setDaemon(true);
        uiThread.start();
        awaitTui();
        awaitText("yca coding agent");
        // sidebar starts with the provider-configured model
        awaitText("model test-model");

        // /model overrides the per-session model: the sidebar must follow it
        tuiRef.get().dispatch(new PasteEvent("/model other-model"));
        tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
        awaitText("model other-model");

        quit();
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

        quit();
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

        quit();
    }

    @Test
    void agentStatusEventsAppendNotificationAndNeverReplaceAgentState() throws Exception {
        // AgentEvent.Status carries informational progress (output optimization, graph
        // refresh...): it must land in the trailing notification slot, ellipsized when too
        // long, while the leading agent state (ready) stays untouched
        final var shellRegistry = testRegistry();
        final var command = command(shellRegistry, stubAgentLoop());
        uiThread = new Thread(command, "tui-agent-status-notification-test");
        uiThread.setDaemon(true);
        uiThread.start();
        awaitTui();
        awaitText("yca coding agent");

        final var sessionId = shellRegistry.sessions().iterator().next();
        shellRegistry
                .active(sessionId)
                .frontend()
                .onEvent(new AgentEvent.Status("MARKER" + "x".repeat(300) + "TAIL", sessionId));

        awaitText("ⓘ");
        awaitText("MARKER");
        awaitText("..."); // clipped to the leading row width
        awaitTextGone("TAIL");
        awaitText("ready"); // agent state still first and untouched

        quit();
    }

    @Test
    void completionArrowsMoveHighlightAndEnterAppliesIt() throws Exception {
        // Up/Down on an open completion popup must move the highlight (not apply-and-reset
        // to the first candidate), and Enter must then accept the highlighted candidate
        final var command = command(testRegistry(), stubAgentLoop());
        uiThread = new Thread(command, "tui-completion-arrows-test");
        uiThread.setDaemon(true);
        uiThread.start();
        awaitTui();
        awaitText("yca coding agent");

        // type "/model m" - the live completion popup opens with the mock models (m01..m30)
        for (final var c : "/model m".toCharArray()) {
            tuiRef.get().dispatch(KeyEvent.ofChar(c));
        }
        awaitText("> m01"); // first candidate highlighted

        // Down moves the highlight to the second candidate without touching the input
        tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.DOWN));
        awaitText("> m02");
        awaitText("/model m"); // the line is unchanged: arrows only cycle the highlight

        // Enter accepts the highlighted candidate into the line
        tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
        awaitText("/model m02");

        quit();
    }

    @Test
    void promptIntegratedUpdatesTheQueuedPromptNotification() throws Exception {
        // PromptIntegrated (a queued prompt picked up by the running turn) must land in the
        // trailing notification slot with the remaining queue count, not in the leading status
        final var shellRegistry = testRegistry();
        final var turnStarted = new CountDownLatch(1);
        final var releaseTurn = new CountDownLatch(1);
        final var agentLoop = new AgentLoop() {
            @Override
            public String run(
                    final String prompt,
                    final String agentName,
                    final String sessionId,
                    final List<Message> history,
                    final BooleanSupplier cancelled) {
                try {
                    turnStarted.countDown();
                    releaseTurn.await();
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "ok";
            }

            @Override
            public String run(
                    final String prompt,
                    final String agentName,
                    final String sessionId,
                    final List<Message> history,
                    final BooleanSupplier cancelled,
                    final Supplier<String> queuedPrompt) {
                return run(prompt, agentName, sessionId, history, cancelled);
            }
        };
        final var command = command(shellRegistry, agentLoop);
        uiThread = new Thread(command, "tui-prompt-integrated-test");
        uiThread.setDaemon(true);
        uiThread.start();
        awaitTui();
        awaitText("yca coding agent");

        // start a live turn, then queue a second prompt while it runs
        tuiRef.get().dispatch(new PasteEvent("first"));
        tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
        assertTrue(
                turnStarted.await(TIMEOUT_MS, TimeUnit.MILLISECONDS), "the stub turn did not start before the timeout");

        tuiRef.get().dispatch(new PasteEvent("follow up"));
        tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
        awaitText("queued prompt (1 pending)");

        // the loop picks the queued prompt up: the notification must reflect the integration
        // (remaining count) while the leading agent state stays untouched - with the stub turn
        // parked the queue still holds the prompt, so the remaining-count form is expected
        final var sessionId = sid(shellRegistry);
        shellRegistry.active(sessionId).frontend().onEvent(new AgentEvent.PromptIntegrated("follow up", sessionId));
        awaitText("1 queued prompts still pending");
        awaitText("thinking..."); // the leading agent state was not replaced by the integration notice

        releaseTurn.countDown();
        quit();
    }

    @Test
    void severeLogErrorRendersRedAfterMetricsAndKeepsStatusText() throws Exception {
        final var command = command(testRegistry(), stubAgentLoop());
        uiThread = new Thread(command, "tui-severe-error-test");
        uiThread.setDaemon(true);
        uiThread.start();
        awaitTui();
        awaitText("yca coding agent");
        awaitText("ready");

        // redirectLoggerToUi routes a SEVERE record on the "io.yupiik" logger into modelError
        Logger.getLogger("io.yupiik").severe("LLM request failed (429) retry-after=1");

        // the trailing red segment (single space after the cross glyph) shows the error after the
        // metrics; the boxed banner below uses a double space, so this matches only the trailing one
        awaitText("✗ LLM request failed (429) retry-after=1");
        // the far-left status text must keep the normal "ready", not be replaced by the error
        awaitText("ready");

        // the error segment must be painted in the error red (#e06c75)
        final var col = columnOfLineContaining("✗ LLM request failed");
        final var row = rowOf("✗ LLM request failed");
        final var rgb = backend.fg(col, row).map(c -> c.toRgb()).orElse(null);
        assertTrue(
                rgb != null && rgb.r() == 0xe0 && rgb.g() == 0x6c && rgb.b() == 0x75,
                "expected the error segment to be painted in the error red, fg=" + backend.fg(col, row));

        quit();
    }

    @Test
    void agentTurnErrorRendersRedRightSlotAndKeepsLeftStatusReady() throws Exception {
        // AgentEvent.Error (a failed turn with all retries exhausted, eg. the JDK transport
        // error "HTTP/1.1 header parser received no bytes") must land in the trailing red
        // error slot (right, Alt+E for details), never replace the leading status text
        final var shellRegistry = testRegistry();
        final var command = command(shellRegistry, stubAgentLoop());
        uiThread = new Thread(command, "tui-agent-error-test");
        uiThread.setDaemon(true);
        uiThread.start();
        awaitTui();
        awaitText("yca coding agent");
        awaitText("ready");

        final var sessionId = shellRegistry.sessions().iterator().next();
        shellRegistry
                .active(sessionId)
                .frontend()
                .onEvent(new AgentEvent.Error("HTTP/1.1 header parser received no bytes", sessionId));

        // the trailing red segment carries the message (single space after the cross glyph);
        // the boxed banner below uses a double space, so this matches only the trailing one
        awaitText("✗ HTTP/1.1 header parser received no bytes");
        // the far-left status text must keep the normal "ready", not be replaced by the error
        awaitText("ready");

        // the error segment must be painted in the error red (#e06c75)
        final var col = columnOfLineContaining("✗ HTTP/1.1 header parser");
        final var row = rowOf("✗ HTTP/1.1 header parser");
        final var rgb = backend.fg(col, row).map(c -> c.toRgb()).orElse(null);
        assertTrue(
                rgb != null && rgb.r() == 0xe0 && rgb.g() == 0x6c && rgb.b() == 0x75,
                "expected the error segment to be painted in the error red, fg=" + backend.fg(col, row));

        quit();
    }

    @Test
    void agentErrorNoticeRendersRedRightSlotAndKeepsLeftStatusUntouched() throws Exception {
        // ERROR notices (truncation give-up, compaction exhaustion...) are failures too: they
        // must reach the red trailing slot while the leading agent state stays untouched
        // ("ready" when idle, "working" mid-turn)
        final var shellRegistry = testRegistry();
        final var command = command(shellRegistry, stubAgentLoop());
        uiThread = new Thread(command, "tui-agent-error-notice-test");
        uiThread.setDaemon(true);
        uiThread.start();
        awaitTui();
        awaitText("yca coding agent");
        awaitText("ready");

        final var sessionId = shellRegistry.sessions().iterator().next();
        shellRegistry
                .active(sessionId)
                .frontend()
                .onEvent(new AgentEvent.Notice(
                        AgentEvent.Notice.Level.ERROR,
                        "Response truncated by the output token limit (finish_reason=length)",
                        sessionId));

        awaitText("✗ Response truncated by the output token limit");
        awaitText("ready");

        quit();
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
                shellRegistry.onEvent(new AgentEvent.TurnDone("some answer", sessionId));
                return "ok";
            }

            @Override
            public String run(
                    final String prompt,
                    final String agentName,
                    final String sessionId,
                    final List<Message> history,
                    final BooleanSupplier cancelled,
                    final Supplier<String> queuedPrompt) {
                return run(prompt, agentName, sessionId, history, cancelled);
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

        quit();
    }

    @Test
    void btwAnswersInModalWithoutEchoOrPersistence() throws Exception {
        llm.answer("side answer");
        final var shellRegistry = testRegistry();
        // the stub turn parks on releaseTurn once it signaled turnStarted, so the
        // /btw modal is exercised over a deterministically-live turn instead of a sleep
        final var turnStarted = new CountDownLatch(1);
        final var releaseTurn = new CountDownLatch(1);
        final var agentLoop = new AgentLoop() {
            @Override
            public String run(
                    final String prompt,
                    final String agentName,
                    final String sessionId,
                    final List<Message> history,
                    final BooleanSupplier cancelled) {
                try {
                    turnStarted.countDown();
                    releaseTurn.await();
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                shellRegistry.onEvent(new AgentEvent.TextDelta("hello from stub", sessionId));
                shellRegistry.onEvent(new AgentEvent.TurnDone("hello from stub", sessionId));
                return "ok";
            }

            @Override
            public String run(
                    final String prompt,
                    final String agentName,
                    final String sessionId,
                    final List<Message> history,
                    final BooleanSupplier cancelled,
                    final Supplier<String> queuedPrompt) {
                return run(prompt, agentName, sessionId, history, cancelled);
            }
        };
        final var command = command(shellRegistry, agentLoop);
        uiThread = new Thread(command, "tui-btw-test");
        uiThread.setDaemon(true);
        uiThread.start();
        awaitTui();
        awaitText("yca coding agent");

        tuiRef.get().dispatch(new PasteEvent("say hi"));
        tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
        assertTrue(
                turnStarted.await(TIMEOUT_MS, TimeUnit.MILLISECONDS), "the stub turn did not start before the timeout");
        // the stub turn is parked on releaseTurn: ask the side question while it runs
        tuiRef.get().dispatch(new PasteEvent("/btw what is this"));
        tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
        awaitText("Q: what is this");
        awaitText("side answer");

        tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ESCAPE));
        awaitTextGone("Q: what is this");
        assertTrue(sessionAppends.isEmpty(), "btw must never write to the session store");
        awaitTextGone("`/btw");

        releaseTurn.countDown();
        awaitText("hello from stub");

        quit();
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

            @Override
            public String run(
                    final String prompt,
                    final String agentName,
                    final String sessionId,
                    final List<Message> history,
                    final BooleanSupplier cancelled,
                    final Supplier<String> queuedPrompt) {
                return run(prompt, agentName, sessionId, history, cancelled);
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
        final var mapper = formatMapper;
        return command(shellRegistry, agentLoop, showThinking, usageTracker, new TodoStore(configuration(), mapper));
    }

    private TuiCommand command(
            final ShellRegistry shellRegistry,
            final AgentLoop agentLoop,
            final boolean showThinking,
            final UsageTracker usageTracker,
            final TodoStore todoStore) {
        return command(shellRegistry, agentLoop, showThinking, usageTracker, todoStore, Clock.systemUTC());
    }

    private TuiCommand command(
            final ShellRegistry shellRegistry,
            final AgentLoop agentLoop,
            final boolean showThinking,
            final UsageTracker usageTracker,
            final TodoStore todoStore,
            final Clock clock) {
        final var mapper = formatMapper;
        final var configuration = configuration();
        return command(
                shellRegistry,
                agentLoop,
                showThinking,
                usageTracker,
                todoStore,
                clock,
                new SubagentRegistry(configuration, new ProviderManager(configuration, mapper)));
    }

    private TuiCommand command(
            final ShellRegistry shellRegistry,
            final AgentLoop agentLoop,
            final boolean showThinking,
            final UsageTracker usageTracker,
            final TodoStore todoStore,
            final Clock clock,
            final SubagentRegistry subagentRegistry) {
        final var mapper = formatMapper;
        final var configuration = configuration();
        final var conf = new TuiCommand.TuiConf("", "", KeyBindingPreset.STANDARD, showThinking, "dark");
        final var tuiConfiguration = new TuiConfiguration(
                "#9d7cd8", "#fab283", "#7fd88f", "#f5a742", "#e06c75", "#b896d8", "#eeeeee", "#808080", -1.0, 0.22,
                0.14, 0.85, "#484848", "#606060", "#141414", "auto", "auto", 4000L, 3500L, true, "unicode", "auto",
                "auto", List.of(), null, true, 3, 10, "", "#ff5555");
        final var sessionStore = new SessionStore() {
            @Override
            public String create() {
                return "s-render";
            }

            @Override
            public void append(final String id, final SessionEntry entry) {
                sessionAppends.add(String.valueOf(entry));
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
        final var chatClient = new ChatClient(configuration, mapper, new ProviderManager(configuration, mapper), clock);
        final var mcpManager = new McpManager(
                configuration,
                mapper,
                List.of(),
                new io.yupiik.fusion.httpclient.core.ExtendedHttpClient(
                        new io.yupiik.fusion.httpclient.core.ExtendedHttpClientConfiguration()
                                .setDelegate(
                                        java.net.http.HttpClient.newBuilder().build())));
        final var factory = new TuiRunnerFactory() {
            @Override
            public TuiRunner create(final TuiCommand.TuiConf tuiConf, final boolean hideCursor) throws Exception {
                final var runner = TuiRunner.create(TuiConfig.builder()
                        .backend(backend)
                        .mouseCapture(false)
                        .rawMode(false)
                        .alternateScreen(false)
                        .bracketedPaste(false)
                        .shutdownHook(false)
                        // the tick *is* the latency of every assertion in this class: a mutation
                        // posted from a background thread (runLater) only paints on the next tick,
                        // so each awaitFrame below costs at least one tick. 20ms made the 48 tests
                        // of this class cost seconds for no added coverage - the clock is a
                        // MutableClock, so elapsed-time rendering does not depend on this value.
                        .tickRate(TICK_RATE)
                        // the event loop waits at most pollTimeout between two runs, so this is
                        // also how long quit() takes to be noticed: the default 40ms was paid by
                        // every single test when joining the ui thread
                        .pollTimeout(POLL_TIMEOUT)
                        .build());
                tuiRef.set(runner);
                return runner;
            }
        };
        final var tabManager = new TabManager();
        final var tuiSlashCommands = new TuiSlashCommands(tabManager);
        final var providerManager = new ProviderManager(configuration, mapper);
        // one shared permission service like in production: the TUI and the slash
        // commands must observe the same mode for the gutter to follow /mode
        final var permissionService = new PermissionService(configuration);
        final var agentSessionService = new AgentSessionService(
                chatClient,
                mcpManager,
                new AgentProvider(configuration),
                sessionStore,
                null,
                null,
                null,
                null,
                configuration,
                null);
        final var configCommands =
                new ConfigCommands(agentSessionService, chatClient, providerManager, new AgentProvider(configuration));
        return new TuiCommand(
                conf,
                tuiConfiguration,
                configuration,
                shellRegistry,
                new SessionRuntimeFactory() {
                    @Override
                    public SessionRuntime create(final String sessionId) {
                        return new SessionRuntime(
                                agentLoop,
                                permissionService,
                                new UserFeedbackService(),
                                usageTracker,
                                todoStore,
                                new io.yupiik.ia.agent.core.runtime.StatusReporter(),
                                null,
                                null);
                    }
                },
                sessionStore,
                new SessionLifecycle(sessionStore),
                chatClient,
                new io.yupiik.ia.agent.core.style.ModelStyleService(configuration, chatClient),
                new SpeakerLabels(configuration, chatClient, new ProviderManager(configuration, formatMapper)),
                mcpManager,
                new VirtualThreadExecutor(),
                providerManager,
                new AgentProvider(configuration),
                agentSessionService,
                new MavenRepositoryKnowledgeService(configuration, null, new VirtualThreadExecutor(), null),
                new TurnRunner(
                        new MavenRepositoryKnowledgeService(configuration, null, new VirtualThreadExecutor(), null),
                        new VirtualThreadExecutor(),
                        agentSessionService),
                new ThinkTagSplitter(),
                factory,
                tuiSlashCommands,
                new SlashRegistry(List.of(tuiSlashCommands, configCommands)),
                clock,
                new AskUserCardRenderer(new AskUserAnswersFormatter(formatMapper)),
                new ToolOutputFormat(formatMapper),
                new BtwAnswerer(
                        chatClient,
                        sessionStore,
                        configuration,
                        new VirtualThreadExecutor(),
                        new ToolCallMarkupStripper(),
                        new ToolCallMarkupParser(),
                        null,
                        null,
                        null,
                        null,
                        null),
                tabManager,
                subagentRegistry,
                new ScrollService(tuiConfiguration),
                null);
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
        shellRegistry.onEvent(new AgentEvent.ThinkingDelta("pondering", sid(shellRegistry)));
        awaitText("pondering");

        tuiRef.get().dispatch(KeyEvent.ofChar('t', KeyModifiers.CTRL));
        awaitTextGone("pondering");
        tuiRef.get().dispatch(KeyEvent.ofChar('t', KeyModifiers.CTRL));
        awaitText("pondering");

        quit();
    }

    @Test
    void thinkingTimerAdvancesWhileToolRunning() throws Exception {
        final var clock = new MutableClock(0);
        final var shellRegistry = testRegistry();
        final var command = command(
                shellRegistry,
                stubAgentLoop(),
                true,
                newUsageTracker(),
                new TodoStore(configuration(), formatMapper),
                clock);
        uiThread = new Thread(command, "tui-thinking-tool-timer-test");
        uiThread.setDaemon(true);
        uiThread.start();
        awaitTui();
        awaitText("yca coding agent");

        // reasoning deltas can stream while a tool is running (subagent output, parallel
        // batches): the timer must arm on the first indicator frame even though the
        // pinned footer is busy showing the tool line
        shellRegistry.onEvent(new AgentEvent.ToolStarted("bash", "{\"command\":\"mvn test\"}", sid(shellRegistry)));
        shellRegistry.onEvent(new AgentEvent.ThinkingDelta("pondering while building", sid(shellRegistry)));
        awaitTextWithin("thinking... (0s)", TIMEOUT_MS);

        clock.advance(5_000);
        awaitTextWithin("thinking... (5s)", TIMEOUT_MS);

        quit();
    }

    @Test
    void thinkingTimerRestartsOnEachTurn() throws Exception {
        final var clock = new MutableClock(0);
        final var shellRegistry = testRegistry();
        final var command = command(
                shellRegistry,
                stubAgentLoop(),
                true,
                newUsageTracker(),
                new TodoStore(configuration(), formatMapper),
                clock);
        uiThread = new Thread(command, "tui-thinking-timer-reset-test");
        uiThread.setDaemon(true);
        uiThread.start();
        awaitTui();
        awaitText("yca coding agent");

        shellRegistry.onEvent(new AgentEvent.ThinkingDelta("first round", sid(shellRegistry)));
        awaitTextWithin("thinking... (0s)", TIMEOUT_MS);
        clock.advance(30_000);
        awaitTextWithin("thinking... (30s)", TIMEOUT_MS);

        // the turn ends while no idle frame may ever catch the reset condition: the
        // next thinking round must restart from zero, not resume the stale timer
        shellRegistry.onEvent(new AgentEvent.TurnDone("answer", sid(shellRegistry)));
        awaitText("answer");
        awaitTextGoneWithin("thinking... (30s)", TIMEOUT_MS);

        clock.advance(5_000);
        shellRegistry.onEvent(new AgentEvent.ThinkingDelta("second round", sid(shellRegistry)));
        awaitTextWithin("thinking... (0s)", TIMEOUT_MS);
        clock.advance(2_000);
        awaitTextWithin("thinking... (2s)", TIMEOUT_MS);

        quit();
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
        shellRegistry.onEvent(new AgentEvent.ThinkingDelta(
                "let's inspect\n```java\npublic int x() {\n return 1;\n}\n```", sid(shellRegistry)));
        shellRegistry.onEvent(new AgentEvent.TurnDone("answer", sid(shellRegistry)));
        awaitText("public int x()");

        quit();
    }

    @Test
    void streamedThoughtsKeepTheBlankLineBetweenParagraphs() throws Exception {
        final var shellRegistry = testRegistry();
        final var command = command(shellRegistry, stubAgentLoop(), true, newUsageTracker());
        uiThread = new Thread(command, "tui-thinking-blank-line-test");
        uiThread.setDaemon(true);
        uiThread.start();
        awaitTui();
        awaitText("yca coding agent");

        // a paragraph separator streamed while the thought is still open must survive on screen:
        // the live tail is cut at a newline and the two halves parsed independently, so without
        // restoring the separator the paragraphs render glued (which is what made thoughts read dense)
        shellRegistry.onEvent(new AgentEvent.ThinkingDelta("first paragraph", sid(shellRegistry)));
        awaitText("first paragraph");
        shellRegistry.onEvent(new AgentEvent.ThinkingDelta("\n\nsecond", sid(shellRegistry)));
        awaitText("second");
        shellRegistry.onEvent(new AgentEvent.ThinkingDelta(" paragraph", sid(shellRegistry)));
        awaitText("second paragraph");
        // the flushed render (the live region is replaced by the memoized one) must keep it too
        shellRegistry.onEvent(new AgentEvent.TurnDone("answer", sid(shellRegistry)));
        awaitText("answer");
        assertBlankRowBetween("first paragraph", "second paragraph");

        quit();
    }

    @Test
    void streamedThoughtsKeepTheBlankLineAroundAFence() throws Exception {
        final var shellRegistry = testRegistry();
        final var command = command(shellRegistry, stubAgentLoop(), true, newUsageTracker());
        uiThread = new Thread(command, "tui-thinking-blank-fence-test");
        uiThread.setDaemon(true);
        uiThread.start();
        awaitTui();
        awaitText("yca coding agent");

        // a fenced block is split out of the prose and highlighted on its own: it must not glue to
        // the paragraph above it, and must not leave a phantom row after it either
        shellRegistry.onEvent(new AgentEvent.ThinkingDelta(
                "let's inspect\n\n```java\npublic int x() {\n return 1;\n}\n```", sid(shellRegistry)));
        awaitText("public int x()");
        shellRegistry.onEvent(new AgentEvent.TurnDone("answer", sid(shellRegistry)));
        awaitText("answer");
        assertBlankRowBetween("let's inspect", "public int x()");

        quit();
    }

    @Test
    void assistantAnswerRendersFencedCodeIndentedAndHighlighted() throws Exception {
        final var shellRegistry = testRegistry();
        final var command = command(shellRegistry, stubAgentLoop(), true, newUsageTracker());
        uiThread = new Thread(command, "tui-answer-code-test");
        uiThread.setDaemon(true);
        uiThread.start();
        awaitTui();
        awaitText("yca coding agent");

        // a fenced java block inside a final assistant ANSWER must be split, boxed and
        // syntax-highlighted like it is inside a thought: indentation kept + keyword colored.
        final var code = """
                public static native BiPredicate declaredType(Object value);

                public BiPredicate declaredType() {
                    return (BiPredicate) field.get(null);
                }
                """;
        shellRegistry.onEvent(
                new AgentEvent.TurnDone("here is the method:\n```java\n" + code + "```", sid(shellRegistry)));

        // the code reached rendering (not dropped) and its body indentation survived
        awaitText("public static native BiPredicate declaredType");
        awaitText("    return (BiPredicate) field.get(null);");

        // a java keyword is coloured for real (not plain prose) when the CODE path is taken
        final var col = columnOfLineContaining("public BiPredicate declaredType()");
        final var row = rowOf("public BiPredicate declaredType()");
        assertTrue(
                col >= 0 && row >= 0 && backend.fg(col, row).isPresent(),
                "expected the 'public' keyword to be syntax-coloured on the row containing it, fg="
                        + backend.fg(col, row));

        quit();
    }

    @Test
    void collapsedTodosStillRenderTheList() throws Exception {
        final var shellRegistry = testRegistry();
        // a store whose render() yields a real todo so the panel auto-shows (expanded)
        final var todoStore = new TodoStore(configuration(), formatMapper) {
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

        quit();
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
        shellRegistry.onEvent(new AgentEvent.ThinkingDelta("secret reasoning", sid(shellRegistry)));
        shellRegistry.onEvent(new AgentEvent.TurnDone("answer", sid(shellRegistry)));
        awaitText("secret reasoning");

        tuiRef.get().dispatch(new PasteEvent("/thinking hide"));
        tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
        awaitTextGone("secret reasoning");

        tuiRef.get().dispatch(new PasteEvent("/thinking show"));
        tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
        awaitText("secret reasoning");

        quit();
    }

    @Test
    void toolOutputCondensedByDefaultAndCtrlYCyclesThreeStates() throws Exception {
        final var shellRegistry = testRegistry();
        final var command = command(shellRegistry, stubAgentLoop(), true, newUsageTracker());
        uiThread = new Thread(command, "tui-tool-output-test");
        uiThread.setDaemon(true);
        uiThread.start();
        awaitTui();
        awaitText("yca coding agent");

        shellRegistry.onEvent(
                new AgentEvent.ToolStarted("bash", "{\"command\":\"cd /tmp && ls -la\"}", sid(shellRegistry)));
        shellRegistry.onEvent(new AgentEvent.ToolStream("bash", "file.txt", sid(shellRegistry)));
        shellRegistry.onEvent(new AgentEvent.ToolFinished("bash", "file.txt", true, sid(shellRegistry)));
        // collapsed by default: the dim one-liner carries the significant command, not the output
        awaitText("✓ bash");
        awaitText("ls -la");
        awaitTextGone("file.txt");

        tuiRef.get().dispatch(KeyEvent.ofChar('y', KeyModifiers.CTRL));
        awaitText("file.txt"); // shown: full card restored with args and output

        tuiRef.get().dispatch(KeyEvent.ofChar('y', KeyModifiers.CTRL));
        awaitTextGone("file.txt"); // hidden: nothing rendered any more
        awaitTextGone("✓ bash");
        awaitTextGone("ls -la");

        tuiRef.get().dispatch(KeyEvent.ofChar('y', KeyModifiers.CTRL));
        awaitText("✓ bash"); // collapsed again
        awaitText("ls -la");
        awaitTextGone("file.txt");

        quit();
    }

    @Test
    void toolOutputSlashCommandHidesAndCollapses() throws Exception {
        final var shellRegistry = testRegistry();
        final var command = command(shellRegistry, stubAgentLoop(), true, newUsageTracker());
        uiThread = new Thread(command, "tui-tool-output-slash-test");
        uiThread.setDaemon(true);
        uiThread.start();
        awaitTui();
        awaitText("yca coding agent");

        shellRegistry.onEvent(new AgentEvent.ToolStarted("bash", "{\"command\":\"ls -la\"}", sid(shellRegistry)));
        shellRegistry.onEvent(new AgentEvent.ToolStream("bash", "secret-out", sid(shellRegistry)));
        shellRegistry.onEvent(new AgentEvent.ToolFinished("bash", "secret-out", true, sid(shellRegistry)));
        awaitText("✓ bash"); // collapsed default

        tuiRef.get().dispatch(new PasteEvent("/tool-output hide"));
        tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
        awaitTextGone("✓ bash");
        awaitTextGone("ls -la");

        tuiRef.get().dispatch(new PasteEvent("/tool-output collapse"));
        tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
        awaitText("✓ bash");
        awaitTextGone("secret-out");

        quit();
    }

    @Test
    void clickingCondensedToolLineOpensFullOutput() throws Exception {
        final var shellRegistry = testRegistry();
        final var command = command(shellRegistry, stubAgentLoop(), true, newUsageTracker());
        uiThread = new Thread(command, "tui-tool-click-test");
        uiThread.setDaemon(true);
        uiThread.start();
        awaitTui();
        awaitText("yca coding agent");

        shellRegistry.onEvent(new AgentEvent.ToolStarted("bash", "{\"command\":\"ls -la\"}", sid(shellRegistry)));
        shellRegistry.onEvent(new AgentEvent.ToolStream("bash", "out-a\nout-b", sid(shellRegistry)));
        shellRegistry.onEvent(new AgentEvent.ToolFinished("bash", "out-a\nout-b", true, sid(shellRegistry)));
        awaitText("✓ bash"); // condensed dim line

        // clicking the dim line opens the full-output modal even while blocks are hidden
        tuiRef.get().dispatch(MouseEvent.press(MouseButton.LEFT, 2, rowOf("✓ bash")));
        awaitText("out-a");
        awaitText("full output");

        tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ESCAPE));
        awaitTextGone("full output");
        awaitTextGone("out-a"); // back to the condensed line, cards stay hidden

        quit();
    }

    /**
     * A python command printing a piped table (eg. {@code tabulate}) used to be detected as
     * markdown and rendered as a bordered grid, prefixing every line with a vertical bar. The
     * output must stay one row per source line.
     */
    @Test
    void toolOverlayKeepsPipedOutputLinesVerbatim() throws Exception {
        final var shellRegistry = testRegistry();
        final var command = command(shellRegistry, stubAgentLoop(), true, newUsageTracker());
        uiThread = new Thread(command, "tui-tool-pipes-test");
        uiThread.setDaemon(true);
        uiThread.start();
        awaitTui();
        awaitText("yca coding agent");

        final var output = """
                | name | age |
                |------|-----|
                | bob  | 42  |
                - bob is 42
                """;
        shellRegistry.onEvent(
                new AgentEvent.ToolStarted("bash", "{\"command\":\"python3 report.py\"}", sid(shellRegistry)));
        shellRegistry.onEvent(new AgentEvent.ToolStream("bash", output, sid(shellRegistry)));
        shellRegistry.onEvent(new AgentEvent.ToolFinished("bash", output, true, sid(shellRegistry)));
        awaitText("✓ bash");

        tuiRef.get().dispatch(MouseEvent.press(MouseButton.LEFT, 2, rowOf("✓ bash")));
        awaitText("full output");
        awaitText("| name | age |");
        awaitText("|------|-----|");
        awaitText("| bob  | 42  |");

        // no grid borders were injected around the piped rows
        assertFalse(backend.text().contains("│ bob"), backend.text());
        assertFalse(backend.text().contains("┌"), backend.text());
        assertTrue(rowIndex("| name | age |") < rowIndex("|------|-----|"));
        assertTrue(rowIndex("|------|-----|") < rowIndex("| bob  | 42  |"));

        quit();
    }

    /** The modal registers its own copy zone: a drag started inside it selects (and copies) it. */
    @Test
    void toolOverlayDragSelectionCopies() throws Exception {
        final var shellRegistry = testRegistry();
        final var command = command(shellRegistry, stubAgentLoop(), true, newUsageTracker());
        uiThread = new Thread(command, "tui-tool-copy-test");
        uiThread.setDaemon(true);
        uiThread.start();
        awaitTui();
        awaitText("yca coding agent");

        shellRegistry.onEvent(new AgentEvent.ToolStarted("bash", "{\"command\":\"cat out.txt\"}", sid(shellRegistry)));
        shellRegistry.onEvent(new AgentEvent.ToolStream("bash", "copy-me-line", sid(shellRegistry)));
        shellRegistry.onEvent(new AgentEvent.ToolFinished("bash", "copy-me-line", true, sid(shellRegistry)));
        awaitText("✓ bash");

        tuiRef.get().dispatch(MouseEvent.press(MouseButton.LEFT, 2, rowOf("✓ bash")));
        awaitText("full output");
        awaitText("copy-me-line");

        final var row = rowOf("copy-me-line");
        final var from = columnOfLineContaining("copy-me-line");
        tuiRef.get().dispatch(MouseEvent.press(MouseButton.LEFT, from, row));
        tuiRef.get().dispatch(MouseEvent.drag(MouseButton.LEFT, from + 5, row));
        tuiRef.get().dispatch(MouseEvent.release(MouseButton.LEFT, from + 5, row));

        // the release is what copies: OSC 52 carries the dragged span of the modal body
        final var deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (!backend.raw().contains(SelectionState.osc52Payload("copy-m")) && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertTrue(
                backend.raw().contains(SelectionState.osc52Payload("copy-m")),
                "expected the drag to copy the selected modal text over OSC 52, raw=" + backend.raw());

        quit();
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

        shellRegistry.onEvent(new AgentEvent.ToolStarted("bash", "{\"command\":\"tail -f log\"}", sid(shellRegistry)));
        shellRegistry.onEvent(new AgentEvent.ToolStream("bash", "streamed line", sid(shellRegistry)));
        awaitTextGone("streamed line"); // condensed default hides the live stream
        awaitText("tail -f log"); // dim one-liner with the significant command
        awaitText("running...");

        tuiRef.get().dispatch(KeyEvent.ofChar('y', KeyModifiers.CTRL));
        awaitText("streamed line"); // cards shown: live stream visible

        quit();
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

        shellRegistry.onEvent(new AgentEvent.TurnMetrics(3, 120, 400, 500, 2, 1500, sid(shellRegistry)));
        awaitText("3 LLM");
        awaitText("1.5s");
        awaitText("session: 1 LLM calls");

        quit();
    }

    @Test
    void outputThroughputShowsNextToTheTurnMetrics() throws Exception {
        final var shellRegistry = testRegistry();
        final var usageTracker = newUsageTracker();
        usageTracker.record(new Usage(10, 5, 15, 0, 0));
        final var command = command(shellRegistry, stubAgentLoop(), true, usageTracker);
        uiThread = new Thread(command, "tui-tps-test");
        uiThread.setDaemon(true);
        uiThread.start();
        awaitTui();
        awaitText("yca coding agent");

        shellRegistry.onEvent(new AgentEvent.TurnMetrics(
                3, 120, 400, 500, 2, 1500, new AgentEvent.Throughput(150, 3000, 0), sid(shellRegistry)));
        awaitText("3 LLM");
        awaitText("50.0 tok/s");

        // a turn whose throughput cannot be measured shows no segment at all
        shellRegistry.onEvent(new AgentEvent.TurnMetrics(3, 120, 400, 500, 2, 1500, sid(shellRegistry)));
        awaitNoText("tok/s");

        quit();
    }

    @Test
    void serverSideThroughputIsShownNextToTheMeasuredOne() throws Exception {
        final var shellRegistry = testRegistry();
        final var usageTracker = newUsageTracker();
        usageTracker.record(new Usage(10, 5, 15, 0, 0));
        final var command = command(shellRegistry, stubAgentLoop(), true, usageTracker);
        uiThread = new Thread(command, "tui-real-tps-test");
        uiThread.setDaemon(true);
        uiThread.start();
        awaitTui();
        awaitText("yca coding agent");

        shellRegistry.onEvent(new AgentEvent.TurnMetrics(
                3, 120, 400, 500, 2, 1500, new AgentEvent.Throughput(150, 3000, 4000), sid(shellRegistry)));
        awaitText("50.0 tok/s (real 37.5 tok/s)");

        quit();
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
                        new AgentEvent.TurnDone("lines:" + prompt.lines().count(), sessionId));
                return "ok";
            }

            @Override
            public String run(
                    final String prompt,
                    final String agentName,
                    final String sessionId,
                    final List<Message> history,
                    final BooleanSupplier cancelled,
                    final Supplier<String> queuedPrompt) {
                return run(prompt, agentName, sessionId, history, cancelled);
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

        quit();
    }

    @Test
    void tabsRouteEventsPerSessionAndShowStatusIcons() throws Exception {
        final var shellRegistry = testRegistry();
        final var lastSid = new AtomicReference<String>();
        final var agentLoop = new AgentLoop() {
            @Override
            public String run(
                    final String prompt,
                    final String agentName,
                    final String sessionId,
                    final List<Message> history,
                    final BooleanSupplier cancelled) {
                lastSid.set(sessionId);
                shellRegistry.onEvent(new AgentEvent.TurnDone("ran:" + sessionId, sessionId));
                return "ok";
            }

            @Override
            public String run(
                    final String prompt,
                    final String agentName,
                    final String sessionId,
                    final List<Message> history,
                    final BooleanSupplier cancelled,
                    final Supplier<String> queuedPrompt) {
                return run(prompt, agentName, sessionId, history, cancelled);
            }
        };
        final var command = command(shellRegistry, agentLoop, true, newUsageTracker());
        uiThread = new Thread(command, "tui-tabs-routing-test");
        uiThread.setDaemon(true);
        uiThread.start();
        awaitTui();
        awaitText("yca coding agent");

        // second tab becomes active on Ctrl+N - the bar appears with a close marker per tab
        tuiRef.get().dispatch(KeyEvent.ofChar('n', KeyModifiers.CTRL));
        awaitTextWithin(Glyphs.of("unicode").cross() + " ", 2_000);

        // a turn on tab two renders only in tab two's transcript
        tuiRef.get().dispatch(new PasteEvent("hi tab two"));
        tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
        awaitTextWithin("ran:", 2_000);
        final var secondSid = lastSid.get();

        // switch back to tab one: tab two's transcript must not leak into it
        // (the tab bar still shows tab two's title - "hi tab two" - on every tab, by design)
        tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.TAB, KeyModifiers.CTRL));
        awaitTextGone("ran:");

        // an event for the background tab two is routed to its own transcript, never the visible one
        shellRegistry.onEvent(new AgentEvent.Notice(AgentEvent.Notice.Level.INFO, "bg-for-two", secondSid));
        awaitTextGone("bg-for-two");

        // back on tab two: the background text arrived while hidden, and the bar shows the done icon
        tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.TAB, KeyModifiers.CTRL));
        awaitTextWithin("bg-for-two", 2_000);
        awaitTextWithin(" " + Glyphs.of("unicode").check() + " ", 2_000);
        awaitTextWithin(" +", 2_000); // the trailing new-tab affordance is on the bar

        quit();
    }

    @Test
    void singleTabShowsAddButtonAndCloseMarker() throws Exception {
        final var shellRegistry = testRegistry();
        final var command = command(shellRegistry, stubAgentLoop());
        uiThread = new Thread(command, "tui-single-tab-test");
        uiThread.setDaemon(true);
        uiThread.start();
        awaitTui();
        awaitText("yca coding agent");

        // with a single tab the strip still renders: the "+" affordance lets the
        // user open a second session and each tab exposes its close marker
        org.junit.jupiter.api.Assertions.assertTrue(
                backend.text().contains(" +"), "single tab must show the add button, got:\n" + backend.text());
        org.junit.jupiter.api.Assertions.assertTrue(
                backend.text().contains(Glyphs.of("unicode").cross() + " "),
                "tab must show the close marker, got:\n" + backend.text());

        quit();
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

        quit();
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
        // settle on the exact home position: with the row also required to match, a transient
        // mid-reflow frame (prompt re-wrapping a row while an async refresh delays the next
        // render) cannot satisfy the wait at a stale row
        final var atHome = awaitCursorAt(promptStart, atEnd[1]);
        org.junit.jupiter.api.Assertions.assertEquals(
                promptStart, atHome[0], "HOME must move the cursor to the first character of the line");
        org.junit.jupiter.api.Assertions.assertEquals(atEnd[1], atHome[1], "HOME must not change the cursor row");

        quit();
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

        quit();
    }

    @Test
    void mouseClickPositionsThePromptCursor() throws Exception {
        final var shellRegistry = testRegistry();
        final var command = command(shellRegistry, stubAgentLoop(), true, newUsageTracker());
        uiThread = new Thread(command, "tui-mouse-click-test");
        uiThread.setDaemon(true);
        uiThread.start();
        awaitTui();
        awaitText("yca coding agent");

        // type a line, bring the caret home, then click on the 3rd character cell:
        // the caret must jump to the clicked cell, not stay at its previous spot
        tuiRef.get().dispatch(new PasteEvent("hello"));
        awaitText("hello");
        tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.HOME));
        final var promptStart = columnOfLineContaining("hello");
        final var row = rowOf("hello");
        awaitCursorAt(promptStart);

        tuiRef.get().dispatch(MouseEvent.press(MouseButton.LEFT, promptStart + 2, row));
        final var clicked = awaitCursorAt(promptStart + 2);
        org.junit.jupiter.api.Assertions.assertEquals(
                row, clicked[1], "clicking the prompt must not change the prompt row");

        // the caret really moved: a typed char lands at the click position
        tuiRef.get().dispatch(KeyEvent.ofChar('X'));
        awaitText("heXllo");

        quit();
    }

    @Test
    void upMovesAcrossWrappedFragmentsLikeAGuiEditor() throws Exception {
        final var shellRegistry = testRegistry();
        final var command = command(shellRegistry, stubAgentLoop(), true, newUsageTracker());
        uiThread = new Thread(command, "tui-wrap-up-test");
        uiThread.setDaemon(true);
        uiThread.start();
        awaitTui();
        awaitText("yca coding agent");

        // 120 chars wrap onto a second visual fragment row of the prompt
        final var longLine = "wrap".repeat(30);
        tuiRef.get().dispatch(new PasteEvent(longLine));
        awaitText("wrap");
        final var promptStart = columnOfLineContaining("wrap");
        final var firstWrapRow = rowOf("wrap");
        final var wrappedRow = firstWrapRow + 1;

        // END places the caret at the end of the wrapped fragment; the draw fix
        // makes that caret visible on the wrapped row instead of off-screen
        tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.END));
        final var endCell = awaitCursorCell();
        org.junit.jupiter.api.Assertions.assertEquals(
                wrappedRow, endCell[1], "END must put the caret on the wrapped fragment row");
        org.junit.jupiter.api.Assertions.assertTrue(
                endCell[0] > promptStart, "END must put the caret inside the wrapped fragment");

        // UP moves to the previous visual fragment row, preserving the display
        // column clamped to the fragment end (GUI-editor behavior)
        tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.UP));
        var upCell = backend.lastReversed();
        while (upCell == null || upCell[1] != firstWrapRow) {
            assertTrue(
                    backend.awaitFrame(1_000),
                    "no frame received while waiting for the caret to move to the previous fragment row");
            upCell = backend.lastReversed();
        }
        org.junit.jupiter.api.Assertions.assertTrue(
                upCell[0] > promptStart, "UP must keep the caret inside the previous fragment");

        // the caret is really at the fragment boundary: a typed char lands there
        tuiRef.get().dispatch(KeyEvent.ofChar('X'));
        awaitText("Xwrap");

        quit();
    }

    @Test
    void mouseClickOnWideCharacterLandsOnItsStart() throws Exception {
        final var shellRegistry = testRegistry();
        final var command = command(shellRegistry, stubAgentLoop(), true, newUsageTracker());
        uiThread = new Thread(command, "tui-mouse-wide-char-test");
        uiThread.setDaemon(true);
        uiThread.start();
        awaitTui();
        awaitText("yca coding agent");

        // "中" occupies two display cells; clicking either of them must place the
        // caret at the wide char start (a caret cannot sit inside a wide char)
        tuiRef.get().dispatch(new PasteEvent("a中b"));
        awaitText("a中b");
        tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.HOME));
        final var promptStart = columnOfLineContaining("a中b");
        final var row = rowOf("a中b");

        tuiRef.get().dispatch(MouseEvent.press(MouseButton.LEFT, promptStart + 1, row));
        final var firstCell = awaitCursorAt(promptStart + 1);
        org.junit.jupiter.api.Assertions.assertEquals(row, firstCell[1], "click must stay on the prompt row");

        // a typed char lands before the wide char -> proves the caret sits at its start
        tuiRef.get().dispatch(KeyEvent.ofChar('X'));
        awaitText("aX中b");

        quit();
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

        quit();
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

        quit();
    }

    @Test
    void shortcutOverlayOpensWithAltQuestion() throws Exception {
        final var command = command(testRegistry(), stubAgentLoop());
        uiThread = new Thread(command, "tui-shortcuts-overlay-test");
        uiThread.setDaemon(true);
        uiThread.start();
        awaitTui();
        awaitText("yca coding agent");

        // plain "?" must type into the prompt, not open the overlay
        tuiRef.get().dispatch(KeyEvent.ofChar('?'));
        awaitText("?");

        // the overlay toggle is reserved to Alt+?
        tuiRef.get().dispatch(KeyEvent.ofChar('?', KeyModifiers.ALT));
        awaitText("Up/Down scroll"); // overlay footer: only rendered while the popup is open
        awaitText("send prompt"); // first binding listed in the Input group

        tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ESCAPE));
        awaitTextGone("Up/Down scroll");

        quit();
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
        // the default permissions state is "ask" (the mode the sidebar shows): the gutter (screen column 0) stays thin
        awaitNoRowStartingWith("┃");
        // the sidebar model line is a plain labeled row; the mode sits in its own row below
        awaitText("model test-model");
        awaitText("permissions ask");

        // the sidebar always shows "mode plan" (its own row), so sync on the
        // mode row before probing the color
        awaitText("mode plan");
        final var col = columnOfLineContaining("mode plan") + 5; // skip the muted "mode " label
        final var row = rowOf("mode plan");
        final var rgb = backend.fg(col, row).map(c -> c.toRgb()).orElse(null);
        assertTrue(
                rgb != null && rgb.r() == 0xf5 && rgb.g() == 0xa7 && rgb.b() == 0x42,
                "expected the plan mode value to be painted in the warning (plan) color, fg=" + backend.fg(col, row));

        tuiRef.get().dispatch(new PasteEvent("/mode build"));
        tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
        awaitRowStartingWith("┃"); // heavy bar on the prompt + global gutters
        awaitText("mode build");

        tuiRef.get().dispatch(new PasteEvent("/mode plan"));
        tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
        awaitNoRowStartingWith("┃");
        awaitText("mode plan");

        quit();
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

        quit();
    }

    @Test
    void toolOverlayShowsFormattedPreviewForEditFile() throws Exception {
        final var shellRegistry = testRegistry();
        final var command = command(shellRegistry, stubAgentLoop(), true, newUsageTracker());
        uiThread = new Thread(command, "tui-tool-overlay-preview-test");
        uiThread.setDaemon(true);
        uiThread.start();
        awaitTui();
        awaitText("yca coding agent");

        // an edit_file tool call whose transcript card is short: Ctrl+O must
        // surface the structured arguments preview (the synthetic diff)
        shellRegistry.onEvent(new AgentEvent.ToolStarted(
                "edit_file",
                "{\"path\":\"README.md\",\"oldString\":\"old\",\"newString\":\"new\"}",
                sid(shellRegistry)));
        shellRegistry.onEvent(new AgentEvent.ToolFinished("edit_file", "patched", true, sid(shellRegistry)));
        awaitText("edit_file"); // condensed card rendered
        awaitTextGone("-old"); // the diff preview only exists inside the overlay

        tuiRef.get().dispatch(KeyEvent.ofChar('o', KeyModifiers.CTRL));
        awaitText("full output");
        awaitText(" - old"); // DiffRenderer renders changed lines as " <tag> <text>"
        awaitText(" + new");

        tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ESCAPE));
        awaitTextGone("full output");

        quit();
    }

    @Test
    void subagentMarkersRenderInParentAndOverlayListsEmpty() throws Exception {
        final var shellRegistry = testRegistry();
        final var command = command(shellRegistry, stubAgentLoop(), true, newUsageTracker());
        uiThread = new Thread(command, "tui-subagents-test");
        uiThread.setDaemon(true);
        uiThread.start();
        awaitTui();
        awaitText("yca coding agent");

        // spawn/done markers fold into the parent transcript, no inline child stream
        final var sessionId = sid(shellRegistry);
        shellRegistry.onEvent(new AgentEvent.SubagentStarted("do the thing", sessionId, "sub-1", "child"));
        shellRegistry.onEvent(new AgentEvent.ThinkingDelta("child reasoning", sessionId, "sub-1", "child"));
        shellRegistry.onEvent(new AgentEvent.SubagentCompleted("done", true, sessionId, "sub-1", "child"));
        awaitText("subagent child started: do the thing");
        awaitTextGone("child reasoning");

        // no subagents registered in this shell registry -> the list is empty
        tuiRef.get().dispatch(new PasteEvent("/subagents"));
        tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
        awaitText("no subagents for this session");
        awaitText("Esc close");

        tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ESCAPE));
        awaitTextGone("no subagents for this session");

        quit();
    }

    @Test
    void subagentDetailModalRendersRegionsAndHonorsThinkingToggle() throws Exception {
        final var shellRegistry = testRegistry();
        final var mapper = formatMapper;
        final var configuration = subagentConfiguration();
        final var subagentRegistry = new SubagentRegistry(configuration, new ProviderManager(configuration, mapper));
        final var command = command(
                shellRegistry,
                stubAgentLoop(),
                true,
                newUsageTracker(),
                new TodoStore(configuration, mapper),
                Clock.systemUTC(),
                subagentRegistry);
        uiThread = new Thread(command, "tui-subagents-detail-test");
        uiThread.setDaemon(true);
        uiThread.start();
        awaitTui();
        awaitText("yca coding agent");

        // a real tracked subagent with a captured thought/answer/tool stream
        final var sessionId = sid(shellRegistry);
        final var sub =
                subagentRegistry.start(sessionId, "do the thing", "child").orElseThrow();
        shellRegistry.onEvent(new AgentEvent.ThinkingDelta("kid thinks", sessionId, sub.id(), "child"));
        shellRegistry.onEvent(new AgentEvent.TextDelta("kid answer", sessionId, sub.id(), "child"));
        shellRegistry.onEvent(new AgentEvent.ToolStarted("grep", "{\"q\":\"x\"}", -1L, sessionId, sub.id(), "child"));
        shellRegistry.onEvent(new AgentEvent.ToolFinished("grep", "match line", true, sessionId, sub.id(), "child"));

        // the detail view renders the captured regions - never the raw yca markers
        tuiRef.get().dispatch(new PasteEvent("/subagents " + sub.id()));
        tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
        awaitText("kid thinks");
        awaitText("kid answer");
        awaitText("match line");
        awaitNoText("<<<yca:");

        // Ctrl+T hides the reasoning in the modal too; the overlay consumes every
        // key while open, so the toggle happens with the modal closed and reopened
        tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ESCAPE));
        tuiRef.get().dispatch(KeyEvent.ofChar('t', KeyModifiers.CTRL));
        tuiRef.get().dispatch(new PasteEvent("/subagents " + sub.id()));
        tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
        awaitNoText("kid thinks");
        awaitText("kid answer");

        // ... and it comes back once thinking is shown again
        tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ESCAPE));
        tuiRef.get().dispatch(KeyEvent.ofChar('t', KeyModifiers.CTRL));
        tuiRef.get().dispatch(new PasteEvent("/subagents " + sub.id()));
        tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
        awaitText("kid thinks");

        quit();
    }

    @Test
    void modelPickerScrollsBeyondTheVisibleWindow() throws Exception {
        final var shellRegistry = testRegistry();
        final var command = command(shellRegistry, stubAgentLoop(), true, newUsageTracker());
        uiThread = new Thread(command, "tui-picker-scroll-test");
        uiThread.setDaemon(true);
        uiThread.start();
        awaitTui();
        awaitText("yca coding agent");

        // /model without an argument opens the interactive picker on the mock's 30 models
        tuiRef.get().dispatch(new PasteEvent("/model"));
        tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
        awaitText("type to filter | Up/Down move | Enter select | Esc cancel");
        awaitText("m01");
        awaitText("▴ 1/30 ▾"); // the list overflows the picker window: the hint shows the viewport

        // moving the selection past the window scrolls it: the top entry leaves the
        // viewport while the focused entry below it becomes visible
        for (int i = 0; i < 23; i++) {
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.DOWN));
        }
        awaitTextGone("m01");
        awaitText("m23");
        awaitText("/30 ▾"); // the scroll hint still tracks the 30-entry list

        // Esc cancels the wizard and closes the picker
        tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ESCAPE));
        awaitTextGone("type to filter | Up/Down move | Enter select | Esc cancel");

        quit();
    }

    @Test
    void modelPickerWheelScrollsTheList() throws Exception {
        final var shellRegistry = testRegistry();
        final var command = command(shellRegistry, stubAgentLoop(), true, newUsageTracker());
        uiThread = new Thread(command, "tui-picker-wheel-test");
        uiThread.setDaemon(true);
        uiThread.start();
        awaitTui();
        awaitText("yca coding agent");

        tuiRef.get().dispatch(new PasteEvent("/model"));
        tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
        awaitText("type to filter | Up/Down move | Enter select | Esc cancel");
        awaitText("m01");
        awaitText("▴ 1/30 ▾");

        // one wheel notch scrolls by yca.tui.scrollLines (3): 8 notches = 24 rows
        for (int i = 0; i < 8; i++) {
            tuiRef.get().dispatch(MouseEvent.scrollDown(10, 10));
        }
        awaitTextGone("m01");
        awaitText("m24");
        awaitText("/30 ▾"); // the scroll hint still tracks the 30-entry list

        tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ESCAPE));
        awaitTextGone("type to filter | Up/Down move | Enter select | Esc cancel");

        quit();
    }

    @Test
    void wheelScrollsTheToolOutputOverlayNotTheTranscript() throws Exception {
        final var shellRegistry = testRegistry();
        final var command = command(shellRegistry, stubAgentLoop(), true, newUsageTracker());
        uiThread = new Thread(command, "tui-tool-wheel-test");
        uiThread.setDaemon(true);
        uiThread.start();
        awaitTui();
        awaitText("yca coding agent");

        // a conversation long enough to overflow the 40-row screen: a wheel leaked to the
        // transcript would be visible (the top row would leave the viewport)
        final var toolOutput =
                IntStream.range(0, 60).mapToObj(i -> "tool line " + i).collect(Collectors.joining("\n"));
        final var transcript =
                IntStream.range(0, 60).mapToObj(i -> "transcript line " + i).collect(Collectors.joining("\n"));
        shellRegistry.onEvent(new AgentEvent.ToolStarted("bash", "{\"command\":\"ls -la\"}", sid(shellRegistry)));
        shellRegistry.onEvent(new AgentEvent.ToolStream("bash", toolOutput, sid(shellRegistry)));
        shellRegistry.onEvent(new AgentEvent.ToolFinished("bash", toolOutput, true, sid(shellRegistry)));
        awaitTextWithin("✓ bash", TIMEOUT_MS); // condensed tool block

        // the transcript now overflows the screen: TurnDone appends and auto-follows to the
        // bottom, away from the tool block and the first transcript line
        shellRegistry.onEvent(new AgentEvent.TurnDone(transcript, sid(shellRegistry)));
        awaitTextWithin("transcript line 59", TIMEOUT_MS);

        // prove the transcript really scrolls, otherwise the "does not move" guard below is void:
        // scrolled to the very top, the clickable tool block is visible again
        for (int i = 0; i < 5; i++) {
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.PAGE_UP));
        }
        awaitText("transcript line 0");
        awaitText("✓ bash"); // the block is above the transcript lines: both at the top
        tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.PAGE_DOWN));
        awaitTextGone("transcript line 0");
        for (int i = 0; i < 5; i++) {
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.PAGE_UP));
        }
        awaitText("transcript line 0");

        // open the tool output overlay: a wheel during a modal must scroll the modal only
        tuiRef.get().dispatch(MouseEvent.press(MouseButton.LEFT, 2, rowOf("✓ bash")));
        awaitText("tool line 0");
        awaitText("full output");
        for (int i = 0; i < 5; i++) {
            tuiRef.get().dispatch(MouseEvent.scrollDown(10, 10));
        }
        // the modal scrolled ...
        awaitTextGone("tool line 0");

        // ... and the transcript did not: a leaked wheel would have moved it down by 15 rows,
        // so once the modal is closed the first transcript line must still be on screen
        tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ESCAPE));
        awaitTextGone("full output");
        awaitText("transcript line 0");

        quit();
    }

    @Test
    void btwWheelScrollsItsDetailNotTheTranscript() throws Exception {
        final var shellRegistry = testRegistry();
        final var command = command(shellRegistry, stubAgentLoop(), true, newUsageTracker());
        uiThread = new Thread(command, "tui-btw-wheel-test");
        uiThread.setDaemon(true);
        uiThread.start();
        awaitTui();
        awaitText("yca coding agent");

        // a conversation long enough to overflow the 40-row screen: a wheel leaked to the
        // transcript would be visible (the top row would leave the viewport)
        final var transcript =
                IntStream.range(0, 60).mapToObj(i -> "transcript line " + i).collect(Collectors.joining("\n"));
        shellRegistry.onEvent(new AgentEvent.TurnDone(transcript, sid(shellRegistry)));
        awaitTextWithin("transcript line 59", TIMEOUT_MS); // appended, auto-followed to the bottom

        // bring the first transcript line back on screen: it must stay put while wheeling
        for (int i = 0; i < 5; i++) {
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.PAGE_UP));
        }
        awaitText("transcript line 0");

        // a tall btw answer makes the modal scrollable, with a unique marker on its first line
        llm.answer("FIRSTMARKER\n\n"
                + IntStream.range(0, 40).mapToObj(i -> "btw detail line " + i).collect(Collectors.joining("\n\n")));
        tuiRef.get().dispatch(new PasteEvent("/btw why?"));
        tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
        awaitText(" btw "); // modal title
        awaitText("Q: why?");
        awaitTextWithin("FIRSTMARKER", TIMEOUT_MS);

        // wheeling must scroll the btw modal, never the transcript hidden behind it
        for (int i = 0; i < 4; i++) {
            tuiRef.get().dispatch(MouseEvent.scrollDown(10, 10));
        }
        awaitTextGone("FIRSTMARKER"); // the detail scrolled: its first line left the window

        // the btw modal covered the first transcript rows, so the transcript guard runs after it
        // closes: a leaked wheel would have moved it down by 12 rows
        tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ESCAPE));
        awaitTextGone(" btw ");
        awaitText("transcript line 0");

        quit();
    }

    @Test
    void escInterruptsTheRunningTurn() throws Exception {
        final var shellRegistry = testRegistry();
        // the stub turn parks on releaseTurn once it signaled turnStarted, so the
        // Esc interrupt is exercised over a deterministically-live turn
        final var turnStarted = new CountDownLatch(1);
        final var releaseTurn = new CountDownLatch(1);
        final var agentLoop = new AgentLoop() {
            @Override
            public String run(
                    final String prompt,
                    final String agentName,
                    final String sessionId,
                    final List<Message> history,
                    final BooleanSupplier cancelled) {
                try {
                    turnStarted.countDown();
                    releaseTurn.await();
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                shellRegistry.onEvent(new AgentEvent.TextDelta("hello from stub", sessionId));
                shellRegistry.onEvent(new AgentEvent.TurnDone("hello from stub", sessionId));
                return "ok";
            }

            @Override
            public String run(
                    final String prompt,
                    final String agentName,
                    final String sessionId,
                    final List<Message> history,
                    final BooleanSupplier cancelled,
                    final Supplier<String> queuedPrompt) {
                return run(prompt, agentName, sessionId, history, cancelled);
            }
        };
        final var command = command(shellRegistry, agentLoop);
        uiThread = new Thread(command, "tui-esc-interrupt-test");
        uiThread.setDaemon(true);
        uiThread.start();
        awaitTui();
        awaitText("yca coding agent");

        tuiRef.get().dispatch(new PasteEvent("say hi"));
        tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
        assertTrue(
                turnStarted.await(TIMEOUT_MS, TimeUnit.MILLISECONDS), "the stub turn did not start before the timeout");

        // Esc while the turn runs must flag it as interrupted (same path Ctrl+C uses)
        tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ESCAPE));
        awaitText("interrupting...");

        releaseTurn.countDown();
        awaitText("hello from stub");

        quit();
    }

    private boolean anyRowStartsWith(final String prefix) {
        for (final var line : backend.text().split("\n")) {
            if (line.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private boolean anyRowContaining(final String needle) {
        for (final var line : backend.text().split("\n")) {
            if (line.contains(needle)) {
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

    /**
     * Deterministic teardown: quits the runner directly instead of relying on a
     * Ctrl+C key event, which overlays (e.g. the /subagents detail modal) can
     * swallow before the global quit handler sees it. Also asserts the UI thread
     * actually stopped so a missed quit fails fast instead of silently burning
     * the join timeout.
     */
    private void quit() throws InterruptedException {
        final var tui = tuiRef.get();
        if (tui != null && tui.isRunning()) {
            tui.quit();
        }
        uiThread.join(TIMEOUT_MS);
        assertFalse(uiThread.isAlive(), "ui thread still alive after quit");
    }

    private CodingAgentConfiguration configuration() {
        return CodingAgentConfigurations.builder()
                .baseUrl(llm.baseUrl())
                .storageHome(temp.resolve("home").toString())
                .build();
    }

    /**
     * Same as {@link #configuration()} but with sub-agents enabled and enough
     * headroom for the main agent (the effective limit is the model's max
     * concurrency minus one): the default test config pins
     * {@code yca.llm.maxConcurrent=1}, which makes {@code SubagentRegistry}
     * compute zero free slots.
     */
    private CodingAgentConfiguration subagentConfiguration() {
        final var map = new HashMap<String, String>();
        map.put("yca.storageHome", temp.resolve("home").toString());
        map.put("yca.provider.providers.length", "1");
        map.put("yca.provider.providers.0.id", "default");
        map.put("yca.provider.providers.0.baseUrl", llm.baseUrl());
        map.put("yca.provider.providers.0.apiKey", "test-key");
        map.put("yca.provider.providers.0.model", "test-model");
        map.put("yca.llm.maxConcurrent", "4");
        map.put("yca.subagents.enabled", "true");
        map.put("yca.subagents.maxConcurrent", "4");
        return new CodingAgentConfiguration$FusionConfigurationFactory(key -> Optional.ofNullable(map.get(key))).get();
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

    /**
     * Waits for {@code unexpected} to leave the screen and fails when it is still there at the
     * deadline: the frames painted before the mutation are legitimately on screen for a while, so
     * asserting on a fixed frame count would be a race (and a fast tick widens that race).
     */
    private void awaitNoText(final String unexpected) throws InterruptedException {
        final var deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (backend.text().contains(unexpected) && System.nanoTime() < deadline) {
            assertTrue(
                    backend.awaitFrame(1_000), "no frame received while waiting for '" + unexpected + "' to be gone");
        }
        assertFalse(backend.text().contains(unexpected), "'" + unexpected + "' should not be rendered");
    }

    /**
     * Like {@link #awaitText(String)} but fails after {@code timeoutMillis} instead of
     * looping forever: frames keep rendering on ticks, so a never-matching text would
     * otherwise hang the test.
     */
    private void awaitTextWithin(final String expected, final long timeoutMillis) throws InterruptedException {
        final var deadline = System.nanoTime() + timeoutMillis * 1_000_000;
        var screen = backend.text();
        while (!screen.contains(expected)) {
            final var snapshot = screen;
            assertTrue(
                    System.nanoTime() < deadline && backend.awaitFrame(200),
                    () -> "'" + expected + "' did not appear within " + timeoutMillis + " ms; last screen:\n"
                            + snapshot);
            screen = backend.text();
        }
    }

    private void awaitTextGoneWithin(final String expected, final long timeoutMillis) throws InterruptedException {
        final var deadline = System.nanoTime() + timeoutMillis * 1_000_000;
        var screen = backend.text();
        while (screen.contains(expected)) {
            final var snapshot = screen;
            assertTrue(
                    System.nanoTime() < deadline && backend.awaitFrame(200),
                    () -> "'" + expected + "' did not clear within " + timeoutMillis + " ms; last screen:\n"
                            + snapshot);
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

    /** Polls until the bottommost reversed-video cell sits at {@code expectedColumn} (row-agnostic). */
    private int[] awaitCursorAt(final int expectedColumn) throws InterruptedException {
        return settledCursor(c -> c.col() == expectedColumn, "cursor at column " + expectedColumn);
    }

    /** Polls until the bottommost reversed-video cell sits at {@code expectedColumn} on row {@code expectedRow}. */
    private int[] awaitCursorAt(final int expectedColumn, final int expectedRow) throws InterruptedException {
        return settledCursor(
                c -> c.col() == expectedColumn && c.row() == expectedRow,
                "cursor at column " + expectedColumn + ", row " + expectedRow);
    }

    /** Bottommost reversed-video cell, which is the prompt input cursor. */
    private int[] awaitCursorCell() throws InterruptedException {
        return settledCursor(c -> true, "the reversed cursor cell");
    }

    /**
     * Polls the prompt cursor until a state satisfying {@code accept} has been stable across two
     * consecutive frames, so a transient mid-reflow frame (the prompt re-wrapping by a row while an
     * asynchronous refresh delays the next render) cannot satisfy the wait: the first matching frame
     * only arms the wait, the frame after it confirms the layout settled. When the backend goes
     * quiescent (no more frames), the last observed state is accepted as-is, mirroring the previous
     * behavior where an already-correct-but-not-repainted state still passed.
     */
    private int[] settledCursor(final java.util.function.Predicate<HeadlessBackend.Cursor> accept, final String what)
            throws InterruptedException {
        var prev = HeadlessBackend.Cursor.NONE;
        while (true) {
            final var cur = backend.cursor();
            if (cur.equals(prev) && accept.test(cur)) {
                return new int[] {cur.col(), cur.row()};
            }
            if (accept.test(cur) && cur != HeadlessBackend.Cursor.NONE) {
                prev = cur; // first sighting: require a second identical frame
                if (!backend.awaitFrame(1_000)) {
                    return new int[] {cur.col(), cur.row()}; // settled: no more frames coming
                }
                continue;
            }
            prev = HeadlessBackend.Cursor.NONE; // state moved: restart the two-frame criterion
            if (!backend.awaitFrame(1_000)) {
                assertTrue(accept.test(cur), "no frame received while waiting for " + what);
                return new int[] {cur.col(), cur.row()};
            }
        }
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

    /** Screen row index of the first row containing {@code token}, without waiting for a frame. */
    private int rowIndex(final String token) {
        final var lines = backend.text().split("\n");
        for (var y = 0; y < lines.length; y++) {
            if (lines[y].contains(token)) {
                return y;
            }
        }
        return -1;
    }

    /** Screen row index of the first row containing {@code token}. */
    private int rowOf(final String token) throws InterruptedException {
        while (true) {
            final var lines = backend.text().split("\n");
            for (var y = 0; y < lines.length; y++) {
                if (lines[y].contains(token)) {
                    return y;
                }
            }
            assertTrue(backend.awaitFrame(1_000), "no frame received while waiting for '" + token + "'");
        }
    }

    /**
     * Asserts the two rows are separated by exactly one separator row (the block separator
     * markdown renders). The row content is not checked beyond the gap: every screen row also
     * carries the right-hand info panel, so a spacer row still shows its text.
     */
    private void assertBlankRowBetween(final String before, final String after) throws InterruptedException {
        final var first = rowOf(before);
        final var second = rowOf(after);
        assertTrue(
                second > first, "'" + after + "' must come after '" + before + "' (" + first + " then " + second + ")");
        org.junit.jupiter.api.Assertions.assertEquals(
                first + 2,
                second,
                () -> "expected one blank row between '" + before + "' and '" + after + "', screen was:\n"
                        + backend.text());
    }

    private static ShellRegistry testRegistry() {
        return new ShellRegistry(new Emitter() {
            @Override
            public <T> void emit(final T event) {
                // no-op: events are dispatched through ShellRegistry.onEvent in tests
            }
        });
    }

    /** Clock the test controls explicitly so elapsed-time rendering is deterministic. */
    private static final class MutableClock extends Clock {
        private final java.util.concurrent.atomic.AtomicLong millis;

        MutableClock(final long initialMillis) {
            this.millis = new java.util.concurrent.atomic.AtomicLong(initialMillis);
        }

        void advance(final long ms) {
            millis.addAndGet(ms);
        }

        @Override
        public java.time.ZoneId getZone() {
            return java.time.ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(final java.time.ZoneId zone) {
            return this;
        }

        @Override
        public java.time.Instant instant() {
            return java.time.Instant.ofEpochMilli(millis.get());
        }
    }
}
