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

import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Layout;
import dev.tamboui.layout.Rect;
import dev.tamboui.markdown.MarkdownStyles;
import dev.tamboui.style.Color;
import dev.tamboui.style.Overflow;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.MouseEvent;
import dev.tamboui.tui.event.MouseEventKind;
import dev.tamboui.tui.event.PasteEvent;
import dev.tamboui.tui.event.TickEvent;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.BorderSet;
import dev.tamboui.widgets.block.BorderType;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.checkbox.CheckboxState;
import dev.tamboui.widgets.gauge.Gauge;
import dev.tamboui.widgets.input.TextArea;
import dev.tamboui.widgets.input.TextAreaState;
import dev.tamboui.widgets.paragraph.Paragraph;
import dev.tamboui.widgets.spinner.SpinnerStyle;
import dev.tamboui.widgets.tabs.Tabs;
import dev.tamboui.widgets.tabs.TabsState;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.cli.Command;
import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;
import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.agent.AgentLoop;
import io.yupiik.ia.agent.core.agent.AgentProvider;
import io.yupiik.ia.agent.core.configuration.AgentConfiguration;
import io.yupiik.ia.agent.core.configuration.AgentMode;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.MetricsDisplayMode;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.maven.MavenRepositoryKnowledgeService;
import io.yupiik.ia.agent.core.mcp.McpManager;
import io.yupiik.ia.agent.core.permissions.ApprovalGate;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.question.FeedbackGate;
import io.yupiik.ia.agent.core.question.Question;
import io.yupiik.ia.agent.core.question.UserFeedbackService;
import io.yupiik.ia.agent.core.runtime.VirtualThreadExecutor;
import io.yupiik.ia.agent.core.session.AgentSessionService;
import io.yupiik.ia.agent.core.session.SessionStore;
import io.yupiik.ia.agent.core.shell.InterruptCoordinator;
import io.yupiik.ia.agent.core.shell.ShellEnv;
import io.yupiik.ia.agent.core.shell.ShellFrontend;
import io.yupiik.ia.agent.core.shell.ShellIO;
import io.yupiik.ia.agent.core.shell.ShellOutcome;
import io.yupiik.ia.agent.core.shell.ShellRegistry;
import io.yupiik.ia.agent.core.shell.ShellSession;
import io.yupiik.ia.agent.core.shell.TurnRunner;
import io.yupiik.ia.agent.core.shell.slash.SlashRegistry;
import io.yupiik.ia.agent.core.todo.TodoStore;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Pattern;

@DefaultScoped
@Command(name = "tui", description = "Interactive terminal UI session with the yca coding agent.")
public class TuiCommand implements Runnable {
    private static final String HINTS =
            "Enter send | Esc interrupt | drag select+copy | Ctrl+T thinking | Ctrl+Y actions | Ctrl+P todos | Ctrl+S compact | Ctrl+L clear | Ctrl+C quit";
    private static final String HINTS_SHORT =
            "Enter | Esc interrupt | drag copy | Ctrl+T | Ctrl+Y | Ctrl+P | Ctrl+S | Ctrl+L | Ctrl+C";
    private static final long DEFAULT_MAX_HISTORY_CHARACTERS = 10_000_000L;
    private static final String WELCOME = "# yca\n\nType a prompt and press Enter. `/help` lists the slash commands.";

    private String help() {
        return "\n\n" + slashRegistry.helpMarkdown();
    }

    private final TuiConf conf;
    private final TuiConfiguration tuiConfiguration;
    private final CodingAgentConfiguration configuration;
    private final AgentLoop agentLoop;
    private final ShellRegistry shellRegistry;
    private final PermissionService permissionService;
    private final UserFeedbackService userFeedbackService;
    private final SessionStore sessionStore;
    private final TodoStore todoStore;
    private final ChatClient chatClient;
    private final io.yupiik.ia.agent.core.style.ModelStyleService modelStyleService;
    private final McpManager mcpManager;
    private final VirtualThreadExecutor executor;
    private final ProviderManager providerManager;
    private final AgentProvider agentProvider;
    private final AgentSessionService agentSessionService;
    private final MavenRepositoryKnowledgeService mavenKnowledgeService;
    private final TurnRunner turnRunner;
    private final InterruptCoordinator interruptor;
    private final TuiTranscriptModel model;
    private final TuiRunnerFactory tuiRunnerFactory;
    private volatile Style thoughtStyle;
    private volatile Style toolStyle;
    private final TranscriptRenderer transcriptRenderer = new TranscriptRenderer();
    private volatile Palette palette;
    private volatile String theme = "dark";
    private final CompletionEngine completionEngine;
    private final PromptHistory promptHistory;
    private final TuiSlashCommands slashCommands;
    private final SlashRegistry slashRegistry;
    private final ShellSession session = new ShellSession();
    private volatile UiState uiState;
    private volatile TuiRunner uiRunner;
    private volatile CompletableFuture<Optional<String>> pendingLine;
    private final Clock clock;
    private final SyntaxHighlighter syntaxHighlighter;
    private final io.yupiik.ia.agent.core.context.UsageTracker usageTracker;

    private static final int MAX_RESUME_LIST = 25;
    private static final int RESUME_SUMMARY_WIDTH = 80;
    private static final Pattern SPLIT_COMMAND = Pattern.compile("\\s+");

    public TuiCommand(
            final TuiConf conf,
            final TuiConfiguration tuiConfiguration,
            final CodingAgentConfiguration configuration,
            final AgentLoop agentLoop,
            final ShellRegistry shellRegistry,
            final PermissionService permissionService,
            final UserFeedbackService userFeedbackService,
            final SessionStore sessionStore,
            final TodoStore todoStore,
            final ChatClient chatClient,
            final io.yupiik.ia.agent.core.style.ModelStyleService modelStyleService,
            final McpManager mcpManager,
            final VirtualThreadExecutor executor,
            final ProviderManager providerManager,
            final AgentProvider agentProvider,
            final AgentSessionService agentSessionService,
            final MavenRepositoryKnowledgeService mavenKnowledgeService,
            final TurnRunner turnRunner,
            final TuiTranscriptModel model,
            final TuiRunnerFactory tuiRunnerFactory,
            final TuiSlashCommands slashCommands,
            final SlashRegistry slashRegistry,
            final Clock clock,
            final io.yupiik.ia.agent.core.context.UsageTracker usageTracker) {
        this.conf = conf;
        this.tuiConfiguration = tuiConfiguration;
        this.configuration = configuration;
        this.agentLoop = agentLoop;
        this.shellRegistry = shellRegistry;
        this.permissionService = permissionService;
        this.userFeedbackService = userFeedbackService;
        this.sessionStore = sessionStore;
        this.todoStore = todoStore;
        this.chatClient = chatClient;
        this.modelStyleService = modelStyleService;
        this.mcpManager = mcpManager;
        this.executor = executor;
        this.providerManager = providerManager;
        this.agentProvider = agentProvider;
        this.agentSessionService = agentSessionService;
        this.mavenKnowledgeService = mavenKnowledgeService;
        this.turnRunner = turnRunner;
        this.model = model;
        this.tuiRunnerFactory = tuiRunnerFactory;
        this.slashCommands = slashCommands;
        this.slashRegistry = slashRegistry;
        this.clock = clock;
        this.usageTracker = usageTracker;
        this.interruptor = new InterruptCoordinator(clock, true);
        this.syntaxHighlighter = new SyntaxHighlighter().maxLineLength(configuration.maxLineLength());
        this.palette = Palette.from(conf.theme(), tuiConfiguration);
        this.completionEngine = new CompletionEngine(
                slashRegistry,
                this::completionModels,
                () -> providerManager == null || providerManager.providers() == null
                        ? List.of()
                        : providerManager.providers().stream()
                                .map(io.yupiik.ia.agent.core.configuration.ProviderConfiguration::id)
                                .toList(),
                () -> agentProvider == null || agentProvider.agents() == null
                        ? List.of()
                        : agentProvider.agents().stream()
                                .map(AgentConfiguration::name)
                                .toList(),
                this::sessionsForCompletion,
                this::toolsForCompletion,
                () -> agentProvider == null || agentProvider.agents() == null
                        ? List.of()
                        : agentProvider.agents().stream()
                                .map(AgentConfiguration::name)
                                .toList());
        this.promptHistory = new PromptHistory();
        applyTheme(persistedTheme() == null ? conf.theme() : persistedTheme());
    }

    @RootConfiguration("tui")
    public record TuiConf(
            @Property(
                    defaultValue = "io.yupiik.ia.agent.tui.KeyBindingPreset.STANDARD",
                    documentation = "Key binding preset: STANDARD, VIM, EMACS, INTELLIJ or VSCODE.")
            KeyBindingPreset bindings,

            @Property(defaultValue = "true", documentation = "Show the reasoning/thinking stream by default.")
            boolean showThinking,

            @Property(defaultValue = "\"dark\"", documentation = "UI theme: dark or light.")
            String theme,

            @Property(
                    defaultValue = "false",
                    documentation =
                            "Bypass permission prompts and allow all tool calls (not persisted to the transcript).")
            boolean permitAll) {}

    /**
     * Resolved color palette. In "dark" theme the configured values are used
     * verbatim; in "light" theme the base foreground/background/border colors are
     * inverted so the interface stays readable, while the accent/status colors
     * keep the configured values.
     */
    private record Palette(
            String accent,
            String primary,
            String success,
            String warning,
            String error,
            String info,
            String text,
            String textMuted,
            String border,
            String borderActive,
            String backgroundPanel,
            String permitAll) {

        private static Palette from(final String theme, final TuiConfiguration c) {
            if (!"light".equalsIgnoreCase(theme)) {
                return new Palette(
                        c.accent(),
                        c.primary(),
                        c.success(),
                        c.warning(),
                        c.error(),
                        c.info(),
                        c.text(),
                        c.textMuted(),
                        c.border(),
                        c.borderActive(),
                        c.backgroundPanel(),
                        c.permitAll());
            }
            return new Palette(
                    c.accent(),
                    c.primary(),
                    c.success(),
                    c.warning(),
                    c.error(),
                    c.info(),
                    "#1a1a1a",
                    "#6b6b6b",
                    "#c0c0c0",
                    "#808080",
                    "#ffffff",
                    c.permitAll());
        }
    }

    private Path themeStoragePath() {
        final var root = Path.of(configuration.resolveStorageHome()).toAbsolutePath();
        return root.resolve("theme");
    }

    private String persistedTheme() {
        final var file = themeStoragePath();
        try {
            if (java.nio.file.Files.isRegularFile(file)) {
                final var value = java.nio.file.Files.readString(file).strip().toLowerCase(java.util.Locale.ROOT);
                if ("light".equals(value) || "dark".equals(value)) {
                    return value;
                }
            }
        } catch (final java.io.IOException e) {
            // ignore and fall back to the configured theme
        }
        return null;
    }

    private void persistTheme(final String theme) {
        final var file = themeStoragePath();
        try {
            java.nio.file.Files.createDirectories(file.getParent() == null ? Path.of(".") : file.getParent());
            java.nio.file.Files.writeString(file, theme);
        } catch (final java.io.IOException e) {
            // best-effort persistence; it must never break a command
        }
    }

    private void applyTheme(final String theme) {
        final var normalized = "light".equalsIgnoreCase(theme) ? "light" : "dark";
        this.theme = normalized;
        this.palette = Palette.from(normalized, tuiConfiguration);
        final var opacity = Math.clamp(tuiConfiguration.thinkingOpacity(), 0.05, 1.0);
        final var thought = TuiColors.blend(palette.text(), palette.backgroundPanel(), opacity);
        this.thoughtStyle = Style.EMPTY.fg(thought);
        final var toolOpacity = Math.clamp(tuiConfiguration.toolOutputOpacity(), 0.05, 1.0);
        final var toolFg = TuiColors.blend(palette.text(), palette.backgroundPanel(), toolOpacity);
        this.toolStyle = Style.EMPTY.fg(toolFg);
    }

    @Override
    public void run() {
        final var state = new UiState(model, clock);
        state.sessionId = sessionStore.create();
        state.showThinking = conf.showThinking();
        session.sessionId(state.sessionId).activeAgent(state.activeAgent).showThinking(conf.showThinking());
        slashCommands.bind(arg -> {
            final var current = uiState;
            return current == null ? ShellOutcome.none() : themeOutcome(arg, current);
        });
        final var transcriptConf = tuiConfiguration.transcript();
        state.model.reset();
        state.model.maxHistoryCharacters(
                transcriptConf == null || transcriptConf.maxHistoryCharacters() <= 0
                        ? DEFAULT_MAX_HISTORY_CHARACTERS
                        : transcriptConf.maxHistoryCharacters());
        try (final var tui = tuiRunnerFactory.create(conf)) {
            state.runner = tui;
            shellRegistry.register(env(tui, state));
            // while the TUI owns the terminal, stderr logging would corrupt the screen:
            // route app and JLine WARNING+ records into the UI instead
            final var julFeedback = redirectLoggerToUi("io.yupiik", tui, state);
            final var jlineFeedback = redirectLoggerToUi("org.jline", tui, state);
            permissionService.setGate(gate(tui, state));
            userFeedbackService.setGate(feedbackGate(tui, state));
            permissionService.setPermitAll(conf.permitAll());
            state.model.status("ready");
            try {
                tui.run((event, runner) -> onKey(event, runner, state), frame -> render(frame, state));
            } finally {
                awaitInFlight(state);
                if (jlineFeedback != null) {
                    jlineFeedback.run();
                }
                if (julFeedback != null) {
                    julFeedback.run();
                }
                shellRegistry.unregister(env(tui, state));
                permissionService.setGate(null);
                userFeedbackService.setGate(null);
                permissionService.setPermitAll(false);
                executor.shutdown();
                if (state.sessionId != null && sessionStore.isEmpty(state.sessionId)) {
                    sessionStore.delete(state.sessionId);
                }
            }
        } catch (final RuntimeException e) {
            throw e;
        } catch (final Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    /**
     * While the TUI owns the terminal, stderr logging would corrupt the screen:
     * detach the parent handlers of the given logger and route WARNING+ records
     * into the UI (status bar, SEVERE as an error) instead. Returns a restore
     * callback, or null when the logger already carries custom handlers.
     */
    private Runnable redirectLoggerToUi(final String name, final TuiRunner tui, final UiState state) {
        final var logger = Logger.getLogger(name);
        final var previousUseParentHandlers = logger.getUseParentHandlers();
        if (logger.getHandlers().length > 0 || !previousUseParentHandlers) {
            return null;
        }
        logger.setUseParentHandlers(false);
        final Handler handler = new Handler() {
            {
                setLevel(Level.WARNING);
            }

            @Override
            public void publish(final LogRecord record) {
                if (record.getLevel().intValue() < Level.WARNING.intValue()) {
                    return;
                }
                final var level = record.getLevel();
                final var message = record.getMessage();
                tui.runLater(() -> {
                    if (level == Level.SEVERE) {
                        modelError(state, message);
                    } else {
                        state.model.status(message);
                    }
                });
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        logger.addHandler(handler);
        return () -> {
            logger.removeHandler(handler);
            logger.setUseParentHandlers(previousUseParentHandlers);
        };
    }

    private ApprovalGate gate(final TuiRunner tui, final UiState state) {
        return (title, detail, toolName, args) -> {
            final var latch = new CountDownLatch(1);
            final var outcome = new AtomicReference<ApprovalGate.Result>();
            tui.runLater(() -> state.approval = new Approval(title, detail, latch, outcome));
            try {
                while (!latch.await(200, TimeUnit.MILLISECONDS)) {
                    if (state.interrupted) {
                        tui.runLater(() -> {
                            if (state.approval != null && state.approval.latch() == latch) {
                                state.approval = null;
                            }
                        });
                        return ApprovalGate.Result.DENY;
                    }
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return ApprovalGate.Result.DENY;
            }
            final var r = outcome.get();
            return r == null ? ApprovalGate.Result.DENY : r;
        };
    }

    private FeedbackGate feedbackGate(final TuiRunner tui, final UiState state) {
        return questions -> {
            final var latch = new CountDownLatch(1);
            final var answers = new java.util.concurrent.ConcurrentHashMap<String, Object>();
            final var answered = new AtomicBoolean(); // true => answered, false => canceled
            tui.runLater(() -> state.feedbackPrompt = new FeedbackPrompt(questions, latch, answers, answered));
            try {
                while (!latch.await(200, TimeUnit.MILLISECONDS)) {
                    if (state.interrupted) {
                        tui.runLater(() -> {
                            if (state.feedbackPrompt != null && state.feedbackPrompt.latch() == latch) {
                                state.feedbackPrompt = null;
                            }
                        });
                        return null;
                    }
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            return answered.get() ? answers : null;
        };
    }

    private boolean onFeedbackKey(final Event event, final TuiRunner runner, final UiState state) {
        final var prompt = state.feedbackPrompt;
        if (prompt == null) {
            return false;
        }
        if (event instanceof final MouseEvent mouse) {
            return onFeedbackMouse(mouse, state);
        }
        if (!(event instanceof final KeyEvent key)) {
            return false;
        }
        final var tabCount = prompt.questions().size() + 1;
        final var isConfirmTab = prompt.tab() == prompt.questions().size();
        final var qIndex = isConfirmTab ? 0 : prompt.tab();
        final var fq = prompt.questions().get(qIndex);
        if (isConfirmTab) {
            if (key.isConfirm()) {
                confirmAnswers(state, prompt);
            } else if (key.isCancel() || key.isKey(KeyCode.ESCAPE)) {
                prompt.tab(Math.max(0, prompt.questions().size() - 1));
            } else if (key.isKey(KeyCode.BACKSPACE)) {
                prompt.tab(Math.max(0, prompt.questions().size() - 1));
            } else if (key.isLeft() || (key.hasCtrl() && key.isCharIgnoreCase('t'))) {
                prompt.tab(prompt.questions().size() - 1);
            }
            return true;
        }
        if (prompt.focusInput()) {
            if (key.isConfirm()) {
                prompt.focusInput(false);
            } else if (key.isKey(KeyCode.TAB)) {
                prompt.focusInput(false);
            } else if (key.isCancel() || key.isKey(KeyCode.ESCAPE)) {
                cancelFeedback(state);
            } else {
                handleTextInput(fq.ownAnswer(), key);
            }
            return true;
        }
        if (key.hasCtrl() && key.isCharIgnoreCase('t')) {
            prompt.tab((prompt.tab() + 1) % tabCount);
            return true;
        }
        switch (key.code()) {
            case LEFT, BACKSPACE -> toggleTab(prompt, false);
            case RIGHT, ENTER -> toggleTab(prompt, true);
            case UP -> moveOption(prompt, qIndex, -1);
            case DOWN -> moveOption(prompt, qIndex, 1);
            case TAB -> prompt.focusInput(true);
            case CHAR -> {
                if (key.codePoint() == ' ') {
                    if (fq.question.multiple()) {
                        activateCheckbox(fq, prompt.optionsFocus());
                    } else {
                        fq.selectedIndex(prompt.optionsFocus());
                        if (prompt.optionsFocus() == optionCount(fq) && optionCount(fq) > 0) {
                            prompt.focusInput(true);
                        }
                    }
                }
            }
            case ESCAPE -> cancelFeedback(state);
            default -> {}
        }
        return true;
    }

    private boolean onFeedbackMouse(final MouseEvent mouse, final UiState state) {
        final var prompt = state.feedbackPrompt;
        if (prompt == null || !(mouse.isPress() || mouse.isClick()) || !mouse.isLeftButton()) {
            return false;
        }
        final var x = mouse.x();
        final var y = mouse.y();
        final var tabs = prompt.tabsRect();
        if (tabs != null && tabs.contains(x, y)) {
            final var per = Math.max(1, tabs.width() / (prompt.questions().size() + 1));
            final var idx = (x - tabs.x()) / per;
            if (idx >= 0 && idx <= prompt.questions().size()) {
                prompt.tab(idx);
            }
            return true;
        }
        if (prompt.tab() < prompt.questions().size()) {
            final var fq = prompt.questions().get(prompt.tab());
            for (int i = 0; i < fq.optionRects().size(); i++) {
                final var r = fq.optionRects().get(i);
                if (r != null && r.contains(x, y)) {
                    if (fq.question.multiple()) {
                        activateCheckbox(fq, i);
                    } else {
                        fq.selectedIndex(i);
                        if (i == optionCount(fq)) {
                            prompt.focusInput(true);
                        }
                    }
                    return true;
                }
            }
        }
        return true;
    }

    private static void toggleTab(final FeedbackPrompt prompt, final boolean forward) {
        final var tabCount = prompt.questions().size() + 1;
        final var next = forward ? prompt.tab() + 1 : prompt.tab() - 1;
        prompt.tab(Math.floorMod(next, tabCount));
    }

    private static void moveOption(final FeedbackPrompt prompt, final int qIndex, final int delta) {
        final var fq = prompt.questions().get(qIndex);
        final var opts = optionCount(fq);
        final var total = opts + (opts == 0 ? 0 : 1);
        if (!fq.question.multiple() && opts > 0) {
            final var current = fq.selectedIndex();
            fq.selectedIndex(Math.floorMod((Math.max(current, 0)) + delta, total));
            setOptionFocus(prompt, fq, fq.selectedIndex());
        } else if (fq.question.multiple() && opts > 0) {
            final var current = prompt.optionsFocus();
            prompt.optionsFocus(Math.floorMod(Math.max(0, current) + delta, total));
        }
    }

    private static int optionCount(final FeedbackQuestion fq) {
        return fq.question.options() == null ? 0 : fq.question.options().size();
    }

    private static boolean ownEntrySelected(final FeedbackQuestion fq) {
        final var opts = optionCount(fq);
        return !fq.question.multiple() && opts > 0 && fq.selectedIndex() == opts;
    }

    private static void setOptionFocus(final FeedbackPrompt prompt, final FeedbackQuestion fq, final int idx) {
        if (idx >= 0) {
            prompt.optionsFocus(idx);
        }
    }

    private static void activateCheckbox(final FeedbackQuestion fq, final int idx) {
        if (!fq.question.multiple()) {
            return;
        }
        final var opts = optionCount(fq);
        if (opts > 0 && idx == opts) {
            fq.ownBox().toggle();
            return;
        }
        if (idx >= 0 && idx < fq.boxes().length && idx < opts) {
            fq.boxes()[idx].toggle();
        }
    }

    private void handleTextInput(final TextAreaState area, final KeyEvent key) {
        switch (key.code()) {
            case BACKSPACE -> area.deleteBackward();
            case DELETE -> area.deleteForward();
            case LEFT -> area.moveCursorLeft();
            case RIGHT -> area.moveCursorRight();
            case UP -> area.moveCursorUp();
            case DOWN -> area.moveCursorDown();
            case HOME -> area.moveCursorToLineStart();
            case END -> area.moveCursorToLineEnd();
            case CHAR -> {
                if (!key.hasCtrl() && !key.hasAlt()) {
                    final var cp = key.codePoint();
                    area.insert(cp > Character.MAX_VALUE ? "?" : String.valueOf((char) cp));
                }
            }
            default -> {}
        }
    }

    private void cancelFeedback(final UiState state) {
        final var prompt = state.feedbackPrompt;
        if (prompt != null) {
            prompt.answered().set(false);
            prompt.latch.countDown();
            state.feedbackPrompt = null;
        }
    }

    private void confirmAnswers(final UiState state, final FeedbackPrompt prompt) {
        final var answers = prompt.answers();
        for (final var fq : prompt.questions()) {
            final var own = fq.ownAnswer().text().strip();
            final var opts = optionCount(fq);
            if (fq.question.multiple()) {
                final var values = new ArrayList<String>();
                for (int i = 0; i < fq.boxes().length && i < opts; i++) {
                    if (fq.boxes()[i].isChecked()) {
                        values.add(fq.question.options().get(i));
                    }
                }
                if (fq.ownBox().isChecked() && !own.isEmpty()) {
                    values.add(own);
                }
                answers.put(fq.question.id(), values);
            } else {
                final var selected = fq.selectedIndex() >= 0 && fq.selectedIndex() < opts
                        ? fq.question.options().get(fq.selectedIndex())
                        : null;
                answers.put(
                        fq.question.id(), fq.selectedIndex() == opts && opts > 0 && !own.isEmpty() ? own : selected);
            }
        }
        prompt.answered().set(true);
        prompt.latch.countDown();
        state.feedbackPrompt = null;
    }

    private void onEvent(final AgentEvent event, final UiState state) {
        state.model.onEvent(event);
        if (event instanceof AgentEvent.ToolStarted) {
            state.spinnerTick = 0;
        } else if (event instanceof AgentEvent.TurnDone && !state.pendingPrompts.isEmpty()) {
            final var runner = state.runner;
            if (runner != null && !state.model.busy()) {
                final var next = state.pendingPrompts.removeFirst();
                runner.runLater(() -> submit(next, runner, state));
            }
        }
    }

    private boolean onKey(final Event event, final TuiRunner runner, final UiState state) {
        if (event instanceof final PasteEvent paste) {
            // normalize CRLF/CR so multiline pastes render and submit cleanly
            final var text = paste.text().replace("\r\n", "\n").replace("\r", "\n");
            if (state.feedbackPrompt != null) {
                feedbackInput(state.feedbackPrompt).insert(text);
                return true;
            }
            state.input.insert(text);
            return true;
        }
        if (event instanceof TickEvent) {
            return true;
        }
        if (state.editPlan != null) {
            if (event instanceof final MouseEvent editMouse) {
                return onEditPlanMouse(editMouse, state);
            }
            if (event instanceof final KeyEvent editKey) {
                return onEditPlanKey(editKey, runner, state);
            }
            return false;
        }
        if (state.feedbackPrompt != null) {
            return onFeedbackKey(event, runner, state);
        }
        if (state.picker != null) {
            if (event instanceof PasteEvent) {
                return true;
            }
            if (event instanceof final MouseEvent pickMouse) {
                return onPickerMouse(pickMouse, state);
            }
            if (event instanceof final KeyEvent pickKey) {
                if (!state.picker.confirm(pickKey)) {
                    state.picker.stroke(pickKey);
                }
            }
            return true;
        }
        if (event instanceof final MouseEvent mouse) {
            if (mouse.isScroll()) {
                if (mouse.kind() == MouseEventKind.SCROLL_UP) {
                    state.model.scrollBack(state.model.scrollBack() + 3);
                } else if (mouse.kind() == MouseEventKind.SCROLL_DOWN) {
                    state.model.scrollBack(Math.max(0, state.model.scrollBack() - 3));
                }
                return true;
            }
            if (mouse.isPress() && mouse.isLeftButton()) {
                state.selection.press(mouse.x(), mouse.y(), state.screenGrid);
                return true;
            }
            if (mouse.kind() == MouseEventKind.DRAG && mouse.isLeftButton()) {
                state.selection.drag(mouse.x(), mouse.y(), state.screenGrid);
                return true;
            }
            if (mouse.isRelease() && mouse.isLeftButton()) {
                copySelection(runner, state);
                return true;
            }
            if (mouse.isPress()) {
                state.selection.clear();
                return true;
            }
            return false;
        }
        if (!(event instanceof final KeyEvent key)) {
            return false;
        }
        if (state.approval != null) {
            final var approval = state.approval;
            if (key.isConfirm()) {
                final var ok = approval.perform();
                state.approval = null;
                state.model.status(ok ? "approved" : "denied");
            } else if (key.isCancel() || key.isKey(KeyCode.ESCAPE)) {
                approval.perform();
                state.approval = null;
                state.model.status("denied");
            } else if (key.isKey(KeyCode.LEFT)) {
                approval.action(approval.action() - 1);
            } else if (key.isKey(KeyCode.RIGHT) || key.isKey(KeyCode.TAB)) {
                approval.action(approval.action() + 1);
            } else if (key.isChar('1')) {
                approval.action(0);
            } else if (key.isChar('2')) {
                approval.action(1);
            } else if (key.isChar('3')) {
                approval.action(2);
            } else if (key.isKey(KeyCode.PAGE_UP)) {
                approval.detailScroll(approval.detailScroll() - 5);
            } else if (key.isKey(KeyCode.PAGE_DOWN)) {
                approval.detailScroll(approval.detailScroll() + 5);
            } else if (key.isKey(KeyCode.UP)) {
                approval.detailScroll(approval.detailScroll() - 1);
            } else if (key.isKey(KeyCode.DOWN)) {
                approval.detailScroll(approval.detailScroll() + 1);
            }
            return true;
        }
        if (key.hasCtrl() && key.hasShift() && key.isCharIgnoreCase('c')) {
            copySelection(runner, state);
            return true;
        }
        if (key.isCtrlC()) {
            if (interruptor.interrupt(state.model.busy()) == InterruptCoordinator.Decision.INTERRUPT) {
                state.quitArmed = true;
                state.interrupted = true;
                waiting(state, "interrupting... press Ctrl+C again to quit", SpinnerStyle.CLOCK);
            } else {
                state.quitArmed = false;
                awaitInFlight(state);
                runner.quit();
            }
            return true;
        }
        if (key.hasCtrl() && key.isCharIgnoreCase('t')) {
            state.showThinking = !state.showThinking;
            // keep the session flag in sync so /thinking (no arg) reports the rendered state
            session.showThinking(state.showThinking);
            state.model.status("thinking blocks " + (state.showThinking ? "shown" : "hidden"));
            return true;
        }
        if (key.hasCtrl() && key.isCharIgnoreCase('p')) {
            if (state.showTodos && !state.showTodosCollapsed) {
                state.showTodosCollapsed = true;
                state.model.status("todos collapsed");
            } else if (state.showTodos) {
                state.showTodos = false;
                state.showTodosCollapsed = false;
                state.model.status("todos hidden");
            } else {
                state.showTodos = true;
                state.model.status("todos shown");
            }
            return true;
        }
        if (key.hasCtrl() && key.isCharIgnoreCase('s')) {
            compact(runner, state);
            return true;
        }
        if (key.hasCtrl() && key.isCharIgnoreCase('l')) {
            state.model.clearConversation();
            state.model.status("transcript cleared");
            return true;
        }
        if (key.hasCtrl() && key.isCharIgnoreCase('y')) {
            state.showToolActions = !state.showToolActions;
            // keep the session flag in sync so /tool-output (no arg) reports the rendered state
            session.showToolOutput(state.showToolActions);
            state.model.status("tool blocks " + (state.showToolActions ? "shown" : "hidden"));
            return true;
        }
        if (key.isCancel() || key.isKey(KeyCode.ESCAPE)) {
            if (state.completion != null) {
                dismissCompletion(state);
                return true;
            }
            if (state.selection.hasSelection()) {
                state.selection.clear();
                return true;
            }
            if (state.model.busy()) {
                state.interrupted = true;
                waiting(state, "interrupting...", SpinnerStyle.CLOCK);
            }
            return true;
        }
        if (key.isPageUp()) {
            state.model.scrollBack(state.model.scrollBack() + 10);
            return true;
        }
        if (key.isPageDown()) {
            state.model.scrollBack(Math.max(0, state.model.scrollBack() - 10));
            return true;
        }
        if (key.hasCtrl() && key.isKey(KeyCode.HOME)) {
            state.model.scrollBack(Integer.MAX_VALUE);
            return true;
        }
        if (key.hasCtrl() && key.isKey(KeyCode.END)) {
            state.model.scrollBack(0);
            return true;
        }
        if (key.hasShift() && key.isKey(KeyCode.TAB)) {
            // shift+tab flips plan/build like /mode (tab stays on completion)
            slash("/mode " + (permissionService.mode() == AgentMode.BUILD ? "plan" : "build"), runner, state);
            return true;
        }
        if (key.isKey(KeyCode.TAB)) {
            complete(runner, state);
            return true;
        }
        if (key.hasAlt() && key.isCharIgnoreCase('b')) {
            moveCursorWord(state, false);
            return true;
        }
        if (key.hasAlt() && key.isCharIgnoreCase('f')) {
            moveCursorWord(state, true);
            return true;
        }
        if (key.hasCtrl() && key.isKey(KeyCode.LEFT)) {
            moveCursorWord(state, false);
            return true;
        }
        if (key.hasCtrl() && key.isKey(KeyCode.RIGHT)) {
            moveCursorWord(state, true);
            return true;
        }
        if (key.hasAlt() && !key.hasShift() && key.isKey(KeyCode.BACKSPACE)) {
            deleteWordBackward(state);
            return true;
        }
        if (key.hasCtrl() && key.isCharIgnoreCase('a')) {
            state.input.moveCursorToLineStart();
            return true;
        }
        if (key.hasCtrl() && key.isCharIgnoreCase('e')) {
            state.input.moveCursorToLineEnd();
            return true;
        }
        if (key.hasCtrl() && key.isCharIgnoreCase('k')) {
            killLineForward(state);
            return true;
        }
        if (key.hasCtrl() && key.isCharIgnoreCase('u')) {
            killLineBackward(state);
            return true;
        }
        if (key.hasCtrl() && key.isCharIgnoreCase('w')) {
            deleteWordBackward(state);
            return true;
        }
        if (key.hasCtrl() && key.isCharIgnoreCase('r')) {
            recallHistory(state, true);
            return true;
        }
        if (key.isKey(KeyCode.UP)) {
            handleLineOrHistory(state, true);
            return true;
        }
        if (key.isKey(KeyCode.DOWN)) {
            handleLineOrHistory(state, false);
            return true;
        }
        if (key.isConfirm() && !key.hasShift() && !key.hasAlt()) {
            final var text = state.input.text().strip();
            state.input.clear();
            if (!text.isEmpty()) {
                submit(text, runner, state);
            }
            return true;
        }
        switch (key.code()) {
            case ENTER -> state.input.insert("\n");
            case BACKSPACE -> {
                state.input.deleteBackward();
                liveComplete(state);
            }
            case DELETE -> {
                state.input.deleteForward();
                liveComplete(state);
            }
            case LEFT -> {
                dismissCompletion(state);
                state.input.moveCursorLeft();
            }
            case RIGHT -> {
                dismissCompletion(state);
                state.input.moveCursorRight();
            }
            case HOME -> {
                dismissCompletion(state);
                state.input.moveCursorToLineStart();
            }
            case END -> {
                dismissCompletion(state);
                state.input.moveCursorToLineEnd();
            }
            case CHAR -> {
                if (!key.hasCtrl()) {
                    state.input.insert(
                            key.codePoint() > Character.MAX_VALUE ? "?" : String.valueOf((char) key.codePoint()));
                    liveComplete(state);
                }
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    /**
     * Copies the current selection to the system clipboard over OSC 52. The
     * write is scheduled on the render thread so it never interleaves with a
     * frame draw. Single-character selections (plain clicks) are ignored to
     * avoid accidental clipboard overwrites.
     */
    private void copySelection(final TuiRunner runner, final UiState state) {
        final var text = state.selection.copy(state.screenGrid);
        if (text == null || text.length() <= 1 || text.isBlank()) {
            return;
        }
        final var payload = SelectionState.osc52Payload(text);
        final var copied = text.length();
        runner.runLater(() -> {
            try {
                runner.backend().writeRaw(payload);
                state.pushToast("copied " + copied + " characters", ToastLevel.SUCCESS);
                state.model.status("copied " + copied + " characters");
            } catch (final IOException e) {
                state.pushToast("clipboard copy failed: " + e.getMessage(), ToastLevel.ERROR);
                state.model.status("clipboard copy failed: " + e.getMessage());
            }
        });
    }

    private void submit(final String text, final TuiRunner runner, final UiState state) {
        this.uiRunner = runner;
        promptHistory.add(text);
        dismissCompletion(state);
        clearError(state);
        if (text.startsWith("/")) {
            slash(text, runner, state);
            return;
        }
        if (state.model.busy()) {
            state.pendingPrompts.addLast(text);
            final var n = state.pendingPrompts.size();
            state.model.status("queued prompt (" + n + " pending) - it will run when the agent finishes");
            state.input.clear();
            return;
        }
        if (agentSessionService.isResumeAlias(text)) {
            startResumeTurn(state);
            return;
        }
        if (state.title == null || state.title.isBlank()) {
            state.title = TuiInputSupport.firstLine(text);
        }
        state.model.startUserTurn(text);
        state.interrupted = false;
        waiting(state, "thinking...", SpinnerStyle.CLOCK);
        final var override = agentSessionService.agentOverride(text);
        final var agentName = override.agentName() != null ? override.agentName() : state.activeAgent;
        final var prompt = override.prompt();
        startTurn(state, agentName, prompt);
    }

    /**
     * Resume turn: re-runs the agent loop on the persisted history without sending any message
     * (zero extra prompt tokens), e.g. to continue after a truncated reply or a cancel.
     */
    private void startResumeTurn(final UiState state) {
        state.model.status("resuming previous turn");
        state.interrupted = false;
        waiting(state, "thinking...", SpinnerStyle.CLOCK);
        startTurn(state, state.activeAgent, null);
    }

    private void startTurn(final UiState state, final String agentName, final String prompt) {
        state.inFlight = turnRunner
                .submit(new TurnRunner.TurnRequest(
                        env(uiRunner, state),
                        state.sessionId,
                        agentName,
                        prompt,
                        () -> sessionStore.load(state.sessionId),
                        () -> state.interrupted))
                .toCompletableFuture();
        state.inFlight.thenAccept(ran -> {
            if (ran == null || !ran) {
                state.wait = null;
                state.model.status("maven knowledge index still running - prompt not sent");
            }
        });
    }

    /**
     * Awaits an in-flight turn so it can finish flushing its session entries
     * before the UI quits. Returns quickly when nothing is running or the run
     * is already complete.
     */
    private static void awaitInFlight(final UiState state) {
        final var inFlight = state.inFlight;
        if (inFlight == null || inFlight.isDone()) {
            return;
        }
        state.interrupted = true;
        try {
            inFlight.get(1, TimeUnit.SECONDS);
        } catch (final Exception e) {
            // best-effort: interrupt is set and the turn unwinds on its own
        }
    }

    private void slash(final String text, final TuiRunner runner, final UiState state) {
        uiState = state;
        state.model.transcript().append("\n\n---\n\n**You**\n\n`").append(text).append("`\n");
        // the TUI keeps its modal-driven plan editor (Enter/Esc handling needs the widget stack)
        final var parts = SPLIT_COMMAND.split(text.substring(1), 2);
        if ("editplan".equals(parts[0].toLowerCase(Locale.ROOT))) {
            editPlan(parts.length > 1 ? parts[1].strip() : "", runner, state);
            return;
        }
        executor.execute(() -> {
            try {
                final var outcome = slashRegistry
                        .dispatch(text, env(runner, state))
                        .toCompletableFuture()
                        .join();
                runner.runLater(() -> apply(outcome, runner, state));
            } catch (final RuntimeException e) {
                final var cause = e.getCause() == null ? e : e.getCause();
                runner.runLater(() -> modelError(state, "command failed: " + cause.getMessage()));
            }
        });
    }

    private void apply(final ShellOutcome outcome, final TuiRunner runner, final UiState state) {
        switch (outcome) {
            case ShellOutcome.None none -> {}
            case ShellOutcome.Status(String message) -> state.model.status(message);
            case ShellOutcome.Transcript(String markdown) ->
                state.model.transcript().append(markdown);
            case ShellOutcome.Error(String message) -> modelError(state, message);
            case ShellOutcome.Quit quit -> {
                awaitInFlight(state);
                runner.quit();
            }
        }
        syncSession(state);
    }

    private void syncSession(final UiState state) {
        if (session.sessionId() != null && !session.sessionId().isBlank()) {
            state.sessionId = session.sessionId();
        }
        if (session.activeAgent() != null) {
            state.activeAgent = session.activeAgent();
        }
    }

    private ShellEnv env(final TuiRunner runner, final UiState state) {
        return new ShellEnv(frontend(runner, state), io(runner, state), session, state.model);
    }

    private ShellFrontend frontend(final TuiRunner runner, final UiState state) {
        return new ShellFrontend() {
            @Override
            public void onEvent(final AgentEvent event) {
                runner.runLater(() -> TuiCommand.this.onEvent(event, state));
            }

            @Override
            public void status(final String message) {
                state.model.status(message);
            }

            @Override
            public void notice(final AgentEvent.Notice.Level level, final String message) {
                state.model.status(message);
            }

            @Override
            public void transcript(final String markdown) {
                state.model.transcript().append(markdown);
            }

            @Override
            public void error(final String message) {
                modelError(state, message);
            }

            @Override
            public void showThinking(final boolean value) {
                state.showThinking = value;
            }

            @Override
            public void showToolOutput(final boolean value) {
                state.showToolActions = value;
            }

            @Override
            public void clear() {
                // conversation state lives in the transcript (already reset by the action)
            }

            @Override
            public void onReplay(final String markdown) {
                // replay already went through the shared transcript, which is the TUI model
            }
        };
    }

    private ShellIO io(final TuiRunner runner, final UiState state) {
        return new ShellIO() {
            @Override
            public CompletionStage<Optional<String>> readLine(final String prompt) {
                final var future = new CompletableFuture<Optional<String>>();
                pendingLine = future;
                return future;
            }

            @Override
            public CompletionStage<Boolean> confirm(final String question) {
                final var answers = userFeedbackService.ask(
                        List.of(new Question("confirm", question, List.of("Yes", "No"), false)));
                return CompletableFuture.completedStage(
                        answers != null && "Yes".equalsIgnoreCase(String.valueOf(answers.get("confirm"))));
            }

            @Override
            public CompletionStage<Optional<String>> pick(final String title, final List<String> options) {
                final var future = new CompletableFuture<Optional<String>>();
                runner.runLater(() -> openPicker(
                        state,
                        title,
                        options,
                        value -> future.complete(Optional.of(value)),
                        () -> future.complete(Optional.empty())));
                return future;
            }

            @Override
            public ApprovalGate approvalGate() {
                return gate(runner, state);
            }

            @Override
            public FeedbackGate feedbackGate() {
                return TuiCommand.this.feedbackGate(runner, state);
            }
        };
    }

    private ShellOutcome themeOutcome(final String arg, final UiState state) {
        final var current = isPaletteLight() ? "light" : "dark";
        if (arg == null || arg.isBlank()) {
            state.model.status("theme: " + current + " (/theme light|dark to switch)");
            return ShellOutcome.none();
        }
        final var next = arg.strip().toLowerCase(Locale.ROOT);
        if (!"light".equals(next) && !"dark".equals(next)) {
            state.model.status("usage: /theme light|dark (current: " + current + ")");
            return ShellOutcome.none();
        }
        applyTheme(next);
        persistTheme(next);
        state.model.status("theme: " + next);
        return ShellOutcome.none();
    }

    private boolean isPaletteLight() {
        return "#1a1a1a".equalsIgnoreCase(palette.text());
    }

    private void complete(final TuiRunner runner, final UiState state) {
        final var row = state.input.cursorRow();
        final var line = state.input.getLine(row);
        final var cursor = state.input.cursorCol();
        final var suggestion = completionEngine.suggest(line, cursor);
        if (suggestion.empty()) {
            state.model.status("no completion for '" + line + "'");
            state.completion = null;
            state.completionCursor = -1;
            return;
        }
        // refresh the popup whenever the suggestion changes
        if (state.completion == null || !sameText(suggestion, state.completion)) {
            state.completion = suggestion;
            state.completionCursor = cursor;
            state.completionIndex = 0;
        }
        applyCompletion(state);
    }

    private static boolean sameText(final CompletionEngine.Suggestion a, final CompletionEngine.Suggestion b) {
        if (a == b) {
            return true;
        }
        return a != null
                && b != null
                && a.items().equals(b.items())
                && a.replaceStart() == b.replaceStart()
                && a.replaceEnd() == b.replaceEnd();
    }

    private void applyCompletion(final UiState state) {
        final var suggestion = state.completion;
        if (suggestion == null || suggestion.empty()) {
            return;
        }
        final var items = suggestion.items();
        final var choice = items.get(state.completionIndex % items.size());
        state.completionIndex++;
        replaceInput(state, suggestion, choice);
    }

    private void replaceInput(
            final UiState state, final CompletionEngine.Suggestion suggestion, final String replacement) {
        final var row = state.input.cursorRow();
        final var line = state.input.getLine(row);
        final var newValue =
                line.substring(0, suggestion.replaceStart()) + replacement + line.substring(suggestion.replaceEnd());
        setInputLine(state, row, newValue);
        // position the cursor just after the inserted replacement range
        applyCursor(state, row, suggestion.replaceStart() + replacement.length());
        state.completionCursor = state.input.cursorCol();
    }

    private void cycleCompletion(final UiState state, final boolean forward) {
        final var suggestion = state.completion;
        if (suggestion == null || suggestion.empty()) {
            return;
        }
        final var size = suggestion.items().size();
        if (forward) {
            state.completionIndex = (state.completionIndex + 1) % size;
        } else {
            state.completionIndex = (state.completionIndex + size - 1) % size;
        }
        applyCompletion(state);
        // after applying, re-suggest so the popup tracks the new input
        final var re = completionEngine.suggest(state.input.getLine(state.input.cursorRow()), state.input.cursorCol());
        state.completion = re.empty() ? suggestion : re;
        state.completionIndex = 0;
    }

    private void dismissCompletion(final UiState state) {
        state.completion = null;
        state.completionCursor = -1;
    }

    /**
     * Live completion: keeps the popup in sync with what is typed, so slash
     * commands and their parameters offer candidates without explicitly
     * pressing Tab. The popup only opens for "/" prefixes and "@agent".
     */
    private void liveComplete(final UiState state) {
        final var line = state.input.getLine(state.input.cursorRow());
        if (!line.startsWith("/")) {
            dismissCompletion(state);
            return;
        }
        final var cursor = state.input.cursorCol();
        final var suggestion = completionEngine.suggest(line, cursor);
        if (suggestion.empty()) {
            dismissCompletion(state);
            return;
        }
        if (state.completion == null
                || state.completionCursor != cursor
                || !suggestion.items().equals(state.completion.items())) {
            state.completion = suggestion;
            state.completionCursor = cursor;
            state.completionIndex = 0;
        }
    }

    private List<String> sessionsForCompletion() {
        if (sessionStore == null) {
            return List.of();
        }
        try {
            final var sessions = sessionStore.list();
            if (sessions == null || sessions.isEmpty()) {
                return List.of();
            }
            return sessions.stream().map(SessionStore.SessionInfo::id).toList();
        } catch (final RuntimeException e) {
            return List.of();
        }
    }

    private List<String> completionModels() {
        if (chatClient == null) {
            return List.of();
        }
        try {
            final var ids = chatClient.models().ids();
            return ids == null ? List.of() : ids;
        } catch (final Exception e) {
            return List.of();
        }
    }

    private List<String> toolsForCompletion() {
        if (mcpManager == null) {
            return List.of();
        }
        try {
            final var byServer = mcpManager.toolsByServer();
            if (byServer == null || byServer.isEmpty()) {
                return List.of();
            }
            return byServer.values().stream()
                    .flatMap(List::stream)
                    .map(EmbeddedTool::name)
                    .distinct()
                    .sorted()
                    .toList();
        } catch (final RuntimeException e) {
            return List.of();
        }
    }

    private void moveCursorWord(final UiState state, final boolean forward) {
        final var row = state.input.cursorRow();
        final var line = state.input.getLine(row);
        final var col = state.input.cursorCol();
        final var target = forward ? TuiInputSupport.nextWord(line, col) : TuiInputSupport.previousWord(line, col);
        applyCursor(state, row, target);
    }

    /** Moves the cursor to an absolute (row, col) cell of the multiline buffer. */
    private void applyCursor(final UiState state, final int row, final int col) {
        state.input.moveCursorToStart();
        for (var i = 0; i < row; i++) {
            state.input.moveCursorDown();
        }
        for (var i = 0; i < col; i++) {
            state.input.moveCursorRight();
        }
    }

    /** Replaces the given row's content, leaving the other lines of the buffer untouched. */
    private void setInputLine(final UiState state, final int row, final String newLine) {
        final var lines = new ArrayList<String>(Math.max(1, state.input.lineCount()));
        for (var i = 0; i < state.input.lineCount(); i++) {
            lines.add(i == row ? newLine : state.input.getLine(i));
        }
        state.input.setText(String.join("\n", lines));
    }

    /**
     * Up/Down on the prompt line: when a completion popup is active the arrows
     * cycle its matches; otherwise (idle, single logical line) they recall the
     * prompt history; in all other cases the cursor moves across lines.
     */
    private void handleLineOrHistory(final UiState state, final boolean up) {
        if (state.completion != null && !state.completion.empty()) {
            cycleCompletion(state, up);
            return;
        }
        if (state.model.busy()) {
            moveCursor(state, up);
            return;
        }
        final var lastLine = Math.max(0, state.input.lineCount() - 1);
        final var atBoundary = up ? state.input.cursorRow() == 0 : state.input.cursorRow() >= lastLine;
        if (!atBoundary) {
            // plain multiline editing: move the cursor across lines
            moveCursor(state, up);
            return;
        }
        if (!historyRecallable()) {
            moveCursor(state, up);
            return;
        }
        if (!state.historyActive) {
            state.historyActive = true;
            state.historyDraft = state.input.text();
        }
        final var recalled =
                up ? promptHistory.olderPush(state.historyDraft) : promptHistory.newerPull(state.historyDraft);
        if (recalled != null) {
            state.input.setText(recalled);
            positionCursorAtEnd(state);
        }
        if (!up && !promptHistory.navigating()) {
            state.historyActive = false;
            state.historyDraft = "";
        }
    }

    private boolean historyRecallable() {
        return promptHistory.navigating() || promptHistory.size() > 0;
    }

    /**
     * Recalls prompt history regardless of cursor position (bound to Ctrl+R for
     * an older entry and Ctrl+Up/Ctrl+Down for older/newer).
     */
    private void recallHistory(final UiState state, final boolean older) {
        if (!historyRecallable()) {
            return;
        }
        if (!state.historyActive) {
            state.historyActive = true;
            state.historyDraft = state.input.text();
        }
        final var recalled =
                older ? promptHistory.olderPush(state.historyDraft) : promptHistory.newerPull(state.historyDraft);
        if (recalled != null) {
            state.input.setText(recalled);
            positionCursorAtEnd(state);
        }
        if (!older && !promptHistory.navigating()) {
            state.historyActive = false;
            state.historyDraft = "";
        }
    }

    private void moveCursor(final UiState state, final boolean up) {
        if (up) {
            state.input.moveCursorUp();
        } else {
            state.input.moveCursorDown();
        }
    }

    private void positionCursorAtEnd(final UiState state) {
        state.input.moveCursorToEnd();
    }

    private void deleteWordBackward(final UiState state) {
        final var row = state.input.cursorRow();
        final var line = state.input.getLine(row);
        final var col = state.input.cursorCol();
        final var start = TuiInputSupport.previousWord(line, col);
        setInputLine(state, row, line.substring(0, start) + line.substring(col));
        applyCursor(state, row, start);
    }

    private void killLineForward(final UiState state) {
        final var row = state.input.cursorRow();
        final var line = state.input.getLine(row);
        setInputLine(state, row, line.substring(0, state.input.cursorCol()));
    }

    private void killLineBackward(final UiState state) {
        final var row = state.input.cursorRow();
        final var line = state.input.getLine(row);
        final var col = state.input.cursorCol();
        setInputLine(state, row, line.substring(col));
        applyCursor(state, row, 0);
    }

    private void models(final TuiRunner runner, final UiState state) {
        executor.execute(() -> {
            try {
                final var ids = chatClient.models().ids();
                runner.runLater(() -> openModelPicker(state, ids));
            } catch (final Exception e) {
                runner.runLater(() -> modelError(state, "models failed: " + e.getMessage()));
            }
        });
    }

    private void openModelPicker(final UiState state, final List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            state.model.status("no models available");
            return;
        }
        openPicker(
                state,
                "model",
                ids,
                selected -> {
                    try {
                        chatClient.setModel(selected);
                        state.model.status("model: " + selected);
                    } catch (final RuntimeException e) {
                        state.model.status("model failed: " + e.getMessage());
                    }
                },
                () -> state.model.status("model picker cancelled"));
    }

    private void editPlan(final String arg, final TuiRunner runner, final UiState state) {
        if (state.model.busy()) {
            state.model.status("editplan is not available while busy");
            return;
        }
        final var title = state.title == null || state.title.isBlank() ? "plan" : state.title;
        final var export = agentSessionService.exportPlan(state.sessionId, arg, title);
        if (export.error() != null) {
            state.model.status(export.error());
            return;
        }
        state.editPlan = new PendingEditPlan(export.path(), export.original(), null, null);
        state.model.status("plan exported to " + export.path()
                + " - edit it, save, then press Enter to reload (Esc or click Cancel to abort)");
        state.model
                .transcript()
                .append("\n\n> plan exported to `")
                .append(export.path())
                .append("` - edit it, save, then press Enter to reload, or Esc to abort\n");
    }

    private boolean onEditPlanKey(final KeyEvent key, final TuiRunner runner, final UiState state) {
        if (key.isConfirm()) {
            reloadPlan(runner, state);
        } else if (key.isCancel() || key.isKey(KeyCode.ESCAPE) || key.isKey(KeyCode.BACKSPACE)) {
            abortEditPlan(state);
        }
        return true;
    }

    private boolean onEditPlanMouse(final MouseEvent mouse, final UiState state) {
        if (!(mouse.isPress() || mouse.isClick()) || !mouse.isLeftButton()) {
            return true;
        }
        final var pending = state.editPlan;
        if (pending == null) {
            return true;
        }
        final var x = mouse.x();
        final var y = mouse.y();
        if (pending.confirmRect() != null && pending.confirmRect().contains(x, y)) {
            reloadPlan(null, state);
        } else if (pending.cancelRect() != null && pending.cancelRect().contains(x, y)) {
            abortEditPlan(state);
        }
        return true;
    }

    private void reloadPlan(final TuiRunner runner, final UiState state) {
        final var pending = state.editPlan;
        if (pending == null) {
            return;
        }
        final Path path = pending.path();
        final var reload = agentSessionService.reloadPlan(state.sessionId, path, pending.original());
        if (reload.error() != null) {
            if (("plan file not found: " + path).equals(reload.error())) {
                state.model.status("plan file not found: " + path + " (still waiting - edit and press Enter)");
                return;
            }
            state.model.status(reload.error());
            return;
        }
        if (reload.changed()) {
            state.editPlan = null;
        }
        state.model.status(reload.message());
    }

    private void abortEditPlan(final UiState state) {
        state.editPlan = null;
        state.model.status("plan edit cancelled");
    }

    private void renderEditPlan(final dev.tamboui.terminal.Frame frame, final UiState state) {
        final var pending = state.editPlan;
        final var accent = Color.hex(palette.accent());
        final var warning = Color.hex(palette.warning());
        final var text = Color.hex(palette.text());
        final var muted = Color.hex(palette.textMuted());
        final var width = Math.min(Math.max(60, frame.width() * 4 / 5), frame.width());
        final var height = Math.min(Math.max(10, frame.height() / 3), frame.height());
        final var rect = Modal.rect(frame, width, height, 0.25);
        final var inner = Modal.box(frame, rect, " edit plan ", warning);
        if (inner == null) {
            return;
        }
        final var splits = Layout.vertical()
                .constraints(Constraint.length(1), Constraint.length(1), Constraint.fill(), Constraint.length(1))
                .split(inner);
        frame.renderWidget(
                Paragraph.builder()
                        .text(Text.from(Line.from(Span.styled(
                                "Edit the file below in your preferred editor, save, then return here.",
                                Style.EMPTY.fg(text).bold()))))
                        .build(),
                splits.get(0));
        frame.renderWidget(
                Paragraph.builder()
                        .text(Text.from(Line.from(Span.styled(
                                ellipsize(
                                        pending.path().toString(), splits.get(1).width()),
                                Style.EMPTY.fg(muted)))))
                        .build(),
                splits.get(1));
        frame.renderWidget(
                Paragraph.builder()
                        .text(Text.from(Line.from(Span.raw(
                                        "Use `# Plan` for the plan text and `# Todos` for the todo list (one `- [ ]` / `- [>]` / `- [x]` item per line).")
                                .style(Style.EMPTY.fg(muted)))))
                        .build(),
                splits.get(2));
        final var footer = splits.get(3);
        final var per = Math.max(1, footer.width() / 2);
        final var confirmRect = new Rect(footer.x(), footer.y(), per, footer.height());
        final var cancelRect = new Rect(footer.x() + per, footer.y(), footer.width() - per, footer.height());
        pending.confirmRect(confirmRect);
        pending.cancelRect(cancelRect);
        final var confirmLine =
                new Rect(confirmRect.x() + 1, confirmRect.y(), confirmRect.width() - 2, confirmRect.height());
        final var cancelLine =
                new Rect(cancelRect.x() + 1, cancelRect.y(), cancelRect.width() - 2, cancelRect.height());
        frame.renderWidget(
                Paragraph.builder()
                        .text(Text.from(Line.from(Span.styled(
                                "[ Confirm (Enter) ]", Style.EMPTY.fg(accent).bold()))))
                        .build(),
                confirmLine);
        frame.renderWidget(
                Paragraph.builder()
                        .text(Text.from(Line.from(Span.styled(
                                "[ Cancel (Esc) ]", Style.EMPTY.fg(text).bold()))))
                        .build(),
                cancelLine);
    }

    private void resyncGraph(final TuiRunner runner, final UiState state) {
        if (state.model.busy()) {
            state.model.status("cannot resync the graph while busy");
            return;
        }
        waiting(state, "resyncing structural code graph...", SpinnerStyle.BOUNCING_BALL);
        executor.execute(() -> {
            try {
                final var result = agentSessionService.resyncGraph();
                runner.runLater(() -> {
                    state.model.status("structural code graph resynced");
                    state.model
                            .transcript()
                            .append("\n\n> structural code graph: `")
                            .append(result)
                            .append("`\n");
                });
            } catch (final RuntimeException e) {
                runner.runLater(() -> modelError(state, "resync failed: " + e.getMessage()));
            }
        });
    }

    private void compact(final TuiRunner runner, final UiState state) {
        if (state.model.busy()) {
            state.model.status("cannot compact while busy");
            return;
        }
        waiting(state, "compacting...", SpinnerStyle.BOUNCING_BALL);
        executor.execute(() -> {
            try {
                final var result = agentSessionService.compact(state.sessionId);
                runner.runLater(() -> {
                    state.model.status(result.message());
                    if (result.applied()) {
                        if (result.error() != null) {
                            state.model
                                    .transcript()
                                    .append("\n\n> ⚠ ")
                                    .append(result.message())
                                    .append(": ")
                                    .append(result.error())
                                    .append('\n');
                        } else {
                            state.model
                                    .transcript()
                                    .append("\n\n> ")
                                    .append(result.message())
                                    .append('\n');
                        }
                    } else {
                        state.model
                                .transcript()
                                .append("\n\n> ")
                                .append(result.message())
                                .append('\n');
                    }
                });
            } catch (final RuntimeException e) {
                runner.runLater(() -> modelError(state, "compaction failed: " + e.getMessage()));
            }
        });
    }

    private void render(final dev.tamboui.terminal.Frame frame, final UiState state) {
        if (state.model.runningTool() != null || activeWait(state) != null) {
            state.spinnerTick++;
        }
        state.model.trim();
        if (state.toast != null
                && tuiConfiguration.toastDurationMillis() > 0
                && clock.millis() - state.toastShownAt > tuiConfiguration.toastDurationMillis()) {
            state.clearToast();
        }
        final var todosRender = todoStore.render();
        if (!todosRender.equals(state.lastTodosRender)) {
            state.lastTodosRender = todosRender;
            if (!todosRender.isBlank() && !"(empty)".equals(todosRender) && !state.showTodos) {
                state.showTodos = true;
            }
        }
        // the prompt grows with its content (multiline pastes) up to a sane maximum
        final var inputRows = Math.min(8, 2 + Math.max(1, state.input.lineCount()));
        final var root = Layout.vertical()
                .constraints(
                        Constraint.fill(), Constraint.length(inputRows), Constraint.length(1), Constraint.length(2))
                .split(frame.area());
        // responsive: drop the sidebar on narrow terminals to give the chat room
        final var screenWidth = frame.width();
        final var narrow = screenWidth < 68;
        final var sidebarWidth = narrow ? 0 : (screenWidth < 95 ? 22 : 28);
        final var body = Layout.horizontal()
                .constraints(Constraint.fill(), Constraint.length(sidebarWidth))
                .split(root.getFirst());
        if (!narrow) {
            renderSidebar(body.get(1), frame, state);
        }
        var chat = accentGutter(body.get(0), frame);
        final var withHints = Layout.vertical()
                .constraints(Constraint.fill(), Constraint.length(1))
                .split(chat);
        chat = withHints.get(0);
        renderHints(withHints.get(1), frame, state, narrow);
        if (state.showTodos) {
            final var withTodos = Layout.horizontal()
                    .constraints(Constraint.fill(), Constraint.length(state.showTodosCollapsed ? 14 : 34))
                    .split(chat);
            chat = withTodos.get(0);
            renderTodos(withTodos.get(1), frame, state, todosRender);
        }
        // thinking streams inline in the conversation panel (dimmed, thin left bar),
        // gated by showThinking, so no dedicated stacked panel is needed anymore
        renderTranscript(chat, frame, state);
        renderInput(root.get(1), frame, state);
        renderCalling(root.get(2), frame, state);
        renderStatus(root.get(3), frame, state);
        renderCompletionPopup(root.get(1), frame, state);
        if (state.picker != null) {
            renderPicker(frame, state);
        }
        if (state.approval != null) {
            renderApproval(frame, state);
        }
        if (state.feedbackPrompt != null) {
            renderFeedbackPrompt(frame, state);
        }
        if (state.editPlan != null) {
            renderEditPlan(frame, state);
        }
        renderToast(frame, state);
        state.selection.highlight(frame.buffer());
        state.screenGrid = SelectionState.snapshot(frame.buffer());
    }

    private Style modelNameStyle() {
        final var style = modelStyleService.resolve(
                chatClient.currentProvider(), chatClient.model(), !"light".equalsIgnoreCase(theme));
        final var base = Style.EMPTY.fg(Color.hex(style.hex()));
        return style.bold() ? base.bold() : base;
    }

    private void renderSidebar(final Rect rect, final dev.tamboui.terminal.Frame frame, final UiState state) {
        final var title =
                state.title == null || state.title.isBlank() ? " yca " : " " + ellipsize(state.title, 24) + " ";
        final var block = block(title);
        frame.renderWidget(block, rect);
        final var inner = block.inner(rect);
        if (inner.isEmpty()) {
            return;
        }
        final var width = inner.width();
        final var label = Style.EMPTY.fg(Color.hex(palette.textMuted()));
        final var value = Style.EMPTY.fg(Color.hex(palette.text()));
        final var branch = gitBranch();
        final var rows = Layout.vertical()
                .constraints(Constraint.fill(), Constraint.length(1))
                .split(inner);
        final var text = new ArrayList<Line>();
        text.add(Line.from(
                Span.styled("yca", Style.EMPTY.fg(Color.hex(palette.accent())).bold()),
                Span.styled(" coding agent", label)));
        text.add(Line.empty());
        text.add(Line.from(
                Span.styled("model ", label), Span.styled(ellipsize(chatClient.model(), width - 6), modelNameStyle())));
        text.add(Line.from(
                Span.styled("provider ", label), Span.styled(ellipsize(chatClient.providerId(), width - 9), value)));
        text.add(Line.from(
                Span.styled("agent ", label),
                Span.styled(ellipsize(state.activeAgent.isBlank() ? "-" : state.activeAgent, width - 7), value)));
        text.add(Line.from(
                Span.styled("flavor ", label),
                Span.styled(ellipsize(chatClient.flavor().name().toLowerCase(Locale.ROOT), width - 7), value)));
        text.add(Line.from(Span.styled("cwd ", label), Span.styled(ellipsize(workingDirectory(), width - 4), value)));
        if (branch != null) {
            text.add(Line.from(Span.styled("branch ", label), Span.styled(ellipsize(branch, width - 7), value)));
        }
        text.add(Line.from(
                Span.styled(permissionService.isPermitAll() ? "permissions all " : "mode ", label),
                Span.styled(
                        permissionService.isPermitAll()
                                ? "⚠"
                                : permissionService.mode().name().toLowerCase(Locale.ROOT),
                        Style.EMPTY.fg(modeColor()))));
        text.add(Line.from(Span.styled("session ", label), Span.styled(ellipsize(state.sessionId, width - 8), value)));
        text.add(Line.from(Span.styled("servers ", label)));
        for (final var status : mcpManager.serverStatuses()) {
            final var dotColor =
                    switch (status.state()) {
                        case ENABLED -> Color.hex(palette.success());
                        case DISABLED -> Color.hex(palette.textMuted());
                        case FAILED -> Color.hex(palette.error());
                    };
            text.add(Line.from(
                    Span.styled("● ", Style.EMPTY.fg(dotColor)),
                    Span.styled(ellipsize(status.name(), width - 2), value)));
        }
        text.add(Line.empty());
        text.add(Line.from(
                Span.styled("tokens in  ", label), Span.styled(formatTokens(state.model.promptTokens()), value)));
        text.add(Line.from(
                Span.styled("tokens out ", label), Span.styled(formatTokens(state.model.completionTokens()), value)));
        text.add(Line.from(
                Span.styled("context ~  ", label),
                Span.styled(
                        formatTokens(state.model.estimatedContext()) + " tok (" + state.model.contextPercent() + "%)",
                        value)));
        if (usageTracker != null && usageTracker.requests() > 0) {
            text.add(Line.from(
                    Span.styled("llm calls  ", label), Span.styled(Long.toString(usageTracker.requests()), value)));
        }
        final var lines = Text.from(text.toArray(Line[]::new));
        final var textRect = rows.getFirst();
        final var overflow = Math.max(0, lines.height() - textRect.height());
        frame.renderWidget(Paragraph.builder().text(lines).scroll(overflow).build(), textRect);
        if (!rows.get(1).isEmpty()) {
            final var percent = Math.clamp(state.model.contextPercent(), 0, 100);
            final var compactionThreshold = compactionThreshold();
            final var pruningThreshold = pruningThreshold();
            final var gaugeColor = Color.hex(
                    percent >= compactionThreshold
                            ? palette.error()
                            : percent >= pruningThreshold ? palette.warning() : palette.info());
            frame.renderWidget(
                    Gauge.builder()
                            .percent(percent)
                            .label(Span.raw(percent + "% context"))
                            .gaugeColor(gaugeColor)
                            .build(),
                    rows.get(1));
        }
    }

    private void renderTranscript(final Rect rect, final dev.tamboui.terminal.Frame frame, final UiState state) {
        final var block = block(" conversation ");
        frame.renderWidget(block, rect);
        final var inner = block.inner(rect);
        if (inner.isEmpty()) {
            return;
        }
        final var chunks = chunks(
                state.model.markdown(spinnerFrame(SpinnerStyle.SQUARE_CORNERS, state.spinnerTick), WELCOME),
                inner.width(),
                state);
        final var total = chunks.stream().mapToInt(RenderedChunk::height).sum();
        final var maxViewOffset = Math.max(0, total - Math.max(1, inner.height()));
        final var viewOffset = Math.clamp(maxViewOffset - state.model.scrollBack(), 0, maxViewOffset);
        var chunkTop = 0;
        for (final var chunk : chunks) {
            final var top = chunkTop - viewOffset;
            chunkTop += chunk.height();
            final var skip = Math.max(0, -top);
            final var visibleTop = Math.max(0, top);
            final var visibleH = Math.min(chunk.height() - skip, Math.max(0, inner.height() - visibleTop));
            if (visibleH <= 0) {
                continue;
            }
            final var sub = new Rect(inner.x(), inner.y() + visibleTop, inner.width(), visibleH);
            frame.renderWidget(chunk.widget(skip), sub);
        }
        int x = inner.x() + inner.width() - 1;
        if (maxViewOffset > 0) {
            final var ratio = Math.clamp(1.0 - (double) viewOffset / maxViewOffset, 0.0, 1.0);
            final var indH = Math.max(1, (int) Math.round(inner.height() * ratio));
            final var indY = inner.y() + inner.height() - indH;
            frame.buffer().setStyle(new Rect(x, indY, 1, indH), Style.EMPTY.reversed());
        }
        if (state.model.busy() && state.spinnerTick % 2 == 0 && inner.height() > 0) {
            final var caretRect = new Rect(x, inner.y() + inner.height() - 1, 1, 1);
            frame.renderWidget(
                    Paragraph.builder()
                            .text(Text.from(Line.from(Span.styled("▌", Style.EMPTY.fg(Color.hex(palette.accent()))))))
                            .build(),
                    caretRect);
        }
    }

    private record RenderedChunk(int height, java.util.function.IntFunction<dev.tamboui.widget.Widget> supplier) {
        dev.tamboui.widget.Widget widget(final int skip) {
            return supplier.apply(skip);
        }
    }

    private List<RenderedChunk> chunks(final String md, final int width, final UiState state) {
        final var out = new ArrayList<RenderedChunk>();
        final var contentWidth = Math.max(1, width - 2); // room for the per-region left bar
        for (final var region : TuiTranscriptModel.regions(md)) {
            if (region.thought()) {
                if (!state.showThinking) {
                    continue;
                }
                addMarkdownChunk(out, thoughtLines(region.text(), contentWidth));
            } else if (region.tool()) {
                addToolCardChunk(out, region.text(), width, state);
            } else if (!region.text().isBlank()) {
                appendChunks(out, region.text(), contentWidth);
            }
        }
        // live (not yet flushed) thinking streams inline, dimmed, at the tail of the conversation
        if (state.showThinking && state.model.thinkingLength() > 0) {
            final var live = state.model.thinkingText().stripLeading();
            if (!live.isEmpty()) {
                addMarkdownChunk(out, thoughtLines(live, contentWidth));
            }
        }
        return out;
    }

    /** Renders a thought region (flushed or live) dimmed with the thin thought bar. */
    private List<Line> thoughtLines(final String text, final int width) {
        return withBar(
                transcriptRenderer.renderRegion(text, width, thoughtStyle, MarkdownStyles.DEFAULTS), thoughtBar());
    }

    private void appendChunks(final List<RenderedChunk> out, final String md, final int width) {
        for (final var seg : syntaxHighlighter.split(md)) {
            if (seg.type() == SyntaxHighlighter.Segment.Type.TEXT) {
                final var lines = withBar(
                        transcriptRenderer.renderRegion(seg.text(), width, Style.EMPTY, MarkdownStyles.DEFAULTS),
                        outputBar());
                addMarkdownChunk(out, lines);
            } else {
                final var lang = seg.language();
                final var text = syntaxHighlighter.highlight(lang, seg.text());
                final var wrapped = MarkdownLines.wrapCode(text.lines(), Math.max(1, width - 2));
                final var wrappedText = Text.from(wrapped);
                final var boxed = codeBlock(lang);
                final var innerH = Math.max(wrappedText.height(), 1);
                out.add(new RenderedChunk(
                        innerH + 2,
                        skip -> Paragraph.builder()
                                .text(wrappedText)
                                .block(boxed)
                                .overflow(Overflow.CLIP)
                                .scroll(Math.clamp(innerH - 1, 0, skip))
                                .build()));
            }
        }
    }

    private void addMarkdownChunk(final List<RenderedChunk> out, final List<Line> lines) {
        final var innerH = Math.max(lines.size(), 1);
        final var text = Text.from(lines);
        out.add(new RenderedChunk(
                innerH,
                skip -> Paragraph.builder()
                        .text(text)
                        .overflow(Overflow.CLIP)
                        .scroll(Math.clamp(innerH - 1, 0, skip))
                        .build()));
    }

    /** Renders a finished tool action as a bordered "card" with a status icon and diff-aware output. */
    private void addToolCardChunk(
            final List<RenderedChunk> out, final String regionText, final int width, final UiState state) {
        final var card = ToolCard.parse(regionText);
        if (!state.showToolActions) {
            // hidden mode: keep a single dim line (name + duration), drop args and output
            out.add(new RenderedChunk(
                    1,
                    skip -> Paragraph.builder()
                            .text(Text.from(hiddenToolLine(card)))
                            .overflow(Overflow.CLIP)
                            .build()));
            return;
        }
        final var statusColor = Color.hex(card.failed() ? palette.error() : palette.success());
        final var block = Block.builder()
                .borders(Borders.ALL)
                .borderType(BorderType.ROUNDED)
                .borderColor(statusColor)
                .title(blockTitle(card))
                .build();
        final var innerW = Math.max(1, width - 2);
        final var muted = Style.EMPTY.fg(Color.hex(palette.textMuted()));
        final var lines = new ArrayList<Line>();
        final var headerSpans = new ArrayList<Span>();
        headerSpans.add(Span.styled(
                card.failed() ? "✖ " : "✓ ", Style.EMPTY.fg(statusColor).bold()));
        headerSpans.add(Span.styled(card.name(), Style.EMPTY.bold().fg(Color.hex(palette.text()))));
        if (!card.args().isBlank()) {
            headerSpans.add(Span.styled((card.failed() ? "  " : " ") + card.args(), muted));
        }
        lines.add(Line.from(headerSpans.toArray(Span[]::new)));
        if (state.showToolActions && !card.output().isBlank()) {
            lines.add(Line.empty());
            lines.addAll(toolOutputLines(card.output(), innerW, card.isDiff()));
        }
        final var text = Text.from(lines.toArray(Line[]::new));
        final var innerH = Math.max(text.height(), 1);
        out.add(new RenderedChunk(
                innerH + 2,
                skip -> Paragraph.builder()
                        .text(text)
                        .block(block)
                        .overflow(Overflow.CLIP)
                        .scroll(Math.clamp(innerH - 1, 0, skip))
                        .build()));
    }

    private static String blockTitle(final ToolCard card) {
        if (card.failed()) {
            return " failed ";
        }
        if (card.running()) {
            return " running ";
        }
        return card.elapsedMs() > 0 ? " " + card.elapsedMs() + " ms " : " tool ";
    }

    /** Single dim summary line shown for a tool action when tool blocks are hidden. */
    private Line hiddenToolLine(final ToolCard card) {
        final var statusColor = Color.hex(card.failed() ? palette.error() : palette.success());
        final var label = new StringBuilder(card.name());
        if (card.running()) {
            label.append(" - running...");
        } else if (card.elapsedMs() > 0) {
            label.append(" - ").append(card.elapsedMs()).append(" ms");
        }
        if (card.failed()) {
            label.append(" - failed");
        }
        return Line.from(
                Span.styled(
                        card.failed() ? "✖ " : card.running() ? "◌ " : "✓ ",
                        Style.EMPTY.fg(statusColor).bold()),
                Span.styled(label.toString(), Style.EMPTY.fg(Color.hex(palette.textMuted()))));
    }

    private List<Line> toolOutputLines(final String output, final int innerW, final boolean diff) {
        final var add = Style.EMPTY.fg(Color.hex(palette.success()));
        final var del = Style.EMPTY.fg(Color.hex(palette.error()));
        final var meta = Style.EMPTY.fg(Color.hex(palette.accent()));
        final var styled = new ArrayList<Line>();
        for (final var raw : output.split("\n")) {
            final var trimmed = raw.stripLeading();
            final Style style;
            if (diff && trimmed.startsWith("+")) {
                style = add;
            } else if (diff && trimmed.startsWith("-")) {
                style = del;
            } else if (diff && trimmed.startsWith("@@")) {
                style = meta;
            } else {
                style = toolStyle;
            }
            styled.add(Line.from(Span.styled(raw, style)));
        }
        return MarkdownLines.wrapCode(styled, innerW);
    }

    private Block codeBlock(final String lang) {
        return Block.builder()
                .title(lang == null || lang.isBlank() ? "" : " " + lang + " ")
                .borders(Borders.ALL)
                .borderType(BorderType.ROUNDED)
                .borderColor(Color.hex(palette.border()))
                .build();
    }

    /**
     * Bold accent bar prefixing regular output lines so assistant/user content
     * reads as the main thread of the conversation.
     */
    private Span outputBar() {
        return Span.styled("┃ ", Style.EMPTY.fg(Color.hex(palette.accent())).bold());
    }

    /** Thin dim bar prefixing reasoning/thought lines, visually secondary to the output bar. */
    private Span thoughtBar() {
        return Span.styled("│ ", thoughtStyle);
    }

    private List<Line> withBar(final List<Line> lines, final Span bar) {
        final var out = new ArrayList<Line>(lines.size());
        for (final var line : lines) {
            out.add(line.isEmpty() ? Line.from(bar) : Line.from(bar).append(line));
        }
        return out;
    }

    private void renderTodos(
            final Rect rect, final dev.tamboui.terminal.Frame frame, final UiState state, final String todosRender) {
        final var block = Block.builder()
                .title(" todos " + (state.showTodosCollapsed ? " (Ctrl+P to expand) " : " (Ctrl+P to collapse) "))
                .borders(Borders.ALL)
                .borderType(BorderType.ROUNDED)
                .borderStyle(Style.EMPTY.fg(Color.hex(palette.accent())))
                .build();
        frame.renderWidget(block, rect);
        final var inner = block.inner(rect);
        if (inner.isEmpty()) {
            return;
        }
        if (state.showTodosCollapsed) {
            return;
        }
        final var render = todosRender;
        final var text = Text.from(render.isBlank() || "(empty)".equals(render) ? "(no todos)" : render);
        final var overflow = Math.max(0, text.height() - inner.height());
        frame.renderWidget(Paragraph.builder().text(text).scroll(overflow).build(), inner);
    }

    private void renderInput(final Rect rect, final dev.tamboui.terminal.Frame frame, final UiState state) {
        final var withGutter = Layout.horizontal()
                .constraints(Constraint.length(2), Constraint.fill())
                .split(rect);
        frame.renderWidget(modeGutter(), withGutter.get(0));
        final var block = activeBlock(" prompt ");
        frame.renderWidget(block, withGutter.get(1));
        final var inner = block.inner(withGutter.get(1));
        if (inner.isEmpty()) {
            return;
        }
        final var area = TextArea.builder()
                .placeholder(state.model.busy() ? "waiting for the agent..." : "type a prompt, Enter to send")
                .build();
        area.renderWithCursor(inner, frame.buffer(), state.input, frame);
    }

    /**
     * Renders the completion candidate popup just above the prompt line when a
     * {@code /command} or parameter completion is active. The currently cycled
     * match is highlighted among {@code items}.
     */
    private void renderCompletionPopup(
            final Rect inputRect, final dev.tamboui.terminal.Frame frame, final UiState state) {
        final var suggestion = state.completion;
        if (suggestion == null || suggestion.empty()) {
            return;
        }
        final var items = suggestion.items();
        final var visible = Math.min(items.size(), 8);
        final var width = Math.clamp(frame.width() - 4, 20, 46);
        final var height = visible + 2; // border + items
        final var x = Math.max(0, Math.min(inputRect.x() + 2, frame.width() - width));
        final var y = Math.max(0, inputRect.y() - height);
        final var rect = new Rect(x, y, width, height);
        frame.renderWidget(dev.tamboui.widgets.Clear.INSTANCE, rect);
        final var accent = Style.EMPTY.fg(Color.hex(palette.accent()));
        final var muted = Style.EMPTY.fg(Color.hex(palette.textMuted()));
        final var textStyle = Style.EMPTY.fg(Color.hex(palette.text()));
        final var block = Block.builder()
                .title(" " + (suggestion.label().isBlank() ? "complete" : suggestion.label()) + " ")
                .borders(Borders.ALL)
                .borderType(BorderType.ROUNDED)
                .borderStyle(accent)
                .build();
        frame.renderWidget(block, rect);
        final var inner = block.inner(rect);
        if (inner.isEmpty()) {
            return;
        }
        final var lines = new ArrayList<Line>();
        for (var i = 0; i < visible; i++) {
            final var selected = i == (state.completionIndex % items.size());
            final var item = items.get(i);
            final var style = selected ? accent.bold() : textStyle;
            final var marker = selected ? "> " : "  ";
            lines.add(Line.from(Span.styled(marker + item, style)));
        }
        frame.renderWidget(
                Paragraph.builder()
                        .text(Text.from(lines.toArray(Line[]::new)))
                        .overflow(dev.tamboui.style.Overflow.CLIP)
                        .build(),
                inner);
    }

    private void renderCalling(final Rect rect, final dev.tamboui.terminal.Frame frame, final UiState state) {
        final var accent = Style.EMPTY.fg(Color.hex(palette.accent()));
        final var muted = Style.EMPTY.fg(Color.hex(palette.textMuted()));
        final var hint = "esc to interrupt";
        final var halves = Layout.horizontal()
                .constraints(Constraint.fill(), Constraint.length(hint.length() + 1))
                .split(rect);
        if (state.model.busy()) {
            if (state.model.runningTool() != null) {
                final var name = state.model.runningToolCommand() == null
                                || state.model.runningToolCommand().isBlank()
                        ? state.model.runningTool()
                        : state.model.runningToolCommand();
                final var line = Line.from(Span.styled(
                        spinnerFrame(SpinnerStyle.SQUARE_CORNERS, state.spinnerTick) + " calling "
                                + ellipsize(name, 60),
                        accent));
                frame.renderWidget(
                        Paragraph.builder()
                                .text(Text.from(line))
                                .overflow(dev.tamboui.style.Overflow.ELLIPSIS)
                                .build(),
                        halves.getFirst());
            } else {
                final var line = Line.from(Span.styled(
                        spinnerFrame(SpinnerStyle.DOTS, state.spinnerTick) + " agent is working...", muted));
                frame.renderWidget(
                        Paragraph.builder()
                                .text(Text.from(line))
                                .overflow(dev.tamboui.style.Overflow.ELLIPSIS)
                                .build(),
                        halves.getFirst());
            }
            final var hintLine = Line.from(Span.styled(hint, muted));
            frame.renderWidget(
                    Paragraph.builder()
                            .text(Text.from(hintLine))
                            .overflow(dev.tamboui.style.Overflow.CLIP)
                            .build(),
                    halves.get(1));
        }
    }

    private void renderStatus(final Rect rect, final dev.tamboui.terminal.Frame frame, final UiState state) {
        final var statusColor = Color.hex(state.model.busy() ? palette.warning() : palette.success());
        final var muted = Style.EMPTY.fg(Color.hex(palette.textMuted()));
        final var parts = new java.util.ArrayList<Span>();
        final var frameStyle = state.model.runningTool() != null ? SpinnerStyle.SQUARE_CORNERS : activeWait(state);
        final var prefix = frameStyle == null ? "" : spinnerFrame(frameStyle, state.spinnerTick) + " ";
        parts.add(Span.styled(" " + prefix + state.model.status() + "  ", Style.EMPTY.fg(statusColor)));
        parts.add(Span.styled("model " + ellipsize(chatClient.model(), 40) + "  ", modelNameStyle()));
        parts.add(Span.styled(state.activeAgent.isBlank() ? "" : "agent " + state.activeAgent + "  ", muted));
        parts.add(Span.styled(modeName(), Style.EMPTY.fg(modeColor())));
        if (conf.bindings() == KeyBindingPreset.VIM) {
            parts.add(Span.styled("  [vim insert]  ", muted));
        }
        // Per-turn metrics (live from the session so /config metrics applies immediately)
        final var metrics = session.metrics();
        if (metrics.showPerTurn() && state.model.turnLlmCalls() > 0) {
            final var mode = metrics.displayMode();
            final var duration = String.format(Locale.ROOT, "%.1fs", state.model.turnDurationMs() / 1000.0);
            if (mode == MetricsDisplayMode.COMPACT) {
                parts.add(Span.styled(
                        " · " + state.model.turnLlmCalls() + " LLM (" + state.model.turnThinkingMs() + "ms) · "
                                + state.model.turnToolsRun() + " tools (" + state.model.turnToolSumMs() + "/"
                                + state.model.turnToolWallMs() + "ms) · " + duration + "  ",
                        muted));
            } else if (mode == MetricsDisplayMode.FULL) {
                parts.add(Span.styled(
                        " · " + state.model.turnLlmCalls() + " LLM (" + state.model.turnThinkingMs() + "ms think) · "
                                + state.model.turnToolsRun() + " tools (" + state.model.turnToolSumMs() + "ms sum/"
                                + state.model.turnToolWallMs() + "ms wall) · " + duration + "  ",
                        muted));
            }
        }
        if (metrics.showSessionTotals() && usageTracker != null && usageTracker.requests() > 0) {
            parts.add(Span.styled(" · session: " + usageTracker.requests() + " LLM calls  ", muted));
        }
        final var line = Line.from(parts.toArray(Span[]::new));
        frame.renderWidget(
                Paragraph.builder()
                        .text(Text.from(line))
                        .overflow(dev.tamboui.style.Overflow.WRAP_WORD)
                        .build(),
                rect);
        final var percent = state.model.contextPercent();
        final var compactionThreshold = compactionThreshold();
        if (percent >= compactionThreshold) {
            final var banner = Line.from(Span.styled(
                    "⚠  context at " + percent + "% (will compact at " + compactionThreshold
                            + "%) - consider /compact  ",
                    Style.EMPTY.fg(Color.hex(palette.warning()))));
            frame.renderWidget(
                    Paragraph.builder()
                            .text(Text.from(banner))
                            .overflow(dev.tamboui.style.Overflow.CLIP)
                            .build(),
                    rect);
        }
        if (state.errorMessage != null) {
            final var err = Line.from(
                    Span.styled("✖  " + state.errorMessage + " ", Style.EMPTY.fg(Color.hex(palette.error()))));
            frame.renderWidget(
                    Paragraph.builder()
                            .text(Text.from(err))
                            .overflow(dev.tamboui.style.Overflow.ELLIPSIS)
                            .build(),
                    rect);
        }
    }

    private void renderHints(
            final Rect rect, final dev.tamboui.terminal.Frame frame, final UiState state, final boolean narrow) {
        final var message = narrow ? HINTS_SHORT : HINTS;
        final var hints = Line.from(Span.styled(message, Style.EMPTY.fg(Color.hex(palette.textMuted()))));
        frame.renderWidget(
                Paragraph.builder()
                        .text(Text.from(hints))
                        .overflow(dev.tamboui.style.Overflow.ELLIPSIS)
                        .build(),
                rect);
    }

    private void renderToast(final dev.tamboui.terminal.Frame frame, final UiState state) {
        final var toast = state.toast;
        if (toast == null || tuiConfiguration.toastDurationMillis() <= 0) {
            return;
        }
        final var color =
                switch (toast.level()) {
                    case SUCCESS -> palette.success();
                    case ERROR -> palette.error();
                    case WARNING -> palette.warning();
                    case INFO -> palette.info();
                };
        final var accent = Style.EMPTY.fg(Color.hex(color));
        final var width = Math.clamp(toast.message().length() + 4, 16, Math.max(16, frame.width()));
        final var rect = new Rect(Math.max(0, (frame.width() - width) / 2), Math.max(0, frame.height() - 6), width, 3);
        final var inner = Modal.box(frame, rect, " notice ", Color.hex(color));
        if (inner == null) {
            return;
        }
        final var line = Line.from(Span.styled(toast.message(), accent));
        frame.renderWidget(
                Paragraph.builder()
                        .text(Text.from(line))
                        .overflow(dev.tamboui.style.Overflow.CLIP)
                        .build(),
                inner);
    }

    private static String spinnerFrame(final SpinnerStyle style, final int tick) {
        final var frames = style.frames();
        return frames[tick % frames.length];
    }

    private static void waiting(final UiState state, final String message, final SpinnerStyle style) {
        state.model.status(message);
        state.wait = new UiState.Wait(message, style);
    }

    private static SpinnerStyle activeWait(final UiState state) {
        final var wait = state.wait;
        return wait != null && wait.message().equals(state.model.status()) ? wait.style() : null;
    }

    private static void modelError(final UiState state, final String message) {
        state.errorMessage = message;
        state.model.status(message);
    }

    private static void clearError(final UiState state) {
        state.errorMessage = null;
    }

    private void renderApproval(final dev.tamboui.terminal.Frame frame, final UiState state) {
        final var approval = state.approval;
        final var width = Math.min(Math.max(60, frame.width() * 4 / 5), frame.width());
        final var height = Math.min(Math.max(12, frame.height() * 2 / 3), frame.height());
        final var rect = Modal.rect(frame, width, height, 0.2);
        final var warning = Color.hex(palette.warning());
        final var inner = Modal.box(frame, rect, " approval required ", warning);
        if (inner == null) {
            return;
        }
        final var titleStyle = Style.EMPTY.fg(warning).bold();
        frame.renderWidget(
                Paragraph.builder()
                        .text(Text.from(Line.from(Span.styled(ellipsize(approval.title(), inner.width()), titleStyle))))
                        .build(),
                new Rect(inner.x(), inner.y(), inner.width(), 1));
        final var actionsY = inner.y() + Math.max(1, inner.height() - 1);
        final var bodyHeight = Math.max(1, actionsY - inner.y() - 1);
        renderApprovalDetail(frame, approval, new Rect(inner.x(), inner.y() + 1, inner.width(), bodyHeight));
        renderApprovalActions(frame, approval, new Rect(inner.x(), actionsY, inner.width(), 1));
    }

    private void renderApprovalDetail(
            final dev.tamboui.terminal.Frame frame, final Approval approval, final Rect rect) {
        final var muted = Style.EMPTY.fg(Color.hex(palette.textMuted()));
        final var lines = detailLines(approval.detail());
        if (lines.isEmpty()) {
            frame.renderWidget(
                    Paragraph.builder()
                            .text(Text.from(Line.from(Span.styled("(no additional detail)", muted))))
                            .build(),
                    rect);
            return;
        }
        final var scroll = approval.detailScroll();
        final var total = lines.size();
        for (int row = 0; row < rect.height(); row++) {
            final var idx = scroll + row;
            if (idx >= total) {
                break;
            }
            final var line = lines.get(idx);
            final var style =
                    idx == 0 ? Style.EMPTY.fg(Color.hex(palette.text())).bold() : muted;
            frame.renderWidget(
                    Paragraph.builder()
                            .text(Text.from(Line.from(Span.styled(ellipsize(line, rect.width()), style))))
                            .build(),
                    new Rect(rect.x(), rect.y() + row, rect.width(), 1));
        }
        final var visible = Math.min(total, rect.height());
        if (scroll + visible < total) {
            frame.renderWidget(
                    Paragraph.builder()
                            .text(Text.from(
                                    Line.from(Span.styled("⤷ " + (total - scroll - visible) + " more - PgDn", muted))))
                            .build(),
                    new Rect(rect.x(), rect.y() + rect.height() - 1, rect.width(), 1));
        }
    }

    private void renderApprovalActions(
            final dev.tamboui.terminal.Frame frame, final Approval approval, final Rect rect) {
        final var actions = Approval.Action.values();
        final var accent = Color.hex(palette.accent());
        final var muted = Color.hex(palette.textMuted());
        final var textColor = Color.hex(palette.text());
        final var spans = new java.util.ArrayList<Span>(actions.length);
        for (int i = 0; i < actions.length; i++) {
            final var label = "[" + (i + 1) + "] " + actions[i].label();
            final var selected = i == approval.action();
            if (selected) {
                spans.add(Span.styled(" " + label + " ", Style.EMPTY.fg(accent).bold()));
            } else {
                spans.add(Span.styled(" " + label + " ", i == 0 ? Style.EMPTY.fg(textColor) : Style.EMPTY.fg(muted)));
            }
        }
        frame.renderWidget(
                Paragraph.builder()
                        .text(Text.from(Line.from(spans.toArray(new Span[0]))))
                        .build(),
                rect);
    }

    private static List<String> detailLines(final String detail) {
        if (detail == null || detail.isBlank()) {
            return List.of();
        }
        return detail.strip()
                .lines()
                .map(String::strip)
                .filter(l -> !l.isEmpty())
                .toList();
    }

    private void openPicker(
            final UiState state,
            final String title,
            final List<String> items,
            final java.util.function.Consumer<String> onSelect,
            final Runnable onCancel) {
        state.picker = new PickerPrompt(
                title,
                items,
                selected -> {
                    state.picker = null;
                    onSelect.accept(selected);
                },
                () -> {
                    state.picker = null;
                    onCancel.run();
                });
    }

    private void renderPicker(final dev.tamboui.terminal.Frame frame, final UiState state) {
        final var picker = state.picker;
        final var accent = Color.hex(palette.accent());
        final var textColor = Color.hex(palette.text());
        final var muted = Color.hex(palette.textMuted());
        final var width = Math.min(Math.max(50, frame.width() * 3 / 5), frame.width());
        final var height = Math.min(Math.max(12, frame.height() * 2 / 3), frame.height());
        final var rect = Modal.rect(frame, width, height, 0.2);
        final var inner = Modal.box(frame, rect, " " + picker.title + " ", accent);
        if (inner == null) {
            return;
        }
        // filter line
        final var filterRect = new Rect(inner.x(), inner.y(), inner.width(), 1);
        final var filterLabel = "filter: ";
        final var filterText = filterLabel + picker.filter + (picker.filter.length() > 0 ? "" : "_");
        frame.renderWidget(
                Paragraph.builder()
                        .text(Text.from(Line.from(
                                Span.styled(filterLabel, Style.EMPTY.fg(muted)),
                                Span.styled(
                                        picker.filter.toString(),
                                        filterRect.width() <= filterLabel.length()
                                                ? Style.EMPTY.fg(textColor)
                                                : Style.EMPTY.fg(accent).bold()))))
                        .overflow(dev.tamboui.style.Overflow.CLIP)
                        .build(),
                filterRect);
        // items
        final var listRect = new Rect(inner.x(), inner.y() + 1, inner.width(), Math.max(1, inner.height() - 2));
        renderPickerList(frame, picker, listRect, textColor, muted, accent);
        // footer hint
        final var footerRect = new Rect(inner.x(), inner.y() + Math.max(1, inner.height() - 1), inner.width(), 1);
        frame.renderWidget(
                Paragraph.builder()
                        .text(Text.from(Line.from(Span.styled(
                                "type to filter | Up/Down move | Enter select | Esc cancel", Style.EMPTY.fg(muted)))))
                        .overflow(dev.tamboui.style.Overflow.CLIP)
                        .build(),
                footerRect);
    }

    private void renderPickerList(
            final dev.tamboui.terminal.Frame frame,
            final PickerPrompt picker,
            final Rect rect,
            final Color textColor,
            final Color muted,
            final Color accent) {
        final var visible = picker.visible();
        picker.prepareList(rect);
        final var rows = Math.max(1, rect.height());
        final var total = visible.size();
        for (int row = 0; row < rows; row++) {
            final var lineRect = new Rect(rect.x(), rect.y() + row, rect.width(), 1);
            if (row >= total) {
                break;
            }
            picker.recordItem(lineRect, visible.get(row));
            final var selected = row == picker.selected();
            final var value = visible.get(row);
            final var marker = selected ? "▸ " : "  ";
            final var style = selected ? Style.EMPTY.fg(accent).bold() : Style.EMPTY.fg(textColor);
            frame.renderWidget(
                    Paragraph.builder()
                            .text(Text.from(Line.from(
                                    Span.styled(marker, selected ? style : Style.EMPTY.fg(muted)),
                                    Span.styled(ellipsize(value, Math.max(1, rect.width() - 2)), style))))
                            .overflow(dev.tamboui.style.Overflow.CLIP)
                            .build(),
                    lineRect);
        }
        if (total == 0) {
            frame.renderWidget(
                    Paragraph.builder()
                            .text(Text.from(Line.from(Span.styled("(no matches)", Style.EMPTY.fg(muted)))))
                            .build(),
                    rect);
        }
    }

    private boolean onPickerMouse(final MouseEvent mouse, final UiState state) {
        final var picker = state.picker;
        if (picker == null) {
            return true;
        }
        if (!(mouse.isPress() || mouse.isClick()) || !mouse.isLeftButton()) {
            return true;
        }
        final var value = picker.itemAt(mouse.x(), mouse.y());
        if (value != null) {
            picker.onSelect().accept(value);
            state.picker = null;
        }
        return true;
    }

    private void renderFeedbackPrompt(final dev.tamboui.terminal.Frame frame, final UiState state) {
        final var prompt = state.feedbackPrompt;
        final var accent = Style.EMPTY.fg(Color.hex(palette.accent()));
        final var muted = Style.EMPTY.fg(Color.hex(palette.textMuted()));
        final var textStyle = Style.EMPTY.fg(Color.hex(palette.text()));
        final var warning = Color.hex(palette.warning());
        final var width = Math.min(Math.max(60, frame.width() * 4 / 5), frame.width());
        final var height =
                Math.min(Math.max(12, 8 + prompt.questions().size() * 4), Math.max(12, frame.height() * 3 / 4));
        final var rect = Modal.rect(frame, width, height, 0.25);
        final var inner = Modal.box(frame, rect, " user request ", Color.hex(palette.accent()));
        if (inner == null || inner.height() < 4) {
            return;
        }
        final var splits = Layout.vertical()
                .constraints(Constraint.length(1), Constraint.fill())
                .split(inner);
        final var tabsRect = splits.get(0);
        prompt.tabsRect(tabsRect);
        final var bodyRect = splits.get(1);
        buildTabs(frame.buffer(), prompt, tabsRect);

        final var isConfirmTab = prompt.tab() >= prompt.questions().size();
        final var qIndex = !isConfirmTab ? prompt.tab() : 0;
        final var fq = prompt.questions().get(qIndex);
        final var content = Layout.vertical()
                .constraints(Constraint.length(2), Constraint.fill(), Constraint.length(3))
                .split(bodyRect);
        final var promptLine = isConfirmTab
                ? "Review your answers below, then press Enter to confirm, Backspace to go back and edit."
                : TuiInputSupport.firstLine(fq.question.prompt());
        frame.renderWidget(
                Paragraph.builder()
                        .text(Text.from(Line.from(Span.styled(
                                promptLine,
                                isConfirmTab
                                        ? textStyle
                                        : Style.EMPTY.fg(warning).bold()))))
                        .build(),
                content.get(0));

        if (isConfirmTab) {
            renderFeedbackSummary(frame, content.get(1), prompt);
        } else {
            renderFeedbackOptions(frame, content.get(1), fq, prompt);
        }
        final var inputRect = content.get(2);
        final var inputBlock = Block.builder()
                .title(" your own answer ")
                .borders(Borders.ALL)
                .borderType(BorderType.ROUNDED)
                .borderColor(Color.hex(prompt.focusInput() ? palette.borderActive() : palette.border()))
                .build();
        frame.renderWidget(inputBlock, inputRect);
        final var inputInner = inputBlock.inner(inputRect);
        if (!inputInner.isEmpty()) {
            final var area = TextArea.builder()
                    .placeholder(
                            ownEntrySelected(fq)
                                    ? "type your answer here" + (prompt.focusInput() ? " (Tab to exit)" : "")
                                    : "or type your own answer"
                                            + (prompt.focusInput() ? " (Tab to exit)" : " (Tab to enter)"))
                    .build();
            area.renderWithCursor(inputInner, frame.buffer(), fq.ownAnswer(), frame);
        }
    }

    private void buildTabs(final dev.tamboui.buffer.Buffer buffer, final FeedbackPrompt prompt, final Rect tabsRect) {
        final var tabs = Tabs.from(buildTitles(prompt));
        tabs.render(tabsRect, buffer, new TabsState(prompt.tab()));
    }

    private String[] buildTitles(final FeedbackPrompt prompt) {
        final var titles = new String[prompt.questions().size() + 1];
        for (int i = 0; i < prompt.questions().size(); i++) {
            titles[i] = (i + 1) + ":" + word(prompt.questions().get(i).question.prompt());
        }
        titles[prompt.questions().size()] = "confirm";
        return titles;
    }

    private static String word(final String prompt) {
        final var w = prompt.strip();
        final var idx = w.indexOf(" ");
        return (idx < 0 ? w : w.substring(0, idx)).trim();
    }

    private void renderFeedbackOptions(
            final dev.tamboui.terminal.Frame frame,
            final Rect rect,
            final FeedbackQuestion fq,
            final FeedbackPrompt prompt) {
        final var accent = Color.hex(palette.accent());
        final var muted = Color.hex(palette.textMuted());
        final var textColor = Color.hex(palette.text());
        final var options = fq.question.options();
        final var opts = options == null ? 0 : options.size();
        fq.optionRects().clear();
        if (opts == 0) {
            frame.renderWidget(
                    Paragraph.builder()
                            .text(Text.from(Line.from(Span.styled(
                                    "(no predefined options - use 'your own answer' below)", Style.EMPTY.fg(muted)))))
                            .build(),
                    rect);
            return;
        }
        if (!fq.question.multiple()) {
            fq.selectedIndex(Math.clamp(fq.selectedIndex(), 0, opts));
        } else {
            prompt.optionsFocus(Math.clamp(prompt.optionsFocus(), 0, opts));
        }
        final var shown = Math.min(opts + 1, rect.height());
        for (int row = 0; row < shown; row++) {
            final var lineRect = new Rect(rect.x(), rect.y() + row, rect.width(), 1);
            fq.optionRects().add(lineRect);
            final var line = Line.from(buildOptionLine(fq, row, accent, muted, textColor));
            frame.renderWidget(Paragraph.builder().text(Text.from(line)).build(), lineRect);
        }
    }

    private Span[] buildOptionLine(
            final FeedbackQuestion fq, final int idx, final Color accent, final Color muted, final Color textColor) {
        final var opts = optionCount(fq);
        if (idx >= opts) {
            if (fq.question.multiple()) {
                final var checked = fq.ownBox().isChecked();
                return new Span[] {
                    Span.raw(checked ? "▣ " : "▢ ").style(checked ? Style.EMPTY.fg(accent) : Style.EMPTY.fg(muted)),
                    Span.raw("Type your own answer...").style(Style.EMPTY.fg(checked ? accent : textColor))
                };
            }
            final var selected = fq.selectedIndex() == idx;
            return new Span[] {
                Span.raw("✎ ").style(Style.EMPTY.fg(selected ? accent : muted)),
                Span.raw("Type your own answer...")
                        .style(selected ? Style.EMPTY.fg(accent).bold() : Style.EMPTY.fg(textColor))
            };
        }
        final var option = fq.question.options().get(idx);
        if (fq.question.multiple()) {
            final var checked = fq.boxes()[idx].isChecked();
            return new Span[] {
                Span.raw(checked ? "▣ " : "▢ ").style(checked ? Style.EMPTY.fg(accent) : Style.EMPTY.fg(muted)),
                Span.raw(option).style(Style.EMPTY.fg(textColor))
            };
        }
        final var selected = fq.selectedIndex() == idx;
        return new Span[] {
            Span.raw(selected ? "◉ " : "○ ").style(selected ? Style.EMPTY.fg(accent) : Style.EMPTY.fg(muted)),
            Span.raw(option).style(selected ? Style.EMPTY.fg(accent).bold() : Style.EMPTY.fg(textColor))
        };
    }

    private void renderFeedbackSummary(
            final dev.tamboui.terminal.Frame frame, final Rect rect, final FeedbackPrompt prompt) {
        final var lines = new ArrayList<Line>();
        for (final var fq : prompt.questions()) {
            lines.add(Line.from(Span.styled(
                    TuiInputSupport.firstLine(fq.question.prompt()),
                    Style.EMPTY.fg(Color.hex(palette.accent())).bold())));
            final var own = fq.ownAnswer().text().strip();
            final var opts = optionCount(fq);
            var any = false;
            if (fq.question.multiple()) {
                for (int i = 0; i < fq.boxes().length && i < opts; i++) {
                    if (fq.boxes()[i].isChecked()) {
                        lines.add(Line.from(Span.styled(
                                "  - " + fq.question.options().get(i), Style.EMPTY.fg(Color.hex(palette.text())))));
                        any = true;
                    }
                }
                if (fq.ownBox().isChecked() && !own.isEmpty()) {
                    lines.add(Line.from(Span.styled("  - " + own, Style.EMPTY.fg(Color.hex(palette.text())))));
                    any = true;
                }
            } else {
                final var selected = fq.selectedIndex() >= 0 && fq.selectedIndex() < opts
                        ? fq.question.options().get(fq.selectedIndex())
                        : null;
                final var value = fq.selectedIndex() == opts && opts > 0 && !own.isEmpty() ? own : selected;
                if (value != null) {
                    lines.add(Line.from(Span.styled("  - " + value, Style.EMPTY.fg(Color.hex(palette.text())))));
                    any = true;
                }
            }
            if (!any) {
                lines.add(Line.from(Span.styled("  - (no selection)", Style.EMPTY.fg(Color.hex(palette.textMuted())))));
            }
            lines.add(Line.empty());
        }
        frame.renderWidget(
                Paragraph.builder().text(Text.from(lines.toArray(Line[]::new))).build(), rect);
    }

    /**
     * Left gutter of the agent mode: heavy bold accent bar in {@code build},
     * thin warning bar in {@code plan}, red when permissions are {@code all}.
     * The bar symbol itself switches (thin vs heavy) so the mode change is
     * unmistakable on every terminal.
     */
    private Block modeGutter() {
        final var build = permissionService.mode() == AgentMode.BUILD;
        return Block.builder()
                .borders(Borders.LEFT_ONLY)
                .borderStyle(build ? Style.EMPTY.fg(modeColor()).bold() : Style.EMPTY.fg(modeColor()))
                .customBorderSet(
                        BorderSet.builder().leftVertical(build ? "┃" : "│").build())
                .build();
    }

    private Rect accentGutter(final Rect rect, final dev.tamboui.terminal.Frame frame) {
        final var parts = Layout.horizontal()
                .constraints(Constraint.length(1), Constraint.fill())
                .split(rect);
        frame.renderWidget(modeGutter(), parts.get(0));
        return parts.get(1);
    }

    private Block block(final String title) {
        return Block.builder()
                .title(title)
                .borders(Borders.ALL)
                .borderType(BorderType.ROUNDED)
                .borderColor(Color.hex(palette.border()))
                .build();
    }

    private Block activeBlock(final String title) {
        return Block.builder()
                .title(title)
                .borders(Borders.ALL)
                .borderType(BorderType.ROUNDED)
                .borderColor(Color.hex(palette.borderActive()))
                .build();
    }

    private boolean planMode() {
        return permissionService.mode() == AgentMode.PLAN;
    }

    private String modeName() {
        if (permissionService.isPermitAll()) {
            return "all";
        }
        return permissionService.mode().name().toLowerCase(Locale.ROOT);
    }

    private Color modeColor() {
        if (permissionService.isPermitAll()) {
            return Color.hex(palette.permitAll());
        }
        return Color.hex(planMode() ? palette.warning() : palette.accent());
    }

    private static String ellipsize(final String text, final int max) {
        if (text == null || text.length() <= max) {
            return text == null ? "" : text;
        }
        return max <= 3 ? text.substring(0, Math.max(0, max)) : text.substring(0, max - 3) + "...";
    }

    private static String formatTokens(final long value) {
        if (value < 1_000) {
            return String.valueOf(value);
        }
        if (value < 1_000_000) {
            final var k = value / 1000.0;
            return (k > 9 ? String.format("%.0fk", k) : String.format("%.1fk", k)).replace(",", ".");
        }
        final var m = value / 1_000_000.0;
        return (m > 9 ? String.format("%.0fM", m) : String.format("%.1fM", m)).replace(",", ".");
    }

    /** The compaction threshold in %, falling back to a safe default when configuration is absent (rendering tests). */
    private int compactionThreshold() {
        return configuration == null || configuration.compaction() == null
                ? 80
                : configuration.compaction().thresholdPercent();
    }

    /** The pruning threshold in %, falling back to a safe default when configuration is absent (rendering tests). */
    private int pruningThreshold() {
        return configuration == null || configuration.pruning() == null
                ? 65
                : configuration.pruning().thresholdPercent();
    }

    private static String cap(final String text, final int max) {
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }

    private String workingDirectory() {
        final var dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        final var home = Path.of(System.getProperty("user.home")).toAbsolutePath();
        return dir.startsWith(home) ? "~/" + home.relativize(dir) : dir.toString();
    }

    private String gitBranch() {
        var dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null) {
            final var head = dir.resolve(".git").resolve("HEAD");
            if (Files.isRegularFile(head)) {
                try {
                    final var content = Files.readString(head).strip();
                    if (content.startsWith("ref: refs/heads/")) {
                        return content.substring("ref: refs/heads/".length());
                    }
                    return content.length() > 7 ? content.substring(0, 7) : content;
                } catch (final IOException e) {
                    return null;
                }
            }
            dir = dir.getParent();
        }
        return null;
    }

    private static final class Approval {
        enum Action {
            DENY("Deny  [Esc]"),
            ALLOW("Allow  [Enter]"),
            ALWAYS("Always (session)");

            private final String label;

            Action(final String label) {
                this.label = label;
            }

            private String label() {
                return label;
            }
        }

        private final String title;
        private final String detail;
        private final CountDownLatch latch;
        private final AtomicReference<ApprovalGate.Result> outcome;
        private int action;
        private int detailScroll;

        private Approval(
                final String title,
                final String detail,
                final CountDownLatch latch,
                final AtomicReference<ApprovalGate.Result> outcome) {
            this.title = title;
            this.detail = detail;
            this.latch = latch;
            this.outcome = outcome;
            this.action = 0; // default-deny: focus Deny first
        }

        private String title() {
            return title;
        }

        private String detail() {
            return detail;
        }

        private CountDownLatch latch() {
            return latch;
        }

        private int action() {
            return action;
        }

        private void action(final int action) {
            this.action = Math.floorMod(action, Action.values().length);
        }

        private int detailScroll() {
            return detailScroll;
        }

        private void detailScroll(final int detailScroll) {
            this.detailScroll = Math.max(0, detailScroll);
        }

        /** Resolves and performs the currently focused action, releasing the gate latch. Returns true when the call is allowed. */
        private boolean perform() {
            final var result =
                    switch (Action.values()[action]) {
                        case ALLOW -> ApprovalGate.Result.ALLOW_ONCE;
                        case ALWAYS -> ApprovalGate.Result.ALLOW_SESSION;
                        default -> ApprovalGate.Result.DENY;
                    };
            outcome.set(result);
            latch.countDown();
            return result != ApprovalGate.Result.DENY;
        }
    }

    private static final class PickerPrompt {
        private final String title;
        private final List<String> items;
        private final StringBuilder filter = new StringBuilder();
        private final java.util.function.Consumer<String> onSelect;
        private final Runnable onCancel;
        private final List<Rect> itemRects = new ArrayList<>();
        private final List<String> renderedValues = new ArrayList<>();
        private int selected;
        private int scroll = -1;

        private PickerPrompt(
                final String title,
                final List<String> items,
                final java.util.function.Consumer<String> onSelect,
                final Runnable onCancel) {
            this.title = title;
            this.items = new ArrayList<>(items == null ? List.of() : items);
            this.onSelect = onSelect;
            this.onCancel = onCancel;
        }

        private java.util.function.Consumer<String> onSelect() {
            return onSelect;
        }

        private int selected() {
            return selected;
        }

        private void prepareList(final Rect rect) {
            itemRects.clear();
            renderedValues.clear();
        }

        private void recordItem(final Rect rect, final String value) {
            itemRects.add(rect);
            renderedValues.add(value);
        }

        /** @return the rendered value at the mouse position, or null when no item was hit. */
        private String itemAt(final int x, final int y) {
            for (int i = 0; i < itemRects.size(); i++) {
                if (itemRects.get(i).contains(x, y)) {
                    return renderedValues.get(i);
                }
            }
            return null;
        }

        private List<String> visible() {
            final var f = filter.toString().toLowerCase(java.util.Locale.ROOT);
            if (f.isEmpty()) {
                return items;
            }
            final var out = new ArrayList<String>();
            for (final var it : items) {
                if (it != null && it.toLowerCase(java.util.Locale.ROOT).contains(f)) {
                    out.add(it);
                }
            }
            return out;
        }

        private void filterAppend(final char c) {
            filter.append(c);
            selected = 0;
            scroll = -1;
        }

        private void filterBackspace() {
            if (filter.length() > 0) {
                filter.deleteCharAt(filter.length() - 1);
                selected = 0;
                scroll = -1;
            }
        }

        private void filterClear() {
            filter.setLength(0);
            selected = 0;
            scroll = -1;
        }

        private void move(final int delta) {
            final var v = visible();
            if (v.isEmpty()) {
                return;
            }
            selected = Math.floorMod(selected + delta, v.size());
            scroll = -1;
        }

        private void stroke(final KeyEvent key) {
            if (key.isKey(KeyCode.UP)) {
                move(-1);
            } else if (key.isKey(KeyCode.DOWN)) {
                move(1);
            } else if (key.isKey(KeyCode.HOME)) {
                selected = 0;
                scroll = -1;
            } else if (key.isKey(KeyCode.END)) {
                final var v = visible();
                selected = v.isEmpty() ? 0 : v.size() - 1;
                scroll = -1;
            } else if (key.isKey(KeyCode.PAGE_UP)) {
                move(-8);
            } else if (key.isKey(KeyCode.PAGE_DOWN)) {
                move(8);
            } else if (key.hasCtrl() && key.isCharIgnoreCase('u')) {
                filterClear();
            } else if (key.isKey(KeyCode.BACKSPACE)) {
                filterBackspace();
            } else {
                final var cp = key.codePoint();
                if (cp >= 32 && cp != 127) {
                    filterAppend((char) cp);
                }
            }
        }

        private boolean confirm(final KeyEvent key) {
            if (key.isConfirm()) {
                final var v = visible();
                if (!v.isEmpty() && selected < v.size() && onSelect != null) {
                    onSelect.accept(v.get(selected));
                }
                return true;
            }
            if (key.isCancel() || key.isKey(KeyCode.ESCAPE)) {
                if (onCancel != null) {
                    onCancel.run();
                }
                return true;
            }
            return false;
        }
    }

    private static final class PendingEditPlan {
        private final Path path;
        private final String original;
        private volatile Rect confirmRect;
        private volatile Rect cancelRect;

        private PendingEditPlan(final Path path, final String original, final Rect confirmRect, final Rect cancelRect) {
            this.path = path;
            this.original = original;
            this.confirmRect = confirmRect;
            this.cancelRect = cancelRect;
        }

        private Path path() {
            return path;
        }

        private String original() {
            return original;
        }

        private Rect confirmRect() {
            return confirmRect;
        }

        private void confirmRect(final Rect confirmRect) {
            this.confirmRect = confirmRect;
        }

        private Rect cancelRect() {
            return cancelRect;
        }

        private void cancelRect(final Rect cancelRect) {
            this.cancelRect = cancelRect;
        }
    }

    private static final class FeedbackPrompt {
        private final List<FeedbackQuestion> questions;
        private final CountDownLatch latch;
        private final Map<String, Object> answers;
        private final AtomicBoolean answered;
        private int tab;
        private int optionsFocus;
        private boolean focusInput;
        private Rect tabsRect;

        private FeedbackPrompt(
                final List<Question> raw,
                final CountDownLatch latch,
                final Map<String, Object> answers,
                final AtomicBoolean answered) {
            this.questions = new ArrayList<>(raw.size());
            for (final var q : raw) {
                this.questions.add(new FeedbackQuestion(q));
            }
            this.latch = latch;
            this.answers = answers;
            this.answered = answered;
        }

        private List<FeedbackQuestion> questions() {
            return questions;
        }

        private CountDownLatch latch() {
            return latch;
        }

        private Map<String, Object> answers() {
            return answers;
        }

        private AtomicBoolean answered() {
            return answered;
        }

        private int tab() {
            return tab;
        }

        private void tab(final int tab) {
            this.tab = Math.max(0, tab);
        }

        private int optionsFocus() {
            return optionsFocus;
        }

        private void optionsFocus(final int optionsFocus) {
            this.optionsFocus = Math.max(0, optionsFocus);
        }

        private boolean focusInput() {
            return focusInput;
        }

        private void focusInput(final boolean focusInput) {
            this.focusInput = focusInput;
        }

        private Rect tabsRect() {
            return tabsRect;
        }

        private void tabsRect(final Rect tabsRect) {
            this.tabsRect = tabsRect;
        }
    }

    private static final class FeedbackQuestion {
        private final Question question;
        private final int[] selectedIndex = {0};
        private final CheckboxState[] boxes;
        private final CheckboxState ownBox = new CheckboxState();
        private final TextAreaState ownAnswer = new TextAreaState();
        private final List<Rect> optionRects = new ArrayList<>();

        private FeedbackQuestion(final Question question) {
            this.question = question;
            if (question.multiple() && question.options() != null) {
                this.boxes = new CheckboxState[question.options().size()];
                for (int i = 0; i < boxes.length; i++) {
                    boxes[i] = new CheckboxState();
                }
            } else {
                this.boxes = new CheckboxState[0];
            }
        }

        private Question question() {
            return question;
        }

        private CheckboxState[] boxes() {
            return boxes;
        }

        private CheckboxState ownBox() {
            return ownBox;
        }

        private TextAreaState ownAnswer() {
            return ownAnswer;
        }

        private List<Rect> optionRects() {
            return optionRects;
        }

        private int selectedIndex() {
            return selectedIndex[0];
        }

        private void selectedIndex(final int index) {
            selectedIndex[0] = Math.max(0, index);
        }
    }

    private static TextAreaState feedbackInput(final FeedbackPrompt prompt) {
        final var idx = prompt.tab() >= prompt.questions().size() ? 0 : prompt.tab();
        return prompt.questions().get(idx).ownAnswer();
    }

    private static final class UiState {
        private record Wait(String message, SpinnerStyle style) {}

        private final TuiTranscriptModel model;
        private final Clock clock;
        private final TextAreaState input = new TextAreaState();
        private volatile String sessionId = "";
        private volatile String title;
        private volatile Wait wait;
        private volatile String activeAgent = "";
        private volatile boolean showThinking;
        private volatile boolean showToolActions = true;
        private volatile boolean showTodos;
        private volatile boolean showTodosCollapsed;
        private volatile boolean interrupted;
        private volatile boolean quitArmed;
        private volatile CompletableFuture<Boolean> inFlight;
        private volatile boolean historyActive;
        private volatile String historyDraft = "";
        private volatile Approval approval;
        private volatile FeedbackPrompt feedbackPrompt;
        private volatile PickerPrompt picker;
        private volatile PendingEditPlan editPlan;
        private volatile CompletionEngine.Suggestion completion;
        private volatile int completionIndex;
        private volatile int completionCursor = -1;
        private final SelectionState selection = new SelectionState();
        private volatile String[][] screenGrid = new String[0][0];
        private volatile int spinnerTick;
        private volatile String lastTodosRender = "";
        private volatile Toast toast;
        private volatile long toastShownAt;
        private volatile TuiRunner runner;
        private final java.util.Deque<String> pendingPrompts = new java.util.ArrayDeque<>();
        private volatile String errorMessage;

        private void pushToast(final String message, final ToastLevel level) {
            toast = new Toast(message, level);
            toastShownAt = clock.millis();
        }

        private void clearToast() {
            toast = null;
        }

        private UiState(final TuiTranscriptModel model, final Clock clock) {
            this.model = model;
            this.clock = clock;
        }
    }

    private record Toast(String message, ToastLevel level) {}

    private enum ToastLevel {
        SUCCESS,
        ERROR,
        INFO,
        WARNING
    }
}
