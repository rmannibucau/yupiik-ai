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
package io.yupiik.ia.agent.core.cli;

import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.cli.Command;
import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.agent.AgentProvider;
import io.yupiik.ia.agent.core.cli.prompt.LinedPrompt;
import io.yupiik.ia.agent.core.cli.prompt.Prompt;
import io.yupiik.ia.agent.core.cli.prompt.PromptInterruptException;
import io.yupiik.ia.agent.core.cli.prompt.PromptOutput;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.MetricsDisplayMode;
import io.yupiik.ia.agent.core.configuration.PermissionMode;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.maven.MavenRepositoryKnowledgeService;
import io.yupiik.ia.agent.core.session.AgentSessionService;
import io.yupiik.ia.agent.core.session.FileSessionStore;
import io.yupiik.ia.agent.core.shell.AnsiShellFrontend;
import io.yupiik.ia.agent.core.shell.AnsiShellIO;
import io.yupiik.ia.agent.core.shell.ConversationCoordinator;
import io.yupiik.ia.agent.core.shell.InterruptCoordinator;
import io.yupiik.ia.agent.core.shell.MetricsFormat;
import io.yupiik.ia.agent.core.shell.SessionLifecycle;
import io.yupiik.ia.agent.core.shell.SessionRuntimeFactory;
import io.yupiik.ia.agent.core.shell.ShellEnv;
import io.yupiik.ia.agent.core.shell.ShellOutcome;
import io.yupiik.ia.agent.core.shell.ShellRegistry;
import io.yupiik.ia.agent.core.shell.ShellSession;
import io.yupiik.ia.agent.core.shell.SpeakerLabels;
import io.yupiik.ia.agent.core.shell.ToolOutputVisibility;
import io.yupiik.ia.agent.core.shell.TurnRunner;
import io.yupiik.ia.agent.core.shell.slash.SlashRegistry;
import io.yupiik.ia.agent.core.style.ModelStyleService;
import io.yupiik.ia.agent.core.todo.TodoItem;
import io.yupiik.ia.agent.core.wiring.HackStdIo;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Interactive plain-terminal REPL for yca. Requires only a terminal
 * ({@code stdin}/{@code stdout} lines and optional ANSI color); it is a
 * drop-in alternative to the TamboUI {@code tui} for environments without a
 * real terminal widget layer.
 */
@DefaultScoped
@Command(name = "cli", description = "Interactive plain-terminal yca session (REPL) with optional ANSI colors.")
public class CliCommand implements Runnable {
    private final CliConf conf;
    private final SessionLifecycle sessionLifecycle;
    private final FileSessionStore sessionStore;
    private final AgentProvider agentProvider;
    private final SessionRuntimeFactory sessionRuntimeFactory;
    private final ChatClient chatClient;
    private final ProviderManager providerManager;
    private final AgentSessionService agentSessionService;
    private final MavenRepositoryKnowledgeService mavenKnowledgeService;
    private final SlashRegistry slashRegistry;
    private final ShellRegistry shellRegistry;
    private final ConversationCoordinator coordinator;
    private final ModelStyleService modelStyleService;
    private final Clock clock;
    private final InterruptCoordinator interruptor;
    private final Prompt prompt;
    private final CodingAgentConfiguration configuration;
    private final ShellSession session = new ShellSession();

    private static final long CLEANUP_AWAIT_NANOS = 1_000_000_000L;

    private final AtomicBoolean cancelled = new AtomicBoolean();
    /**
     * Whether an inline interaction read (approval, confirm, picker, feedback) owns the
     * terminal: a Ctrl-C there must deny/cancel that interaction, never open the interrupt
     * menu. Guarded by the AnsiShellIO interaction hooks.
     */
    private final AtomicBoolean interactionActive = new AtomicBoolean();
    /**
     * Set by the SIGINT handler when a turn runs and no interactive read is live; the REPL
     * thread in {@link #awaitTurn} picks it up and renders the interrupt menu. Needed because
     * the jline reader is not thread-safe: the menu cannot be painted from the signal thread.
     */
    private final AtomicBoolean menuPending = new AtomicBoolean();

    private final TurnRunner turnRunner;
    private final MarkdownColorizer colorizer;
    private final Symbols symbols;
    private final AnsiShellFrontend frontend;
    private final AnsiShellIO io;
    private final SpeakerLabels speakerLabels;
    private final boolean queueing;
    private final Deque<String> pendingPrompts = new ConcurrentLinkedDeque<>();
    private final AtomicInteger activeTurns = new AtomicInteger();
    private final ReentrantLock turnLock = new ReentrantLock();
    private volatile CompletableFuture<Boolean> inFlight;
    private volatile boolean quitRequested;
    private String sessionId;
    private String activeAgent = "";
    private Path contextPath;
    private final CliPromptTemplate promptTemplate;
    private final AtomicBoolean cleaned = new AtomicBoolean();
    /**
     * Line-editing sink for output landing while a prompt is live (jline
     * {@code printAbove}); {@code null} on the plain-stdin backend. Turn-end
     * writes (todos, usage) race the next {@code readLine} and late async
     * events (subagents, memory extraction) can land on it: routing them
     * through the sink keeps the prompt visible and redrawn.
     */
    private final PromptOutput externalOutput;

    private final HackStdIo stdio;

    public CliCommand(
            final CliConf conf,
            final SessionLifecycle sessionLifecycle,
            final FileSessionStore sessionStore,
            final SessionRuntimeFactory sessionRuntimeFactory,
            final AgentProvider agentProvider,
            final ChatClient chatClient,
            final ProviderManager providerManager,
            final AgentSessionService agentSessionService,
            final MavenRepositoryKnowledgeService mavenKnowledgeService,
            final TurnRunner turnRunner,
            final JsonMapper mapper,
            final SlashRegistry slashRegistry,
            final ShellRegistry shellRegistry,
            final ConversationCoordinator coordinator,
            final ModelStyleService modelStyleService,
            final CliPromptTemplate promptTemplate,
            final Prompt prompt,
            final Clock clock,
            final CodingAgentConfiguration configuration,
            final SpeakerLabels speakerLabels,
            final HackStdIo stdio) {
        this.conf = conf;
        this.sessionLifecycle = sessionLifecycle;
        this.sessionStore = sessionStore;
        this.agentProvider = agentProvider;
        this.sessionRuntimeFactory = sessionRuntimeFactory;
        this.chatClient = chatClient;
        this.providerManager = providerManager;
        this.agentSessionService = agentSessionService;
        this.mavenKnowledgeService = mavenKnowledgeService;
        this.turnRunner = turnRunner;
        this.slashRegistry = slashRegistry;
        this.shellRegistry = shellRegistry;
        this.coordinator = coordinator;
        this.modelStyleService = modelStyleService;
        this.promptTemplate = promptTemplate;
        this.prompt = prompt;
        this.clock = clock;
        this.interruptor = new InterruptCoordinator(clock);
        this.configuration = configuration;
        this.speakerLabels = speakerLabels;
        this.stdio = stdio;
        session.metrics(configuration.metrics());
        final var parsedToolOutput = ToolOutputVisibility.parse(conf.showToolOutput());
        final var showToolOutput = parsedToolOutput == null ? ToolOutputVisibility.COLLAPSED : parsedToolOutput;
        session.showThinking(conf.showThinking()).showToolOutput(showToolOutput);
        this.colorizer = new MarkdownColorizer(colorEnabled(conf.color()));
        this.symbols = new Symbols(conf.ascii());
        this.externalOutput = prompt.externalOutput() == null ? PromptOutput.raw() : prompt.externalOutput();
        this.frontend = new AnsiShellFrontend(
                colorizer,
                symbols,
                new ToolPresenter(mapper),
                CliVerbosity.parse(conf.presentation()),
                System.console() != null,
                conf.showThinking(),
                showToolOutput,
                externalOutput);
        this.io = new AnsiShellIO(
                prompt,
                symbols,
                this::onInterrupt,
                () -> {
                    interactionActive.set(true);
                    frontend.suspendOutput();
                },
                () -> {
                    interactionActive.set(false);
                    frontend.resumeOutput();
                },
                frontend.renderLock());
        this.queueing = prompt instanceof LinedPrompt lined && lined.queuing();
    }

    @RootConfiguration("cli")
    public record CliConf(
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
                    defaultValue = "\"\"",
                    documentation =
                            "Name of a custom agent (from yca.agents) active by default. Its prompt is injected on top of the base system prompt.")
            String agent,

            @Property(
                    defaultValue = "true",
                    documentation =
                            "Enable ANSI colors. Color is only emitted when stdout is a terminal and neither NO_COLOR nor TERM=dumb is set.")
            boolean color,

            @Property(
                    defaultValue = "false",
                    documentation =
                            "Force ASCII-only symbols (no Unicode glyphs) in the output, useful for scripts or non-Unicode terminals.")
            boolean ascii,

            @Property(
                    defaultValue = "\"compact\"",
                    documentation =
                            "Tool output verbosity: compact (summaries and live progress), verbose (full dimmed stream) or raw (verbatim, legacy behaviour).")
            String presentation,

            @Property(defaultValue = "true", documentation = "Show the reasoning/thinking stream on stderr.")
            boolean showThinking,

            @Property(
                    defaultValue = "\"collapsed\"",
                    documentation =
                            "Tool output verbosity on stderr: shown (full invocation and output), collapsed (a single "
                                    + "summary line per tool) or hidden (nothing). Accepts the legacy booleans true/false "
                                    + "(true = shown, false = collapsed/off).")
            String showToolOutput,

            @Property(
                    defaultValue = "false",
                    documentation =
                            "Print the context usage (estimated tokens and percent of the context window) on stderr after each turn.")
            boolean showUsage,

            @Property(
                    defaultValue = "java.util.List.of()",
                    documentation =
                            "Prompts submitted at startup, in order, through the same path as a typed line: use it to "
                                    + "script a soft resume, e.g. continue re-runs the agent on the persisted history "
                                    + "without sending any message. A slash-prefixed entry is dispatched as a usual "
                                    + "slash command. Values are comma separated; prefix with "
                                    + "sep=<char>, to split on another single character when a value itself contains "
                                    + "commas.")
            List<String> initialPrompt) {

        /**
         * Convenience constructor for the common case: no prompt injected at startup.
         *
         * @param session resume an existing session id
         * @param context session context file
         * @param agent default custom agent name
         * @param color enable ANSI colors
         * @param ascii force ASCII-only symbols
         * @param presentation tool output verbosity
         * @param showThinking show the reasoning stream
         * @param showToolOutput tool output verbosity on stderr
         * @param showUsage print the context usage after each turn
         */
        public CliConf(
                final String session,
                final String context,
                final String agent,
                final boolean color,
                final boolean ascii,
                final String presentation,
                final boolean showThinking,
                final String showToolOutput,
                final boolean showUsage) {
            this(
                    session,
                    context,
                    agent,
                    color,
                    ascii,
                    presentation,
                    showThinking,
                    showToolOutput,
                    showUsage,
                    List.of());
        }
    }

    @Override
    public void run() {
        installInterruptHandler();
        // from here on this command owns the terminal: every writer (ours, the JUL root
        // handlers, a dependency) is funnelled through the prompt aware sink, so a log line
        // or a stray print landing between two turns can no longer overwrite the row jline
        // already painted and leave the prompt invisible until the next keystroke
        stdio.streamTarget(externalOutput);
        sessionId = sessionLifecycle.resolveSessionId(conf.session(), conf.context());
        contextPath = sessionStore.path(sessionId);
        try {
            sessionLifecycle.prepareContextFile(contextPath, conf.context());
        } catch (final IOException e) {
            println(MarkdownColorizer.error("failed to prepare context file " + contextPath + ": " + e.getMessage()));
        }
        println(MarkdownColorizer.info("session " + sessionId + " - context file: " + contextPath));
        // the banner reads the session runtime (permission mode), wire it before printing
        final var runtime = sessionRuntimeFactory.create(sessionId);
        session.runtime(runtime);
        printBanner();
        if (conf.agent() != null && !conf.agent().isBlank()) {
            if (agentProvider.byName(conf.agent()) == null) {
                throw new IllegalArgumentException(
                        "unknown agent: " + conf.agent() + " (configure one under yca.agents)");
            }
            activeAgent = conf.agent();
        }
        sessionLifecycle.installGates(io.approvalGate(), io.feedbackGate(), runtime);
        session.sessionId(sessionId).activeAgent(activeAgent);
        shellRegistry.register(env());
        final var shutdownHook = new Thread(this::shutdownCleanup, "yca-cli-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        try {
            submitInitialPrompts();
            repLoop();
        } finally {
            cleanup();
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (final IllegalStateException ignored) {
                // the JVM is already shutting down
            }
        }
    }

    /**
     * Best-effort cleanup, safe to run several times: both the normal exit path
     * (finally) and the JVM shutdown hook share it.
     */
    private void cleanup() {
        if (!cleaned.compareAndSet(false, true)) {
            return;
        }
        // give the terminal back before the closing lines so they go straight out
        if (stdio.streamTarget() == externalOutput) {
            stdio.streamTarget(null);
        }
        frontend.idle();
        shellRegistry.unregister(env());
        sessionLifecycle.release(sessionId, session.runtime());
        System.out.flush();
        System.err.flush();
        if (sessionId != null && contextPath != null && Files.exists(contextPath)) {
            println(MarkdownColorizer.info("session " + sessionId + " - context file: " + contextPath));
        } else {
            println(MarkdownColorizer.info("session " + sessionId + " (empty; no context file was created)"));
        }
    }

    /**
     * Invoked on JVM shutdown (SIGTERM, or Ctrl-C when the signal handler is not
     * installed): set the flags so an in-flight turn unwinds, then clean up.
     */
    private void shutdownCleanup() {
        cancelled.set(true);
        if (session.busy()) {
            // give the in-flight turn a short window to unwind and flush its
            // session entries before we tear the gates down
            final var deadline = System.nanoTime() + CLEANUP_AWAIT_NANOS;
            while (session.busy() && System.nanoTime() < deadline) {
                try {
                    Thread.sleep(25);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        quitRequested = true;
        cleanup();
    }

    private boolean colorEnabled(final boolean configured) {
        return configured && MarkdownColorizer.autoEnabled();
    }

    /**
     * Context-rich prompt line: reveals the agent mode (critical for a code
     * writing agent - plan vs build), the active model and the effective
     * permission posture (ask / auto / yolo / all). The rendering delegates to
     * the configurable handlebars template ({@code yca.prompt.template}) which
     * colors the mode and the permission badge when ANSI color is enabled and
     * falls back to plain text otherwise.
     */
    private String promptLine() {
        final var mode = session.runtime().permissions().mode().name().toLowerCase(Locale.ROOT);
        final var info = agentSessionService.permissionsInfo(session.runtime());
        final var usage = frontend.stats().lastUsage();
        final var model = chatClient.model(session.chatSettings());
        final var modelData = new LinkedHashMap<String, Object>();
        modelData.put("name", model);
        modelData.put("contextPercentage", usage == null ? 0 : (int) Math.round(usage.contextPercent()));
        modelData.put("contextTokens", usage == null ? 0 : usage.estimatedContextTokens());
        modelData.put("contextWindow", session.runtime().usage().contextWindow());

        final var data = new LinkedHashMap<String, Object>();
        data.put("mode", mode);
        data.put("modelName", model);
        data.put("permission", permissionLabel(info.permissionMode()));
        data.put("agent", activeAgent);
        data.put("model", modelData);
        data.put("prune", configuration.pruning().thresholdPercent());
        data.put("compact", configuration.compaction().thresholdPercent());
        data.put("color", colorizer.isEnabled());
        data.put("colors", promptColors(model));
        return promptTemplate.render(data);
    }

    private Map<String, String> promptColors(final String model) {
        if (!colorizer.isEnabled()) {
            return Map.of();
        }
        final var colors = new LinkedHashMap<>(configuration.prompt().colors());
        colors.put(
                "model",
                modelStyleService.resolve(chatClient.currentProvider(), model).ansiPrefix());
        return colors;
    }

    private String styledModel(final String model) {
        final var style = modelStyleService.resolve(chatClient.currentProvider(), model);
        return style.ansiPrefix() + model + "\u001b[0m";
    }

    private String permissionLabel(final PermissionMode mode) {
        if (mode == null) {
            return null;
        }
        return switch (mode) {
            case YOLO -> "yolo";
            case AUTO_EDIT -> "auto";
            default -> "ask";
        };
    }

    private void printBanner() {
        final var sb = new StringBuilder();
        sb.append(MarkdownColorizer.bold(symbols.toolMarker() + "yca"))
                .append(" · ")
                .append(session.runtime().permissions().mode().name().toLowerCase(Locale.ROOT))
                .append(" mode · session ")
                .append(sessionId);
        final var model = chatClient.model(session.chatSettings());
        if (model != null && !model.isBlank()) {
            sb.append(" · ").append(colorizer.isEnabled() ? styledModel(model) : model);
        }
        sb.append(
                " · type /help for commands · 'go' resumes the last turn · ctrl-c opens the interrupt menu (cancel turn / exit)");
        println(MarkdownColorizer.status(sb.toString()));
    }

    private void repLoop() {
        try {
            while (true) {
                if (queueing && turnActive()) {
                    if (awaitAndRouteWhileBusy()) {
                        if (quitRequested) {
                            break;
                        }
                        continue;
                    }
                    break;
                }
                println(dividingRule());
                final Optional<String> line;
                try {
                    line = awaitLine(promptLine());
                } catch (final PromptInterruptException e) {
                    onInterrupt();
                    if (quitRequested) {
                        break;
                    }
                    continue;
                }
                if (line.isEmpty() || quitRequested) {
                    break;
                }
                final var text = line.get().strip();
                if (text.isBlank()) {
                    continue;
                }
                if (!runLine(text)) {
                    break;
                }
            }
        } catch (final IOException e) {
            println(MarkdownColorizer.error("cli read failed: " + e.getMessage()));
        }
    }

    /**
     * Dispatches one non-blank user line exactly like the REPL: {@code exit} leaves the shell,
     * a slash command is dispatched through {@link #slash(String)} and anything else runs a
     * resume alias or a regular turn ({@link #runInput(String)}).
     *
     * @return {@code false} when the shell should exit.
     */
    private boolean runLine(final String text) {
        if (text.equals("exit")) {
            println(MarkdownColorizer.status("bye"));
            return false;
        }
        if (text.startsWith("/")) {
            slash(text);
            return !quitRequested;
        }
        runInput(text);
        println();
        return !quitRequested;
    }

    /**
     * Submits the {@code --initial-prompt} entries before the REPL takes over, in order, through
     * the very same dispatch as a typed line (see {@link #runLine(String)}): a resume alias such
     * as {@code continue} restarts the loop on the persisted history without sending any message,
     * a slash-prefixed entry is dispatched as a usual slash command and anything else runs a
     * regular turn. Entries are sequential, so in queueing mode (interactive terminal) each turn
     * waits for the previous one to complete instead of being integrated into it as a typed-ahead
     * prompt; slash entries at startup start no turn and have nothing to wait for.
     */
    private void submitInitialPrompts() {
        final var prompts = conf.initialPrompt();
        if (prompts == null || prompts.isEmpty()) {
            return;
        }
        for (final var prompt : prompts) {
            if (prompt == null || prompt.isBlank()) {
                continue;
            }
            final var text = prompt.strip();
            final var slash = text.startsWith("/");
            if (!runLine(text)) {
                break;
            }
            if (!slash && queueing) {
                final var future = inFlight;
                if (future != null) {
                    awaitTurn(future);
                }
            }
        }
    }

    /**
     * Runs one line of user input: a resume alias ({@code continue}, ...) re-runs the agent loop on
     * the persisted history, anything else is a regular (possibly queued) turn.
     */
    private void runInput(final String text) {
        if (agentSessionService.isResumeAlias(text)) {
            runResume();
        } else {
            runTurn(text);
        }
    }

    /**
     * While a turn runs the REPL keeps reading silently; every non-slash line
     * is queued and integrated by the running turn at its next checkpoint,
     * mirroring the TUI's prompt queue.
     *
     * @return {@code false} when the REPL should exit.
     */
    private boolean awaitAndRouteWhileBusy() throws IOException {
        final Optional<String> line;
        try {
            line = awaitLine("");
        } catch (final PromptInterruptException e) {
            // Ctrl-C while waiting: render the interrupt menu (or fall back to the legacy
            // immediate-cancel when the backend raises the exception without arming the menu)
            if (menuPending.compareAndSet(true, false)) {
                promptInterruptMenu();
            } else {
                onInterrupt();
            }
            return !quitRequested;
        }
        // a Ctrl-C woke the turn-lane read (wakeAll -> empty line): render the menu, keep looping
        if (menuPending.compareAndSet(true, false)) {
            promptInterruptMenu();
            return !quitRequested;
        }
        if (line.isEmpty() || quitRequested) {
            return false;
        }
        final var text = line.get().strip();
        if (text.isBlank()) {
            return true;
        }
        if (text.equals("exit")) {
            println(MarkdownColorizer.status("bye"));
            return false;
        }
        if (text.startsWith("/")) {
            slash(text);
            return !quitRequested;
        }
        pendingPrompts.addLast(text);
        println(MarkdownColorizer.status("queued prompt (" + pendingPrompts.size()
                + " pending) - integrated into the running turn at the next checkpoint"));
        drainQueueIfIdle();
        return !quitRequested;
    }

    private boolean turnActive() {
        return activeTurns.get() > 0 || session.busy();
    }

    /** Joins the (already completed for terminal IO) asynchronous read of the shell. */
    private Optional<String> awaitLine(final String promptText) throws IOException {
        try {
            return io.readLine(promptText).toCompletableFuture().join();
        } catch (final CompletionException e) {
            final var cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof PromptInterruptException interrupt) {
                throw interrupt;
            }
            throw cause instanceof IOException io ? io : new IOException(cause);
        }
    }

    /**
     * Reads a line for a non-REPL interaction. A Ctrl-C is treated as a
     * cancellation (returns an empty optional) after notifying the interrupt
     * handler.
     */
    private Optional<String> readLine(final String promptText) {
        try {
            return awaitLine(promptText);
        } catch (final PromptInterruptException e) {
            onInterrupt();
            return Optional.empty();
        } catch (final IOException e) {
            return Optional.empty();
        }
    }

    private void runTurn(final String input) {
        if (queueing) {
            pendingPrompts.addFirst(input);
            drainQueueIfIdle();
            return;
        }
        startPromptTurn(input);
    }

    private void startPromptTurn(final String text) {
        final var override = agentSessionService.agentOverride(text);
        final var agentName =
                override.agentName() != null ? override.agentName() : (activeAgent.isBlank() ? null : activeAgent);
        startTurn(override.prompt(), agentName, "prompt not sent (maven knowledge index still running)");
    }

    /**
     * Starts the next queued prompt as a fresh turn, but only when the CLI is
     * otherwise idle (the running turn already injects the queue directly).
     */
    private void drainQueueIfIdle() {
        turnLock.lock();
        try {
            if (!pendingPrompts.isEmpty() && !turnActive() && (inFlight == null || inFlight.isDone())) {
                final var next = pendingPrompts.pollFirst();
                if (next != null) {
                    startPromptTurn(next);
                }
            }
        } finally {
            turnLock.unlock();
        }
    }

    /**
     * Resume turn: re-runs the agent loop on the persisted history without sending any message,
     * so continuing an interrupted or truncated turn costs zero extra prompt tokens.
     */
    private void runResume() {
        startTurn(
                null,
                activeAgent.isBlank() ? null : activeAgent,
                "resume skipped (maven knowledge index still running)");
    }

    /**
     * Runs one turn through the shared {@link TurnRunner} (prompt {@code null}
     * means resume). In queueing mode it returns immediately so the REPL keeps
     * reading lines (which the running turn integrates); otherwise it blocks
     * until completion, keeping the CLI REPL synchronous by nature.
     */
    private void startTurn(final String prompt, final String agentName, final String blockedMessage) {
        cancelled.set(false);
        final var todosBefore = session.runtime().todos().all();
        activeTurns.incrementAndGet();
        final var future = turnRunner
                .submit(new TurnRunner.TurnRequest(
                        session.runtime().loop(),
                        env(),
                        sessionId,
                        agentName,
                        prompt,
                        () -> sessionStore.load(sessionId),
                        cancelled::get,
                        queueing ? () -> pendingPrompts.pollFirst() : null))
                .whenComplete((ran, error) -> {
                    activeTurns.decrementAndGet();
                    if (Boolean.FALSE.equals(ran)) {
                        println(MarkdownColorizer.status(blockedMessage));
                    }
                    printTodosIfChanged(todosBefore);
                    printUsage();
                    if (queueing) {
                        drainQueueIfIdle();
                    }
                })
                .toCompletableFuture();
        inFlight = future;
        if (!queueing) {
            awaitTurn(future);
        }
    }

    /**
     * Blocks until the turn completes so the divider and the next prompt are
     * painted the moment the turn future is done. While waiting, a pending
     * interrupt menu (armed by Ctrl-C on the signal thread) is rendered on this
     * REPL thread and answered; this is what makes "interrupt without exiting"
     * work even on the jline backend whose reader cannot be polled.
     */
    private void awaitTurn(final CompletableFuture<Boolean> future) {
        while (!future.isDone()) {
            if (menuPending.compareAndSet(true, false)) {
                promptInterruptMenu();
            }
            try {
                future.get(100, TimeUnit.MILLISECONDS);
            } catch (final ExecutionException e) {
                throw new CompletionException(e.getCause() == null ? e : e.getCause());
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CompletionException(e);
            } catch (final TimeoutException e) {
                // keep polling for the interrupt menu and the completion
            }
        }
    }

    /**
     * Prints the todo list once at the end of the turn, on stdout, when the
     * turn changed it; an unchanged list stays silent.
     */
    private void printTodosIfChanged(final List<TodoItem> before) {
        final var after = session.runtime().todos().all();
        if (after.equals(before)) {
            return;
        }
        println();
        println(MarkdownColorizer.status("todos:"));
        frontend.renderTodos(after).forEach(this::println);
    }

    private void printUsage() {
        final var stats = frontend.stats();
        final var usage = stats.lastUsage();
        if (usage == null) {
            return;
        }
        final var lastTurnMetrics = stats.lastTurnMetrics();
        final var toolsRun = stats.toolsRun();
        final var sb = new StringBuilder();
        sb.append(symbols.infoMarker());
        if (conf.showUsage()) {
            sb.append(String.format(
                    Locale.ROOT,
                    "~%,d tokens (%.1f%% of %,d)",
                    usage.estimatedContextTokens(),
                    usage.contextPercent(),
                    session.runtime().usage().contextWindow()));
            if (usage.usage() != null && usage.usage().cacheReadTokens() > 0) {
                sb.append(String.format(
                        Locale.ROOT, " · %,d cache-read tokens", usage.usage().cacheReadTokens()));
            }
            final var backgroundTokens = session.runtime().usage().auxiliaryPromptTokens()
                    + session.runtime().usage().auxiliaryCompletionTokens();
            if (backgroundTokens > 0) {
                sb.append(String.format(Locale.ROOT, " · %,d background tokens (compaction/memory)", backgroundTokens));
            }
        }
        if (session.metrics().showPerTurn() && lastTurnMetrics != null) {
            if (conf.showUsage()) {
                sb.append(" · ");
            }
            sb.append(lastTurnMetrics.llmCalls()).append(" LLM");
            if (lastTurnMetrics.thinkingDurationMs() > 0) {
                sb.append(" (").append(lastTurnMetrics.thinkingDurationMs()).append("ms think)");
            }
            final var tokPerSec = MetricsFormat.tokPerSec(lastTurnMetrics.throughput());
            if (!tokPerSec.isEmpty()) {
                sb.append(" · ").append(tokPerSec);
            }
            if (session.metrics().displayMode() == MetricsDisplayMode.FULL) {
                sb.append(" · ")
                        .append(lastTurnMetrics.toolsRun())
                        .append(" tools ")
                        .append("(")
                        .append(lastTurnMetrics.toolDurationSumMs())
                        .append("ms sum")
                        .append("/")
                        .append(lastTurnMetrics.toolDurationWallMs())
                        .append("ms wall)");
            } else {
                sb.append(" · ")
                        .append(lastTurnMetrics.toolsRun())
                        .append(" tools (")
                        .append(lastTurnMetrics.toolDurationSumMs())
                        .append("ms/")
                        .append(lastTurnMetrics.toolDurationWallMs())
                        .append("ms)");
            }
        } else if (toolsRun > 0) {
            if (conf.showUsage()) {
                sb.append(" · ");
            }
            sb.append(toolsRun).append(toolsRun == 1 ? " tool" : " tools");
        }
        if (session.metrics().showSessionTotals() && session.runtime().usage().requests() > 0) {
            if (conf.showUsage() || toolsRun > 0 || (session.metrics().showPerTurn() && lastTurnMetrics != null)) {
                sb.append(" · ");
            }
            sb.append("session: ").append(session.runtime().usage().requests()).append(" LLM calls");
        }
        final var elapsedMillis = (System.nanoTime() - stats.turnStartNanos()) / 1_000_000L;
        if (conf.showUsage()
                || toolsRun > 0
                || (session.metrics().showPerTurn() && lastTurnMetrics != null)
                || (session.metrics().showSessionTotals()
                        && session.runtime().usage().requests() > 0)) {
            sb.append(" · ");
        }
        sb.append(String.format(Locale.ROOT, "%.1fs", elapsedMillis / 1000.0));
        writeLineErr(MarkdownColorizer.status(sb.toString()));
    }

    /** The environment shared actions run against; the transcript is the shared one. */
    private ShellEnv env() {
        refreshLabels();
        return new ShellEnv(frontend, io, session, coordinator.transcript());
    }

    /**
     * Renders the current speaker labels (from {@code yca.labels}) into the shared
     * transcript before each interaction. Labels change with the active model, provider
     * and agent, so they are refreshed at every turn boundary: already-written blocks
     * keep the label they were written with, the next ones use the fresh render.
     */
    private void refreshLabels() {
        final var ctx = new SpeakerLabels.Ctx(
                chatClient.model(session.chatSettings()),
                providerManager == null ? null : providerManager.currentProviderId(),
                activeAgent,
                session.runtime() == null
                        ? null
                        : String.valueOf(session.runtime().permissions().mode()),
                session.sessionId(),
                null);
        coordinator.transcript().setLabels(speakerLabels.userLabel(ctx), speakerLabels.assistantLabel(ctx));
        coordinator.transcript().setLabelResolver((identity, user) -> {
            final var ictx =
                    new SpeakerLabels.Ctx(identity.model(), identity.provider(), identity.agent(), null, null, null);
            return user ? speakerLabels.userLabel(ictx) : speakerLabels.assistantLabel(ictx);
        });
    }

    private void slash(final String text) {
        final ShellOutcome outcome;
        try {
            outcome = slashRegistry.dispatch(text, env()).toCompletableFuture().join();
        } catch (final CompletionException e) {
            final var cause = e.getCause();
            if (cause instanceof PromptInterruptException) {
                onInterrupt();
                return;
            }
            frontend.error(cause == null ? e.getMessage() : cause.getMessage());
            return;
        }
        this.sessionId = session.sessionId();
        this.activeAgent = session.activeAgent() == null ? "" : session.activeAgent();
        outcome.applyTo(frontend);
        if (outcome instanceof ShellOutcome.Quit) {
            println(MarkdownColorizer.status("bye"));
            quitRequested = true;
        }
    }

    private void printHelp() {
        println(MarkdownColorizer.bold(symbols.toolMarker() + "yca cli - plain-terminal interactive session"));
        println("  type a prompt to run the agent, or use a slash command:");
        for (final var line : slashRegistry.helpMarkdown().split("\n", -1)) {
            println(colorizer.colorizeLine(line));
        }
        println(
                "  keys: Ctrl-C opens the interrupt menu (cancel the current turn / exit); pressed twice when idle it quits; Ctrl-D exits.");
    }

    private void installInterruptHandler() {
        try {
            // fired reflectively so the build does not depend on the internal
            // sun.misc package; when unavailable the portable shutdown hook covers Ctrl-C.
            final var signal = Class.forName("sun.misc.Signal");
            final var handler = Class.forName("sun.misc.SignalHandler");
            final var handle = signal.getMethod("handle", signal, handler);
            final var ctor = signal.getConstructor(String.class);
            final var interruptHandler = Proxy.newProxyInstance(
                    CliCommand.class.getClassLoader(), new Class<?>[] {handler}, (_, method, _) -> {
                        if (method.getName().equals("handle")) {
                            onInterrupt();
                        }
                        return null;
                    });
            handle.invoke(null, ctor.newInstance("INT"), interruptHandler);
        } catch (final Throwable ignored) {
            // signal handling is best-effort; the shutdown hook remains the portable fallback
        }
    }

    private void onInterrupt() {
        // while a turn runs with no interactive read live, SIGINT arms the menu; the menu is
        // rendered on the REPL thread (the signal handler must not touch the jline reader)
        if (session.busy() && !interactionActive.get()) {
            menuPending.set(true);
            // queueing mode: the REPL is blocked in a turn-lane read; wake it so it can render
            // the menu. Non-queueing (jline) awaits the turn future and polls menuPending itself.
            wakePrompt();
            return;
        }
        switch (interruptor.interrupt(session.busy())) {
            case INTERRUPT -> {
                cancelled.set(true);
                writeLineErr(
                        MarkdownColorizer.status("interrupting the current turn (ctrl-c again once idle to quit)..."));
            }
            case ARM_QUIT ->
                println(MarkdownColorizer.status("press Ctrl+C again within 2s to quit (Ctrl-D exits too)"));
            case QUIT -> {
                quitRequested = true;
                wakePrompt();
                println(MarkdownColorizer.status("bye"));
            }
        }
    }

    /**
     * Renders and answers the Ctrl-C interrupt menu: cancel the current turn (stays in the
     * REPL) or exit. The read goes through the interaction lane so the answer is never handed
     * to the turn queue; a Ctrl-C while the menu itself reads cancels the menu without effects.
     */
    private void promptInterruptMenu() {
        try {
            final var answer = interactionLine(
                            MarkdownColorizer.accent("What do you want to do? [1=cancel current turn, 2=exit] "))
                    .map(line -> line.strip().toLowerCase(Locale.ROOT))
                    .orElse("");
            if (answer.equals("1") || answer.equals("c") || answer.equals("cancel")) {
                cancelled.set(true);
                println(MarkdownColorizer.status("interrupting the current turn..."));
            } else if (answer.equals("2") || answer.equals("e") || answer.equals("exit") || answer.equals("q")) {
                // quitting also cancels a running turn so the REPL thread can actually exit
                cancelled.set(true);
                quitRequested = true;
                wakePrompt();
                println(MarkdownColorizer.status("bye"));
            } else {
                println(MarkdownColorizer.status("no change - turn keeps running"));
            }
        } catch (final PromptInterruptException e) {
            println(MarkdownColorizer.status("interrupt cancelled"));
        } catch (final IOException e) {
            println(MarkdownColorizer.status("interrupt cancelled"));
        }
    }

    /** Reads one line destined for an inline interaction (higher priority than a turn prompt). */
    private Optional<String> interactionLine(final String promptText) throws IOException {
        return prompt instanceof LinedPrompt lined
                ? lined.readInteractionLine(promptText)
                : prompt.readLine(promptText);
    }

    private void wakePrompt() {
        if (prompt instanceof LinedPrompt lined) {
            lined.wakeAll();
        }
    }

    /**
     * A thin divider rule printed before each user prompt so consecutive
     * turns remain visually separated across the scrolling stdout output.
     */
    private String dividingRule() {
        return MarkdownColorizer.dim(symbols.boxHorizontal().repeat(80));
    }

    private void println() {
        writeLine("\n");
    }

    private void println(final String text) {
        writeLine(text + "\n");
    }

    /**
     * Routes a completed line through the single prompt write path. Turn-end
     * output (todos, usage, dividers) and late async events race the next
     * prompt; the sink decides - atomically with the read lifecycle - between a
     * raw stdout write and a {@code printAbove} plus prompt repaint, so the
     * prompt stays visible without the user having to press a key for it to
     * reappear.
     */
    private void writeLine(final String line) {
        externalOutput.print(line);
    }

    /** Same as {@link #writeLine(String)} but on stderr (usage/metrics lines). */
    private void writeLineErr(final String line) {
        externalOutput.printErr(line + "\n");
    }
}
