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
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.MetricsDisplayMode;
import io.yupiik.ia.agent.core.configuration.PermissionMode;
import io.yupiik.ia.agent.core.context.UsageTracker;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.maven.MavenRepositoryKnowledgeService;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.session.AgentSessionService;
import io.yupiik.ia.agent.core.session.FileSessionStore;
import io.yupiik.ia.agent.core.shell.AnsiShellFrontend;
import io.yupiik.ia.agent.core.shell.AnsiShellIO;
import io.yupiik.ia.agent.core.shell.ConversationCoordinator;
import io.yupiik.ia.agent.core.shell.InterruptCoordinator;
import io.yupiik.ia.agent.core.shell.SessionLifecycle;
import io.yupiik.ia.agent.core.shell.ShellEnv;
import io.yupiik.ia.agent.core.shell.ShellOutcome;
import io.yupiik.ia.agent.core.shell.ShellRegistry;
import io.yupiik.ia.agent.core.shell.ShellSession;
import io.yupiik.ia.agent.core.shell.ToolOutputVisibility;
import io.yupiik.ia.agent.core.shell.TurnRunner;
import io.yupiik.ia.agent.core.shell.slash.SlashRegistry;
import io.yupiik.ia.agent.core.style.ModelStyleService;
import io.yupiik.ia.agent.core.todo.TodoItem;
import io.yupiik.ia.agent.core.todo.TodoStore;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

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
    private final PermissionService permissionService;
    private final SessionLifecycle sessionLifecycle;
    private final FileSessionStore sessionStore;
    private final TodoStore todoStore;
    private final AgentProvider agentProvider;
    private final ChatClient chatClient;
    private final ProviderManager providerManager;
    private final AgentSessionService agentSessionService;
    private final MavenRepositoryKnowledgeService mavenKnowledgeService;
    private final UsageTracker usageTracker;
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
    private final TurnRunner turnRunner;
    private final MarkdownColorizer colorizer;
    private final Symbols symbols;
    private final AnsiShellFrontend frontend;
    private final AnsiShellIO io;
    private final boolean queueing;
    private final Deque<String> pendingPrompts = new ConcurrentLinkedDeque<>();
    private final AtomicInteger activeTurns = new AtomicInteger();
    private final ReentrantLock turnLock = new ReentrantLock();
    private volatile CompletableFuture<Boolean> inFlight;
    private volatile boolean quitRequested;
    private String sessionId;
    private String activeAgent = "";
    private Path contextPath;
    private final AtomicBoolean cleaned = new AtomicBoolean();

    public CliCommand(
            final CliConf conf,
            final PermissionService permissionService,
            final SessionLifecycle sessionLifecycle,
            final FileSessionStore sessionStore,
            final TodoStore todoStore,
            final AgentProvider agentProvider,
            final UsageTracker usageTracker,
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
            final Prompt prompt,
            final Clock clock,
            final CodingAgentConfiguration configuration) {
        this.conf = conf;
        this.permissionService = permissionService;
        this.sessionLifecycle = sessionLifecycle;
        this.sessionStore = sessionStore;
        this.todoStore = todoStore;
        this.agentProvider = agentProvider;
        this.usageTracker = usageTracker;
        this.chatClient = chatClient;
        this.providerManager = providerManager;
        this.agentSessionService = agentSessionService;
        this.mavenKnowledgeService = mavenKnowledgeService;
        this.turnRunner = turnRunner;
        this.slashRegistry = slashRegistry;
        this.shellRegistry = shellRegistry;
        this.coordinator = coordinator;
        this.modelStyleService = modelStyleService;
        this.prompt = prompt;
        this.clock = clock;
        this.interruptor = new InterruptCoordinator(clock);
        this.configuration = configuration;
        session.metrics(configuration.metrics());
        final var parsedToolOutput = ToolOutputVisibility.parse(conf.showToolOutput());
        final var showToolOutput = parsedToolOutput == null ? ToolOutputVisibility.COLLAPSED : parsedToolOutput;
        session.showThinking(conf.showThinking()).showToolOutput(showToolOutput);
        this.colorizer = new MarkdownColorizer(colorEnabled(conf.color()));
        this.symbols = new Symbols(conf.ascii());
        this.frontend = new AnsiShellFrontend(
                colorizer,
                symbols,
                new ToolPresenter(mapper),
                CliVerbosity.parse(conf.presentation()),
                System.console() != null,
                conf.showThinking(),
                showToolOutput);
        this.io = new AnsiShellIO(
                prompt,
                symbols,
                this::onInterrupt,
                frontend::suspendOutput,
                frontend::resumeOutput,
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

            @Property(defaultValue = "false", documentation = "Bypass permission checks and allow all tool calls.")
            boolean permitAll) {}

    @Override
    public void run() {
        installInterruptHandler();
        sessionId = sessionLifecycle.resolveSessionId(conf.session(), conf.context());
        contextPath = sessionStore.path(sessionId);
        try {
            sessionLifecycle.prepareContextFile(contextPath, conf.context());
        } catch (final IOException e) {
            println(MarkdownColorizer.error("failed to prepare context file " + contextPath + ": " + e.getMessage()));
        }
        println(MarkdownColorizer.info("session " + sessionId + " - context file: " + contextPath));
        printBanner();
        if (conf.permitAll()) {
            println(MarkdownColorizer.warning(
                    "WARNING: --permit-all is on; all tool calls will run without approval."));
        }
        if (conf.agent() != null && !conf.agent().isBlank()) {
            if (agentProvider.byName(conf.agent()) == null) {
                throw new IllegalArgumentException(
                        "unknown agent: " + conf.agent() + " (configure one under yca.agents)");
            }
            activeAgent = conf.agent();
        }
        sessionLifecycle.installGates(io.approvalGate(), io.feedbackGate(), conf.permitAll());
        session.sessionId(sessionId).activeAgent(activeAgent);
        shellRegistry.register(env());
        final var shutdownHook = new Thread(this::shutdownCleanup, "yca-cli-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        try {
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
        frontend.idle();
        shellRegistry.unregister(env());
        sessionLifecycle.release(sessionId);
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
     * permission posture (ask / auto / yolo / all). When ANSI color is enabled
     * the mode and the permission badge are colour-coded so the safety state is
     * readable at a glance; otherwise they fall back to plain text.
     */
    private String promptLine() {
        final var mode = permissionService.mode().name().toLowerCase(Locale.ROOT);
        final var info = agentSessionService.permissionsInfo();
        final var permissionLabel = permissionLabel(info.permitAll(), info.permissionMode());
        final var sb = new StringBuilder("yca");
        if (colorizer.isEnabled()) {
            sb.append('[').append(modeColor(mode));
            final var model = chatClient.model();
            if (model != null && !model.isBlank()) {
                sb.append(MarkdownColorizer.dim("@")).append(styledModel(model));
            }
            sb.append(']');
            if (permissionLabel != null) {
                sb.append(' ')
                        .append(permissionColor(info.permitAll(), info.permissionMode())
                                .apply(permissionLine(permissionLabel)));
            }
        } else {
            sb.append('[').append(mode);
            final var model = chatClient.model();
            if (model != null && !model.isBlank()) {
                sb.append('·').append(model);
            }
            sb.append(']');
            if (permissionLabel != null) {
                sb.append(' ').append(permissionLine(permissionLabel));
            }
        }
        return sb.append("> ").toString();
    }

    private String modeColor(final String mode) {
        if (mode.equals("plan")) {
            return MarkdownColorizer.accent(mode);
        }
        return MarkdownColorizer.success(mode);
    }

    private String styledModel(final String model) {
        final var style = modelStyleService.resolve(chatClient.currentProvider(), model);
        return style.ansiPrefix() + model + "\u001b[0m";
    }

    private Function<String, String> permissionColor(final boolean permitAll, final PermissionMode mode) {
        if (permitAll) {
            return MarkdownColorizer::error;
        }
        if (mode == null) {
            return MarkdownColorizer::status;
        }
        return switch (mode) {
            case YOLO -> MarkdownColorizer::error;
            case AUTO_EDIT -> MarkdownColorizer::warning;
            default -> MarkdownColorizer::status;
        };
    }

    private static String permissionLine(final String label) {
        return "(" + label + ")";
    }

    private String permissionLabel(final boolean permitAll, final PermissionMode mode) {
        if (permitAll) {
            return "all";
        }
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
                .append(permissionService.mode().name().toLowerCase(Locale.ROOT))
                .append(" mode · session ")
                .append(sessionId);
        final var model = chatClient.model();
        if (model != null && !model.isBlank()) {
            sb.append(" · ").append(colorizer.isEnabled() ? styledModel(model) : model);
        }
        sb.append(" · type /help for commands · 'go' resumes the last turn · ctrl-c interrupts, twice quits");
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
                if (text.equals("exit")) {
                    println(MarkdownColorizer.status("bye"));
                    break;
                }
                if (text.startsWith("/")) {
                    slash(text);
                    if (quitRequested) {
                        break;
                    }
                    continue;
                }
                if (agentSessionService.isResumeAlias(text)) {
                    runResume();
                    println();
                    continue;
                }
                runTurn(text);
                println();
            }
        } catch (final IOException e) {
            println(MarkdownColorizer.error("cli read failed: " + e.getMessage()));
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
            onInterrupt();
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
        final var todosBefore = todoStore.all();
        activeTurns.incrementAndGet();
        final var future = turnRunner
                .submit(new TurnRunner.TurnRequest(
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
            future.join();
        }
    }

    /**
     * Prints the todo list once at the end of the turn, on stdout, when the
     * turn changed it; an unchanged list stays silent.
     */
    private void printTodosIfChanged(final List<TodoItem> before) {
        final var after = todoStore.all();
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
                    usageTracker.contextWindow()));
            if (usage.usage() != null && usage.usage().cacheReadTokens() > 0) {
                sb.append(String.format(
                        Locale.ROOT, " · %,d cache-read tokens", usage.usage().cacheReadTokens()));
            }
            final var backgroundTokens =
                    usageTracker.auxiliaryPromptTokens() + usageTracker.auxiliaryCompletionTokens();
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
        if (session.metrics().showSessionTotals() && usageTracker != null && usageTracker.requests() > 0) {
            if (conf.showUsage() || toolsRun > 0 || (session.metrics().showPerTurn() && lastTurnMetrics != null)) {
                sb.append(" · ");
            }
            sb.append("session: ").append(usageTracker.requests()).append(" LLM calls");
        }
        final var elapsedMillis = (System.nanoTime() - stats.turnStartNanos()) / 1_000_000L;
        if (conf.showUsage()
                || toolsRun > 0
                || (session.metrics().showPerTurn() && lastTurnMetrics != null)
                || (session.metrics().showSessionTotals() && usageTracker != null && usageTracker.requests() > 0)) {
            sb.append(" · ");
        }
        sb.append(String.format(Locale.ROOT, "%.1fs", elapsedMillis / 1000.0));
        System.err.println(MarkdownColorizer.status(sb.toString()));
    }

    /** The environment shared actions run against; the transcript is the shared one. */
    private ShellEnv env() {
        return new ShellEnv(frontend, io, session, coordinator.transcript());
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
        println("  keys: Ctrl-C interrupts the running turn; pressed twice when idle (within 2s) it quits.");
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
        switch (interruptor.interrupt(session.busy())) {
            case INTERRUPT -> {
                cancelled.set(true);
                System.err.println(
                        MarkdownColorizer.status("interrupting the current turn (ctrl-c again once idle to quit)..."));
            }
            case ARM_QUIT ->
                println(MarkdownColorizer.status("press Ctrl+C again within 2s to quit (Ctrl-D exits too)"));
            case QUIT -> {
                quitRequested = true;
                if (prompt instanceof LinedPrompt lined) {
                    lined.wakeAll();
                }
                println(MarkdownColorizer.status("bye"));
            }
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
        System.out.println();
    }

    private void println(final String text) {
        System.out.println(text);
    }
}
