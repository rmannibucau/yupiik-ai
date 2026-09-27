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
import dev.tamboui.layout.Padding;
import dev.tamboui.layout.Rect;
import dev.tamboui.markdown.MarkdownStyles;
import dev.tamboui.style.Color;
import dev.tamboui.style.Overflow;
import dev.tamboui.style.Style;
import dev.tamboui.text.CharWidth;
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
import io.yupiik.ia.agent.core.agent.AgentProvider;
import io.yupiik.ia.agent.core.agent.SubagentRegistry;
import io.yupiik.ia.agent.core.configuration.AgentConfiguration;
import io.yupiik.ia.agent.core.configuration.AgentMode;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.MetricsDisplayMode;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.maven.MavenRepositoryKnowledgeService;
import io.yupiik.ia.agent.core.mcp.McpManager;
import io.yupiik.ia.agent.core.permissions.ApprovalGate;
import io.yupiik.ia.agent.core.question.FeedbackGate;
import io.yupiik.ia.agent.core.question.Question;
import io.yupiik.ia.agent.core.runtime.VirtualThreadExecutor;
import io.yupiik.ia.agent.core.session.AgentSessionService;
import io.yupiik.ia.agent.core.session.SessionStore;
import io.yupiik.ia.agent.core.shell.InterruptCoordinator;
import io.yupiik.ia.agent.core.shell.SessionLifecycle;
import io.yupiik.ia.agent.core.shell.SessionRuntime;
import io.yupiik.ia.agent.core.shell.SessionRuntimeFactory;
import io.yupiik.ia.agent.core.shell.ShellEnv;
import io.yupiik.ia.agent.core.shell.ShellFrontend;
import io.yupiik.ia.agent.core.shell.ShellIO;
import io.yupiik.ia.agent.core.shell.ShellOutcome;
import io.yupiik.ia.agent.core.shell.ShellRegistry;
import io.yupiik.ia.agent.core.shell.ShellSession;
import io.yupiik.ia.agent.core.shell.ThinkTagSplitter;
import io.yupiik.ia.agent.core.shell.ToolOutputVisibility;
import io.yupiik.ia.agent.core.shell.TurnRunner;
import io.yupiik.ia.agent.core.shell.btw.BtwAnswerer;
import io.yupiik.ia.agent.core.shell.slash.SlashRegistry;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Pattern;

@DefaultScoped
@Command(name = "tui", description = "Interactive terminal UI session with the yca coding agent.")
public class TuiCommand implements Runnable {
    private static final long DEFAULT_MAX_HISTORY_CHARACTERS = 10_000_000L;
    private static final String WELCOME = "# yca\n\nType a prompt and press Enter. `/help` lists the slash commands.";
    private static final Logger LOG = Logger.getLogger(TuiCommand.class.getName());

    private String help() {
        return "\n\n" + slashRegistry.helpMarkdown();
    }

    private final TuiConf conf;
    private final TuiConfiguration tuiConfiguration;
    private final CodingAgentConfiguration configuration;
    private final SessionRuntimeFactory sessionRuntimeFactory;
    private final ShellRegistry shellRegistry;
    private final SessionStore sessionStore;
    private final SessionLifecycle sessionLifecycle;
    private final ChatClient chatClient;
    private final io.yupiik.ia.agent.core.style.ModelStyleService modelStyleService;
    private final McpManager mcpManager;
    private final VirtualThreadExecutor executor;
    private final ProviderManager providerManager;
    private final AgentProvider agentProvider;
    private final AgentSessionService agentSessionService;
    private final SubagentRegistry subagentRegistry;
    private final MavenRepositoryKnowledgeService mavenKnowledgeService;
    private final TurnRunner turnRunner;
    private final ThinkTagSplitter thinkTags;
    private final TuiRunnerFactory tuiRunnerFactory;
    /** Safety repaint: max idle time (ms) without a full redraw when nothing changed (0 disables it). */
    private final long heartbeatMillis;
    /** Open tabs, one per concurrent session; index 0 is the original tab. */
    private final TabManager tabManager;
    /** Header hit-areas of the rendered tab bar, one per tab (empty while the bar is hidden). */
    private final List<Rect> tabRects = new CopyOnWriteArrayList<>();

    private final List<Rect> tabCloseRects = new CopyOnWriteArrayList<>();
    private volatile Rect tabAddRect;

    private volatile Style thoughtStyle;
    private volatile Style thoughtCodeStyle;
    private volatile Style thoughtBarStyle;
    private volatile Color thoughtBg;
    private volatile Color toolBg;
    private volatile Style toolStyle;
    private final TranscriptRenderer transcriptRenderer = new TranscriptRenderer();
    private volatile Palette palette;
    private volatile String theme = "dark";
    private final CompletionEngine completionEngine;
    private final PromptHistory promptHistory;
    private volatile long completionGeneration;
    private final CompletionCache completionCache = new CompletionCache();
    private volatile String cachedGitBranch;
    private volatile long cachedGitBranchAt;
    private final TuiSlashCommands slashCommands;
    private final SlashRegistry slashRegistry;
    private final Clock clock;
    /** Polls the git branch off the render thread; {@code null} in render-only tests (on-demand fallback). */
    private final GitBranchPoller gitBranchPoller;

    private final SyntaxHighlighter syntaxHighlighter;
    private final Glyphs glyphs;
    // single-entry render memo: when neither the transcript markdown nor the
    // mutable render inputs changed, the previous frame's chunks are reused
    // verbatim (only the spinner/animation parts are re-rendered each frame)
    private final Object contentLock = new Object();
    private volatile ContentKey lastContentKey;
    private volatile ContentResult lastContent;
    // incremental live-thinking memo: the flushed part of the growing stream is
    // rendered once per stable prefix and the last (still open) line re-rendered
    private String lastLiveThoughtPrefix = "";
    private int lastLiveThoughtWidth = -1;
    private Style lastLiveThoughtStyle;
    private List<Line> lastLiveThoughtLines;
    private final CostEstimator costEstimator;
    private final boolean persistHistory;
    private final AskUserCardRenderer askUserCardRenderer;
    private final DiffRenderer diffRenderer = new DiffRenderer();
    private final ToolArgsPreview toolArgsPreview;
    private final ToolOutputFormat toolOutputFormat;
    private final BtwAnswerer btwAnswerer;
    private final boolean formatToolOutput;
    private volatile CodeTheme codeTheme = CodeTheme.ONE_DARK;

    private static final int MAX_RESUME_LIST = 25;
    private static final int RESUME_SUMMARY_WIDTH = 80;
    /** Inline preview lines kept in a tool card; the rest lives behind the expand link / Ctrl+O. */
    private static final int MAX_INLINE_TOOL_LINES = 3;
    /** TTL for the git branch filesystem lookup so the render thread does not stat/log each frame. */
    private static final long GIT_BRANCH_CACHE_TTL_MILLIS = 3_000;
    /** If the render thread has not ticked for this long while an approval/feedback gate waits, fail closed. */
    private static final long RENDER_STALL_TIMEOUT_MILLIS = 5_000;

    /**
     * Fallback safety-repaint interval when no TUI configuration resolves a value (rendering-only tests).
     * Mirrors {@code yca.tui.heartbeatMillis}; see {@link TuiConfiguration#heartbeatMillis()}.
     */
    private static final long DEFAULT_HEARTBEAT_RENDER_INTERVAL_MILLIS = 3_500;

    private static final Pattern SPLIT_COMMAND = Pattern.compile("\\s+");

    public TuiCommand(
            final TuiConf conf,
            final TuiConfiguration tuiConfiguration,
            final CodingAgentConfiguration configuration,
            final ShellRegistry shellRegistry,
            final SessionRuntimeFactory sessionRuntimeFactory,
            final SessionStore sessionStore,
            final SessionLifecycle sessionLifecycle,
            final ChatClient chatClient,
            final io.yupiik.ia.agent.core.style.ModelStyleService modelStyleService,
            final McpManager mcpManager,
            final VirtualThreadExecutor executor,
            final ProviderManager providerManager,
            final AgentProvider agentProvider,
            final AgentSessionService agentSessionService,
            final MavenRepositoryKnowledgeService mavenKnowledgeService,
            final TurnRunner turnRunner,
            final ThinkTagSplitter thinkTags,
            final TuiRunnerFactory tuiRunnerFactory,
            final TuiSlashCommands slashCommands,
            final SlashRegistry slashRegistry,
            final Clock clock,
            final AskUserCardRenderer askUserCardRenderer,
            final ToolOutputFormat toolOutputFormat,
            final BtwAnswerer btwAnswerer,
            final TabManager tabManager,
            final SubagentRegistry subagentRegistry,
            final ScheduledExecutorService scheduler) {
        this.conf = conf;
        this.tuiConfiguration = tuiConfiguration;
        this.configuration = configuration;
        this.shellRegistry = shellRegistry;
        this.sessionRuntimeFactory = sessionRuntimeFactory;
        this.sessionStore = sessionStore;
        this.sessionLifecycle = sessionLifecycle;
        this.chatClient = chatClient;
        this.modelStyleService = modelStyleService;
        this.mcpManager = mcpManager;
        this.executor = executor;
        this.providerManager = providerManager;
        this.agentProvider = agentProvider;
        this.agentSessionService = agentSessionService;
        this.mavenKnowledgeService = mavenKnowledgeService;
        this.turnRunner = turnRunner;
        this.thinkTags = thinkTags;
        this.tuiRunnerFactory = tuiRunnerFactory;
        this.heartbeatMillis = tuiConfiguration == null
                ? DEFAULT_HEARTBEAT_RENDER_INTERVAL_MILLIS
                : tuiConfiguration.heartbeatMillis();
        this.slashCommands = slashCommands;
        this.slashRegistry = slashRegistry;
        this.gitBranchPoller = scheduler == null ? null : new GitBranchPoller(scheduler, this::onBranchChanged);
        this.tabManager = tabManager;
        this.clock = clock;
        this.askUserCardRenderer = askUserCardRenderer;
        this.toolOutputFormat = toolOutputFormat;
        this.btwAnswerer = btwAnswerer;
        this.subagentRegistry = subagentRegistry;
        this.formatToolOutput = !"off".equalsIgnoreCase(tuiConfiguration.toolOutputFormat());
        this.syntaxHighlighter = new SyntaxHighlighter().maxLineLength(configuration.maxLineLength());
        this.toolArgsPreview = new ToolArgsPreview(toolOutputFormat, syntaxHighlighter, diffRenderer);
        this.palette = Palette.from(conf.theme(), tuiConfiguration);
        this.glyphs = Glyphs.of(tuiConfiguration.glyphs());
        this.costEstimator = CostEstimator.parse(tuiConfiguration.pricing());
        this.persistHistory = tuiConfiguration.history();
        this.completionEngine = new CompletionEngine(
                slashRegistry,
                completionCache::models,
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
                completionCache::sessions,
                completionCache::tools,
                () -> agentProvider == null || agentProvider.agents() == null
                        ? List.of()
                        : agentProvider.agents().stream()
                                .map(AgentConfiguration::name)
                                .toList(),
                completionCache::variants);
        this.promptHistory = new PromptHistory();
        applyTheme(persistedTheme() == null ? conf.theme() : persistedTheme());
        // warm the completion cache off the render thread so TAB never blocks on the network
        refreshCompletionCache();
    }

    @RootConfiguration("tui")
    public record TuiConf(
            @Property(
                    defaultValue = "\"\"",
                    documentation = "Resume an existing session by id; a new session is created when empty.")
            String session,

            @Property(
                    defaultValue = "\"\"",
                    documentation =
                            "Session context file (a yca session JSONL). Its file name provides the session id to resume or create.")
            String context,

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

    private Path themeStoragePath() {
        final var root = Path.of(configuration.resolveStorageHome()).toAbsolutePath();
        return root.resolve("theme");
    }

    private Path historyStoragePath() {
        final var root = Path.of(configuration.resolveStorageHome()).toAbsolutePath();
        return root.resolve("history");
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
        this.codeTheme = CodeTheme.parse(tuiConfiguration.codeTheme(), "light".equals(normalized));
        this.syntaxHighlighter.theme(codeTheme);
        // opacity is the transparency level toward the panel background (0 opaque,
        // 1 invisible). A negative configured value means "auto": use a theme-aware
        // default so thoughts stay clearly readable (dark prose slightly dimmed toward
        // the panel, light prose almost untouched) while still reading as secondary.
        final var configuredOpacity = tuiConfiguration.thinkingOpacity();
        final var lightTheme = "light".equals(normalized);
        final var defaultOpacity =
                lightTheme ? tuiConfiguration.thinkingOpacityLight() : tuiConfiguration.thinkingOpacityDark();
        final var opacity = Math.clamp(configuredOpacity >= 0 ? configuredOpacity : defaultOpacity, 0.0, 0.95);
        this.thoughtStyle = palette.blended(palette.text(), opacity).italic().dim();
        // code tokens keep their palette color inside thoughts: only the italic modifier is
        // layered on (no fg, no dim) so highlighted code stays colourful and readable
        this.thoughtCodeStyle = Style.EMPTY.italic();
        // categorical thought/output separation: reasoning sits on a subtle full-width
        // info-tinted panel (terminals have no alpha, a pre-blended bg IS the tint)
        this.thoughtBg = palette.mono() ? null : TuiColors.blend(palette.info(), palette.backgroundPanel(), 0.9);
        this.thoughtBarStyle = palette.blended(palette.info(), 0.15);
        // tool cards get a neutral background tint derived from the default background
        // so streamed command output reads as a distinct block (mono terminals skip it)
        this.toolBg = palette.mono() ? null : TuiColors.blend(palette.text(), palette.backgroundPanel(), 0.95);
        final var toolOpacity = Math.clamp(tuiConfiguration.toolOutputOpacity(), 0.05, 0.95);
        this.toolStyle = palette.blended(palette.text(), toolOpacity);
    }

    @Override
    public void run() {
        slashCommands.bind(arg -> {
            final var current = active();
            return current == null ? ShellOutcome.none() : themeOutcome(arg, current);
        });
        slashCommands.bindSubagents(this::openSubagents);
        shellRegistry.bindSubagents(subagentRegistry);
        if (persistHistory) {
            this.promptHistory.replaceWith(PromptHistory.load(historyStoragePath(), promptHistory.capacity()));
        }
        try (final var tui = tuiRunnerFactory.create(conf, tuiConfiguration.hideCursor())) {
            // the manager is application-scoped: clear any state left by a previous run
            tabManager.reset();
            final var initialSessionId = sessionLifecycle.resolveSessionId(conf.session(), conf.context());
            if (conf.context() != null && !conf.context().isBlank()) {
                final var contextPath = sessionStore.path(initialSessionId);
                try {
                    sessionLifecycle.prepareContextFile(contextPath, conf.context());
                } catch (final IOException e) {
                    LOG.severe(() -> "failed to prepare context file " + contextPath + ": " + e.getMessage());
                }
            }
            newTab(tui, initialSessionId);
            if (gitBranchPoller != null) {
                gitBranchPoller.start();
            }
            // while the TUI owns the terminal, stderr logging would corrupt the screen:
            // route app and JLine WARNING+ records into the UI instead
            final var julFeedback = redirectLoggerToUi("io.yupiik", tui);
            final var jlineFeedback = redirectLoggerToUi("org.jline", tui);
            try {
                tui.run((event, runner) -> onKey(event, runner, active()), frame -> render(frame, active()));
            } finally {
                // quitting the app tears every tab down: interrupt each running turn, wait
                // for the in-flight futures and release the per-session resources
                for (final var state : tabManager.list()) {
                    state.interrupted = true;
                    awaitInFlight(state);
                    disposeTab(state);
                }
                if (jlineFeedback != null) {
                    jlineFeedback.run();
                }
                if (julFeedback != null) {
                    julFeedback.run();
                }
                if (persistHistory) {
                    promptHistory.save(historyStoragePath());
                }
                if (gitBranchPoller != null) {
                    gitBranchPoller.close();
                }
                executor.shutdown();
            }
        } catch (final RuntimeException e) {
            throw e;
        } catch (final Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    /** The tab currently rendered and receiving the keyboard. */
    private UiState active() {
        return tabManager.active();
    }

    private void activate(final int index) {
        tabManager.activate(index);
    }

    private void cycleTab(final int delta) {
        tabManager.cycle(delta);
    }

    /**
     * Opens a new tab: a fresh session (new id, new persisted store entry) with its own
     * transcript, runtime (permissions, todos, usage) and shell registration. The new tab
     * becomes the active one; turns of the previous tabs keep running in the background.
     */
    private UiState newTab(final TuiRunner tui) {
        return newTab(tui, sessionStore.create());
    }

    /**
     * Opens a new tab on an explicit session: when {@code sessionId} was passed at
     * launch (or comes from a context file) the tab starts on that session and its
     * persisted history is replayed into the model instead of a blank conversation,
     * mirroring {@code /resume}. A brand-new id behaves exactly like {@link #newTab(TuiRunner)}.
     */
    private UiState newTab(final TuiRunner tui, final String sessionId) {
        final var state = new UiState(thinkTags, clock);
        state.sessionId = sessionId;
        state.showThinking = conf.showThinking();
        state.runner = tui;
        state.session.sessionId(state.sessionId).activeAgent(state.activeAgent);
        final var runtime = sessionRuntimeFactory.create(state.sessionId);
        state.runtime(runtime);
        state.session.runtime(runtime);
        final var transcriptConf = tuiConfiguration.transcript();
        state.model.reset();
        state.model.maxHistoryCharacters(
                transcriptConf == null || transcriptConf.maxHistoryCharacters() <= 0
                        ? DEFAULT_MAX_HISTORY_CHARACTERS
                        : transcriptConf.maxHistoryCharacters());
        state.model.replay(sessionStore.load(state.sessionId));
        if (state.title == null || state.title.isBlank()) {
            final var summary = sessionStore.summary(state.sessionId);
            if (summary != null && !summary.isBlank()) {
                state.title = TuiInputSupport.firstLine(summary);
            }
        }
        runtime.permissions().setGate(gate(tui, state));
        runtime.feedback().setGate(feedbackGate(tui, state));
        runtime.permissions().setPermitAll(conf.permitAll());
        state.model.status("ready");
        shellRegistry.register(env(tui, state));
        tabManager.add(state);
        return state;
    }

    /**
     * Releases a tab: unregisters its shell, drops the per-session gates and deletes the
     * session when it never got any content. Does not wait for turns: the caller does.
     */
    private void disposeTab(final UiState state) {
        final var runner = state.runner;
        if (runner != null) {
            shellRegistry.unregister(env(runner, state));
        }
        final var runtime = state.runtime();
        if (runtime != null) {
            runtime.permissions().setGate(null);
            runtime.feedback().setGate(null);
            runtime.permissions().setPermitAll(false);
        }
        if (state.sessionId != null && sessionStore.isEmpty(state.sessionId)) {
            sessionStore.delete(state.sessionId);
        }
        tabManager.remove(state);
    }

    /**
     * Closes a tab after interrupting its turn. Closing the last tab quits the application.
     *
     * @return {@code true} when the event is fully handled
     */
    private boolean closeTab(final UiState state, final TuiRunner tui) {
        state.interrupted = true;
        awaitInFlight(state);
        disposeTab(state);
        if (tabManager.isEmpty()) {
            tui.quit();
            return true;
        }
        activate(tabManager.activeTab());
        return true;
    }

    /**
     * {@code /resume} switched the tab to another session: the per-session world (runtime,
     * todo file, loop session id) is rebuilt and the shell is re-registered under the new id.
     */
    private void rebind(final UiState state) {
        final var tui = state.runner;
        if (tui != null) {
            shellRegistry.unregister(env(tui, state));
        }
        final var runtime = sessionRuntimeFactory.create(state.sessionId);
        state.runtime(runtime);
        state.session.runtime(runtime);
        if (tui != null) {
            runtime.permissions().setGate(gate(tui, state));
            runtime.feedback().setGate(feedbackGate(tui, state));
            runtime.permissions().setPermitAll(conf.permitAll());
            shellRegistry.register(env(tui, state));
        }
    }

    /**
     * While the TUI owns the terminal, stderr logging would corrupt the screen:
     * detach the parent handlers of the given logger and route WARNING+ records
     * into the active tab (status bar, SEVERE as an error) instead. Returns a restore
     * callback, or null when the logger already carries custom handlers.
     */
    private Runnable redirectLoggerToUi(final String name, final TuiRunner tui) {
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
                    final var target = active();
                    if (target == null) {
                        return;
                    }
                    target.dirty = true;
                    if (level == Level.SEVERE) {
                        modelError(target, message);
                    } else {
                        TuiCommand.notify(target, message);
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
            tui.runLater(() -> {
                state.dirty = true;
                state.approval = new Approval(title, detail, latch, outcome);
            });
            try {
                while (!latch.await(200, TimeUnit.MILLISECONDS)) {
                    if (state.interrupted || renderStalled(state)) {
                        tui.runLater(() -> {
                            state.dirty = true;
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
            tui.runLater(() -> {
                state.dirty = true;
                state.feedbackPrompt = new FeedbackPrompt(questions, latch, answers, answered);
            });
            try {
                while (!latch.await(200, TimeUnit.MILLISECONDS)) {
                    if (state.interrupted || renderStalled(state)) {
                        tui.runLater(() -> {
                            state.dirty = true;
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

    /** True when the render/UI thread has not produced a frame for {@link RENDER_STALL_TIMEOUT_MILLIS}. */
    private boolean renderStalled(final UiState state) {
        final var last = state.lastRenderTick;
        return last != 0 && clock.millis() - last >= RENDER_STALL_TIMEOUT_MILLIS;
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
                    area.insert(
                            cp > Character.MAX_VALUE ? new String(Character.toChars(cp)) : String.valueOf((char) cp));
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
        if (event instanceof AgentEvent.Status status) {
            // informational progress (output optimization, graph refresh, provider pauses,
            // stale-read invalidations...): appended to the trailing notification slot instead
            // of replacing the leading agent state, which must stay short and stable
            notify(state, status.message());
            return;
        }
        state.model.onEvent(event);
        if (event instanceof AgentEvent.ToolStarted started) {
            state.spinnerTick = 0;
            state.pendingToolArgs
                    .computeIfAbsent(started.name(), n -> new java.util.ArrayDeque<>())
                    .addLast(started.args());
        } else if (event instanceof AgentEvent.ToolFinished finished) {
            // the transcript just stamped this call with ordinal #toolCallCount: keep the
            // full uncapped output around so the Ctrl+O overlay can expand the truncated card
            final var args = state.pendingToolArgs.get(finished.name());
            final var argsValue = args == null || args.isEmpty() ? "" : args.pollFirst();
            state.toolOutputs.record(
                    state.model.toolCallCount(),
                    new ToolOutputStore.Entry(
                            finished.name(),
                            argsValue == null ? "" : argsValue,
                            finished.output(),
                            !finished.success(),
                            0));
        } else if (event instanceof AgentEvent.TurnDone || event instanceof AgentEvent.Error) {
            // tab icon: the last turn finished (done) until a new one starts
            state.turnCompleted = true;
            state.wait = null;
            if (!state.pendingPrompts.isEmpty()) {
                final var runner = state.runner;
                if (runner != null && !state.model.busy()) {
                    final var next = state.pendingPrompts.removeFirst();
                    runLater(state, () -> submit(next, state.runner, state, false));
                }
            }
        }
    }

    /**
     * True when a periodic tick may need a repaint even though no input event fired:
     * every animated region (spinner, "thinking Xs" counter, tool progress, queued prompts),
     * a live toast (its expiry changes the frame), or a pending background mutation that was
     * posted through {@link #runLater(UiState, Runnable)} and still awaits its paint.
     * Anything else ticks as cheaply as possible: the handler returns false and the frame
     * is left untouched until input or a state change forces one.
     */
    private boolean needsTickRender(final UiState state) {
        return state.model.runningTool() != null
                || activeWait(state) != null
                || state.model.thinkingLength() > 0
                || !state.pendingPrompts.isEmpty()
                || state.toast != null
                || state.dirty;
    }

    /**
     * Posts a UI mutation from a background thread and marks the tab dirty so the next tick
     * paints it: the TamboUI runner executes the action on the render thread without an
     * immediate redraw, the tick right after carries the paint (exactly the pre-optimization
     * cadence, but only while a mutation is actually pending).
     */
    private void runLater(final UiState state, final Runnable action) {
        state.runner.runLater(() -> {
            state.dirty = true;
            action.run();
        });
    }

    private boolean onKey(final Event event, final TuiRunner runner, final UiState state) {
        if (event instanceof final PasteEvent paste) {
            // normalize CRLF/CR so multiline pastes render and submit cleanly
            final var text = paste.text().replace("\r\n", "\n").replace("\r", "\n");
            if (state.feedbackPrompt != null) {
                feedbackInput(state.feedbackPrompt).insert(text);
                return true;
            }
            beforeEdit(state);
            state.input.insert(text);
            return true;
        }
        if (event instanceof TickEvent) {
            // idle heartbeat: only repaint when something could have changed since the last
            // frame (busy/animations, a running-mutation painted via runLater, a live toast),
            // or when the safety repaint interval expired to bound any missed update
            return needsTickRender(state)
                    || (heartbeatMillis > 0 && clock.millis() - state.lastRenderTick >= heartbeatMillis);
        }
        if (state.toolOverlay != null) {
            return onToolOverlayEvent(event, runner, state);
        }
        if (tabManager.isListOpen()) {
            return onTabsListKey(event);
        }
        if (state.shortcutsOpen) {
            return onShortcutsKey(event, state);
        }
        if (state.subagentsOverlay != null) {
            return onSubagentsKey(event, state);
        }
        if (state.searchOpen) {
            return onSearchKey(event, state);
        }
        if (state.errorDetail != null && state.errorOpen) {
            return onErrorKey(event, runner, state);
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
                    state.model.anchorRow(state.model.anchorRow() - 3);
                    state.model.userScrolled(true);
                } else if (mouse.kind() == MouseEventKind.SCROLL_DOWN) {
                    state.model.anchorRow(state.model.anchorRow() + 3);
                    state.model.userScrolled(state.model.anchorRow() < state.maxViewOffset);
                }
                return true;
            }
            if (mouse.isPress() && mouse.isLeftButton()) {
                // tab close zones first: they are sub-rects of the tab cells
                for (var i = 0; i < tabCloseRects.size(); i++) {
                    if (i < tabManager.size() && tabCloseRects.get(i).contains(mouse.x(), mouse.y())) {
                        state.selection.clear();
                        closeTab(tabManager.get(i), runner);
                        return true;
                    }
                }
                // the "+" cell at the end of the bar opens a new tab
                if (tabAddRect != null && tabAddRect.contains(mouse.x(), mouse.y())) {
                    state.selection.clear();
                    newTab(runner);
                    return true;
                }
                // tab strip: clicking a tab switches to it
                for (var i = 0; i < tabRects.size(); i++) {
                    if (tabRects.get(i).contains(mouse.x(), mouse.y())) {
                        state.selection.clear();
                        activate(i);
                        return true;
                    }
                }
                // shortcuts appearance: clicking the "?" opens the full list
                if (state.shortcutRect != null && state.shortcutRect.contains(mouse.x(), mouse.y())) {
                    state.selection.clear();
                    state.shortcutsOpen = !state.shortcutsOpen;
                    state.shortcutsScroll = 0;
                    return true;
                }
                // queued prompts: clicking a queued row removes it
                for (var i = 0; i < state.queueRects.size(); i++) {
                    if (state.queueRects.get(i).contains(mouse.x(), mouse.y())) {
                        state.selection.clear();
                        removeQueued(state, i);
                        return true;
                    }
                }
                // truncated tool cards: clicking the "click to expand" footer opens the full output
                for (final var link : state.toolFooterLinks) {
                    if (link.ordinal() >= 0 && link.header().contains(mouse.x(), mouse.y())) {
                        state.selection.clear();
                        openToolOverlay(state, link.ordinal());
                        return true;
                    }
                }
                // tool card headers: clicking toggles that card's collapse
                for (final var anchor : state.toolAnchors) {
                    if (anchor.ordinal() >= 0 && anchor.header().contains(mouse.x(), mouse.y())) {
                        state.selection.clear();
                        if (state.showToolActions == ToolOutputVisibility.COLLAPSED) {
                            // condensed mode: the dim one-liner itself opens the full output
                            openToolOverlay(state, anchor.ordinal());
                        } else if (state.showToolActions == ToolOutputVisibility.SHOWN
                                && !state.collapsedTools.remove(anchor.ordinal())) {
                            state.collapsedTools.add(anchor.ordinal());
                        }
                        return true;
                    }
                }
                // prompt input: a left click positions the edit caret (the selection
                // press below still anchors the drag so text selection keeps working)
                if (state.inputZone != null && state.inputZone.contains(mouse.x(), mouse.y())) {
                    dismissCompletion(state);
                    final var caret = inputCaretAt(state, mouse.x(), mouse.y());
                    applyCursor(state, caret.row(), caret.col());
                }
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
                notify(state, ok ? "approved" : "denied");
            } else if (key.isCancel() || key.isKey(KeyCode.ESCAPE)) {
                approval.perform();
                state.approval = null;
                notify(state, "denied");
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
        if (state.btw != null) {
            return onBtwKey(key, state);
        }
        if (key.hasCtrl() && key.hasShift() && key.isCharIgnoreCase('c')) {
            copySelection(runner, state);
            return true;
        }
        if (key.hasCtrl() && key.isCharIgnoreCase('n')) {
            newTab(runner);
            return true;
        }
        if (key.hasAlt() && !key.hasCtrl() && key.isCharIgnoreCase('w')) {
            return closeTab(state, runner);
        }
        // fallback: many terminals intercept Ctrl+Shift+W as a window close, Alt+W is the safe one
        if (key.hasCtrl() && key.hasShift() && key.isCharIgnoreCase('w')) {
            return closeTab(state, runner);
        }
        if (key.hasCtrl() && !key.hasShift() && (key.isKey(KeyCode.TAB) || key.isKey(KeyCode.PAGE_DOWN))) {
            cycleTab(1);
            return true;
        }
        if (key.hasCtrl() && (key.hasShift() && key.isKey(KeyCode.TAB) || key.isKey(KeyCode.PAGE_UP))) {
            cycleTab(-1);
            return true;
        }
        if (key.hasAlt() && !key.hasCtrl() && key.character() >= '1' && key.character() <= '9') {
            final var index = Character.digit(key.character(), 10) - 1;
            if (index < tabManager.size()) {
                activate(index);
            }
            return true;
        }
        if (key.isCtrlC()) {
            if (state.interruptor.interrupt(state.model.busy()) == InterruptCoordinator.Decision.INTERRUPT) {
                state.quitArmed = true;
                state.interrupted = true;
                waiting(state, "interrupting... press Ctrl+C again to quit", SpinnerStyle.CLOCK);
            } else {
                state.quitArmed = false;
                // quitting interrupts every tab, not only the visible one
                for (final var tab : tabManager.list()) {
                    tab.interrupted = true;
                    awaitInFlight(tab);
                }
                runner.quit();
            }
            return true;
        }
        if (key.hasCtrl() && key.isCharIgnoreCase('t')) {
            state.showThinking = !state.showThinking;
            // UI state is authoritative for the TUI; the /thinking command routes through
            // the frontend which reflects the same toggle, so no session mirror is kept here
            notify(state, "thinking blocks " + (state.showThinking ? "shown" : "hidden"));
            return true;
        }
        if (key.hasAlt() && key.isChar('?')) {
            state.shortcutsOpen = !state.shortcutsOpen;
            state.shortcutsScroll = 0;
            return true;
        }
        if (key.hasCtrl() && key.isCharIgnoreCase('p')) {
            if (state.showTodos && !state.showTodosCollapsed) {
                state.showTodosCollapsed = true;
                notify(state, "todos collapsed");
            } else if (state.showTodos) {
                state.showTodos = false;
                state.showTodosCollapsed = false;
                notify(state, "todos hidden");
            } else {
                state.showTodos = true;
                notify(state, "todos shown");
            }
            return true;
        }
        if (key.hasCtrl() && key.isCharIgnoreCase('s')) {
            compact(runner, state);
            return true;
        }
        if (key.hasCtrl() && key.isCharIgnoreCase('l')) {
            state.model.clearConversation();
            state.toolNavIndex = -1;
            state.focusedToolOrdinal = -1;
            state.collapsedTools.clear();
            notify(state, "transcript cleared");
            return true;
        }
        if (key.hasCtrl() && key.isCharIgnoreCase('y')) {
            state.showToolActions = state.showToolActions.next();
            // keep the session flag in sync so /tool-output (no arg) reports the rendered state
            state.session.showToolOutput(state.showToolActions);
            notify(state, "tool blocks " + state.showToolActions.name().toLowerCase(Locale.ROOT));
            return true;
        }
        if (key.hasCtrl() && key.isCharIgnoreCase('f')) {
            openSearch(state);
            return true;
        }
        if (key.hasCtrl() && key.isCharIgnoreCase('o')) {
            openToolOverlay(
                    state,
                    state.focusedToolOrdinal >= 0 ? state.focusedToolOrdinal : state.toolOutputs.latestOrdinal());
            return true;
        }
        if (key.hasAlt() && key.isCharIgnoreCase('e') && state.errorDetail != null) {
            state.errorOpen = !state.errorOpen;
            state.errorScroll = 0;
            return true;
        }
        if (key.hasCtrl() && key.isCharIgnoreCase('x') && !state.pendingPrompts.isEmpty()) {
            removeQueued(state, state.pendingPrompts.size() - 1);
            return true;
        }
        if (key.hasAlt() && key.isCharIgnoreCase('c')) {
            copyLastCodeBlock(runner, state);
            return true;
        }
        // Alt+Up/Down plus Alt+K/Alt+J: alt+arrows are not delivered by every
        // terminal (macOS Terminal, rxvt) so the letters are the portable fallback
        if (key.hasAlt() && (key.isKey(KeyCode.UP) || key.isCharIgnoreCase('k'))) {
            navigateTools(state, -1);
            return true;
        }
        if (key.hasAlt() && (key.isKey(KeyCode.DOWN) || key.isCharIgnoreCase('j'))) {
            navigateTools(state, 1);
            return true;
        }
        if (key.hasCtrl() && !key.hasShift() && key.isCharIgnoreCase('z')) {
            final var previous = state.undoStack.undo(state.input.text());
            if (previous != null) {
                state.input.setText(previous);
                positionCursorAtEnd(state);
            }
            return true;
        }
        if (key.hasAlt() && key.isCharIgnoreCase('z')) {
            final var next = state.undoStack.redo(state.input.text());
            if (next != null) {
                state.input.setText(next);
                positionCursorAtEnd(state);
            }
            return true;
        }
        if (key.hasAlt() && key.isCharIgnoreCase('y')) {
            yank(state);
            return true;
        }
        if (key.isCancel() || key.isKey(KeyCode.ESCAPE)) {
            final var pending = state.pendingLine;
            if (pending != null) {
                state.input.clear();
                state.pendingLine = null;
                pending.complete(Optional.empty());
                return true;
            }
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
            state.model.anchorRow(state.model.anchorRow() - 10);
            state.model.userScrolled(true);
            return true;
        }
        if (key.isPageDown()) {
            state.model.anchorRow(state.model.anchorRow() + 10);
            state.model.userScrolled(state.model.anchorRow() < state.maxViewOffset);
            return true;
        }
        if (key.hasCtrl() && key.isKey(KeyCode.HOME)) {
            state.model.anchorRow(0);
            state.model.userScrolled(true);
            return true;
        }
        if (key.hasCtrl() && key.isKey(KeyCode.END)) {
            state.model.anchorRow(state.maxViewOffset);
            state.model.userScrolled(false);
            return true;
        }
        if (key.hasShift() && key.isKey(KeyCode.TAB)) {
            // shift+tab flips plan/build like /mode (tab stays on completion)
            slash(
                    "/mode " + (state.session.runtime().permissions().mode() == AgentMode.BUILD ? "plan" : "build"),
                    runner,
                    state);
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
            openHistoryPicker(state);
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
            final var pending = state.pendingLine;
            if (pending != null) {
                final var answer = state.input.text();
                state.input.clear();
                state.pendingLine = null;
                pending.complete(Optional.of(answer));
                return true;
            }
            final var text = state.input.text().strip();
            state.input.clear();
            if (!text.isEmpty()) {
                submit(text, runner, state, true);
            }
            return true;
        }
        switch (key.code()) {
            case ENTER -> {
                beforeEdit(state);
                state.input.insert("\n");
            }
            case BACKSPACE -> {
                beforeEdit(state);
                state.input.deleteBackward();
                liveComplete(state);
            }
            case DELETE -> {
                beforeEdit(state);
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
                    beforeEdit(state);
                    final var cp = key.codePoint();
                    state.input.insert(
                            cp > Character.MAX_VALUE ? new String(Character.toChars(cp)) : String.valueOf((char) cp));
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
        final var text = state.selection.copy(state.screenGrid, copyZones(state));
        if (text == null || text.length() <= 1 || text.isBlank()) {
            return;
        }
        copyText(runner, state, text);
    }

    /**
     * The zones a selection may be confined to, most specific first: the chat
     * body (conversation) and the prompt input. The selection engine clips to
     * whichever zone holds the drag's anchor.
     */
    private static java.util.List<Rect> copyZones(final UiState state) {
        return java.util.List.of(state.copyZone, state.inputZone);
    }

    /**
     * Schedules a clipboard write for the given text (used by selection, code and error
     * copies): system-level channels first ({@link Clipboard} ladder), then OSC 52 which
     * still covers ssh/tmux terminals. The toast reports which channel actually won.
     */
    private void copyText(final TuiRunner runner, final UiState state, final String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        final var via = Clipboard.copy(text);
        final var payload = SelectionState.osc52Payload(text);
        final var copied = text.length();
        runLater(state, () -> {
            try {
                runner.backend().writeRaw(payload);
                final var channel = via == null ? " (OSC 52)" : " via " + via;
                state.pushToast("copied " + copied + " characters" + channel, ToastLevel.SUCCESS);
                notify(state, "copied " + copied + " characters" + channel);
            } catch (final IOException e) {
                state.pushToast("clipboard copy failed: " + e.getMessage(), ToastLevel.ERROR);
                notify(state, "clipboard copy failed: " + e.getMessage());
            }
        });
    }

    private void copyLastCodeBlock(final TuiRunner runner, final UiState state) {
        final var code = state.lastCodeBlock;
        if (code == null || code.isBlank()) {
            notify(state, "no code block to copy yet");
            return;
        }
        copyText(runner, state, code);
    }

    private void submit(final String text, final TuiRunner runner, final UiState state, final boolean pinToBottom) {
        if (!text.regionMatches(true, 0, "/btw", 0, 4)) {
            promptHistory.add(text);
        }
        dismissCompletion(state);
        clearError(state);
        if (text.startsWith("/")) {
            slash(text, runner, state);
            return;
        }
        if (state.model.busy()) {
            state.pendingPrompts.addLast(text);
            final var n = state.pendingPrompts.size();
            notify(
                    state,
                    "queued prompt (" + n + " pending) - it will be picked up as soon as the agent hits a checkpoint");
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
        state.model.startUserTurn(text, pinToBottom);
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
        notify(state, "resuming previous turn");
        state.interrupted = false;
        waiting(state, "thinking...", SpinnerStyle.CLOCK);
        startTurn(state, state.activeAgent, null);
    }

    private void startTurn(final UiState state, final String agentName, final String prompt) {
        state.turnCompleted = false;
        state.inFlight = turnRunner
                .submit(new TurnRunner.TurnRequest(
                        state.session.runtime().loop(),
                        env(state.runner, state),
                        state.sessionId,
                        agentName,
                        prompt,
                        () -> sessionStore.load(state.sessionId),
                        () -> state.interrupted,
                        () -> state.pendingPrompts.pollFirst()))
                .toCompletableFuture();
        state.inFlight.thenAccept(ran -> {
            if (ran == null || !ran) {
                state.wait = null;
                notify(state, "maven knowledge index still running - prompt not sent");
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
        final var parts = SPLIT_COMMAND.split(text.substring(1), 2);
        if ("btw".equals(parts[0].toLowerCase(Locale.ROOT))) {
            btw(parts.length > 1 ? parts[1].strip() : "", runner, state);
            return;
        }
        state.model.appendTranscript("\n\n---\n\n**You**\n\n`" + text + "`\n");
        // the TUI keeps its modal-driven plan editor (Enter/Esc handling needs the widget stack)
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
                runLater(state, () -> apply(outcome, runner, state));
            } catch (final RuntimeException e) {
                final var cause = e.getCause() == null ? e : e.getCause();
                runLater(state, () -> modelError(state, "command failed: " + cause.getMessage()));
            }
        });
    }

    private void btw(final String question, final TuiRunner runner, final UiState state) {
        if (question.isBlank()) {
            notify(state, "usage: /btw <question>");
            return;
        }
        if (state.btw != null) {
            notify(state, "a btw answer is already open - close it first (Esc)");
            return;
        }
        final var ordinal = state.btwOrdinal.incrementAndGet();
        final var cancelled = new AtomicBoolean(false);
        state.btw = new UiState.BtwOverlay(ordinal, question, "", null, true, 0, cancelled);
        final var sessionId = state.session.sessionId();
        btwAnswerer
                .ask(
                        question,
                        sessionId == null ? "" : sessionId,
                        state.session.runtime() != null
                                ? state.session.runtime().usage()
                                : null,
                        cancelled::get)
                .whenComplete((answer, error) ->
                        runLater(state, () -> onBtwAnswer(state, ordinal, answer, error, cancelled)));
    }

    private void onBtwAnswer(
            final UiState state,
            final long ordinal,
            final String answer,
            final Throwable error,
            final AtomicBoolean cancelled) {
        if (state.btw == null || state.btw.ordinal() != ordinal) {
            return;
        }
        if (cancelled.get()) {
            state.btw = null;
            return;
        }
        final var question = state.btw.question();
        if (error != null) {
            final var cause = error.getCause() == null ? error : error.getCause();
            final var message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
            state.btw = new UiState.BtwOverlay(ordinal, question, "", message, false, 0, cancelled);
            return;
        }
        state.btw = new UiState.BtwOverlay(
                ordinal,
                question,
                answer == null || answer.isBlank() ? "(no answer)" : answer,
                null,
                false,
                0,
                cancelled);
    }

    private void closeBtw(final UiState state) {
        final var btw = state.btw;
        state.btw = null;
        if (btw != null) {
            btw.cancelled().set(true);
        }
    }

    private boolean onBtwKey(final KeyEvent key, final UiState state) {
        if (key.isCancel() || key.isKey(KeyCode.ESCAPE) || key.isConfirm() || key.isChar('q')) {
            closeBtw(state);
            return true;
        }
        if (key.isKey(KeyCode.UP) || key.isKey(KeyCode.PAGE_UP)) {
            state.btw = withBtwScroll(state.btw, key.isKey(KeyCode.PAGE_UP) ? -5 : -1);
            return true;
        }
        if (key.isKey(KeyCode.DOWN) || key.isKey(KeyCode.PAGE_DOWN)) {
            state.btw = withBtwScroll(state.btw, key.isKey(KeyCode.PAGE_DOWN) ? 5 : 1);
            return true;
        }
        return true;
    }

    private UiState.BtwOverlay withBtwScroll(final UiState.BtwOverlay btw, final int delta) {
        return new UiState.BtwOverlay(
                btw.ordinal(),
                btw.question(),
                btw.answer(),
                btw.error(),
                btw.running(),
                btw.scroll() + delta,
                btw.cancelled());
    }

    private void apply(final ShellOutcome outcome, final TuiRunner runner, final UiState state) {
        switch (outcome) {
            case ShellOutcome.None none -> {}
            case ShellOutcome.Status(String message) -> notify(state, message);
            case ShellOutcome.Transcript(String markdown) -> state.model.appendTranscript(markdown);
            case ShellOutcome.Error(String message) -> modelError(state, message);
            case ShellOutcome.Quit quit -> {
                awaitInFlight(state);
                runner.quit();
            }
        }
        syncSession(state);
    }

    private void syncSession(final UiState state) {
        if (state.session.sessionId() != null && !state.session.sessionId().isBlank()) {
            final var switched = !state.session.sessionId().equals(state.sessionId);
            state.sessionId = state.session.sessionId();
            if (switched) {
                // /resume (or any other session switch) replaced the session: the per-session
                // world is keyed by session id so it has to be rebuilt
                rebind(state);
            }
        }
        if (state.session.activeAgent() != null) {
            state.activeAgent = state.session.activeAgent();
        }
    }

    private ShellEnv env(final TuiRunner runner, final UiState state) {
        return new ShellEnv(frontend(runner, state), io(runner, state), state.session, state.model);
    }

    private ShellFrontend frontend(final TuiRunner runner, final UiState state) {
        return new ShellFrontend() {
            @Override
            public void onEvent(final AgentEvent event) {
                runLater(state, () -> TuiCommand.this.onEvent(event, state));
            }

            @Override
            public void status(final String message) {
                runLater(state, () -> TuiCommand.notify(state, message));
            }

            @Override
            public void notice(final AgentEvent.Notice.Level level, final String message) {
                runLater(state, () -> TuiCommand.notify(state, message));
            }

            @Override
            public void transcript(final String markdown) {
                runLater(state, () -> state.model.appendTranscript(markdown));
            }

            @Override
            public void error(final String message) {
                runLater(state, () -> modelError(state, message));
            }

            @Override
            public void showThinking(final boolean value) {
                runLater(state, () -> state.showThinking = value);
            }

            @Override
            public void showToolOutput(final ToolOutputVisibility value) {
                runLater(state, () -> state.showToolActions = value);
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
                focus(state, runner);
                final var future = new CompletableFuture<Optional<String>>();
                state.pendingLine = future;
                return future;
            }

            @Override
            public CompletionStage<Boolean> confirm(final String question) {
                focus(state, runner);
                final var answers = state.session
                        .runtime()
                        .feedback()
                        .ask(List.of(new Question("confirm", question, List.of("Yes", "No"), false)));
                return CompletableFuture.completedStage(
                        answers != null && "Yes".equalsIgnoreCase(String.valueOf(answers.get("confirm"))));
            }

            @Override
            public CompletionStage<Optional<String>> pick(final String title, final List<String> options) {
                focus(state, runner);
                final var future = new CompletableFuture<Optional<String>>();
                runLater(
                        state,
                        () -> openPicker(
                                state,
                                title,
                                options,
                                value -> future.complete(Optional.of(value)),
                                () -> future.complete(Optional.empty())));
                return future;
            }

            @Override
            public ApprovalGate approvalGate() {
                focus(state, runner);
                return gate(runner, state);
            }

            @Override
            public FeedbackGate feedbackGate() {
                focus(state, runner);
                return TuiCommand.this.feedbackGate(runner, state);
            }
        };
    }

    /**
     * Brings the tab owning an interaction (input line, picker, approval, question) to the
     * front: such requests can come from a background tab's turn and the user has to see
     * the prompt to answer it.
     */
    private void focus(final UiState state, final TuiRunner runner) {
        final var index = tabManager.indexOf(state);
        if (index >= 0 && index != tabManager.activeTab()) {
            runLater(state, () -> activate(index));
        }
    }

    private ShellOutcome themeOutcome(final String arg, final UiState state) {
        final var current = isPaletteLight() ? "light" : "dark";
        if (arg == null || arg.isBlank()) {
            notify(state, "theme: " + current + " (/theme light|dark to switch)");
            return ShellOutcome.none();
        }
        final var next = arg.strip().toLowerCase(Locale.ROOT);
        if (!"light".equals(next) && !"dark".equals(next)) {
            notify(state, "usage: /theme light|dark (current: " + current + ")");
            return ShellOutcome.none();
        }
        applyTheme(next);
        persistTheme(next);
        notify(state, "theme: " + next);
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
            notify(state, "no completion for '" + line + "'");
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
     * pressing Tab. The popup opens for "/" prefixes and "@agent" tokens.
     */
    /**
     * Live completion keeps the popup in sync with what is typed. The suggestion lookup
     * (which may resolve the model list over the network) runs on the shared executor so
     * it never blocks the UI/render thread; results are applied only when still relevant
     * (generation + input unchanged) to avoid stale popups while typing fast.
     */
    private void liveComplete(final UiState state) {
        final var line = state.input.getLine(state.input.cursorRow());
        final var cursor = state.input.cursorCol();
        completionGeneration++;
        if (!line.startsWith("/") && !tokenAt(line, cursor).startsWith("@")) {
            dismissCompletion(state);
            return;
        }
        final var gen = completionGeneration;
        final var reqLine = line;
        final var reqCursor = cursor;
        executor.execute(() -> {
            final var suggestion = completionEngine.suggest(reqLine, reqCursor);
            if (gen != completionGeneration || !completionInputMatches(state, reqLine, reqCursor)) {
                return;
            }
            if (suggestion.empty()) {
                state.completion = null;
                state.completionCursor = -1;
                return;
            }
            state.completion = suggestion;
            state.completionCursor = reqCursor;
            state.completionIndex = 0;
        });
    }

    /**
     * Whether the current input still equals the completion request that was issued, so a
     * result computed on the executor thread can be safely applied to the live UI state.
     */
    private boolean completionInputMatches(final UiState state, final String line, final int cursor) {
        return line.equals(state.input.getLine(state.input.cursorRow())) && cursor == state.input.cursorCol();
    }

    /** @return the whitespace-delimited token the cursor sits in. */
    private static String tokenAt(final String line, final int cursor) {
        final var end = Math.min(cursor, line.length());
        var start = end;
        while (start > 0 && !Character.isWhitespace(line.charAt(start - 1))) {
            start--;
        }
        return line.substring(start, end);
    }

    /** Asynchronously reloads the completion value cache so render/onKey never does network or IO. */
    private void refreshCompletionCache() {
        try {
            executor.execute(() -> {
                if (chatClient != null) {
                    try {
                        final var ids = chatClient.models().ids();
                        completionCache.models(ids == null ? List.of() : List.copyOf(ids));
                    } catch (final Exception e) {
                        LOG.log(Level.SEVERE, "model list refresh failed; keeping previous completion cache", e);
                    }
                }
                if (mcpManager != null) {
                    try {
                        final var byServer = mcpManager.toolsByServer();
                        if (byServer == null || byServer.isEmpty()) {
                            completionCache.tools(List.of());
                        } else {
                            completionCache.tools(byServer.values().stream()
                                    .flatMap(List::stream)
                                    .map(EmbeddedTool::name)
                                    .distinct()
                                    .sorted()
                                    .toList());
                        }
                    } catch (final RuntimeException e) {
                        LOG.log(Level.SEVERE, "tools refresh failed; keeping previous completion cache", e);
                    }
                }
                if (sessionStore != null) {
                    try {
                        // most recent first regardless of the store implementation
                        final var sessions = sessionStore.list().stream()
                                .sorted(java.util.Comparator.comparingLong(SessionStore.SessionInfo::modifiedAt)
                                        .reversed())
                                .toList();
                        if (sessions.isEmpty()) {
                            completionCache.sessions(List.of());
                        } else {
                            completionCache.sessions(sessions.stream()
                                    .map(SessionStore.SessionInfo::id)
                                    .toList());
                        }
                    } catch (final RuntimeException e) {
                        LOG.log(Level.SEVERE, "sessions refresh failed; keeping previous completion cache", e);
                    }
                }
                if (chatClient != null) {
                    final var variants = new LinkedHashMap<String, List<String>>();
                    for (final var model : completionCache.models()) {
                        final var selected = new ArrayList<String>();
                        if (chatClient.implicitVariant(model) != null) {
                            selected.add("default");
                        }
                        try {
                            final var raw = chatClient.variants(model);
                            if (raw != null) {
                                for (final var v : raw) {
                                    selected.add(v.name());
                                }
                            }
                        } catch (final Exception e) {
                            LOG.log(
                                    Level.SEVERE,
                                    "variants refresh failed for model " + model + "; using implicit defaults",
                                    e);
                        }
                        if (!selected.isEmpty()) {
                            variants.put(model, List.copyOf(selected));
                        }
                    }
                    completionCache.variants(variants);
                }
            });
        } catch (final RuntimeException e) {
            // warm-up is best-effort: a closing executor or transient failure must not break construction
            LOG.log(Level.SEVERE, "completion cache warm-up skipped", e);
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
    private static void applyCursor(final UiState state, final int row, final int col) {
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
            moveCursorVisual(state, up);
            return;
        }
        final var inner = state.inputZone;
        if (inner != null && inner.width() > 0) {
            // GUI-editor motion: vertical moves cross the soft-wrapped visual rows
            // of the current input (same display column, clamped), only falling
            // through to history recall at the very first/last visual row
            final var grid = visualGrid(state.input, inner.width());
            final var caretVisual = visualRowOfCaret(state.input, inner.width());
            if (up) {
                if (caretVisual > 0) {
                    moveCursorToVisual(state, grid, caretVisual - 1);
                    return;
                }
            } else if (caretVisual < grid.size() - 1) {
                moveCursorToVisual(state, grid, caretVisual + 1);
                return;
            }
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
            beforeEdit(state);
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
            beforeEdit(state);
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

    /**
     * Busy-mode vertical motion: crosses soft-wrapped visual rows like a GUI
     * editor and does nothing at the input boundaries (no history recall while
     * the agent is streaming).
     */
    private void moveCursorVisual(final UiState state, final boolean up) {
        final var inner = state.inputZone;
        if (inner == null || inner.width() <= 0) {
            moveCursor(state, up);
            return;
        }
        final var grid = visualGrid(state.input, inner.width());
        final var caretVisual = visualRowOfCaret(state.input, inner.width());
        if (up) {
            if (caretVisual > 0) {
                moveCursorToVisual(state, grid, caretVisual - 1);
            }
        } else if (caretVisual < grid.size() - 1) {
            moveCursorToVisual(state, grid, caretVisual + 1);
        }
    }

    private void positionCursorAtEnd(final UiState state) {
        state.input.moveCursorToEnd();
    }

    /** Snapshots the input before a mutating edit (undo) and ends any yank sequence. */
    private void beforeEdit(final UiState state) {
        state.undoStack.push(state.input.text());
        state.yankArmed = false;
        state.lastYankLen = 0;
    }

    private void deleteWordBackward(final UiState state) {
        beforeEdit(state);
        final var row = state.input.cursorRow();
        final var col = state.input.cursorCol();
        final var line = state.input.getLine(row);
        final var start = TuiInputSupport.previousWord(line, col);
        if (start < col) {
            state.killRing.kill(line.substring(start, col));
            setInputLine(state, row, line.substring(0, start) + line.substring(col));
            applyCursor(state, row, start);
            return;
        }
        if (row == 0) {
            return;
        }
        final var prevLine = state.input.getLine(row - 1);
        final var prevEnd = prevLine.length();
        final var prevStart = TuiInputSupport.previousWord(prevLine, prevEnd);
        if (prevStart < prevEnd) {
            state.killRing.kill(prevLine.substring(prevStart, prevEnd));
            final var remaining = prevLine.substring(0, prevStart);
            if (remaining.isEmpty()) {
                setBufferWithoutRow(state, row - 1);
                applyCursor(state, row - 1, 0);
            } else {
                setInputLine(state, row - 1, remaining);
                applyCursor(state, row - 1, prevStart);
            }
        } else {
            setInputLine(state, row - 1, prevLine + line);
            applyCursor(state, row - 1, prevEnd);
        }
    }

    /** Removes the given row from the buffer, shifting the following lines up. */
    private void setBufferWithoutRow(final UiState state, final int dropRow) {
        final var lines = new ArrayList<String>(Math.max(1, state.input.lineCount() - 1));
        for (var i = 0; i < state.input.lineCount(); i++) {
            if (i != dropRow) {
                lines.add(state.input.getLine(i));
            }
        }
        state.input.setText(String.join("\n", lines));
    }

    private void killLineForward(final UiState state) {
        beforeEdit(state);
        final var row = state.input.cursorRow();
        final var line = state.input.getLine(row);
        final var col = state.input.cursorCol();
        state.killRing.kill(line.substring(col));
        setInputLine(state, row, line.substring(0, col));
    }

    private void killLineBackward(final UiState state) {
        beforeEdit(state);
        final var row = state.input.cursorRow();
        final var line = state.input.getLine(row);
        final var col = state.input.cursorCol();
        state.killRing.kill(line.substring(0, col));
        setInputLine(state, row, line.substring(col));
        applyCursor(state, row, 0);
    }

    /**
     * Yanks (re-inserts) the most recent kill; consecutive Alt+Y presses cycle
     * through the kill ring, replacing the previously yanked text.
     */
    private void yank(final UiState state) {
        final var text = state.yankArmed ? state.killRing.yankPop() : state.killRing.yank();
        if (text == null || text.isEmpty()) {
            return;
        }
        if (state.yankArmed) {
            for (var i = 0; i < state.lastYankLen; i++) {
                state.input.deleteBackward();
            }
        }
        state.input.insert(text);
        state.lastYankLen = text.length();
        state.yankArmed = true;
    }

    private void models(final TuiRunner runner, final UiState state) {
        executor.execute(() -> {
            try {
                final var ids = chatClient.models().ids();
                runLater(state, () -> openModelPicker(state, ids));
            } catch (final Exception e) {
                runLater(state, () -> modelError(state, "models failed: " + e.getMessage(), e));
            }
        });
    }

    private void openModelPicker(final UiState state, final List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            notify(state, "no models available");
            return;
        }
        openPicker(
                state,
                "model",
                ids,
                selected -> {
                    try {
                        // same reset semantics as AgentSessionService.setModel: a model
                        // change drops any stale variant/sampling override of the old model
                        final var settings = state.session.chatSettings();
                        settings.setModel(selected);
                        settings.setVariant(null);
                        settings.setSampling(null);
                        notify(state, "model: " + selected);
                        refreshCompletionCache();
                    } catch (final RuntimeException e) {
                        notify(state, "model failed: " + e.getMessage());
                    }
                },
                () -> notify(state, "model picker cancelled"));
    }

    private void editPlan(final String arg, final TuiRunner runner, final UiState state) {
        if (state.model.busy()) {
            notify(state, "editplan is not available while busy");
            return;
        }
        final var title = state.title == null || state.title.isBlank() ? "plan" : state.title;
        final var export = agentSessionService.exportPlan(state.sessionId, arg, title, state.runtime());
        if (export.error() != null) {
            notify(state, export.error());
            return;
        }
        state.editPlan = new PendingEditPlan(export.path(), export.original(), null, null);
        notify(
                state,
                "plan exported to " + export.path()
                        + " - edit it, save, then press Enter to reload (Esc or click Cancel to abort)");
        state.model.appendTranscript("\n\n> plan exported to `"
                + export.path()
                + "` - edit it, save, then press Enter to reload, or Esc to abort\n");
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
        final var reload =
                agentSessionService.reloadPlan(state.sessionId, path, pending.original(), state.session.runtime());
        if (reload.error() != null) {
            if (("plan file not found: " + path).equals(reload.error())) {
                notify(state, "plan file not found: " + path + " (still waiting - edit and press Enter)");
                return;
            }
            notify(state, reload.error());
            return;
        }
        if (reload.changed()) {
            state.editPlan = null;
        }
        notify(state, reload.message());
    }

    private void abortEditPlan(final UiState state) {
        state.editPlan = null;
        notify(state, "plan edit cancelled");
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
            notify(state, "cannot resync the graph while busy");
            return;
        }
        waiting(state, "resyncing structural code graph...", SpinnerStyle.BOUNCING_BALL);
        executor.execute(() -> {
            try {
                final var result = agentSessionService.resyncGraph();
                runLater(state, () -> {
                    notify(state, "structural code graph resynced");
                    state.model.appendTranscript("\n\n> structural code graph: `" + result + "`\n");
                });
            } catch (final RuntimeException e) {
                runLater(state, () -> modelError(state, "resync failed: " + e.getMessage()));
            }
        });
    }

    private void compact(final TuiRunner runner, final UiState state) {
        if (state.model.busy()) {
            notify(state, "cannot compact while busy");
            return;
        }
        waiting(state, "compacting...", SpinnerStyle.BOUNCING_BALL);
        executor.execute(() -> {
            try {
                final var result = agentSessionService.compact(state.sessionId, state.runtime());
                runLater(state, () -> {
                    notify(state, result.message());
                    if (result.applied()) {
                        if (result.error() != null) {
                            state.model.appendTranscript("\n\n> ⚠ " + result.message() + ": " + result.error() + "\n");
                        } else {
                            state.model.appendTranscript("\n\n> " + result.message() + "\n");
                        }
                    } else {
                        state.model.appendTranscript("\n\n> " + result.message() + "\n");
                    }
                });
            } catch (final RuntimeException e) {
                runLater(state, () -> modelError(state, "compaction failed: " + e.getMessage(), e));
            }
        });
    }

    private void render(final dev.tamboui.terminal.Frame frame, final UiState state) {
        state.lastRenderTick = clock.millis();
        state.dirty = false;
        if (state.model.runningTool() != null || activeWait(state) != null || state.model.thinkingLength() > 0) {
            state.spinnerTick++;
        }
        // thinking timer lifecycle: armed on the first frame with live reasoning and
        // cleared as soon as it stops, independent of busy/runningTool so both the
        // in-pane indicator and the pinned footer read a correct elapsed time even
        // while a tool runs and across consecutive turns
        if (state.model.thinkingLength() > 0) {
            if (state.thinkingStartedAtMillis < 0) {
                state.thinkingStartedAtMillis = clock.millis();
            }
        } else {
            state.thinkingStartedAtMillis = -1;
        }
        state.model.trim();
        if (state.toast != null
                && tuiConfiguration.toastDurationMillis() > 0
                && clock.millis() - state.toastShownAt > tuiConfiguration.toastDurationMillis()) {
            state.clearToast();
        }
        final var todosRender = state.session.runtime().todos().render();
        if (!todosRender.equals(state.lastTodosRender)) {
            state.lastTodosRender = todosRender;
            if (!todosRender.isBlank() && !"(empty)".equals(todosRender) && !state.showTodos) {
                state.showTodos = true;
            }
        }
        // responsive: drop the sidebar on narrow terminals to give the chat room
        final var screenWidth = frame.width();
        final var narrow = screenWidth < 68;
        final var sidebarWidth = narrow ? 0 : (screenWidth < 95 ? 22 : 28);
        // the prompt grows with its content (multiline pastes) up to a sane maximum,
        // and queued prompts get a compact strip right above it
        final var inputWidth = Math.max(20, screenWidth - sidebarWidth - 2 - 2 - 2);
        final var inputRows = Math.min(8, 2 + visualRows(state.input, inputWidth));
        final var queueRows = Math.min(3, state.pendingPrompts.size());
        final var bottomConstraints = new ArrayList<Constraint>();
        bottomConstraints.add(Constraint.fill());
        if (queueRows > 0) {
            bottomConstraints.add(Constraint.length(queueRows));
        }
        bottomConstraints.add(Constraint.length(inputRows));
        bottomConstraints.add(Constraint.length(1));
        bottomConstraints.add(Constraint.length(statusStripRows(state)));
        // the tab strip is always present: with a single tab it carries the
        // "+" affordance; with several, the active one is highlighted and
        // background tabs keep a busy marker so a running turn stays visible
        final var rootConstraints = new ArrayList<Constraint>(bottomConstraints.size() + 1);
        rootConstraints.add(Constraint.length(1));
        rootConstraints.addAll(bottomConstraints);
        final var root = Layout.vertical()
                .constraints(rootConstraints.toArray(Constraint[]::new))
                .split(frame.area());
        renderTabBar(root.getFirst(), frame);
        final var body = Layout.horizontal()
                .constraints(Constraint.fill(), Constraint.length(sidebarWidth))
                .split(root.get(1));
        if (!narrow) {
            renderSidebar(body.get(1), frame, state);
        }
        var chat = accentGutter(body.get(0), frame);
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
        // the copy-safe zone is the chat body: chat already excludes the accent gutter
        // (column 0) and the sidebar/todos; +2 skips the per-region left bar ("│ "/"┃ ")
        state.copyZone = new Rect(chat.x() + 2, chat.y(), Math.max(1, chat.width() - 2), Math.max(1, chat.height()));
        var bottomIndex = 2;
        if (queueRows > 0) {
            renderQueue(root.get(bottomIndex++), frame, state);
        }
        renderInput(root.get(bottomIndex), frame, state);
        renderCompletionPopup(root.get(bottomIndex), frame, state);
        bottomIndex++;
        renderCalling(root.get(bottomIndex++), frame, state);
        renderStatus(root.get(bottomIndex), frame, state, narrow);
        if (state.searchOpen) {
            renderSearchBar(frame, state);
        }
        if (state.shortcutsOpen) {
            renderShortcutsOverlay(frame, state);
        }
        if (state.subagentsOverlay != null) {
            renderSubagentsOverlay(frame, state);
        }
        if (tabManager.isListOpen()) {
            renderTabsListOverlay(frame);
        }
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
        if (state.toolOverlay != null) {
            renderToolOverlay(frame, state);
        }
        if (state.errorDetail != null && state.errorOpen) {
            renderErrorDetail(frame, state);
        }
        if (state.btw != null) {
            renderBtw(frame, state);
        }
        renderToast(frame, state);
        state.selection.highlight(frame.buffer(), copyZones(state));
        state.screenGrid = SelectionState.snapshot(frame.buffer());
    }

    private Style modelNameStyle(final String model) {
        final var style =
                modelStyleService.resolve(chatClient.currentProvider(), model, !"light".equalsIgnoreCase(theme));
        final var base = Style.EMPTY.fg(Color.hex(style.hex()));
        return style.bold() ? base.bold() : base;
    }

    /**
     * Renders the session tab strip: one cell per open tab, the active one in the
     * accent color, a spinner-like dot while a background turn runs. The hit rects
     * are kept so a mouse press can switch tabs.
     */
    private void renderTabBar(final Rect rect, final dev.tamboui.terminal.Frame frame) {
        tabRects.clear();
        tabCloseRects.clear();
        final var open = tabManager.list();
        if (open.isEmpty() || rect.isEmpty()) {
            return;
        }
        final var muted = Style.EMPTY.fg(Color.hex(palette.textMuted()));
        final var activeStyle = Style.EMPTY.fg(Color.hex(palette.accent())).bold();
        // reserve 2 columns for the trailing "+" affordance so it always fits,
        // even with a single open tab
        final var width = Math.max(4, Math.min(28, (rect.width() - 2) / open.size() - 1));
        var x = rect.x();
        for (var i = 0; i < open.size(); i++) {
            final var state = open.get(i);
            final var selected = i == tabManager.activeTab();
            // the close marker reserves its columns before the title is ellipsized, so it never truncates
            final var closeable = width >= 6;
            final var body = " " + tabStatusIcon(state) + " " + tabTitle(state) + " ";
            // a padded label always fills the cell exactly: Paragraph aligns by
            // width, so a shorter line would drift away from the close zone
            final var title = closeable ? ellipsize(body, width - 2) : ellipsize(body, width);
            final var label = closeable ? title + " ".repeat(width - 2 - title.length()) + glyphs.cross() + " " : title;
            final var cell = new Rect(x, rect.y(), Math.min(width, rect.x() + rect.width() - x), rect.height());
            if (cell.width() > 0) {
                tabRects.add(cell);
                if (closeable) {
                    tabCloseRects.add(new Rect(cell.x() + cell.width() - 2, cell.y(), 2, cell.height()));
                }
                frame.renderWidget(
                        Paragraph.builder()
                                .text(Text.from(Line.from(Span.styled(label, selected ? activeStyle : muted))))
                                .build(),
                        cell);
                x += width;
            }
        }
        // trailing "+" affordance: clicking it opens a new tab
        tabAddRect = x + 2 <= rect.x() + rect.width() ? new Rect(x, rect.y(), 2, rect.height()) : null;
        if (tabAddRect != null) {
            frame.renderWidget(
                    Paragraph.builder()
                            .text(Text.from(Line.from(Span.styled(" +", muted))))
                            .build(),
                    tabAddRect);
        }
    }

    /**
     * The tab-bar status icon, most urgent first: a pending approval, an agent
     * question, a running turn, the last completed turn, then plain ready.
     * Symbols come from the {@link Glyphs} catalog so limited terminals can
     * fall back to ASCII ({@code tui.glyphs=ascii}).
     */
    private String tabStatusIcon(final UiState state) {
        if (state.approval != null) {
            return "!";
        }
        if (state.feedbackPrompt != null) {
            return "?";
        }
        if (state.model.busy() || state.wait != null) {
            return glyphs.running();
        }
        if (state.turnCompleted) {
            return glyphs.check();
        }
        return "-";
    }

    private String tabTitle(final UiState state) {
        final var title = state.title == null || state.title.isBlank() ? null : state.title.strip();
        final var agent = state.session.activeAgent();
        return title != null ? title : agent != null && !agent.isBlank() ? agent : "new session";
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
        final var model = chatClient.model(state.session.chatSettings());
        text.add(Line.from(
                Span.styled("model ", label), Span.styled(ellipsize(model, width - 6), modelNameStyle(model))));
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
        text.add(Line.from(Span.styled("session ", label), Span.styled(ellipsize(state.sessionId, width - 8), value)));
        // the mode and permissions each get their own row: mode is always visible with
        // the mode-colored value, permissions shows the approval mode (all -> red)
        final var permissions = state.session.runtime().permissions();
        text.add(Line.from(
                Span.styled("mode ", label),
                Span.styled(permissions.mode().name().toLowerCase(Locale.ROOT), Style.EMPTY.fg(modeColor()))));
        text.add(Line.from(
                Span.styled("permissions ", label),
                Span.styled(
                        permissions.isPermitAll()
                                ? "all ⚠"
                                : permissions.permissionMode().name().toLowerCase(Locale.ROOT),
                        permissions.isPermitAll() ? Style.EMPTY.fg(Color.hex(palette.permitAll())) : value)));
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
        if (state.session.runtime().usage().requests() > 0) {
            text.add(Line.from(
                    Span.styled("llm calls  ", label),
                    Span.styled(Long.toString(state.session.runtime().usage().requests()), value)));
            // optional session cost estimate, only shown when pricing is configured
            final var cost = costEstimator.cost(model, state.model.promptTokens(), state.model.completionTokens());
            cost.ifPresent(c -> text.add(Line.from(
                    Span.styled("cost ~     ", label), Span.styled(String.format(Locale.ROOT, "$%.4f", c), value))));
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
        final var rows = new ArrayList<String>();
        final var toolTops = new ArrayList<UiState.ToolTop>();
        final var footerLinks = new ArrayList<UiState.FooterLink>();
        final var chunks = chunks(
                state.model.markdown(spinnerFrame(SpinnerStyle.SQUARE_CORNERS, state.spinnerTick), WELCOME),
                inner.width(),
                state,
                toolTops,
                footerLinks,
                rows);
        state.flatRows = rows;
        final var total = chunks.stream().mapToInt(RenderedChunk::height).sum();
        final var maxViewOffset = Math.max(0, total - Math.max(1, inner.height()));
        state.maxViewOffset = maxViewOffset;
        state.model.maxViewOffset(maxViewOffset);
        if (state.pendingJumpRow >= 0) {
            // jump (search match or Alt+Up/Down tool navigation): bring the target row
            // into view with a third of a screen of context above it
            state.model.anchorRow(Math.clamp(state.pendingJumpRow - Math.max(1, inner.height() / 3), 0, maxViewOffset));
            // an explicit jump counts as user navigation: pin the view there
            state.model.userScrolled(state.model.anchorRow() < maxViewOffset);
            state.pendingJumpRow = -1;
        } else if (!state.model.userScrolled()) {
            // auto-follow: glue the view to the live tail. the anchored row may re-pin
            // to maxViewOffset as the bottom grows while streaming
            state.model.anchorRow(maxViewOffset);
        } else {
            // user browsing: keep the absolute anchor stable even as content grows below;
            // clamp if the content shrank, and reaching the bottom re-arms auto-follow
            state.model.anchorRow(Math.min(state.model.anchorRow(), maxViewOffset));
            if (state.model.anchorRow() >= maxViewOffset) {
                state.model.userScrolled(false);
            }
        }
        final var viewOffset = state.model.anchorRow();
        // expose tool card header hit areas for mouse clicks and Alt+Up/Down navigation.
        // rows are absolute transcript rows (not screen rows) so every card gets an
        // anchor even when scrolled out of view: navigation must reach off-screen cards
        final var anchors = new ArrayList<UiState.ToolAnchor>();
        for (final var top : toolTops) {
            anchors.add(new UiState.ToolAnchor(
                    top.ordinal(),
                    new Rect(inner.x(), inner.y() + top.row() - viewOffset, inner.width(), 1),
                    top.row()));
        }
        state.toolAnchors = anchors;
        // "click to expand" links of truncated tool cards: screen hit-areas, only when on screen
        // (these are click targets, unlike anchors, so visibility filtering is correct here)
        final var links = new ArrayList<UiState.ToolAnchor>();
        for (final var link : footerLinks) {
            final var y = inner.y() + link.row() - viewOffset;
            if (y >= inner.y() && y < inner.y() + inner.height()) {
                links.add(new UiState.ToolAnchor(link.ordinal(), new Rect(inner.x(), y, inner.width(), 1), link.row()));
            }
        }
        state.toolFooterLinks = links;
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
        // search match highlighting: reversed strips over matched rows, same buffer
        // trick as the scrollbar indicator (no span surgery needed)
        if (state.search.active() && !state.search.matches().isEmpty()) {
            final var matches = new java.util.HashSet<>(state.search.matches());
            var top = 0;
            for (final var chunk : chunks) {
                for (var r = 0; r < chunk.height(); r++) {
                    if (!matches.contains(top + r)) {
                        continue;
                    }
                    final var y = inner.y() + top + r - viewOffset;
                    if (y >= inner.y() && y < inner.y() + inner.height()) {
                        frame.buffer().setStyle(new Rect(inner.x(), y, inner.width(), 1), Style.EMPTY.reversed());
                    }
                }
                top += chunk.height();
            }
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
                            .text(Text.from(Line.from(
                                    Span.styled(glyphs.caret(), Style.EMPTY.fg(Color.hex(palette.accent()))))))
                            .build(),
                    caretRect);
        }
    }

    private record RenderedChunk(int height, java.util.function.IntFunction<dev.tamboui.widget.Widget> supplier) {
        dev.tamboui.widget.Widget widget(final int skip) {
            return supplier.apply(skip);
        }
    }

    /** Everything that, when unchanged, lets a frame reuse the previous chunk render. */
    private record ContentKey(
            String md,
            int width,
            boolean showThinking,
            ToolOutputVisibility showToolActions,
            long toolOutputs,
            int collapsedToolsHash,
            int paletteId) {}

    private record ContentResult(
            List<RenderedChunk> chunks,
            List<String> rows,
            List<UiState.ToolTop> toolTops,
            List<UiState.FooterLink> footerLinks) {}

    private List<RenderedChunk> chunks(
            final String md,
            final int width,
            final UiState state,
            final List<UiState.ToolTop> toolTops,
            final List<UiState.FooterLink> footerLinks,
            final List<String> rows) {
        final var out = new ArrayList<RenderedChunk>();
        contentChunks(out, md, width, state, toolTops, footerLinks, rows);
        final var contentWidth = Math.max(1, width - 2); // room for the per-region left bar
        // live (not yet flushed) thinking streams inline on the thought panel; it
        // grows every tick so it stays outside the chunk memo (spinner-sensitive)
        if (state.showThinking && state.model.thinkingLength() > 0) {
            final var live = state.model.thinkingText().stripLeading();
            if (!live.isEmpty()) {
                addThoughtChunk(out, liveThoughtLines(live, contentWidth), rows);
            }
        }
        // in-pane activity indicator: shown even when thoughts are hidden so a
        // reasoning round never looks like a hang
        if (state.model.thinkingLength() > 0) {
            addThinkingIndicator(out, state, rows);
        }
        return out;
    }

    /**
     * Builds the stable chunk list (flushed regions only) with a single-entry memo:
     * unless the markdown, width or any mutable render input changed since the last
     * frame, the previous lists are reused verbatim. The live thinking tail and the
     * spinner indicator are appended by {@link #chunks(String, int, UiState, List, List, List)}
     * outside the cache because they change every tick.
     */
    private void contentChunks(
            final List<RenderedChunk> out,
            final String md,
            final int width,
            final UiState state,
            final List<UiState.ToolTop> toolTops,
            final List<UiState.FooterLink> footerLinks,
            final List<String> rows) {
        final var key = new ContentKey(
                md,
                width,
                state.showThinking,
                state.showToolActions,
                state.toolOutputs.size() + state.toolOutputs.latestOrdinal(),
                state.collapsedTools.hashCode(),
                System.identityHashCode(palette));
        final ContentResult cached;
        synchronized (contentLock) {
            cached = lastContentKey != null && lastContentKey.equals(key) ? lastContent : null;
        }
        if (cached != null) {
            out.addAll(cached.chunks());
            rows.addAll(cached.rows());
            toolTops.addAll(cached.toolTops());
            footerLinks.addAll(cached.footerLinks());
            return;
        }
        final var contentWidth = Math.max(1, width - 2); // room for the per-region left bar
        final var local = new ArrayList<RenderedChunk>();
        final var localRows = new ArrayList<String>();
        final var localToolTops = new ArrayList<UiState.ToolTop>();
        final var localFooterLinks = new ArrayList<UiState.FooterLink>();
        for (final var region : TuiTranscriptModel.regions(md)) {
            if (region.thought()) {
                if (!state.showThinking) {
                    continue;
                }
                addThoughtChunk(local, thoughtLines(region.text(), contentWidth), localRows);
            } else if (region.tool()) {
                addToolCardChunk(local, region.text(), width, state, localToolTops, localFooterLinks, localRows);
            } else if (!region.text().isBlank()) {
                appendChunks(local, region.text(), contentWidth, state, localRows);
            }
        }
        final var result = new ContentResult(
                List.copyOf(local), List.copyOf(localRows), List.copyOf(localToolTops), List.copyOf(localFooterLinks));
        synchronized (contentLock) {
            lastContentKey = key;
            lastContent = result;
        }
        out.addAll(result.chunks());
        rows.addAll(result.rows());
        toolTops.addAll(result.toolTops());
        footerLinks.addAll(result.footerLinks());
    }

    /**
     * Renders a thought region (flushed or live) italicized behind the thin bar. Prose is
     * rendered by the markdown engine on the italic thought base; fenced code blocks are
     * split and syntax-highlighted like normal output, then every token span is italicized
     * (keeping its token color) so code stays readable and colour-coded inside thoughts. The
     * flushed marker text carries a bold `**Thinking**` header mimicking a speaker heading -
     * it is stripped and replaced by the lowercase panel label below.
     */
    private List<Line> thoughtLines(final String text, final int width) {
        var source = text.stripLeading();
        if (source.startsWith("**Thinking**")) {
            source = source.substring("**Thinking**".length()).stripLeading();
        }
        final var lines = new ArrayList<Line>();
        lines.add(Line.from(thoughtBar(), Span.styled("thinking", thoughtStyle)));
        appendThoughtBody(lines, source, width);
        return lines;
    }

    /** Renders one thought source region (prose or fenced code) into existing output lines. */
    private void appendThoughtBody(final List<Line> lines, final String source, final int width) {
        for (final var seg : syntaxHighlighter.split(source)) {
            if (seg.type() == SyntaxHighlighter.Segment.Type.CODE) {
                final var highlighted = MarkdownLines.wrapCode(
                        italicizeCode(syntaxHighlighter.highlightLines(seg.language(), seg.text())), width);
                lines.addAll(withBar(highlighted, thoughtBar()));
            } else if (!seg.text().isBlank()) {
                lines.addAll(withBar(
                        transcriptRenderer.renderRegion(seg.text(), width, thoughtStyle, MarkdownStyles.DEFAULTS),
                        thoughtBar()));
            }
        }
    }

    /**
     * Renders the live (still streaming) thinking tail. The growing text is cut at the last
     * safe (non-fence) newline: the stable prefix is rendered once and memoized, and only the
     * still-open suffix is re-rendered each tick. Falls back to the plain whole-region render
     * until the stream produced a first complete line.
     */
    private List<Line> liveThoughtLines(final String text, final int width) {
        var live = text.stripLeading();
        if (live.startsWith("**Thinking**")) {
            live = live.substring("**Thinking**".length()).stripLeading();
        }
        final var cut = transcriptRenderer.lastSafeNewline(live);
        if (cut == 0) {
            return thoughtLines(live, width);
        }
        final var prefix = live.substring(0, cut);
        final var suffix = live.substring(cut);
        if (lastLiveThoughtLines == null
                || !prefix.equals(lastLiveThoughtPrefix)
                || width != lastLiveThoughtWidth
                || thoughtStyle != lastLiveThoughtStyle) {
            lastLiveThoughtPrefix = prefix;
            lastLiveThoughtWidth = width;
            lastLiveThoughtStyle = thoughtStyle;
            lastLiveThoughtLines = thoughtLines(prefix, width);
        }
        final var out = new ArrayList<Line>(lastLiveThoughtLines.size() + 16);
        out.addAll(lastLiveThoughtLines);
        appendThoughtBody(out, suffix, width);
        return out;
    }

    /** Applies the thought italic to every span of a highlighted code block, preserving token colors. */
    private List<Line> italicizeCode(final List<Line> lines) {
        final var out = new ArrayList<Line>(lines.size());
        for (final var line : lines) {
            final var spans = new ArrayList<Span>(line.spans().size());
            for (final var span : line.spans()) {
                spans.add(span.patchStyle(thoughtCodeStyle));
            }
            out.add(Line.from(spans));
        }
        return out;
    }

    private void appendChunks(
            final List<RenderedChunk> out,
            final String md,
            final int width,
            final UiState state,
            final List<String> rows) {
        for (final var seg : syntaxHighlighter.split(md)) {
            if (seg.type() == SyntaxHighlighter.Segment.Type.TEXT) {
                for (final var turn : TuiTranscriptModel.splitTurns(seg.text())) {
                    if (turn.text().isBlank()) {
                        continue;
                    }
                    final var lines = withBar(
                            transcriptRenderer.renderRegion(turn.text(), width, Style.EMPTY, MarkdownStyles.DEFAULTS),
                            turn.user() ? userBar() : outputBar());
                    addMarkdownChunk(out, lines, rows);
                }
            } else {
                final var lang = seg.language();
                final var text = syntaxHighlighter.highlight(lang, seg.text());
                // wrap at box inner width minus the code block's horizontal padding
                final var wrapped = MarkdownLines.wrapCode(text.lines(), Math.max(1, width - 4));
                final var wrappedText = Text.from(wrapped);
                final var boxed = codeBlock(lang);
                final var innerH = Math.max(wrappedText.height(), 1);
                state.lastCodeBlock = seg.text();
                for (final var line : wrapped) {
                    rows.add(plainText(line));
                }
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

    private void addMarkdownChunk(final List<RenderedChunk> out, final List<Line> lines, final List<String> rows) {
        final var innerH = Math.max(lines.size(), 1);
        final var text = Text.from(lines);
        for (final var line : lines) {
            rows.add(plainText(line));
        }
        out.add(new RenderedChunk(
                innerH,
                skip -> Paragraph.builder()
                        .text(text)
                        .overflow(Overflow.CLIP)
                        .scroll(Math.clamp(innerH - 1, 0, skip))
                        .build()));
    }

    /** Concatenated span contents of a line (used for transcript search). */
    private static String plainText(final Line line) {
        final var sb = new StringBuilder();
        for (final var span : line.spans()) {
            sb.append(span.content());
        }
        return sb.toString();
    }

    /**
     * Like {@link #addMarkdownChunk} but paints the thought panel: a subtle full-width
     * info background tint (pre-blended solid color - terminals have no alpha) that
     * categorically separates reasoning from conversation output.
     */
    private void addThoughtChunk(final List<RenderedChunk> out, final List<Line> lines, final List<String> rows) {
        final var innerH = Math.max(lines.size(), 1);
        final var text = Text.from(lines);
        for (final var line : lines) {
            rows.add(plainText(line));
        }
        out.add(new RenderedChunk(innerH, skip -> {
            final var paragraph = Paragraph.builder().text(text).overflow(Overflow.CLIP);
            if (thoughtBg != null) {
                paragraph.background(thoughtBg);
            }
            return paragraph.scroll(Math.clamp(innerH - 1, 0, skip)).build();
        }));
    }

    /** One-row in-pane "thinking..." activity line shown while a reasoning round runs. */
    private void addThinkingIndicator(final List<RenderedChunk> out, final UiState state, final List<String> rows) {
        final var secs = state.thinkingStartedAtMillis < 0
                ? 0
                : Math.max(0, (clock.millis() - state.thinkingStartedAtMillis) / 1000);
        final var line = Line.from(
                thoughtBar(),
                Span.styled(
                        spinnerFrame(SpinnerStyle.SQUARE_CORNERS, state.spinnerTick) + " thinking... (" + secs + " s)",
                        thoughtBarStyle));
        addThoughtChunk(out, List.of(line), rows);
    }

    /** Renders a finished tool action as a bordered "card" with a status icon and diff-aware output. */
    private void addToolCardChunk(
            final List<RenderedChunk> out,
            final String regionText,
            final int width,
            final UiState state,
            final List<UiState.ToolTop> toolTops,
            final List<UiState.FooterLink> footerLinks,
            final List<String> rows) {
        final var card = ToolCard.parse(regionText);
        if (state.showToolActions == ToolOutputVisibility.HIDDEN) {
            // hidden mode (Ctrl+Y): render nothing at all for tool blocks
            return;
        }
        final var collapsed = card.ordinal() >= 0 && state.collapsedTools.contains(card.ordinal());
        if (state.showToolActions == ToolOutputVisibility.COLLAPSED || collapsed) {
            // collapsed mode (Ctrl+Y) or per-card collapse: keep a single dim summary line
            final var top = out.stream().mapToInt(RenderedChunk::height).sum();
            if (card.ordinal() >= 0) {
                toolTops.add(new UiState.ToolTop(card.ordinal(), top));
            }
            final var line = hiddenToolLine(card);
            rows.add(plainText(line));
            out.add(new RenderedChunk(
                    1,
                    skip -> Paragraph.builder()
                            .text(Text.from(line))
                            .overflow(Overflow.CLIP)
                            .build()));
            return;
        }
        final var top = out.stream().mapToInt(RenderedChunk::height).sum();
        if (card.ordinal() >= 0) {
            toolTops.add(new UiState.ToolTop(card.ordinal(), top));
        }
        final var statusColor = palette.color(card.failed() ? palette.error() : palette.success());
        final var blockBuilder = Block.builder()
                .borders(Borders.ALL)
                .borderType(BorderType.ROUNDED)
                .borderColor(statusColor)
                .title(blockTitle(card));
        if (toolBg != null) {
            blockBuilder.background(toolBg);
        }
        final var block = blockBuilder.build();
        final var innerW = Math.max(1, width - 2);
        final var muted = Style.EMPTY.fg(Color.hex(palette.textMuted()));
        final var lines = new ArrayList<Line>();
        final var headerSpans = new ArrayList<Span>();
        final var icon = card.failed() ? glyphs.cross() : card.running() ? glyphs.running() : glyphs.check();
        headerSpans.add(Span.styled(icon + " ", Style.EMPTY.fg(statusColor).bold()));
        headerSpans.add(Span.styled(card.name(), Style.EMPTY.bold().fg(Color.hex(palette.text()))));
        if (card.ordinal() >= 0) {
            headerSpans.add(Span.styled(" #" + card.ordinal(), muted));
        }
        if (!card.args().isBlank()) {
            headerSpans.add(Span.styled((card.failed() ? "  " : " ") + card.args(), muted));
        }
        lines.add(Line.from(headerSpans.toArray(Span[]::new)));
        if (!card.output().isBlank()) {
            lines.add(Line.empty());
            // ask_user answers read better as key: value pairs than raw JSON
            var outputLines = toolBodyLines(card.name(), null, card.output(), innerW, card.isDiff(diffRenderer));
            // keep the card compact: a short inline preview, the rest lives behind
            // the "click to expand" footer / Ctrl+O overlay
            final var clipped = outputLines.size() > MAX_INLINE_TOOL_LINES;
            if (clipped) {
                outputLines = new ArrayList<>(outputLines.subList(0, MAX_INLINE_TOOL_LINES));
            }
            for (final var line : outputLines) {
                rows.add(plainText(line));
            }
            lines.addAll(outputLines);
            // point at the full output when the preview was cut (line cap or transcript cap)
            final var full = card.ordinal() >= 0 ? state.toolOutputs.get(card.ordinal()) : null;
            final var transcriptCapped =
                    full != null && full.output().length() > card.output().length();
            if (clipped || transcriptCapped) {
                // link-styled footer: click it (or Ctrl+O) to expand the full output
                lines.add(Line.from(Span.styled(
                        glyphs.more() + " click to expand full output (Ctrl+O) ",
                        Style.EMPTY.fg(Color.hex(palette.accent())).underlined())));
                rows.add(glyphs.more() + " click to expand full output (Ctrl+O) ");
                // content starts one row below the card top border
                footerLinks.add(new UiState.FooterLink(card.ordinal(), top + 1 + lines.size() - 1));
            }
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

    private String blockTitle(final ToolCard card) {
        if (card.failed()) {
            return " failed ";
        }
        if (card.running()) {
            return " running ";
        }
        final var duration = card.elapsedMs() > 0 ? card.elapsedMs() + " ms" : "done";
        return card.ordinal() >= 0 ? " #" + card.ordinal() + " - " + duration + " " : " " + duration + " ";
    }

    /** Single dim summary line shown for a tool action when tool blocks are hidden. */
    private Line hiddenToolLine(final ToolCard card) {
        final var statusColor = palette.color(card.failed() ? palette.error() : palette.success());
        final var label = new StringBuilder();
        if (card.running()) {
            // live cards carry the raw command as name: show its significant part
            label.append(condensedCommand(card));
        } else {
            label.append(card.name());
            if (card.ordinal() >= 0) {
                label.append(" #").append(card.ordinal());
            }
            final var key = condensedCommand(card);
            if (!key.isBlank()) {
                label.append(" - ").append(key);
            }
        }
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
                        (card.failed() ? glyphs.cross() : card.running() ? glyphs.running() : glyphs.check()) + " ",
                        Style.EMPTY.fg(statusColor).bold()),
                Span.styled(label.toString(), Style.EMPTY.fg(Color.hex(palette.textMuted()))));
    }

    /**
     * The significant command of a tool card for the condensed rendering, with
     * read/write/edit file paths shortened to their cwd-relative form so the
     * working-directory prefix does not eat the whole line. The shortening is
     * applied before the display clip so long absolute paths stay relative.
     */
    private String condensedCommand(final ToolCard card) {
        if (!card.running()
                && !card.failed()
                && ("read_file".equals(card.name())
                        || "write_file".equals(card.name())
                        || "edit_file".equals(card.name()))) {
            return card.keyCommand(TuiCommand::shortenToRelative);
        }
        return card.keyCommand();
    }

    /** Converts an absolute path under the working directory into a cwd-relative one; passthrough otherwise. */
    private static String shortenToRelative(final String path) {
        try {
            final var cwd =
                    Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
            final var candidate = Path.of(path).toAbsolutePath().normalize();
            if (candidate.startsWith(cwd)) {
                return cwd.relativize(candidate).toString();
            }
        } catch (final RuntimeException e) {
            // not a path: keep the raw significant command
        }
        return path;
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
                .borderColor(palette.color(palette.border()))
                .padding(Padding.symmetric(0, 2))
                .build();
    }

    /**
     * Bold accent bar prefixing assistant output lines so the main thread of the
     * conversation reads distinctly from user turns (primary bar), thoughts (thin info
     * bar) and tool cards (status-colored borders).
     */
    private Span outputBar() {
        return Span.styled("┃ ", palette.bold(palette.accent()));
    }

    /** Bold primary bar prefixing user ("You") turns. */
    private Span userBar() {
        return Span.styled("┃ ", palette.bold(palette.primary()));
    }

    /**
     * Thin bar prefixing reasoning/thought lines, visually secondary to the output bars:
     * clearly visible info hue (mono: plain) so the thought panel reads as one block.
     */
    private Span thoughtBar() {
        return Span.styled("│ ", thoughtBarStyle);
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
                .borderStyle(palette.style(palette.accent()))
                .build();
        frame.renderWidget(block, rect);
        final var inner = block.inner(rect);
        if (inner.isEmpty()) {
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
        state.inputZone = inner;
        if (inner.isEmpty()) {
            return;
        }
        final var width = inner.width();
        if (state.input.text().isEmpty()) {
            final var style = Style.EMPTY.fg(Color.hex(palette.textMuted()));
            frame.renderWidget(
                    Paragraph.builder()
                            .text(Text.from(Line.from(Span.styled(
                                    state.model.busy() ? "waiting for the agent..." : "type a prompt, Enter to send",
                                    style))))
                            .overflow(Overflow.CLIP)
                            .build(),
                    inner);
            return;
        }
        // soft-wrap each logical line to display width; vertical rows follow the
        // wrapped fragments so long pasted lines grow the text area instead of
        // scrolling horizontally
        final var visual = new ArrayList<Line>();
        final var cursor = new int[] {-1, -1};
        for (var row = 0; row < state.input.lineCount(); row++) {
            final var fragment = wrappedFragments(state.input.getLine(row), width);
            // cursorCol is relative to this row's logical line, so the wrapped
            // fragment offsets reset at each row; track the char offset accumulated
            // so far to place the reversed-cursor on the fragment that actually
            // holds the logical column
            var charStart = 0;
            for (var f = 0; f < fragment.size(); f++) {
                final var frag = fragment.get(f);
                visual.add(Line.from(Span.styled(frag, textInputStyle())));
                if (row == state.input.cursorRow() && cursor[0] < 0) {
                    final var col = state.input.cursorCol();
                    final var fragEnd = charStart + frag.length();
                    if (col <= fragEnd || f == fragment.size() - 1) {
                        cursor[0] = visual.size() - 1;
                        final var within = Math.max(0, Math.min(col, fragEnd) - charStart);
                        // the caret column is relative to the fragment: each wrapped
                        // fragment is rendered as a new visual line at the same left
                        // edge, so the accumulated display offset must not shift it
                        cursor[1] = CharWidth.of(frag.substring(0, within));
                    }
                    charStart = fragEnd;
                }
            }
        }
        final var visible = inner.height();
        final var maxScroll = Math.max(0, visual.size() - visible);
        final var baseScroll = Math.clamp(state.input.scrollRow(), 0, maxScroll);
        // keep the cursor row within the viewport
        final var scroll = Math.clamp(Math.min(baseScroll, cursor[0] - visible + 1), 0, maxScroll);
        frame.renderWidget(
                Paragraph.builder()
                        .text(Text.from(visual.stream().skip(scroll).toList().toArray(Line[]::new)))
                        .overflow(Overflow.CLIP)
                        .build(),
                inner);
        // cursor: within-fragment column, clamped to width; a caret at the very end
        // of a full fragment (col == width) lands on the padding cell next to the
        // border and stays visible
        final var rel = cursor[0] - scroll;
        if (rel >= 0 && rel < visible && cursor[1] <= width) {
            frame.buffer().setStyle(new Rect(inner.x() + cursor[1], inner.y() + rel, 1, 1), Style.EMPTY.reversed());
        }
    }

    private Style textInputStyle() {
        return Style.EMPTY.fg(Color.hex(palette.text()));
    }

    private static List<String> wrappedFragments(final String line, final int width) {
        if (width <= 0 || line.isEmpty()) {
            return List.of(line);
        }
        final var out = new ArrayList<String>();
        var rem = line;
        while (CharWidth.of(rem) > width) {
            final var piece = CharWidth.substringByWidth(rem, width);
            out.add(piece);
            rem = rem.substring(CharWidth.of(piece) == 0 ? 1 : piece.length());
        }
        out.add(rem);
        return out;
    }

    /** Number of visual (wrapped) rows the current input needs for the given inner width. */
    private static int visualRows(final TextAreaState area, final int width) {
        if (width <= 0) {
            return Math.max(1, area.lineCount());
        }
        var rows = 0;
        for (var i = 0; i < area.lineCount(); i++) {
            rows += wrappedFragments(area.getLine(i), width).size();
        }
        return Math.max(1, rows);
    }

    /** One soft-wrapped fragment of the input buffer plus its position in the logical line. */
    private record VisualRow(int row, int charStart, int displayStart, String fragment) {}

    /** Absolute buffer position of the edit caret after a click inside the prompt input. */
    private record CaretAt(int row, int col) {}

    /**
     * Flattens the input buffer into its visual rows, one entry per soft-wrapped
     * fragment (in rendering order), so click mapping and vertical caret motion
     * use the exact same geometry as {@link #renderInput(Rect, Frame, UiState)}.
     */
    private static List<VisualRow> visualGrid(final TextAreaState area, final int width) {
        final var grid = new ArrayList<VisualRow>();
        for (var row = 0; row < area.lineCount(); row++) {
            final var fragments = wrappedFragments(area.getLine(row), width);
            var charStart = 0;
            var displayStart = 0;
            for (final var fragment : fragments) {
                grid.add(new VisualRow(row, charStart, displayStart, fragment));
                charStart += fragment.length();
                displayStart += CharWidth.of(fragment);
            }
        }
        return grid;
    }

    /**
     * Global visual-row index of the fragment holding the caret, using the same
     * {@code col <= fragEnd || last fragment} boundary rule as the renderer.
     */
    private static int visualRowOfCaret(final TextAreaState area, final int width) {
        var visual = 0;
        for (var row = 0; row < area.lineCount(); row++) {
            final var line = area.getLine(row);
            final var fragments = wrappedFragments(line, width);
            if (row == area.cursorRow()) {
                final var col = area.cursorCol();
                var start = 0;
                for (var f = 0; f < fragments.size(); f++) {
                    if (col <= start + fragments.get(f).length() || f == fragments.size() - 1) {
                        return visual + f;
                    }
                    start += fragments.get(f).length();
                }
                return visual;
            }
            visual += fragments.size();
        }
        return visual;
    }

    /** Display column of the caret measured from the start of its logical line. */
    private static int caretDisplayCol(final TextAreaState area) {
        final var line = area.getLine(area.cursorRow());
        return CharWidth.of(line.substring(0, Math.min(area.cursorCol(), line.length())));
    }

    /**
     * Moves the caret to the given visual row, preserving the current display
     * column (clamped to the target fragment, like a GUI editor).
     */
    private static void moveCursorToVisual(final UiState state, final List<VisualRow> grid, final int targetVisual) {
        final var target = grid.get(targetVisual);
        final var dc = caretDisplayCol(state.input);
        final var within =
                Math.max(0, TuiInputSupport.columnAtDisplayWidth(target.fragment(), dc - target.displayStart()));
        applyCursor(
                state,
                target.row(),
                target.charStart() + Math.min(within, target.fragment().length()));
    }

    /** Maps a screen point inside the input zone to the absolute (row, col) of the edit caret. */
    private static CaretAt inputCaretAt(final UiState state, final int mx, final int my) {
        final var inner = state.inputZone;
        final var width = inner.width();
        final var grid = visualGrid(state.input, width);
        final var visible = inner.height();
        final var maxScroll = Math.max(0, grid.size() - visible);
        final var baseScroll = Math.clamp(state.input.scrollRow(), 0, maxScroll);
        final var scroll =
                Math.clamp(Math.min(baseScroll, visualRowOfCaret(state.input, width) - visible + 1), 0, maxScroll);
        final var visualIndex = (my - inner.y()) + scroll;
        if (visualIndex >= grid.size()) {
            final var last = grid.get(grid.size() - 1);
            return new CaretAt(last.row(), last.charStart() + last.fragment().length());
        }
        final var target = grid.get(visualIndex);
        final var within = TuiInputSupport.columnAtDisplayWidth(target.fragment(), mx - inner.x());
        return new CaretAt(target.row(), target.charStart() + within);
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
                final Line line;
                if (state.model.thinkingLength() > 0) {
                    // pinned "thinking" progress line with elapsed time, stays at the
                    // bottom while the reasoning streams inline above (timer lifecycle
                    // is managed once per frame in render())
                    final var secs = Math.max(0, (clock.millis() - state.thinkingStartedAtMillis) / 1000);
                    line = Line.from(Span.styled(
                            spinnerFrame(SpinnerStyle.SQUARE_CORNERS, state.spinnerTick) + " thinking (" + secs + " s)",
                            palette.blended(palette.info(), 0.35)));
                } else {
                    line = Line.from(Span.styled(
                            spinnerFrame(SpinnerStyle.DOTS, state.spinnerTick) + " agent is working...", muted));
                }
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

    private void renderStatus(
            final Rect rect, final dev.tamboui.terminal.Frame frame, final UiState state, final boolean narrow) {
        final var statusColor = Color.hex(
                activeError(state) ? palette.error() : (state.model.busy() ? palette.warning() : palette.success()));
        final var muted = Style.EMPTY.fg(Color.hex(palette.textMuted()));
        final var parts = new java.util.ArrayList<Span>();
        final var frameStyle = state.model.runningTool() != null ? SpinnerStyle.SQUARE_CORNERS : activeWait(state);
        final var prefix = frameStyle == null ? "" : spinnerFrame(frameStyle, state.spinnerTick) + " ";
        parts.add(Span.styled(" " + prefix + state.model.status() + "  ", Style.EMPTY.fg(statusColor)));
        final var statusModel = chatClient.model(state.session.chatSettings());
        final var activeModel = ellipsize(statusModel, 40);
        final var activeVariant = chatClient.variant(state.session.chatSettings());
        final var variantSuffix =
                activeVariant == null || activeVariant.isBlank() ? " (default)" : " [" + activeVariant + "]";
        parts.add(Span.styled(modeWord() + " ", Style.EMPTY.fg(modeColor())));
        parts.add(Span.styled(activeModel + variantSuffix + "  ", modelNameStyle(statusModel)));
        parts.add(Span.styled(state.activeAgent.isBlank() ? "" : "agent " + state.activeAgent + "  ", muted));
        // narrow terminals have no sidebar: keep the essential context numbers in the strip
        if (narrow) {
            parts.add(Span.styled("  ctx " + state.model.contextPercent() + "%", muted));
            parts.add(Span.styled(
                    "  in " + formatTokens(state.model.promptTokens()) + " out "
                            + formatTokens(state.model.completionTokens()),
                    muted));
        }
        if (conf.bindings() == KeyBindingPreset.VIM) {
            parts.add(Span.styled("  [vim insert]  ", muted));
        }
        // Per-turn metrics (live from the session so /config metrics applies immediately)
        final var metrics = state.session.metrics();
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
        if (metrics.showSessionTotals() && state.session.runtime().usage().requests() > 0) {
            parts.add(
                    Span.styled(" · session: " + state.session.runtime().usage().requests() + " LLM calls  ", muted));
        }
        // the status strip reserves a right edge for the clickable "?" shortcuts affordance
        final var helpText = "  [Alt+?] ";
        final var helpW = Math.min(helpText.length(), Math.max(1, rect.width()));
        final var statusW = Math.max(1, rect.width() - helpW);
        // flexible informational messages (code graph refreshes, clipboard feedback, queue notices...)
        // trail the fixed segments: clipped with an ellipsis to the space left on the leading row
        // so they never wrap onto a second line nor push model/permission/metrics out
        if (state.notification != null && !state.notification.isBlank()) {
            final var fixedWidth = parts.stream().mapToInt(Span::width).sum();
            final var notifColor = activeError(state) ? palette.error() : palette.warning();
            final var notifPrefix = "  ⓘ ";
            final var remaining = statusW - fixedWidth - CharWidth.of(notifPrefix) - 2; // trailing "  "
            parts.add(Span.styled(
                    notifPrefix + ellipsize(state.notification, Math.max(8, remaining)) + "  ",
                    Style.EMPTY.fg(Color.hex(notifColor))));
        }
        // errors trail the metrics in error red: clipped with an ellipsis to the remaining row width so
        // the message never wraps nor pushes model/metrics out (full text + stack live behind Alt+E)
        if (state.errorMessage != null) {
            final var fixedWidth = parts.stream().mapToInt(Span::width).sum();
            final var errPrefix = "  ✗ ";
            final var remaining = statusW - fixedWidth - CharWidth.of(errPrefix) - 2; // trailing "  "
            parts.add(Span.styled(
                    errPrefix + ellipsize(state.errorMessage, Math.max(8, remaining)) + "  ",
                    Style.EMPTY.fg(Color.hex(palette.error()))));
        }
        final var line = Line.from(parts.toArray(Span[]::new));
        final var statusSplit = helpW >= rect.width()
                ? List.of(rect)
                : List.of(new Rect(rect.x(), rect.y(), statusW, 1), new Rect(rect.x() + statusW, rect.y(), helpW, 1));
        state.shortcutRect = statusSplit.size() == 2 ? statusSplit.get(1) : null;
        frame.renderWidget(
                Paragraph.builder()
                        .text(Text.from(line))
                        .overflow(dev.tamboui.style.Overflow.WRAP_WORD)
                        .build(),
                statusSplit.getFirst());
        if (state.shortcutRect != null) {
            frame.renderWidget(
                    Paragraph.builder()
                            .text(Text.from(Line.from(Span.styled(
                                    helpText,
                                    Style.EMPTY.fg(Color.hex(palette.accent())).bold()))))
                            .overflow(dev.tamboui.style.Overflow.CLIP)
                            .build(),
                    state.shortcutRect);
        }
        final var percent = state.model.contextPercent();
        final var compactionThreshold = compactionThreshold();
        if (percent >= compactionThreshold) {
            final var banner = Line.from(Span.styled(
                    "⚠  context at " + percent + "% (will compact at " + compactionThreshold
                            + "%) - consider /compact  ",
                    Style.EMPTY.fg(Color.hex(palette.warning()))));
            // the strip grows a row for this warning: draw it below the status line, not over it
            frame.renderWidget(
                    Paragraph.builder()
                            .text(Text.from(banner))
                            .overflow(dev.tamboui.style.Overflow.CLIP)
                            .build(),
                    new Rect(rect.x(), rect.y() + rect.height() - 1, rect.width(), 1));
        }
        if (state.errorMessage != null) {
            final var err = Line.from(Span.styled(
                    glyphs.cross() + "  " + state.errorMessage + "  (Alt+E for details) ",
                    Style.EMPTY.fg(Color.hex(palette.error()))));
            // the boxed banner lives below the single-row status line (row 0) so it never covers it:
            // the strip already grows a row for the status line on top of the box's own 3 rows
            final var bannerRect = new Rect(rect.x(), rect.y() + 1, rect.width(), Math.max(1, rect.height() - 1));
            final var errBlock = Block.builder()
                    .borders(Borders.ALL)
                    .borderType(BorderType.ROUNDED)
                    .borderColor(Color.hex(palette.error()))
                    .build();
            frame.renderWidget(errBlock, bannerRect);
            final var inner = errBlock.inner(bannerRect);
            if (!inner.isEmpty()) {
                frame.renderWidget(
                        Paragraph.builder()
                                .text(Text.from(err))
                                .overflow(dev.tamboui.style.Overflow.ELLIPSIS)
                                .build(),
                        inner);
            }
        }
    }

    /** Floating find bar popup shown while Ctrl+F search is open. */
    private void renderSearchBar(final dev.tamboui.terminal.Frame frame, final UiState state) {
        final var width = Math.min(66, frame.width() - 4);
        final var height = Math.min(3, frame.height());
        final var rect = Modal.rect(frame, width, height, 0.72);
        final var inner = Modal.box(frame, rect, " find ", Color.hex(palette.accent()));
        if (inner == null) {
            return;
        }
        final var counter = state.search.active()
                ? "  "
                        + (state.search.count() == 0
                                ? "no match"
                                : state.search.currentMatch() + "/" + state.search.count())
                : "";
        final var hints = Line.from(
                Span.styled(state.search.query() + "_", Style.EMPTY.fg(Color.hex(palette.text()))),
                Span.styled(
                        counter + "  Enter/Ctrl+N next  Ctrl+P prev  Esc close",
                        Style.EMPTY.fg(Color.hex(palette.textMuted()))));
        frame.renderWidget(
                Paragraph.builder()
                        .text(Text.from(hints))
                        .overflow(dev.tamboui.style.Overflow.ELLIPSIS)
                        .build(),
                inner);
    }

    private void openSearch(final UiState state) {
        state.searchOpen = true;
        state.search.recompute(state.flatRows);
    }

    /** Key handling while the shortcuts overlay is open. */
    private boolean onShortcutsKey(final Event event, final UiState state) {
        if (event instanceof final MouseEvent mouse) {
            // wheel support: the overlay is taller than the terminal on small screens
            if (mouse.isScroll()) {
                if (mouse.kind() == MouseEventKind.SCROLL_UP) {
                    state.shortcutsScroll = Math.max(0, state.shortcutsScroll - 3);
                } else {
                    state.shortcutsScroll += 3;
                }
            }
            return true;
        }
        if (event instanceof PasteEvent) {
            return true;
        }
        if (!(event instanceof final KeyEvent key)) {
            return false;
        }
        if (key.isCancel() || key.isKey(KeyCode.ESCAPE) || key.isCharIgnoreCase('?')) {
            state.shortcutsOpen = false;
            return true;
        }
        if (key.isKey(KeyCode.PAGE_UP) || (key.hasAlt() && key.isCharIgnoreCase('u'))) {
            state.shortcutsScroll = Math.max(0, state.shortcutsScroll - 10);
        } else if (key.isKey(KeyCode.PAGE_DOWN) || (key.hasAlt() && key.isCharIgnoreCase('d'))) {
            state.shortcutsScroll += 10;
        } else if (key.isKey(KeyCode.UP)) {
            state.shortcutsScroll = Math.max(0, state.shortcutsScroll - 1);
        } else if (key.isKey(KeyCode.DOWN)) {
            state.shortcutsScroll += 1;
        }
        return true;
    }

    /** Key handling while the "/tabs" list overlay is open. */
    private boolean onTabsListKey(final Event event) {
        if (event instanceof final MouseEvent mouse) {
            // wheel support: the tab titles can overflow the overlay height
            if (mouse.isScroll()) {
                if (mouse.kind() == MouseEventKind.SCROLL_UP) {
                    tabManager.listScroll(tabManager.listScroll() - 3);
                } else {
                    tabManager.listScroll(tabManager.listScroll() + 3);
                }
            }
            return true;
        }
        if (event instanceof PasteEvent) {
            return true;
        }
        if (!(event instanceof final KeyEvent key)) {
            return false;
        }
        if (key.isCancel() || key.isKey(KeyCode.ESCAPE)) {
            tabManager.closeList();
            return true;
        }
        if (key.isKey(KeyCode.PAGE_UP)) {
            tabManager.listScroll(tabManager.listScroll() - 10);
        } else if (key.isKey(KeyCode.PAGE_DOWN)) {
            tabManager.listScroll(tabManager.listScroll() + 10);
        } else if (key.isKey(KeyCode.UP)) {
            tabManager.listScroll(tabManager.listScroll() - 1);
        } else if (key.isKey(KeyCode.DOWN)) {
            tabManager.listScroll(tabManager.listScroll() + 1);
        } else if (!key.hasAlt() && !key.hasCtrl() && key.character() >= '0' && key.character() <= '9') {
            final var index = key.character() == '0' ? 9 : Character.digit(key.character(), 10) - 1;
            if (index < tabManager.size()) {
                tabManager.closeList();
                activate(index);
            }
            return true;
        }
        return true;
    }

    /** Scrollable modal listing every documented key binding, opened by the "?" affordance. */
    private void renderShortcutsOverlay(final dev.tamboui.terminal.Frame frame, final UiState state) {
        final var width = Math.min(Math.max(64, frame.width() * 4 / 5), frame.width());
        final var height = Math.min(Math.max(12, frame.height() * 4 / 5), frame.height());
        final var rect = Modal.rect(frame, width, height, 0.12);
        final var inner = Modal.box(frame, rect, " shortcuts ", Color.hex(palette.accent()));
        if (inner == null) {
            return;
        }
        final var title = Style.EMPTY.fg(Color.hex(palette.accent())).bold();
        final var key = Style.EMPTY.fg(Color.hex(palette.text())).bold();
        final var label = Style.EMPTY.fg(Color.hex(palette.textMuted()));
        final var rows = new ArrayList<Line>();
        var group = "";
        for (final var binding : KeymapCatalog.all()) {
            if (!binding.group().equals(group)) {
                group = binding.group();
                rows.add(Line.empty());
                rows.add(Line.from(Span.styled(group, title)));
            }
            rows.add(Line.from(Span.styled("  " + binding.key(), key), Span.styled(" - " + binding.label(), label)));
        }
        final var splits = Layout.vertical()
                .constraints(Constraint.fill(), Constraint.length(1))
                .split(inner);
        final var text = Text.from(rows.toArray(Line[]::new));
        final var maxScroll =
                Math.max(0, text.height() - Math.max(1, splits.getFirst().height()));
        state.shortcutsScroll = Math.clamp(state.shortcutsScroll, 0, maxScroll);
        if (!splits.getFirst().isEmpty()) {
            frame.renderWidget(
                    Paragraph.builder()
                            .text(text)
                            .overflow(Overflow.CLIP)
                            .scroll(state.shortcutsScroll)
                            .build(),
                    splits.getFirst());
        }
        frame.renderWidget(
                Paragraph.builder()
                        .text(Text.from(Line.from(Span.styled(
                                "  Up/Down scroll  Esc close  ", Style.EMPTY.fg(Color.hex(palette.textMuted()))))))
                        .overflow(Overflow.CLIP)
                        .build(),
                splits.get(1));
    }

    /** Modal listing every open tab with its full title; opened by "/tabs" (or an arg to switch directly). */
    private void renderTabsListOverlay(final dev.tamboui.terminal.Frame frame) {
        final var width = Math.min(Math.max(64, frame.width() * 4 / 5), frame.width());
        final var height = Math.min(Math.max(10, frame.height() * 4 / 5), frame.height());
        final var rect = Modal.rect(frame, width, height, 0.12);
        final var inner = Modal.box(frame, rect, " tabs ", Color.hex(palette.accent()));
        if (inner == null) {
            return;
        }
        final var activeIndex = tabManager.activeTab();
        final var accent = Style.EMPTY.fg(Color.hex(palette.accent())).bold();
        final var textStyle = Style.EMPTY.fg(Color.hex(palette.text()));
        final var muted = Style.EMPTY.fg(Color.hex(palette.textMuted()));
        final var rows = new ArrayList<Line>();
        final var open = tabManager.list();
        for (int i = 0; i < open.size(); i++) {
            final var tab = open.get(i);
            final var isActive = i == activeIndex;
            final var index = Span.styled(String.format("%2d ", i + 1), isActive ? accent : muted);
            final var icon = Span.styled(tabStatusIcon(tab) + " ", isActive ? accent : muted);
            final var title = Span.styled(tabTitle(tab), isActive ? accent : textStyle);
            final var marker = isActive ? Span.styled("  (active)", accent) : Span.styled("", muted);
            rows.add(Line.from(index, icon, title, marker));
        }
        if (rows.isEmpty()) {
            rows.add(Line.from(Span.styled("no open tabs", muted)));
        }
        final var splits = Layout.vertical()
                .constraints(Constraint.fill(), Constraint.length(1))
                .split(inner);
        final var text = Text.from(rows.toArray(Line[]::new));
        final var maxScroll =
                Math.max(0, text.height() - Math.max(1, splits.getFirst().height()));
        tabManager.listScroll(Math.clamp(tabManager.listScroll(), 0, maxScroll));
        if (!splits.getFirst().isEmpty()) {
            frame.renderWidget(
                    Paragraph.builder()
                            .text(text)
                            .overflow(Overflow.CLIP)
                            .scroll(tabManager.listScroll())
                            .build(),
                    splits.getFirst());
        }
        frame.renderWidget(
                Paragraph.builder()
                        .text(Text.from(Line.from(Span.styled(
                                "  1-9 switch  Up/Down scroll  Esc close  ",
                                Style.EMPTY.fg(Color.hex(palette.textMuted()))))))
                        .overflow(Overflow.CLIP)
                        .build(),
                splits.get(1));
    }

    /** Opens the subagents overlay from "/subagents": no arg = the list, an id = one subagent detail. */
    private ShellOutcome openSubagents(final String arg) {
        final var state = active();
        if (state == null) {
            return ShellOutcome.none();
        }
        final var id = arg == null ? "" : arg.strip();
        if (!id.isEmpty()) {
            final var present = subagentRegistry.get(id).isPresent();
            if (!present) {
                return ShellOutcome.status("unknown subagent: " + id);
            }
        }
        state.subagentsOverlay = id;
        state.subagentsScroll = 0;
        return ShellOutcome.none();
    }

    /** Key handling while the subagents overlay is open. */
    private boolean onSubagentsKey(final Event event, final UiState state) {
        if (event instanceof final MouseEvent mouse) {
            if (mouse.isScroll()) {
                state.subagentsScroll += mouse.kind() == MouseEventKind.SCROLL_UP ? -3 : 3;
            }
            return true;
        }
        if (event instanceof PasteEvent) {
            return true;
        }
        if (!(event instanceof final KeyEvent key)) {
            return false;
        }
        if (key.isCancel() || key.isKey(KeyCode.ESCAPE)) {
            state.subagentsOverlay = null;
            return true;
        }
        if (key.isKey(KeyCode.PAGE_UP)) {
            state.subagentsScroll -= 10;
        } else if (key.isKey(KeyCode.PAGE_DOWN)) {
            state.subagentsScroll += 10;
        } else if (key.isKey(KeyCode.UP)) {
            state.subagentsScroll -= 1;
        } else if (key.isKey(KeyCode.DOWN)) {
            state.subagentsScroll += 1;
        } else if ((key.isLeft() || key.isKey(KeyCode.BACKSPACE)) && !state.subagentsOverlay.isEmpty()) {
            state.subagentsOverlay = "";
            state.subagentsScroll = 0;
        }
        return true;
    }

    /** Modal showing the session subagents; with an id, the live transcript of one of them. */
    private void renderSubagentsOverlay(final dev.tamboui.terminal.Frame frame, final UiState state) {
        final var width = Math.min(Math.max(64, frame.width() * 4 / 5), frame.width());
        final var height = Math.min(Math.max(12, frame.height() * 4 / 5), frame.height());
        final var rect = Modal.rect(frame, width, height, 0.12);
        final var detail = !state.subagentsOverlay.isEmpty();
        final var inner = Modal.box(
                frame,
                rect,
                detail ? " subagents - " + state.subagentsOverlay : " subagents ",
                Color.hex(palette.accent()));
        if (inner == null) {
            return;
        }
        final var accent = Style.EMPTY.fg(Color.hex(palette.accent())).bold();
        final var textStyle = Style.EMPTY.fg(Color.hex(palette.text()));
        final var muted = Style.EMPTY.fg(Color.hex(palette.textMuted()));
        final var rows = new ArrayList<Line>();
        if (detail) {
            final var captured = subagentRegistry.transcriptOf(state.subagentsOverlay);
            final var md = captured.markdown("|", "");
            rows.addAll(transcriptRenderer.renderRegion(
                    md.isBlank() ? "*still starting...*" : md,
                    Math.max(1, inner.width() - 2),
                    textStyle,
                    MarkdownStyles.DEFAULTS));
        } else {
            final var active = active();
            final var list = subagentRegistry.listAll(active == null ? null : active.sessionId);
            if (list.isEmpty()) {
                rows.add(Line.from(Span.styled("no subagents for this session", muted)));
            }
            for (final var sub : list) {
                final var label = sub.name() != null ? sub.name() : sub.id();
                final var status = sub.isDone() ? (sub.isCancelled() ? "cancelled" : "completed") : "running";
                rows.add(Line.from(
                        Span.styled(label, accent),
                        Span.styled("  [" + status + "]", sub.isDone() ? muted : accent),
                        Span.styled("  " + TuiInputSupport.firstLine(summarize(sub)), muted)));
            }
            rows.add(Line.empty());
            rows.add(Line.from(Span.styled("  /subagents <id> to open one subagent transcript", muted)));
        }
        final var splits = Layout.vertical()
                .constraints(Constraint.fill(), Constraint.length(1))
                .split(inner);
        final var text = Text.from(rows.toArray(Line[]::new));
        final var maxScroll =
                Math.max(0, text.height() - Math.max(1, splits.getFirst().height()));
        state.subagentsScroll = Math.clamp(state.subagentsScroll, 0, maxScroll);
        if (!splits.getFirst().isEmpty()) {
            frame.renderWidget(
                    Paragraph.builder()
                            .text(text)
                            .overflow(Overflow.CLIP)
                            .scroll(state.subagentsScroll)
                            .build(),
                    splits.getFirst());
        }
        frame.renderWidget(
                Paragraph.builder()
                        .text(Text.from(Line.from(Span.styled(
                                "  Up/Down scroll  Esc close  ", Style.EMPTY.fg(Color.hex(palette.textMuted()))))))
                        .overflow(Overflow.CLIP)
                        .build(),
                splits.get(1));
    }

    private static String summarize(final SubagentRegistry.SubagentState sub) {
        final var result = sub.result();
        if (result == null) {
            return sub.task() == null ? "" : sub.task();
        }
        final var summary = result.summary() != null ? result.summary() : result.error();
        return summary == null || summary.isBlank() ? (sub.task() == null ? "" : sub.task()) : summary;
    }

    private void renderBtw(final dev.tamboui.terminal.Frame frame, final UiState state) {
        final var btw = state.btw;
        final var width = Math.min(Math.max(64, frame.width() * 4 / 5), frame.width());
        final var height = Math.min(Math.max(12, frame.height() * 3 / 5), frame.height());
        final var rect = Modal.rect(frame, width, height, 0.12);
        final var inner = Modal.box(frame, rect, " btw ", Color.hex(palette.accent()));
        if (inner == null) {
            return;
        }
        final var lines = new ArrayList<Line>();
        lines.add(Line.from(Span.styled("Q: " + btw.question(), Style.EMPTY.fg(Color.hex(palette.textMuted())))));
        lines.add(Line.empty());
        if (btw.running()) {
            lines.add(Line.from(Span.styled("thinking...", Style.EMPTY.fg(Color.hex(palette.textMuted())))));
        } else if (btw.error() != null) {
            lines.add(Line.from(Span.styled("failed: " + btw.error(), Style.EMPTY.fg(Color.hex(palette.error())))));
        } else {
            lines.addAll(
                    transcriptRenderer.renderRegion(btw.answer(), inner.width(), Style.EMPTY, MarkdownStyles.DEFAULTS));
        }
        final var splits = Layout.vertical()
                .constraints(Constraint.fill(), Constraint.length(1))
                .split(inner);
        final var text = Text.from(lines.toArray(Line[]::new));
        final var maxScroll =
                Math.max(0, text.height() - Math.max(1, splits.getFirst().height()));
        final var scroll = Math.clamp(btw.scroll(), 0, maxScroll);
        if (!splits.getFirst().isEmpty()) {
            frame.renderWidget(
                    Paragraph.builder()
                            .text(text)
                            .overflow(Overflow.CLIP)
                            .scroll(scroll)
                            .build(),
                    splits.getFirst());
        }
        frame.renderWidget(
                Paragraph.builder()
                        .text(Text.from(Line.from(Span.styled(
                                btw.running() ? "  Esc cancel" : "  Up/Down scroll  Esc close  ",
                                Style.EMPTY.fg(Color.hex(palette.textMuted()))))))
                        .overflow(Overflow.CLIP)
                        .build(),
                splits.get(1));
    }

    private void closeSearch(final UiState state) {
        state.searchOpen = false;
        state.search.clear();
        state.pendingJumpRow = -1;
    }

    private void jumpToCurrentMatch(final UiState state) {
        if (state.search.currentRow() >= 0) {
            state.pendingJumpRow = state.search.currentRow();
        }
    }

    /** Key handling while the Ctrl+F find bar is open (the hints row doubles as the search input). */
    private boolean onSearchKey(final Event event, final UiState state) {
        if (event instanceof final PasteEvent paste) {
            state.search.query(state.search.query() + paste.text().replace("\n", " "));
            state.search.recompute(state.flatRows);
            jumpToCurrentMatch(state);
            return true;
        }
        if (event instanceof MouseEvent) {
            return true;
        }
        if (!(event instanceof final KeyEvent key)) {
            return false;
        }
        if (key.isCancel() || key.isKey(KeyCode.ESCAPE) || (key.hasCtrl() && key.isCharIgnoreCase('f'))) {
            closeSearch(state);
            return true;
        }
        if (key.isConfirm() || (key.hasCtrl() && key.isCharIgnoreCase('n'))) {
            state.search.next();
            jumpToCurrentMatch(state);
            return true;
        }
        if (key.hasCtrl() && key.isCharIgnoreCase('p')) {
            state.search.previous();
            jumpToCurrentMatch(state);
            return true;
        }
        if (key.isKey(KeyCode.BACKSPACE)) {
            final var q = state.search.query();
            if (!q.isEmpty()) {
                state.search.query(q.substring(0, q.offsetByCodePoints(q.length(), -1)));
                state.search.recompute(state.flatRows);
            }
            return true;
        }
        if (!key.hasCtrl() && !key.hasAlt() && key.codePoint() >= 32) {
            final var cp = key.codePoint();
            state.search.query(state.search.query()
                    + (cp > Character.MAX_VALUE ? new String(Character.toChars(cp)) : String.valueOf((char) cp)));
            state.search.recompute(state.flatRows);
            jumpToCurrentMatch(state);
            return true;
        }
        return true;
    }

    private void openToolOverlay(final UiState state, final long ordinal) {
        if (ordinal < 0) {
            notify(state, "no tool output captured yet - Ctrl+O opens the latest tool call");
            return;
        }
        final var entry = state.toolOutputs.get(ordinal);
        if (entry == null) {
            notify(state, "tool #" + ordinal + " output is no longer in memory");
            return;
        }
        state.focusedToolOrdinal = ordinal;
        state.toolOverlay = new UiState.ToolOverlay(ordinal, entry, 0);
    }

    private void scrollToolOverlay(final UiState state, final int delta) {
        state.toolOverlay = new UiState.ToolOverlay(
                state.toolOverlay.ordinal(),
                state.toolOverlay.entry(),
                Math.max(0, state.toolOverlay.scroll() + delta));
    }

    /** Key/mouse handling while the full tool output overlay is open. */
    private boolean onToolOverlayEvent(final Event event, final TuiRunner runner, final UiState state) {
        if (event instanceof final MouseEvent mouse) {
            if (mouse.isScroll()) {
                scrollToolOverlay(state, mouse.kind() == MouseEventKind.SCROLL_UP ? -3 : 3);
            }
            return true;
        }
        if (!(event instanceof final KeyEvent key)) {
            return false;
        }
        if (key.isCancel()
                || key.isKey(KeyCode.ESCAPE)
                || key.isCharIgnoreCase('q')
                || (key.hasCtrl() && key.isCharIgnoreCase('o'))) {
            state.toolOverlay = null;
            return true;
        }
        if (key.isCharIgnoreCase('c')) {
            copyText(runner, state, state.toolOverlay.entry().output());
            return true;
        }
        if (key.isKey(KeyCode.PAGE_UP)) {
            scrollToolOverlay(state, -20);
        } else if (key.isKey(KeyCode.PAGE_DOWN)) {
            scrollToolOverlay(state, 20);
        } else if (key.isKey(KeyCode.UP)) {
            scrollToolOverlay(state, -1);
        } else if (key.isKey(KeyCode.DOWN)) {
            scrollToolOverlay(state, 1);
        }
        return true;
    }

    /** Full-screen scrollable view of the uncapped output of one tool call. */
    /**
     * Shared renderer for tool output bodies (inline cards and the Ctrl+O overlay):
     * ask_user answers first, then format detection (markdown/JSON/XML/YAML/diff),
     * always falling back to the historical rendering on any failure.
     */
    private List<Line> toolBodyLines(
            final String name, final String args, final String output, final int width, final boolean isDiff) {
        if (output == null || output.isBlank()) {
            return List.of();
        }
        final var body = renderToolBody(name, output, width, isDiff);
        if (args == null || args.isBlank()) {
            return body;
        }
        // the Ctrl+O overlay enriches the historical rendering with a structured
        // preview of the meaningful arguments (file content / diff / patch)
        final var preview = toolArgsPreview.preview(name, args, width, palette);
        if (preview == null || preview.isEmpty()) {
            return body;
        }
        final var all = new ArrayList<Line>(preview.size() + body.size() + 1);
        all.addAll(preview);
        all.add(Line.empty());
        all.addAll(body);
        return all;
    }

    private List<Line> renderToolBody(final String name, final String output, final int width, final boolean isDiff) {
        final var answers = "ask_user".equals(name) ? askUserCardRenderer.render(output, palette) : null;
        if (answers != null) {
            return answers;
        }
        try {
            if (!formatToolOutput) {
                return legacyBody(output, width, isDiff);
            }
            return switch (toolOutputFormat.detect(output)) {
                case DIFF -> diffRenderer.render(output, width, palette);
                case JSON -> syntaxHighlighter.highlightLines("json", toolOutputFormat.prettyJson(output));
                case XML -> syntaxHighlighter.highlightLines("xml", toolOutputFormat.prettyXml(output));
                case YAML -> syntaxHighlighter.highlightLines("yaml", output);
                case MARKDOWN -> transcriptRenderer.renderRegion(output, width, toolStyle, MarkdownStyles.DEFAULTS);
                case TEXT -> toolOutputLines(output, width, false);
            };
        } catch (final RuntimeException e) {
            return legacyBody(output, width, isDiff);
        }
    }

    private List<Line> legacyBody(final String output, final int width, final boolean isDiff) {
        return isDiff ? diffRenderer.render(output, width, palette) : toolOutputLines(output, width, false);
    }

    private void renderToolOverlay(final dev.tamboui.terminal.Frame frame, final UiState state) {
        final var entry = state.toolOverlay.entry();
        final var width = Math.min(Math.max(70, frame.width() * 4 / 5), frame.width());
        final var height = Math.min(Math.max(14, frame.height() * 4 / 5), frame.height());
        final var rect = Modal.rect(frame, width, height, 0.1);
        final var title = " " + (entry.failed() ? glyphs.cross() + " " : "") + entry.name() + " - full output ";
        final var inner =
                Modal.box(frame, rect, title, Color.hex(entry.failed() ? palette.error() : palette.success()));
        if (inner == null) {
            return;
        }
        final var body = toolBodyLines(entry.name(), entry.args(), entry.output(), inner.width(), false);
        final var splits = Layout.vertical()
                .constraints(Constraint.fill(), Constraint.length(1))
                .split(inner);
        final var text = Text.from(body.toArray(Line[]::new));
        final var maxScroll =
                Math.max(0, text.height() - Math.max(1, splits.getFirst().height()));
        final var overlay = state.toolOverlay;
        state.toolOverlay =
                new UiState.ToolOverlay(overlay.ordinal(), overlay.entry(), Math.clamp(overlay.scroll(), 0, maxScroll));
        if (!splits.getFirst().isEmpty()) {
            frame.renderWidget(
                    Paragraph.builder()
                            .text(text)
                            .overflow(Overflow.CLIP)
                            .scroll(state.toolOverlay.scroll())
                            .build(),
                    splits.getFirst());
        }
        final var footer = " "
                + (entry.args() == null || entry.args().isBlank()
                        ? ""
                        : ellipsize(entry.args(), Math.max(1, inner.width() - 42)) + "  ")
                + "Ctrl+O/q/Esc close | Up/Down scroll | c copy output ";
        frame.renderWidget(
                Paragraph.builder()
                        .text(Text.from(Line.from(Span.styled(footer, Style.EMPTY.fg(Color.hex(palette.textMuted()))))))
                        .overflow(Overflow.CLIP)
                        .build(),
                splits.get(1));
    }

    /** Key handling while the error detail modal (Alt+E) is open. */
    private boolean onErrorKey(final Event event, final TuiRunner runner, final UiState state) {
        if (event instanceof PasteEvent || event instanceof MouseEvent) {
            return true;
        }
        if (!(event instanceof final KeyEvent key)) {
            return false;
        }
        if (key.isCancel()
                || key.isKey(KeyCode.ESCAPE)
                || key.isCharIgnoreCase('q')
                || (key.hasAlt() && key.isCharIgnoreCase('e'))) {
            state.errorOpen = false;
            return true;
        }
        if (key.isCharIgnoreCase('c')) {
            final var detail = state.errorDetail;
            copyText(
                    runner,
                    state,
                    detail.stack() == null ? detail.message() : detail.message() + "\n" + detail.stack());
            return true;
        }
        if (key.isKey(KeyCode.PAGE_UP)) {
            state.errorScroll = Math.max(0, state.errorScroll - 10);
        } else if (key.isKey(KeyCode.PAGE_DOWN)) {
            state.errorScroll += 10;
        } else if (key.isKey(KeyCode.UP)) {
            state.errorScroll = Math.max(0, state.errorScroll - 1);
        } else if (key.isKey(KeyCode.DOWN)) {
            state.errorScroll += 1;
        }
        return true;
    }

    /** Scrollable modal with the last error message and (when available) its stack trace. */
    private void renderErrorDetail(final dev.tamboui.terminal.Frame frame, final UiState state) {
        final var detail = state.errorDetail;
        final var width = Math.min(Math.max(70, frame.width() * 4 / 5), frame.width());
        final var height = Math.min(Math.max(12, frame.height() * 3 / 5), frame.height());
        final var rect = Modal.rect(frame, width, height, 0.15);
        final var inner = Modal.box(frame, rect, " error details ", Color.hex(palette.error()));
        if (inner == null) {
            return;
        }
        final var lines = new ArrayList<Line>();
        lines.add(Line.from(Span.styled(
                ellipsize(detail.message(), inner.width()),
                Style.EMPTY.fg(Color.hex(palette.error())).bold())));
        lines.add(Line.empty());
        if (detail.stack() != null && !detail.stack().isBlank()) {
            for (final var l : detail.stack().split("\n")) {
                lines.add(Line.from(
                        Span.styled(ellipsize(l, inner.width()), Style.EMPTY.fg(Color.hex(palette.textMuted())))));
            }
        } else {
            lines.add(Line.from(Span.styled("(no stack trace)", Style.EMPTY.fg(Color.hex(palette.textMuted())))));
        }
        final var splits = Layout.vertical()
                .constraints(Constraint.fill(), Constraint.length(1))
                .split(inner);
        final var text = Text.from(lines.toArray(Line[]::new));
        final var maxScroll =
                Math.max(0, text.height() - Math.max(1, splits.getFirst().height()));
        state.errorScroll = Math.clamp(state.errorScroll, 0, maxScroll);
        if (!splits.getFirst().isEmpty()) {
            frame.renderWidget(
                    Paragraph.builder()
                            .text(text)
                            .overflow(Overflow.CLIP)
                            .scroll(state.errorScroll)
                            .build(),
                    splits.getFirst());
        }
        frame.renderWidget(
                Paragraph.builder()
                        .text(Text.from(Line.from(Span.styled(
                                " Esc close | Up/Down scroll | c copy ",
                                Style.EMPTY.fg(Color.hex(palette.textMuted()))))))
                        .overflow(Overflow.CLIP)
                        .build(),
                splits.get(1));
    }

    /** Ctrl+R: fuzzy-searchable prompt history picker (falls back to cycling when empty). */
    private void openHistoryPicker(final UiState state) {
        final var entries = promptHistory.entries();
        if (entries.isEmpty()) {
            recallHistory(state, true);
            return;
        }
        openPicker(
                state,
                "prompt history",
                entries,
                selected -> {
                    beforeEdit(state);
                    state.input.setText(selected);
                    positionCursorAtEnd(state);
                    dismissCompletion(state);
                    notify(state, "recalled from history");
                },
                () -> notify(state, "history picker cancelled"));
    }

    /** Alt+Up/Alt+Down (or Alt+K/Alt+J): jump to the previous/next tool card in the transcript. */
    private void navigateTools(final UiState state, final int direction) {
        final var anchors =
                state.toolAnchors.stream().filter(a -> a.ordinal() >= 0).toList();
        if (anchors.isEmpty()) {
            state.toolNavIndex = -1;
            state.focusedToolOrdinal = -1;
            notify(state, "no tool cards yet");
            return;
        }
        // resolve the focused ordinal against the fresh anchor list first so navigation
        // survives re-renders (anchors are rebuilt each frame from the transcript layout)
        final var idx = nextToolAnchorIndex(
                anchors.stream().map(UiState.ToolAnchor::ordinal).toList(),
                state.focusedToolOrdinal,
                state.toolNavIndex,
                direction);
        state.toolNavIndex = idx;
        final var anchor = anchors.get(idx);
        state.focusedToolOrdinal = anchor.ordinal();
        state.pendingJumpRow = anchor.row();
        notify(
                state,
                "tool #" + anchor.ordinal()
                        + (state.collapsedTools.contains(anchor.ordinal()) ? " (collapsed - click to expand)" : ""));
    }

    /**
     * Pure navigation resolver over tool card ordinals (in transcript order). Returns the
     * index to focus for a step in {@code direction} (-1 up, 1 down), wrapping around the
     * ends. {@code focusedOrdinal} wins over {@code fallbackIndex} when still present so
     * navigation stays stable across re-renders; -1 is returned only when there is nothing.
     */
    static int nextToolAnchorIndex(
            final List<Long> ordinals, final long focusedOrdinal, final int fallbackIndex, final int direction) {
        if (ordinals.isEmpty()) {
            return -1;
        }
        var idx = -1;
        for (var i = 0; i < ordinals.size(); i++) {
            if (ordinals.get(i) == focusedOrdinal) {
                idx = i;
                break;
            }
        }
        if (idx < 0) {
            idx = fallbackIndex >= 0 && fallbackIndex < ordinals.size() ? fallbackIndex : -1;
        }
        if (idx < 0) {
            return direction > 0 ? 0 : ordinals.size() - 1;
        }
        return Math.floorMod(idx + direction, ordinals.size());
    }

    /** Removes the queued prompt at the given index (Ctrl+X pops the most recent one). */
    private static void removeQueued(final UiState state, final int index) {
        final var items = new ArrayList<>(state.pendingPrompts);
        if (index < 0 || index >= items.size()) {
            return;
        }
        final var removed = items.remove(index);
        state.pendingPrompts.clear();
        state.pendingPrompts.addAll(items);
        notify(state, "removed queued prompt (" + items.size() + " still pending)");
        if (!removed.isBlank()) {
            state.pushToast("removed: " + TuiInputSupport.firstLine(removed), ToastLevel.INFO);
        }
    }

    /** Compact strip above the prompt listing queued prompts (click a row or Ctrl+X to remove). */
    private void renderQueue(final Rect rect, final dev.tamboui.terminal.Frame frame, final UiState state) {
        state.queueRects.clear();
        final var items = List.copyOf(state.pendingPrompts);
        final var muted = Style.EMPTY.fg(Color.hex(palette.textMuted()));
        final var accent = Style.EMPTY.fg(Color.hex(palette.primary())).bold();
        for (var i = 0; i < rect.height() && i < items.size(); i++) {
            final var lineRect = new Rect(rect.x(), rect.y() + i, rect.width(), 1);
            state.queueRects.add(lineRect);
            frame.renderWidget(
                    Paragraph.builder()
                            .text(Text.from(Line.from(
                                    Span.styled(" " + glyphs.bar() + " queued ", accent),
                                    Span.styled(
                                            ellipsize(
                                                    TuiInputSupport.firstLine(items.get(i)),
                                                    Math.max(1, rect.width() - 24)),
                                            muted),
                                    Span.styled("  (Ctrl+X removes)", muted))))
                            .overflow(Overflow.CLIP)
                            .build(),
                    lineRect);
        }
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

    private static void notify(final UiState state, final String message) {
        state.notification = message;
    }

    /** True while an error recorded via {@link #modelError} is awaiting acknowledgment. */
    private static boolean activeError(final UiState state) {
        return state.errorMessage != null;
    }

    private static void modelError(final UiState state, final String message) {
        modelError(state, message, null);
    }

    /** Records an error: persisted detail (message + stack) openable with Alt+E; the status strip shows it in red. */
    private static void modelError(final UiState state, final String message, final Throwable error) {
        state.errorMessage = message;
        state.errorDetail = new UiState.ErrorDetail(message, error == null ? null : stackTraceOf(error));
        state.errorScroll = 0;
    }

    private static String stackTraceOf(final Throwable error) {
        final var sw = new java.io.StringWriter();
        error.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }

    private static void clearError(final UiState state) {
        state.errorMessage = null;
        state.errorDetail = null;
        state.errorOpen = false;
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
        final var rows = Math.max(1, rect.height());
        final var total = visible.size();
        picker.prepareList(rect);
        // viewport: keep the selection visible, clamp to the tail, render only the window
        final var maxScroll = Math.max(0, total - rows);
        final var scroll = Math.min(Math.max(0, picker.selected() - rows / 2), Math.max(0, maxScroll));
        final var from = Math.min(scroll, total);
        final var to = Math.min(total, scroll + rows);
        for (int row = from; row < to; row++) {
            final var lineRect = new Rect(rect.x(), rect.y() + (row - from), rect.width(), 1);
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
        // scroll hint when the list overflows the window
        if (total > rows) {
            frame.renderWidget(
                    Paragraph.builder()
                            .text(Text.from(Line.from(
                                    Span.styled("▴ " + (scroll + 1) + "/" + total + " ▾", Style.EMPTY.fg(muted)))))
                            .build(),
                    new Rect(rect.x() + rect.width() - 12, rect.y() + Math.max(0, rect.height() - 1), 12, 1));
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
        final var textStyle = Style.EMPTY.fg(Color.hex(palette.text()));
        final var warning = Color.hex(palette.warning());
        final var width = Math.min(Math.max(60, frame.width() * 4 / 5), frame.width());
        final var isConfirmTab = prompt.tab() >= prompt.questions().size();
        final var qIndex = !isConfirmTab ? prompt.tab() : 0;
        final var fq = prompt.questions().get(qIndex);
        // the question is word-wrapped; the modal grows to fit its rows (capped) so
        // long questions stay fully readable instead of being clipped to one line
        final var innerWidth = Math.max(1, width - 2);
        final var questionLines = TuiInputSupport.wrapProse(fq.question.prompt(), innerWidth);
        final var questionRows = Math.min(6, Math.max(1, questionLines.size()));
        final var height = Math.min(
                Math.max(12, 8 + prompt.questions().size() * 3 + questionRows), Math.max(12, frame.height() * 3 / 4));
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

        final var content = Layout.vertical()
                .constraints(Constraint.length(questionRows), Constraint.fill(), Constraint.length(3))
                .split(bodyRect);
        final var promptLines = new ArrayList<Line>();
        final var promptStyle =
                isConfirmTab ? textStyle : Style.EMPTY.fg(warning).bold();
        if (isConfirmTab) {
            promptLines.add(Line.from(Span.styled(
                    "Review your answers below, then press Enter to confirm, Backspace to go back and edit.",
                    promptStyle)));
        } else {
            for (final var wrapped : questionLines) {
                promptLines.add(Line.from(Span.styled(wrapped, promptStyle)));
            }
        }
        frame.renderWidget(
                Paragraph.builder()
                        .text(Text.from(promptLines.toArray(Line[]::new)))
                        .overflow(Overflow.CLIP)
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
        // windowed rendering: keep the focus visible, scroll the tail when there are
        // more options (plus the own-answer row) than the panel can show
        final var total = opts + 1;
        final var rows = Math.max(1, rect.height());
        final var maxScroll = Math.max(0, total - rows);
        final var scroll = Math.min(Math.max(0, prompt.optionsFocus() - rows / 2), maxScroll);
        final var from = Math.min(scroll, total);
        final var to = Math.min(total, scroll + rows);
        for (int row = from; row < to; row++) {
            final var lineRect = new Rect(rect.x(), rect.y() + (row - from), rect.width(), 1);
            fq.optionRects().add(lineRect);
            final var line = Line.from(buildOptionLine(fq, row, accent, muted, textColor));
            frame.renderWidget(Paragraph.builder().text(Text.from(line)).build(), lineRect);
        }
        if (total > rows) {
            frame.renderWidget(
                    Paragraph.builder()
                            .text(Text.from(Line.from(Span.styled(
                                    (scroll > 0 ? "▲ " : "  ") + (scroll + 1) + "/" + total
                                            + (scroll + rows < total ? " ▼" : "  "),
                                    Style.EMPTY.fg(muted)))))
                            .build(),
                    new Rect(rect.x(), rect.y() + Math.max(0, rect.height() - 1), rect.width() / 2, 1));
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
            // the full question, word-wrapped to the panel width so long prompts stay readable
            for (final var wrapped : TuiInputSupport.wrapProse(fq.question.prompt(), Math.max(1, rect.width()))) {
                lines.add(Line.from(Span.styled(
                        wrapped, Style.EMPTY.fg(Color.hex(palette.accent())).bold())));
            }
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
        final var build = active().session.runtime().permissions().mode() == AgentMode.BUILD;
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
        return active().session.runtime().permissions().mode() == AgentMode.PLAN;
    }

    private String modeName() {
        if (active().session.runtime().permissions().isPermitAll()) {
            return "all";
        }
        return active().session.runtime().permissions().mode().name().toLowerCase(Locale.ROOT);
    }

    /** Mode word shown in the status strip; "all ⚠" when permissions are open. */
    private String modeWord() {
        return modeName() + (active().session.runtime().permissions().isPermitAll() ? " ⚠" : "");
    }

    private Color modeColor() {
        if (active().session.runtime().permissions().isPermitAll()) {
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

    /**
     * Height of the status strip: 4 rows while a boxed error is shown (1 status line + the 3-row
     * error banner below it, so the model/metrics stay visible), 2 rows when the context compaction
     * warning banner is active (it occupies the strip's last row), 1 row otherwise.
     */
    private int statusStripRows(final UiState state) {
        if (state.errorMessage != null) {
            return 4;
        }
        return state.model.contextPercent() >= compactionThreshold() ? 2 : 1;
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

    /**
     * Branch shown in the sidebar. When the shared scheduler runs a {@link GitBranchPoller} the value comes
     * from its last tick (no disk access on the render thread, the poller repaints on change); otherwise the
     * historical on-demand read with a short TTL is kept for render-only tests.
     */
    private String gitBranch() {
        final var poller = gitBranchPoller;
        if (poller != null) {
            return poller.branch();
        }
        final var now = clock.millis();
        final var cachedAt = cachedGitBranchAt;
        if (cachedGitBranch != null && now - cachedAt < GIT_BRANCH_CACHE_TTL_MILLIS) {
            return cachedGitBranch;
        }
        try {
            final var head = GitBranchPoller.resolveHead(System.getProperty("user.dir"));
            final var branch = head != null && Files.isRegularFile(head)
                    ? GitBranchPoller.branchOf(Files.readString(head).strip())
                    : null;
            cachedGitBranch = branch;
            cachedGitBranchAt = now;
            return branch;
        } catch (final IOException e) {
            cachedGitBranch = null;
            cachedGitBranchAt = now;
            return null;
        }
    }

    /** Called by the branch poller (off the render thread) once the branch actually changed. */
    private void onBranchChanged(final String branch) {
        final var state = active();
        if (state != null) {
            runLater(state, () -> LOG.fine(() -> "branch changed to " + branch));
        }
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
        }

        private void filterBackspace() {
            if (filter.length() > 0) {
                filter.deleteCharAt(filter.length() - 1);
                selected = 0;
            }
        }

        private void filterClear() {
            filter.setLength(0);
            selected = 0;
        }

        /** Moves the selection; the renderer keeps the selected row inside the visible window. */
        private void move(final int delta) {
            final var v = visible();
            if (v.isEmpty()) {
                return;
            }
            selected = Math.clamp(selected + delta, 0, v.size() - 1);
        }

        private void stroke(final KeyEvent key) {
            if (key.isKey(KeyCode.UP)) {
                move(-1);
            } else if (key.isKey(KeyCode.DOWN)) {
                move(1);
            } else if (key.isKey(KeyCode.HOME)) {
                selected = 0;
            } else if (key.isKey(KeyCode.END)) {
                final var v = visible();
                selected = v.isEmpty() ? 0 : v.size() - 1;
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

    /**
     * Cached completion value sources. Populated asynchronously on the shared
     * executor so the render thread never blocks on a network/IO round-trip
     * while completing. Each field is replaced atomically with an immutable
     * list (or map); readers just call get.
     */
    private static final class CompletionCache {
        private volatile List<String> models = List.of();
        private volatile List<String> sessions = List.of();
        private volatile List<String> tools = List.of();
        private volatile Map<String, List<String>> variants = Map.of();

        List<String> models() {
            return models;
        }

        void models(final List<String> value) {
            models = value == null ? List.of() : value;
        }

        List<String> sessions() {
            return sessions;
        }

        void sessions(final List<String> value) {
            sessions = value == null ? List.of() : value;
        }

        List<String> tools() {
            return tools;
        }

        void tools(final List<String> value) {
            tools = value == null ? List.of() : value;
        }

        List<String> variants(final String model) {
            final var map = variants;
            return model == null ? List.of() : map.getOrDefault(model, List.of());
        }

        void variants(final Map<String, List<String>> value) {
            variants = value == null ? Map.of() : value;
        }
    }

    static final class UiState {
        private record Wait(String message, SpinnerStyle style) {}

        /** Full-screen expandable view of one captured tool output (Ctrl+O). */
        private record ToolOverlay(long ordinal, ToolOutputStore.Entry entry, int scroll) {}

        /** Persisted detail of the last error (message + optional stack trace) for the Alt+E modal. */
        private record ErrorDetail(String message, String stack) {}

        /** Screen hit-area of a rendered tool card header (click to collapse/expand, Alt+Up/Down to navigate). */
        private record ToolAnchor(long ordinal, Rect header, int row) {}

        /** Transcript row of a truncated tool card "click to expand" footer (click opens the full output). */
        private record FooterLink(long ordinal, int row) {}

        /** A tool card position in the flat transcript row space (row = top line of the card). */
        private record ToolTop(long ordinal, int row) {}

        private final TuiTranscriptModel model;
        private final Clock clock;
        private final TextAreaState input = new TextAreaState();
        private volatile String sessionId = "";
        /** Per-session services (loop, permissions, todos, usage...) created when the tab opens. */
        private volatile SessionRuntime runtime;

        private volatile String title;
        private volatile Wait wait;
        private volatile String activeAgent = "";
        private volatile boolean showThinking;
        private volatile ToolOutputVisibility showToolActions = ToolOutputVisibility.COLLAPSED;
        private volatile boolean showTodos;
        private volatile boolean showTodosCollapsed;
        private volatile boolean interrupted;

        /** Whether the last turn completed: drives the tab-bar done icon until a new turn starts. */
        private volatile boolean turnCompleted;

        private volatile long lastRenderTick;
        /**
         * Pending background mutation awaiting its tick paint: set by {@link #runLater(UiState, Runnable)}
         * before the action runs, cleared at the start of {@link #render}. Render-thread only.
         */
        private volatile boolean dirty;

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
        private volatile Rect copyZone;
        private volatile Rect inputZone;
        private volatile int spinnerTick;
        private volatile String lastTodosRender = "";
        private volatile Toast toast;
        private volatile long toastShownAt;
        private volatile TuiRunner runner;
        private final java.util.Deque<String> pendingPrompts = new java.util.concurrent.ConcurrentLinkedDeque<>();
        private volatile String errorMessage;
        private volatile ErrorDetail errorDetail;
        private volatile boolean errorOpen;
        private volatile int errorScroll;
        private volatile String notification;
        private volatile ToolOverlay toolOverlay;
        private volatile BtwOverlay btw;

        private record BtwOverlay(
                long ordinal,
                String question,
                String answer,
                String error,
                boolean running,
                int scroll,
                AtomicBoolean cancelled) {}

        private final TranscriptSearch search = new TranscriptSearch();
        private volatile boolean searchOpen;
        private volatile List<String> flatRows = List.of();
        private volatile int maxViewOffset;
        private volatile int pendingJumpRow = -1;
        private final java.util.Set<Long> collapsedTools = new java.util.HashSet<>();
        private volatile List<ToolAnchor> toolAnchors = List.of();
        private volatile List<ToolAnchor> toolFooterLinks = List.of();
        /** Wall-clock millis when the current live thinking stream started, -1 when idle. */
        private volatile long thinkingStartedAtMillis = -1;

        private final java.util.List<Rect> queueRects = new ArrayList<>();
        private volatile boolean shortcutsOpen;
        private volatile int shortcutsScroll;
        private volatile Rect shortcutRect;
        /** Subagents overlay: null when closed, the detail id when showing one subagent, empty for the list. */
        private volatile String subagentsOverlay = null;

        private volatile int subagentsScroll;
        private volatile String lastCodeBlock;
        /** Pending tool args per name (FIFO), mirroring the core transcript's running tools. */
        private final java.util.Map<String, java.util.ArrayDeque<String>> pendingToolArgs = new java.util.HashMap<>();

        private volatile long focusedToolOrdinal = -1;
        private volatile int toolNavIndex = -1;
        private volatile boolean yankArmed;
        private volatile int lastYankLen;

        private void pushToast(final String message, final ToastLevel level) {
            toast = new Toast(message, level);
            toastShownAt = clock.millis();
        }

        private void clearToast() {
            toast = null;
        }

        UiState(final ThinkTagSplitter thinkTags, final Clock clock) {
            this.model = new TuiTranscriptModel(thinkTags);
            this.clock = clock;
            this.session = new ShellSession();
            this.toolOutputs = new ToolOutputStore();
            this.killRing = new KillRing();
            this.undoStack = new UndoStack();
            this.interruptor = new InterruptCoordinator(clock, true);
        }

        /** Per-tab shell state: session id, agent, view flags, busy and metrics. */
        private ShellSession session;

        /** Captured tool outputs of this tab (Ctrl+O overlay, copy from tool cards). */
        private final ToolOutputStore toolOutputs;

        /** Ctrl+U/Ctrl+K last-deleted text ring. */
        private final KillRing killRing;

        /** Undo/redo stack of the input text. */
        private final UndoStack undoStack;

        /** Ctrl+C press window: interrupt vs quit. */
        private final InterruptCoordinator interruptor;

        /** Monotonic ordinal for this tab's /btw overlays. */
        private final AtomicLong btwOrdinal = new AtomicLong();

        /** Pending readLine future, only set while the input is not owned by a turn. */
        private volatile CompletableFuture<Optional<String>> pendingLine;

        /** Per-session services of this tab; wired once the runtime is created at startup. */
        private SessionRuntime runtime() {
            return runtime;
        }

        /** Wires the per-session services of this tab. */
        private void runtime(final SessionRuntime runtime) {
            this.runtime = runtime;
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
