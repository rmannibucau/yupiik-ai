package io.yupiik.ia.agent.core.cli;

import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.cli.Command;
import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.agent.AgentEventBridge;
import io.yupiik.ia.agent.core.agent.AgentLoop;
import io.yupiik.ia.agent.core.agent.AgentProvider;
import io.yupiik.ia.agent.core.cli.slash.ReplSlashCommands;
import io.yupiik.ia.agent.core.cli.slash.SlashCommandSpec;
import io.yupiik.ia.agent.core.cli.slash.SlashGroup;
import io.yupiik.ia.agent.core.configuration.PermissionMode;
import io.yupiik.ia.agent.core.context.UsageTracker;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.permissions.ApprovalGate;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.question.FeedbackGate;
import io.yupiik.ia.agent.core.question.Question;
import io.yupiik.ia.agent.core.question.UserFeedbackService;
import io.yupiik.ia.agent.core.session.AgentSessionService;
import io.yupiik.ia.agent.core.session.SessionStore;
import io.yupiik.ia.agent.core.todo.TodoItem;
import io.yupiik.ia.agent.core.todo.TodoStore;
import io.yupiik.ia.agent.core.wiring.ProviderManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private final AgentLoop agentLoop;
    private final AgentEventBridge bridge;
    private final PermissionService permissionService;
    private final UserFeedbackService userFeedbackService;
    private final SessionStore sessionStore;
    private final TodoStore todoStore;
    private final AgentProvider agentProvider;
    private final ChatClient chatClient;
    private final ProviderManager providerManager;
    private final AgentSessionService agentSessionService;
    private final UsageTracker usageTracker;
    private final ReplSlashCommands replSlashCommands;
    private final Clock clock;

    private static final long QUIT_WINDOW_MILLIS = 2000;
    private static final int DIFF_MAX_LINES = 5;

    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean busy = new AtomicBoolean();
    private final MarkdownColorizer colorizer;
    private final StreamTextBuffer textBuffer;
    private final Symbols symbols;
    private final ToolPresenter presenter;
    private final CliVerbosity verbosity;
    private final boolean tty;
    private volatile boolean showThinking;
    private volatile boolean showToolOutput;
    private Spinner spinner;
    private volatile long lastInterruptAt;
    private volatile boolean quitRequested;
    private volatile String lastToolArgs;
    private volatile long turnStartNanos;
    private volatile int toolsRun;
    private volatile AgentEvent.UsageUpdated lastUsageEvent;
    private BufferedReader stdin;
    private String sessionId;
    private String activeAgent = "";
    private Path contextPath;
    private final AtomicBoolean cleaned = new AtomicBoolean();

    public CliCommand(final CliConf conf, final AgentLoop agentLoop, final AgentEventBridge bridge,
                      final PermissionService permissionService, final UserFeedbackService userFeedbackService,
                      final SessionStore sessionStore, final TodoStore todoStore,
                      final AgentProvider agentProvider,
                      final UsageTracker usageTracker,
                      final ChatClient chatClient,
                      final ProviderManager providerManager,
                      final AgentSessionService agentSessionService,
                      final JsonMapper mapper, final ReplSlashCommands replSlashCommands, final Clock clock) {
        this.conf = conf;
        this.agentLoop = agentLoop;
        this.bridge = bridge;
        this.permissionService = permissionService;
        this.userFeedbackService = userFeedbackService;
        this.sessionStore = sessionStore;
        this.todoStore = todoStore;
        this.agentProvider = agentProvider;
        this.usageTracker = usageTracker;
        this.chatClient = chatClient;
        this.providerManager = providerManager;
        this.agentSessionService = agentSessionService;
        this.replSlashCommands = replSlashCommands;
        this.clock = clock;
        this.colorizer = new MarkdownColorizer(colorEnabled(conf.color()));
        this.textBuffer = new StreamTextBuffer(colorizer::colorizeLine);
        this.symbols = new Symbols(conf.ascii());
        this.presenter = new ToolPresenter(mapper);
        this.verbosity = CliVerbosity.parse(conf.presentation());
        this.tty = System.console() != null;
        this.showThinking = conf.showThinking();
        this.showToolOutput = conf.showToolOutput();
    }

    @RootConfiguration("cli")
    public record CliConf(
            @Property(defaultValue = "\"\"", documentation = "Resume an existing session by id; a new session is created when empty.") String session,
            @Property(defaultValue = "\"\"", documentation = "Session context file (a yca session JSONL). Its file name provides the session id to resume or create.") String context,
            @Property(defaultValue = "\"\"", documentation = "Name of a custom agent (from yca.agents) active by default. Its prompt is injected on top of the base system prompt.") String agent,
            @Property(defaultValue = "true", documentation = "Enable ANSI colors. Color is only emitted when stdout is a terminal and neither NO_COLOR nor TERM=dumb is set.") boolean color,
            @Property(defaultValue = "false", documentation = "Force ASCII-only symbols (no Unicode glyphs) in the output, useful for scripts or non-Unicode terminals.") boolean ascii,
            @Property(defaultValue = "\"compact\"", documentation = "Tool output verbosity: compact (summaries and live progress), verbose (full dimmed stream) or raw (verbatim, legacy behaviour).") String presentation,
            @Property(defaultValue = "true", documentation = "Show the reasoning/thinking stream on stderr.") boolean showThinking,
            @Property(defaultValue = "true", documentation = "Show tool invocations and output on stderr.") boolean showToolOutput,
            @Property(defaultValue = "false", documentation = "Print the context usage (estimated tokens and percent of the context window) on stderr after each turn.") boolean showUsage,
            @Property(defaultValue = "false", documentation = "Bypass permission checks and allow all tool calls.") boolean permitAll) {
    }

    @Override
    public void run() {
        stdin = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        installInterruptHandler();
        sessionId = resolveSessionId();
        contextPath = sessionStore.path(sessionId);
        if (conf.context() != null && !conf.context().isBlank()) {
            try {
                Files.createDirectories(contextPath.getParent() == null ? Path.of(".") : contextPath.getParent());
                if (!Files.isRegularFile(contextPath)) {
                    Files.writeString(contextPath, "");
                }
            } catch (final IOException e) {
                println(MarkdownColorizer.error("failed to prepare context file " + contextPath + ": " + e.getMessage()));
            }
        }
        println(MarkdownColorizer.status("session " + sessionId + " - context file: " + contextPath));
        printBanner();
        if (conf.permitAll()) {
            println(MarkdownColorizer.warning("WARNING: --permit-all is on; all tool calls will run without approval."));
        }
        if (conf.agent() != null && !conf.agent().isBlank()) {
            if (agentProvider.byName(conf.agent()) == null) {
                throw new IllegalArgumentException("unknown agent: " + conf.agent() + " (configure one under yca.agents)");
            }
            activeAgent = conf.agent();
        }
        permissionService.setPermitAll(conf.permitAll());
        permissionService.setGate(approvalGate());
        userFeedbackService.setGate(feedbackGate());
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
        stopSpinner();
        bridge.sink(null);
        permissionService.setGate(null);
        permissionService.setPermitAll(false);
        userFeedbackService.setGate(null);
        System.out.flush();
        System.err.flush();
        println(MarkdownColorizer.status("session " + sessionId + " - context file: " + contextPath));
    }

    /**
     * Invoked on JVM shutdown (SIGTERM, or Ctrl-C when the signal handler is not
     * installed): set the flags so an in-flight turn unwinds, then clean up.
     */
    private void shutdownCleanup() {
        cancelled.set(true);
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
                sb.append(MarkdownColorizer.dim("·")).append(MarkdownColorizer.dim(model));
            }
            sb.append(']');
            if (permissionLabel != null) {
                sb.append(' ').append(permissionColor(info.permitAll(), info.permissionMode()).apply(permissionLine(permissionLabel)));
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
                .append(" · ").append(permissionService.mode().name().toLowerCase(Locale.ROOT))
                .append(" mode · session ").append(sessionId);
        final var model = chatClient.model();
        if (model != null && !model.isBlank()) {
            sb.append(" · ").append(model);
        }
        sb.append(" · type /help for commands");
        println(MarkdownColorizer.status(sb.toString()));
    }

    private void repLoop() {
        try {
            while (true) {
                print(promptLine());
                final var line = stdin.readLine();
                if (line == null || quitRequested) {
                    break;
                }
                final var text = line.strip();
                if (text.isBlank()) {
                    continue;
                }
                if (text.equals("exit")) {
                    println(MarkdownColorizer.status("bye"));
                    break;
                }
                if (text.startsWith("/")) {
                    if (slash(text)) {
                        break;
                    }
                    continue;
                }
                println();
                runTurn(text);
                println();
            }
        } catch (final IOException e) {
            println(MarkdownColorizer.error("cli read failed: " + e.getMessage()));
        }
    }

    private void runTurn(final String input) {
        final var override = agentSessionService.agentOverride(input);
        final var agentName = override.agentName() != null ? override.agentName()
                : (activeAgent.isBlank() ? null : activeAgent);
        final var prompt = override.prompt();
        cancelled.set(false);
        busy.set(true);
        toolsRun = 0;
        turnStartNanos = System.nanoTime();
        textBuffer.reset();
        stopSpinner();
        beginSpinner("thinking");
        final var todosBefore = todoStore.all();
        final var history = sessionStore.load(sessionId);
        bridge.sink(this::dispatch);
        try {
            agentLoop.run(prompt, agentName, sessionId, history, cancelled::get);
        } finally {
            bridge.sink(null);
            busy.set(false);
            stopSpinner();
            print(textBuffer.flush());
            System.out.flush();
            System.err.flush();
            printTodosIfChanged(todosBefore);
            printUsage();
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
        renderTodos(after).forEach(this::println);
    }

    /**
     * Colored, current-state rendering of the todo list: green check for
     * completed, yellow arrow for in-progress, dim dot for pending, plus a
     * done/total progress line.
     */
    private List<String> renderTodos(final List<TodoItem> todos) {
        final var out = new ArrayList<String>();
        if (todos.isEmpty()) {
            out.add(MarkdownColorizer.status("(no todos)"));
            return out;
        }
        var done = 0;
        for (final var item : todos) {
            switch (item.status() == null ? "" : item.status()) {
                case "in_progress" -> out.add(MarkdownColorizer.warning(symbols.todoInProgress() + item.content()));
                case "completed" -> {
                    out.add(MarkdownColorizer.diffAdd(symbols.todoCompleted() + item.content()));
                    done++;
                }
                default -> out.add(MarkdownColorizer.dim(symbols.todoPending() + item.content()));
            }
        }
        out.add(MarkdownColorizer.status(done + "/" + todos.size() + " done"));
        return out;
    }

    private void printUsage() {
        if (lastUsageEvent == null) {
            return;
        }
        final var sb = new StringBuilder();
        sb.append(symbols.infoMarker());
        if (conf.showUsage()) {
            final var usage = lastUsageEvent;
            sb.append(String.format(Locale.ROOT, "~%,d tokens (%.1f%% of %,d)",
                    usage.estimatedContextTokens(), usage.contextPercent(), usageTracker.contextWindow()));
        }
        if (toolsRun > 0) {
            if (conf.showUsage()) {
                sb.append(" · ");
            }
            sb.append(toolsRun).append(toolsRun == 1 ? " tool" : " tools");
        }
        final var elapsedMillis = (System.nanoTime() - turnStartNanos) / 1_000_000L;
        if (conf.showUsage() || toolsRun > 0) {
            sb.append(" · ");
        }
        sb.append(String.format(Locale.ROOT, "%.1fs", elapsedMillis / 1000.0));
        System.err.println(MarkdownColorizer.status(sb.toString()));
    }

    private void dispatch(final AgentEvent event) {
        if (event instanceof AgentEvent.TextDelta(String delta)) {
            stopSpinner();
            print(textBuffer.append(delta));
        } else if (event instanceof AgentEvent.ThinkingDelta(String delta)) {
            if (showThinking) {
                if (verbosity == CliVerbosity.RAW) {
                    System.err.print(MarkdownColorizer.dim(delta));
                } else {
                    beginSpinner("thinking");
                }
            }
        } else if (event instanceof final AgentEvent.ToolStarted t) {
            stopSpinner();
            onToolStarted(t);
        } else if (event instanceof final AgentEvent.ToolStream t) {
            if (verbosity == CliVerbosity.RAW) {
                System.err.print(t.fragment().endsWith("\n") ? t.fragment() : t.fragment() + "\n");
            } else if (verbosity == CliVerbosity.VERBOSE) {
                System.err.print(MarkdownColorizer.dim(t.fragment()));
            }
        } else if (event instanceof final AgentEvent.ToolFinished t) {
            toolsRun++;
            stopSpinner();
            if (showToolOutput) {
                onToolFinished(t);
            }
            beginSpinner("thinking");
        } else if (event instanceof AgentEvent.Compacted(String kind)) {
            stopSpinner();
            System.err.println(MarkdownColorizer.status(symbols.infoMarker() + "context " + kind + " applied"));
        } else if (event instanceof AgentEvent.Status(String message)) {
            stopSpinner();
            System.err.println(MarkdownColorizer.status(symbols.infoMarker() + message));
        } else if (event instanceof AgentEvent.Error(String message)) {
            stopSpinner();
            System.err.println(MarkdownColorizer.error("[error] " + message));
        } else if (event instanceof final AgentEvent.UsageUpdated u) {
            lastUsageEvent = u;
        }
    }

    private void onToolStarted(final AgentEvent.ToolStarted t) {
        if (!showToolOutput) {
            return;
        }
        if (verbosity == CliVerbosity.RAW) {
            System.err.println(MarkdownColorizer.status("[tool] " + t.name() + " " + t.args()));
            return;
        }
        System.err.println(MarkdownColorizer.bold(symbols.toolMarker() + presenter.summarize(t.name(), t.args())));
        if (verbosity == CliVerbosity.COMPACT && t.name().equals("bash")) {
            beginSpinner("running " + presenter.summarize(t.name(), t.args()));
        }
    }

    private void onToolFinished(final AgentEvent.ToolFinished t) {
        stopSpinner();
        if (verbosity == CliVerbosity.RAW) {
            if (!t.success() && t.output() != null && !t.output().isBlank()) {
                System.err.println(MarkdownColorizer.error(t.output()));
            }
            return;
        }
        final var indent = "  ";
        final var result = presenter.result(t.name(), t.output(), t.success());
        System.err.println(MarkdownColorizer.dim(indent + symbols.resultPrefix())
                + (t.success() ? MarkdownColorizer.success(result) : MarkdownColorizer.error(result)));
        if (verbosity == CliVerbosity.COMPACT) {
            final var diff = presenter.diff(t.name(), lastToolArgs, DIFF_MAX_LINES);
            for (final var line : diff) {
                System.err.println(indent + colorizeDiff(line));
            }
        }
    }

    private String colorizeDiff(final String line) {
        final var marker = line.startsWith(symbols.diffDel()) ? symbols.diffDel()
                : line.startsWith(symbols.diffAdd()) ? symbols.diffAdd() : null;
        if (marker == null) {
            return MarkdownColorizer.dim(line);
        }
        final var rest = line.substring(marker.length());
        return (marker.equals(symbols.diffDel()) ? MarkdownColorizer.diffDel(marker + rest)
                : MarkdownColorizer.diffAdd(marker + rest));
    }

    /**
     * Starts the spinner when none is running; a thinking stream can call this
     * once per token, so reusing the active spinner avoids leaking one thread
     * (and one elapsed timer) per delta.
     */
    private void beginSpinner(final String label) {
        if (!tty || spinner != null) {
            return;
        }
        spinner = new Spinner(symbols, System.err, tty);
        spinner.start(label);
    }

    private void stopSpinner() {
        if (spinner != null) {
            spinner.stop();
            spinner = null;
        }
    }

    private boolean slash(final String text) {
        final var value = text.substring(1);
        final var command = value.split("\\s+", 2);
        final var name = command[0].toLowerCase(Locale.ROOT);
        final var arg = command.length > 1 ? command[1].strip() : "";
        final var handler = slashHandlers().get(name);
        if (handler == null) {
            println(MarkdownColorizer.status("unknown command /" + name + " - type /help"));
            return false;
        }
        return handler.apply(arg);
    }

    private Map<String, Function<String, Boolean>> slashHandlers() {
        return Map.ofEntries(
                Map.entry("help", arg -> {
                    printHelp();
                    return false;
                }),
                Map.entry("shortcuts", arg -> {
                    printShortcuts();
                    return false;
                }),
                Map.entry("clear", arg -> {
                    clearScreen();
                    return false;
                }),
                Map.entry("quit", arg -> {
                    println(MarkdownColorizer.status("bye"));
                    return true;
                }),
                Map.entry("exit", arg -> {
                    println(MarkdownColorizer.status("bye"));
                    return true;
                }),
                Map.entry("new", arg -> {
                    sessionId = sessionStore.create();
                    println(MarkdownColorizer.status("new session " + sessionId));
                    return false;
                }),
                Map.entry("resume", arg -> {
                    if (arg.isBlank()) {
                        println(MarkdownColorizer.status("usage: /resume <session-id>"));
                        return false;
                    }
                    sessionId = arg;
                    println(MarkdownColorizer.status("resumed session " + sessionId));
                    return false;
                }),
                Map.entry("mode", arg -> {
                    mode(arg);
                    return false;
                }),
                Map.entry("model", arg -> {
                    model(arg);
                    return false;
                }),
                Map.entry("provider", arg -> {
                    provider(arg);
                    return false;
                }),
                Map.entry("agent", arg -> {
                    agent(arg);
                    return false;
                }),
                Map.entry("context", arg -> {
                    context(arg);
                    return false;
                }),
                Map.entry("memory", arg -> {
                    memory();
                    return false;
                }),
                Map.entry("todos", arg -> {
                    todos();
                    return false;
                }),
                Map.entry("tools", arg -> {
                    tools(arg);
                    return false;
                }),
                Map.entry("permissions", arg -> {
                    permissions(arg);
                    return false;
                }),
                Map.entry("thinking", arg -> {
                    thinking(arg);
                    return false;
                }),
                Map.entry("tool-output", arg -> {
                    toolOutput(arg);
                    return false;
                }),
                Map.entry("structural-code-graph", arg -> {
                    resyncGraph();
                    return false;
                }),
                Map.entry("compact", arg -> {
                    compact();
                    return false;
                }),
                Map.entry("editplan", arg -> {
                    editPlan(arg);
                    return false;
                }));
    }

    private void mode(final String arg) {
        final var result = agentSessionService.setMode(arg, sessionId);
        println(MarkdownColorizer.status(result.message()));
        if (result.seedMessage() != null) {
            println(MarkdownColorizer.status(result.seedMessage()));
        }
        if (result.seedError() != null) {
            println(MarkdownColorizer.error(result.seedError()));
        }
    }

    private void model(final String arg) {
        if (arg.isBlank()) {
            try {
                final var models = chatClient.models().ids();
                println(MarkdownColorizer.status("models (" + models.size() + "):"));
                final var current = chatClient.model();
                for (final var m : models) {
                    println(currentEntry(Objects.equals(m, current), m));
                }
                println(MarkdownColorizer.status("use /model <id> to switch"));
            } catch (final IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                println(MarkdownColorizer.error("cannot list models: " + e.getMessage()));
            }
            return;
        }
        final var model = agentSessionService.setModel(arg);
        println(MarkdownColorizer.status("model: " + model));
    }

    private void provider(final String arg) {
        final var providers = providerManager.providers();
        if (!arg.isBlank()) {
            final var id = agentSessionService.setProvider(arg);
            println(MarkdownColorizer.status("provider: " + id));
            return;
        }
        final var current = chatClient.providerId();
        println(MarkdownColorizer.status("providers (" + providers.size() + "):"));
        if (providers.isEmpty()) {
            println("(no providers configured)");
        } else {
            for (final var p : providers) {
                final var id = p.id();
                println(currentEntry(Objects.equals(id, current), id));
            }
        }
        println(MarkdownColorizer.status("use /provider <id> to switch"));
    }

    private void agent(final String arg) {
        if (arg.isBlank()) {
            final var agents = agentProvider.agents();
            println(MarkdownColorizer.status("custom agents (" + agents.size() + "):"));
            if (agents.isEmpty()) {
                println("(no custom agents configured; add them under yca.agents)");
            } else {
                for (final var a : agents) {
                    println(currentEntry(Objects.equals(a.name(), activeAgent), a.name()));
                }
            }
            println(MarkdownColorizer.status("use /agent <name> to set the active agent, /agent to clear it"));
            return;
        }
        final var name = agentSessionService.setAgent(arg);
        if (name == null) {
            println(MarkdownColorizer.error("unknown agent: " + arg));
            return;
        }
        activeAgent = name;
        println(MarkdownColorizer.status("agent: " + name));
    }

    /**
     * Renders one listing entry, marking the current selection with the filled
     * dot marker and dimming the marker colour for inactive entries.
     */
    private String currentEntry(final boolean isCurrent, final String value) {
        final var marker = isCurrent ? symbols.currentMarker() : " ";
        final var name = isCurrent ? MarkdownColorizer.accent(value) : value;
        return marker + " " + name + (isCurrent ? MarkdownColorizer.status(" (current)") : "");
    }

    private void context(final String arg) {
        final var dump = agentSessionService.context(sessionId);
        final var sb = new StringBuilder();
        sb.append("## System prompt\n\n").append(dump.systemPrompt()).append("\n\n");
        sb.append("## Session ").append(dump.sessionId()).append(" (").append(dump.messages().size()).append(" messages)\n\n");
        for (final var message : dump.messages()) {
            sb.append("- ").append(message.role()).append(": ").append(message.firstLine()).append('\n');
        }
        if (arg.isBlank()) {
            println(sb.toString());
            return;
        }
        try {
            final var target = Path.of(arg).toAbsolutePath();
            Files.createDirectories(target.getParent() == null ? Path.of(".") : target.getParent());
            Files.writeString(target, sb.toString());
            println(MarkdownColorizer.status("context written to " + target));
        } catch (final IOException e) {
            println(MarkdownColorizer.error("context failed: " + e.getMessage()));
        }
    }

    private void memory() {
        final var memory = agentSessionService.memory();
        if (memory.global().isBlank() && memory.project().isBlank()) {
            println(MarkdownColorizer.status("(empty memory)"));
            return;
        }
        if (!memory.global().isBlank()) {
            println(MarkdownColorizer.status("-- global memory --"));
            println(memory.global());
        }
        if (!memory.project().isBlank()) {
            println(MarkdownColorizer.status("-- project memory --"));
            println(memory.project());
        }
    }

    private void todos() {
        renderTodos(todoStore.all()).forEach(this::println);
    }

    private void tools(final String arg) {
        final var listing = agentSessionService.tools(arg);
        if (!listing.anyTool()) {
            println(MarkdownColorizer.status("(no tools available)"));
            return;
        }
        for (final var server : listing.servers()) {
            println(MarkdownColorizer.accent(server.server()));
            for (final var tool : server.tools()) {
                println("  " + tool.name() + (tool.description() == null ? "" : ": " + tool.description()));
            }
        }
        if (listing.matches() == 0) {
            println(MarkdownColorizer.status("(no tool matches)"));
        }
    }

    private void permissions(final String arg) {
        final var value = arg == null ? "" : arg.strip();
        if (value.equals("show") || value.equals("list")) {
            final var info = agentSessionService.permissionsInfo();
            println(MarkdownColorizer.accent("permissions status (session-scoped):"));
            println(MarkdownColorizer.status("permit all: " + (info.permitAll() ? "on" : "off")));
            println(MarkdownColorizer.status("agent mode: " + info.agentMode().name().toLowerCase(Locale.ROOT)
                    + ", permission mode: " + (info.permissionMode() == null ? "?" : info.permissionMode().toString().toLowerCase(Locale.ROOT))));
            if (info.sessionGrants().isEmpty()) {
                println(MarkdownColorizer.status("session grants: none (use 'a' in the approval prompt to add one)"));
            } else {
                println(MarkdownColorizer.status("session grants (in-memory, lost on restart):"));
                for (final var grant : info.sessionGrants()) {
                    println(MarkdownColorizer.status("  - " + grant));
                }
            }
            return;
        }
        final var permitAll = agentSessionService.setPermissions(value);
        if (permitAll == null) {
            println(MarkdownColorizer.status("usage: /permissions all|none|show"));
        } else if (permitAll) {
            println(MarkdownColorizer.status("permissions: all tools allowed"));
        } else {
            println(MarkdownColorizer.status("permissions: asking for approval"));
        }
    }

    private void thinking(final String arg) {
        final var parsed = parseShowHide(arg);
        if (parsed == null) {
            println(MarkdownColorizer.status("thinking " + (showThinking ? "shown" : "hidden") + " - usage: /thinking show|hide"));
            return;
        }
        showThinking = parsed;
        println(MarkdownColorizer.status("thinking " + (showThinking ? "shown" : "hidden")));
    }

    private void toolOutput(final String arg) {
        final var parsed = parseShowHide(arg);
        if (parsed == null) {
            println(MarkdownColorizer.status("tool output " + (showToolOutput ? "shown" : "hidden") + " - usage: /tool-output show|hide"));
            return;
        }
        showToolOutput = parsed;
        println(MarkdownColorizer.status("tool output " + (showToolOutput ? "shown" : "hidden")));
    }

    private Boolean parseShowHide(final String arg) {
        final var value = arg.strip().toLowerCase(Locale.ROOT);
        if (value.equals("show")) {
            return true;
        }
        if (value.equals("hide")) {
            return false;
        }
        return null;
    }

    private void resyncGraph() {
        try {
            final var result = agentSessionService.resyncGraph();
            println(MarkdownColorizer.status(result));
        } catch (final RuntimeException e) {
            println(MarkdownColorizer.error("resync failed: " + e.getMessage()));
        }
    }

    private void compact() {
        final var result = agentSessionService.compact(sessionId);
        println(MarkdownColorizer.status(result == null ? "nothing to compact" : result));
    }

    private void editPlan(final String arg) {
        final var export = agentSessionService.exportPlan(sessionId, arg, "");
        if (export.error() != null) {
            println(MarkdownColorizer.error(export.error()));
            return;
        }
        println(MarkdownColorizer.status("plan exported to " + export.path()));
        println(MarkdownColorizer.status("edit the file (# Plan and # Todos sections), then press Enter to reload, or type 'c' to cancel"));
        try {
            final var input = stdin.readLine();
            if (input != null) {
                final var decision = input.trim();
                if (decision.equalsIgnoreCase("c") || decision.equalsIgnoreCase("cancel")) {
                    println(MarkdownColorizer.status("plan edit cancelled"));
                    return;
                }
            }
        } catch (final IOException e) {
            println(MarkdownColorizer.error("editplan read failed: " + e.getMessage()));
            return;
        }
        final var reload = agentSessionService.reloadPlan(sessionId, export.path(), export.original());
        if (reload.error() != null) {
            println(MarkdownColorizer.error(reload.error()));
        } else {
            println(MarkdownColorizer.status(reload.message()));
        }
    }

    private ApprovalGate approvalGate() {
        return (title, detail, toolName, args) -> {
            stopSpinner();
            printApprovalBox(title, detail);
            print(MarkdownColorizer.accent("allow? [y/n/a] (y=once, n=no, a=always this session) "));
            try {
                final var line = stdin.readLine();
                if (line != null) {
                    final var c = line.strip().toLowerCase(Locale.ROOT);
                    if (c.equals("y") || c.equals("yes") || c.equals("o") || c.equals("ok")) {
                        return ApprovalGate.Result.ALLOW_ONCE;
                    }
                    if (c.equals("a") || c.equals("always")) {
                        return ApprovalGate.Result.ALLOW_SESSION;
                    }
                }
            } catch (final IOException ignored) {
                // treat as deny
            }
            return ApprovalGate.Result.DENY;
        };
    }

    private void printApprovalBox(final String title, final String detail) {
        final var lines = new ArrayList<String>();
        lines.add("Permission required");
        lines.add(title);
        if (detail != null && !detail.isBlank()) {
            detail.lines().forEach(line -> {
                if (!line.isBlank()) {
                    lines.add(line);
                }
            });
        }
        var width = 0;
        for (final var line : lines) {
            width = Math.max(width, line.length());
        }
        width = Math.clamp(width, 32, 72);
        final var rule = symbols.boxHorizontal().repeat(width);
        println(MarkdownColorizer.accent(symbols.boxTop() + rule + symbols.boxCorner()));
        for (int i = 0; i < lines.size(); i++) {
            for (final var fragment : wrapLine(lines.get(i), width)) {
                final var styled = i == 0 ? MarkdownColorizer.bold(padTo(fragment, width))
                        : i == 1 ? MarkdownColorizer.warning(padTo(fragment, width))
                        : MarkdownColorizer.status(padTo(fragment, width));
                println(MarkdownColorizer.accent(symbols.boxLine()) + styled);
            }
        }
        println(MarkdownColorizer.accent(symbols.boxBottom() + rule + symbols.boxCornerBottom()));
    }

    private static List<String> wrapLine(final String text, final int width) {
        if (text.isEmpty()) {
            return List.of("");
        }
        final var out = new ArrayList<String>();
        final var words = text.split("\\s+");
        final var sb = new StringBuilder();
        for (final var word : words) {
            if (!sb.isEmpty() && sb.length() + 1 + word.length() > width) {
                out.add(sb.toString());
                sb.setLength(0);
            }
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(word);
        }
        out.add(sb.toString());
        return out;
    }

    private static String padTo(final String text, final int width) {
        if (text.length() > width) {
            return text.substring(0, Math.max(0, width - 1)) + "...";
        }
        final var sb = new StringBuilder(text);
        while (sb.length() < width) {
            sb.append(' ');
        }
        return sb.toString();
    }

    private FeedbackGate feedbackGate() {
        return questions -> {
            if (questions == null || questions.isEmpty()) {
                return Map.of();
            }
            final var reader = stdin;
            final Map<String, Object> answers = new HashMap<>();
            boolean restart;
            do {
                restart = false;
                answers.clear();
                for (final var question : questions) {
                    final var answer = askOne(reader, question);
                    if (answer == null) {
                        return null;
                    }
                    answers.put(question.id(), answer);
                }
                // summary
                println(MarkdownColorizer.status("your answers:"));
                for (final var question : questions) {
                    println("- " + question.prompt() + " -> " + answers.get(question.id()));
                }
                print(MarkdownColorizer.status("confirm? (a=accept, e=restart, c=cancel) [a] "));
                try {
                    final var line = reader.readLine();
                    if (line != null) {
                        final var c = line.strip().toLowerCase(Locale.ROOT);
                        if (c.equals("e") || c.equals("edit") || c.equals("restart")) {
                            restart = true;
                        } else if (c.equals("c") || c.equals("cancel")) {
                            return null;
                        }
                    }
                } catch (final IOException e) {
                    return null;
                }
            } while (restart);
            return answers;
        };
    }

    private Object askOne(final BufferedReader reader, final Question question) {
        println();
        println(MarkdownColorizer.accent(question.prompt()));
        final var options = question.options() == null ? List.<String>of() : question.options();
        for (int i = 0; i < options.size(); i++) {
            println("  " + (i + 1) + ". " + options.get(i));
        }
        final var ownIndex = options.size(); // 1-based id = options.size()
        if (question.multiple()) {
            println("  " + (ownIndex + 1) + ". (type your own answer)");
            println(MarkdownColorizer.status("enter comma-separated numbers"));
        } else {
            println("  " + (ownIndex + 1) + ". (type your own answer)");
            println(MarkdownColorizer.status("enter a number, or " + (ownIndex + 1) + " then your text"));
        }
        print(MarkdownColorizer.status("> "));
        try {
            final var line = reader.readLine();
            if (line == null) {
                return null;
            }
            final var trimmed = line.strip();
            if (trimmed.isBlank()) {
                return question.multiple() ? List.of() : "";
            }
            if (question.multiple()) {
                final var values = new ArrayList<String>();
                for (final var part : trimmed.split(",")) {
                    final var v = part.strip();
                    if (v.isEmpty()) {
                        continue;
                    }
                    int id;
                    try {
                        id = Integer.parseInt(v);
                    } catch (final NumberFormatException nfe) {
                        values.add(v);
                        continue;
                    }
                    if (id >= 1 && id <= options.size()) {
                        values.add(options.get(id - 1));
                    } else if (id == ownIndex + 1) {
                        print(MarkdownColorizer.status("your answer> "));
                        final var own = reader.readLine();
                        if (own != null && !own.isBlank()) {
                            values.add(own);
                        }
                    }
                }
                return values;
            }
            int id;
            try {
                id = Integer.parseInt(trimmed);
            } catch (final NumberFormatException nfe) {
                return trimmed;
            }
            if (id >= 1 && id <= options.size()) {
                return options.get(id - 1);
            }
            if (id == ownIndex + 1) {
                print(MarkdownColorizer.status("your answer> "));
                final var own = reader.readLine();
                return own == null ? "" : own;
            }
            return "";
        } catch (final IOException e) {
            return null;
        }
    }

    private void printHelp() {
        println(MarkdownColorizer.bold(symbols.toolMarker() + "yca cli - plain-terminal interactive session"));
        println("  type a prompt to run the agent, or use a slash command:");
        for (final var group : SlashGroup.values()) {
            final var rows = replSlashCommands.commands().stream()
                    .filter(c -> c.group() == group)
                    .toList();
            if (rows.isEmpty()) {
                continue;
            }
            printHelpGroup(group.name().toLowerCase(Locale.ROOT), rows);
        }
        println("  keys: Ctrl-C interrupts the running turn; pressed twice when idle (within 2s) it quits.");
    }

    private void printHelpGroup(final String title, final List<SlashCommandSpec> rows) {
        println(MarkdownColorizer.accent("  " + title + ":"));
        for (final var spec : rows) {
            final var display = spec.display();
            final var desc = spec.description();
            final var pad = " ".repeat(Math.max(2, 12 - display.length()));
            println("    " + MarkdownColorizer.bold(display) + pad + desc);
        }
    }

    private void printShortcuts() {
        println(MarkdownColorizer.bold(symbols.toolMarker() + "yca cli - keys"));
        println(MarkdownColorizer.accent("  editing:"));
        println("    Ctrl-C   interrupt the running turn; pressed twice when idle (within 2s) it quits");
        println("    Ctrl-D   exit");
    }

    private void clearScreen() {
        println("\u001B[2J\u001B[H");
    }

    private String resolveSessionId() {
        if (conf.session() != null && !conf.session().isBlank()) {
            return conf.session();
        }
        if (conf.context() != null && !conf.context().isBlank()) {
            var name = Path.of(conf.context()).getFileName().toString();
            if (name.endsWith(".jsonl")) {
                name = name.substring(0, name.length() - ".jsonl".length());
            }
            return name;
        }
        return sessionStore.create();
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
                    CliCommand.class.getClassLoader(),
                    new Class<?>[]{handler},
                    (_, method, _) -> {
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
        if (busy.get()) {
            cancelled.set(true);
            return;
        }
        final var now = clock.millis();
        final var previous = lastInterruptAt;
        lastInterruptAt = now;
        if (previous > 0 && now - previous <= QUIT_WINDOW_MILLIS) {
            quitRequested = true;
            println(MarkdownColorizer.status("bye"));
        } else {
            println(MarkdownColorizer.status("press Ctrl+C again within 2s to quit (Ctrl-D exits too)"));
        }
    }

    private void print(final String text) {
        System.out.print(text);
        System.out.flush();
    }

    private void println() {
        System.out.println();
    }

    private void println(final String text) {
        System.out.println(text);
    }
}