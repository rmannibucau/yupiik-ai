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
import io.yupiik.ia.agent.core.agent.SystemPromptBuilder;
import io.yupiik.ia.agent.core.codegraph.StructuralCodeGraph;
import io.yupiik.ia.agent.core.cli.slash.ReplSlashCommands;
import io.yupiik.ia.agent.core.cli.slash.SlashCommandSpec;
import io.yupiik.ia.agent.core.cli.slash.SlashGroup;
import io.yupiik.ia.agent.core.configuration.AgentMode;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.context.ContextManager;
import io.yupiik.ia.agent.core.context.UsageTracker;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.mcp.McpManager;
import io.yupiik.ia.agent.core.memory.MemoryStore;
import io.yupiik.ia.agent.core.permissions.ApprovalGate;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.question.FeedbackGate;
import io.yupiik.ia.agent.core.question.Question;
import io.yupiik.ia.agent.core.question.UserFeedbackService;
import io.yupiik.ia.agent.core.session.SessionStore;
import io.yupiik.ia.agent.core.todo.PlanFileService;
import io.yupiik.ia.agent.core.todo.PlanTodoSeeder;
import io.yupiik.ia.agent.core.todo.TodoItem;
import io.yupiik.ia.agent.core.todo.TodoStore;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.wiring.ProviderManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
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
    private final MemoryStore memoryStore;
    private final AgentProvider agentProvider;
    private final ContextManager contextManager;
    private final SystemPromptBuilder systemPromptBuilder;
    private final StructuralCodeGraph structuralCodeGraph;
    private final CodingAgentConfiguration configuration;
    private final PlanFileService planFileService;
    private final PlanTodoSeeder planTodoSeeder;
    private final ChatClient chatClient;
    private final ProviderManager providerManager;
    private final McpManager mcpManager;
    private final UsageTracker usageTracker;
    private final ReplSlashCommands replSlashCommands;

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
                      final SessionStore sessionStore, final TodoStore todoStore, final MemoryStore memoryStore,
                      final AgentProvider agentProvider, final ContextManager contextManager,
                      final UsageTracker usageTracker,
                      final SystemPromptBuilder systemPromptBuilder, final StructuralCodeGraph structuralCodeGraph,
                      final CodingAgentConfiguration configuration, final PlanFileService planFileService,
                      final PlanTodoSeeder planTodoSeeder, final ChatClient chatClient,
                      final ProviderManager providerManager, final McpManager mcpManager,
                      final JsonMapper mapper, final ReplSlashCommands replSlashCommands) {
        this.conf = conf;
        this.agentLoop = agentLoop;
        this.bridge = bridge;
        this.permissionService = permissionService;
        this.userFeedbackService = userFeedbackService;
        this.sessionStore = sessionStore;
        this.todoStore = todoStore;
        this.memoryStore = memoryStore;
        this.agentProvider = agentProvider;
        this.contextManager = contextManager;
        this.systemPromptBuilder = systemPromptBuilder;
        this.structuralCodeGraph = structuralCodeGraph;
        this.configuration = configuration;
        this.planFileService = planFileService;
        this.planTodoSeeder = planTodoSeeder;
        this.chatClient = chatClient;
        this.providerManager = providerManager;
        this.mcpManager = mcpManager;
        this.usageTracker = usageTracker;
        this.replSlashCommands = replSlashCommands;
        this.colorizer = new MarkdownColorizer(colorEnabled(conf.color()));
        this.textBuffer = new StreamTextBuffer(colorizer::colorizeLine);
        this.symbols = new Symbols(conf.ascii());
        this.presenter = new ToolPresenter(mapper);
        this.verbosity = CliVerbosity.parse(conf.presentation());
        this.tty = System.console() != null;
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
                println(colorizer.error("failed to prepare context file " + contextPath + ": " + e.getMessage()));
            }
        }
        println(colorizer.status("session " + sessionId + " - context file: " + contextPath));
        printBanner();
        if (conf.permitAll()) {
            println(colorizer.warning("WARNING: --permit-all is on; all tool calls will run without approval."));
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
        println(colorizer.status("session " + sessionId + " - context file: " + contextPath));
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
     * writing agent - plan vs build) and the active model.
     */
    private String promptLine() {
        final var mode = permissionService.mode().name().toLowerCase(Locale.ROOT);
        final var sb = new StringBuilder("yca");
        if (colorizer.isEnabled()) {
            sb.append('[').append(MarkdownColorizer.dim(mode));
            final var model = chatClient.model();
            if (model != null && !model.isBlank()) {
                sb.append(MarkdownColorizer.dim("\u00b7")).append(MarkdownColorizer.dim(model));
            }
            sb.append(']');
        } else {
            sb.append('[').append(mode).append(']');
        }
        return sb.append("> ").toString();
    }

    private void printBanner() {
        final var sb = new StringBuilder();
        sb.append(MarkdownColorizer.bold(symbols.toolMarker() + "yca"))
                .append(" \u00b7 ").append(permissionService.mode().name().toLowerCase(Locale.ROOT))
                .append(" mode \u00b7 session ").append(sessionId);
        final var model = chatClient.model();
        if (model != null && !model.isBlank()) {
            sb.append(" \u00b7 ").append(model);
        }
        sb.append(" \u00b7 type /help for commands");
        println(colorizer.status(sb.toString()));
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
                    println(colorizer.status("bye"));
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
            println(colorizer.error("cli read failed: " + e.getMessage()));
        }
    }

    private void runTurn(final String input) {
        final var override = agentOverride(input);
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
        println(colorizer.status("todos:"));
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
            out.add(colorizer.status("(no todos)"));
            return out;
        }
        var done = 0;
        for (final var item : todos) {
            switch (item.status() == null ? "" : item.status()) {
                case "in_progress" -> out.add(colorizer.warning(symbols.todoInProgress() + item.content()));
                case "completed" -> {
                    out.add(MarkdownColorizer.diffAdd(symbols.todoCompleted() + item.content()));
                    done++;
                }
                default -> out.add(MarkdownColorizer.dim(symbols.todoPending() + item.content()));
            }
        }
        out.add(colorizer.status(done + "/" + todos.size() + " done"));
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
                sb.append(" \u00b7 ");
            }
            sb.append(toolsRun).append(toolsRun == 1 ? " tool" : " tools");
        }
        final var elapsedMillis = (System.nanoTime() - turnStartNanos) / 1_000_000L;
        if (conf.showUsage() || toolsRun > 0) {
            sb.append(" \u00b7 ");
        }
        sb.append(String.format(Locale.ROOT, "%.1fs", elapsedMillis / 1000.0));
        System.err.println(colorizer.status(sb.toString()));
    }

    private void dispatch(final AgentEvent event) {
        if (event instanceof final AgentEvent.TextDelta t) {
            stopSpinner();
            print(textBuffer.append(t.delta()));
        } else if (event instanceof final AgentEvent.ThinkingDelta t) {
            if (conf.showThinking()) {
                if (verbosity == CliVerbosity.RAW) {
                    System.err.print(MarkdownColorizer.dim(t.delta()));
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
            if (conf.showToolOutput()) {
                onToolFinished(t);
            }
            beginSpinner("thinking");
        } else if (event instanceof final AgentEvent.Compacted c) {
            stopSpinner();
            System.err.println(colorizer.status(symbols.infoMarker() + "context " + c.kind() + " applied"));
        } else if (event instanceof final AgentEvent.Status s) {
            stopSpinner();
            System.err.println(colorizer.status(symbols.infoMarker() + s.message()));
        } else if (event instanceof final AgentEvent.Error e) {
            stopSpinner();
            System.err.println(colorizer.error("[error] " + e.message()));
        } else if (event instanceof final AgentEvent.UsageUpdated u) {
            lastUsageEvent = u;
        }
    }

private void onToolStarted(final AgentEvent.ToolStarted t) {
        if (!conf.showToolOutput()) {
            return;
        }
        if (verbosity == CliVerbosity.RAW) {
            System.err.println(colorizer.status("[tool] " + t.name() + " " + t.args()));
            return;
        }
        System.err.println(colorizer.bold(symbols.toolMarker() + presenter.summarize(t.name(), t.args())));
        if (verbosity == CliVerbosity.COMPACT && t.name().equals("bash")) {
            beginSpinner("running " + presenter.summarize(t.name(), t.args()));
        }
    }

    private void onToolFinished(final AgentEvent.ToolFinished t) {
        stopSpinner();
        if (verbosity == CliVerbosity.RAW) {
            if (!t.success() && t.output() != null && !t.output().isBlank()) {
                System.err.println(colorizer.error(t.output()));
            }
            return;
        }
        final var indent = "  ";
        final var result = presenter.result(t.name(), t.output(), t.success());
        System.err.println(MarkdownColorizer.dim(indent + symbols.resultPrefix())
                + (t.success() ? colorizer.success(result) : colorizer.error(result)));
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

    private void beginSpinner(final String label) {
        if (!tty) {
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
            println(colorizer.status("unknown command /" + name + " - type /help"));
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
                Map.entry("quit", arg -> {
                    println(colorizer.status("bye"));
                    return true;
                }),
                Map.entry("exit", arg -> {
                    println(colorizer.status("bye"));
                    return true;
                }),
                Map.entry("new", arg -> {
                    sessionId = sessionStore.create();
                    println(colorizer.status("new session " + sessionId));
                    return false;
                }),
                Map.entry("resume", arg -> {
                    if (arg.isBlank()) {
                        println(colorizer.status("usage: /resume <session-id>"));
                        return false;
                    }
                    sessionId = arg;
                    println(colorizer.status("resumed session " + sessionId));
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
        final var mode = AgentMode.fromConfigurationString(arg);
        permissionService.setMode(mode);
        println(colorizer.status("mode: " + mode.name().toLowerCase()));
        if (mode == AgentMode.BUILD) {
            try {
                final var seeded = planTodoSeeder.seedFromLastAssistant(sessionId);
                println(colorizer.status(seeded ? "todos seeded from the plan" : "no plan found to seed todos from"));
            } catch (final RuntimeException e) {
                println(colorizer.error("todo seeding failed: " + e.getMessage()));
            }
        }
    }

    private void model(final String arg) {
        if (arg.isBlank()) {
            try {
                final var models = chatClient.models().ids();
                println(colorizer.status("models (" + models.size() + "):"));
                final var current = chatClient.model();
                for (final var m : models) {
                    println(currentEntry(Objects.equals(m, current), m));
                }
                println(colorizer.status("use /model <id> to switch"));
            } catch (final IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                println(colorizer.error("cannot list models: " + e.getMessage()));
            }
            return;
        }
        final var model = arg.startsWith("$") ? arg.substring(1) : arg;
        chatClient.setModel(model);
        println(colorizer.status("model: " + model));
    }

    private void provider(final String arg) {
        final var providers = providerManager.providers();
        if (!arg.isBlank()) {
            chatClient.setProvider(arg);
            println(colorizer.status("provider: " + arg));
            return;
        }
        final var current = chatClient.providerId();
        println(colorizer.status("providers (" + providers.size() + "):"));
        if (providers.isEmpty()) {
            println("(no providers configured)");
        } else {
            for (final var p : providers) {
                final var id = p.id();
                println(currentEntry(Objects.equals(id, current), id));
            }
        }
        println(colorizer.status("use /provider <id> to switch"));
    }

    private void agent(final String arg) {
        if (arg.isBlank()) {
            final var agents = agentProvider.agents();
            println(colorizer.status("custom agents (" + agents.size() + "):"));
            if (agents.isEmpty()) {
                println("(no custom agents configured; add them under yca.agents)");
            } else {
                for (final var a : agents) {
                    println(currentEntry(Objects.equals(a.name(), activeAgent), a.name()));
                }
            }
            println(colorizer.status("use /agent <name> to set the active agent, /agent to clear it"));
            return;
        }
        if (agentProvider.byName(arg) == null) {
            println(colorizer.error("unknown agent: " + arg));
            return;
        }
        activeAgent = arg;
        println(colorizer.status("agent: " + arg));
    }

    /**
     * Renders one listing entry, marking the current selection with the filled
     * dot marker and dimming the marker colour for inactive entries.
     */
    private String currentEntry(final boolean isCurrent, final String value) {
        final var marker = isCurrent ? symbols.currentMarker() : " ";
        final var name = isCurrent ? MarkdownColorizer.accent(value) : value;
        return marker + " " + name + (isCurrent ? colorizer.status(" (current)") : "");
    }

    private void context(final String arg) {
        final var sb = new StringBuilder();
        sb.append("## System prompt\n\n").append(systemPromptBuilder.build()).append("\n\n");
        final var messages = sessionStore.load(sessionId);
        sb.append("## Session ").append(sessionId).append(" (").append(messages.size()).append(" messages)\n\n");
        for (final var message : messages) {
            sb.append("- ").append(message.role()).append(": ").append(firstLine(String.valueOf(message.content()))).append('\n');
        }
        if (arg.isBlank()) {
            println(sb.toString());
            return;
        }
        try {
            final var target = Path.of(arg).toAbsolutePath();
            Files.createDirectories(target.getParent() == null ? Path.of(".") : target.getParent());
            Files.writeString(target, sb.toString());
            println(colorizer.status("context written to " + target));
        } catch (final IOException e) {
            println(colorizer.error("context failed: " + e.getMessage()));
        }
    }

    private void memory() {
        final var project = memoryStore.readProject();
        final var global = memoryStore.readGlobal();
        if (project.isBlank() && global.isBlank()) {
            println(colorizer.status("(empty memory)"));
            return;
        }
        if (!global.isBlank()) {
            println(colorizer.status("-- global memory --"));
            println(global);
        }
        if (!project.isBlank()) {
            println(colorizer.status("-- project memory --"));
            println(project);
        }
    }

    private void todos() {
        renderTodos(todoStore.all()).forEach(this::println);
    }

    private void tools(final String arg) {
        final var filters = new ArrayList<String>();
        for (final var token : arg.split(" ")) {
            final var filter = token.strip().toLowerCase(Locale.ROOT);
            if (!filter.isEmpty()) {
                filters.add(filter);
            }
        }
        final var byServer = new TreeMap<>(mcpManager.toolsByServer());
        if (byServer.isEmpty()) {
            println(colorizer.status("(no tools available)"));
            return;
        }
        var count = 0;
        for (final var server : byServer.entrySet()) {
            final var lines = new ArrayList<String>();
            final var tools = server.getValue().stream()
                    .sorted(java.util.Comparator.comparing(EmbeddedTool::name))
                    .toList();
            for (final var tool : tools) {
                final var rootKey = (server.getKey() + '_' + tool.name()).toLowerCase(Locale.ROOT);
                if (filters.stream().anyMatch(filter -> !rootKey.contains(filter))) {
                    continue;
                }
                final var description = toolDescription(server.getKey(), tool);
                lines.add(tool.name() + (description == null ? "" : ": " + description));
            }
            if (lines.isEmpty()) {
                continue;
            }
            println(colorizer.accent(server.getKey()));
            for (final var line : lines) {
                println("  " + line);
            }
            count += lines.size();
        }
        if (count == 0) {
            println(colorizer.status("(no tool matches)"));
        }
    }

    private String toolDescription(final String server, final EmbeddedTool tool) {
        var description = tool.description() == null ? "" : tool.description().strip();
        final var prefix = '[' + server + "] ";
        if (description.startsWith(prefix)) {
            description = description.substring(prefix.length()).strip();
        }
        final var lineBreak = description.indexOf('\n');
        if (lineBreak >= 0) {
            description = description.substring(0, lineBreak).strip();
        }
        return description.isEmpty() || description.equals(tool.name()) ? null : description;
    }

    private void permissions(final String arg) {
        final var v = arg == null ? "" : arg.strip();
        if (v.equals("all") || v.equals("unsafe")) {
            permissionService.setPermitAll(true);
            println(colorizer.status("permissions: all tools allowed"));
        } else if (v.equals("none") || v.equals("safe")) {
            permissionService.setPermitAll(false);
            println(colorizer.status("permissions: asking for approval"));
        } else {
            println(colorizer.status("usage: /permissions all|none"));
        }
    }

    private void resyncGraph() {
        try {
            final var result = structuralCodeGraph.resync();
            println(colorizer.status(result));
        } catch (final RuntimeException e) {
            println(colorizer.error("resync failed: " + e.getMessage()));
        }
    }

    private void compact() {
        final var history = sessionStore.load(sessionId);
        if (history.isEmpty()) {
            println(colorizer.status("nothing to compact"));
            return;
        }
        sessionStore.replace(sessionId, contextManager.compact(history));
        println(colorizer.status("context compacted"));
    }

    private void editPlan(final String arg) {
        if (permissionService.mode() != AgentMode.PLAN) {
            println(colorizer.error("editplan is only available in plan mode (run /mode plan)"));
            return;
        }
        final var plan = planTodoSeeder.plan(sessionId);
        if (plan == null || plan.isBlank()) {
            println(colorizer.error("no plan to edit yet - produce a plan first"));
            return;
        }
        final var path = arg.isBlank()
                ? planFileService.defaultPath(Path.of(configuration.resolveStorageHome()), "", sessionId)
                : Path.of(arg).toAbsolutePath();
        try {
            Files.createDirectories(path.getParent() == null ? Path.of(".") : path.getParent());
            Files.writeString(path, planFileService.render(plan, todoStore.all()));
        } catch (final IOException e) {
            println(colorizer.error("editplan failed to write " + path + ": " + e.getMessage()));
            return;
        }
        final var original = planFileService.render(plan, todoStore.all());
        println(colorizer.status("plan exported to " + path));
        println(colorizer.status("edit the file (# Plan and # Todos sections), then press Enter to reload, or type 'c' to cancel"));
        try {
            final var input = stdin.readLine();
            if (input != null) {
                final var decision = input.trim();
                if (decision.equalsIgnoreCase("c") || decision.equalsIgnoreCase("cancel")) {
                    println(colorizer.status("plan edit cancelled"));
                    return;
                }
            }
        } catch (final IOException e) {
            println(colorizer.error("editplan read failed: " + e.getMessage()));
            return;
        }
        try {
            final var edited = Files.readString(path);
            if (edited.equals(original)) {
                println(colorizer.status("no changes in the plan (nothing to reload)"));
                return;
            }
            final var parsed = planFileService.parse(edited);
            println(colorizer.status(planFileService.reload(sessionId, parsed)));
        } catch (final IOException e) {
            println(colorizer.error("editplan failed to read " + path + ": " + e.getMessage()));
        }
    }

    private ApprovalGate approvalGate() {
        return (title, detail) -> {
            stopSpinner();
            printApprovalBox(title, detail);
            print(colorizer.accent("allow? [y/N] "));
            try {
                final var line = stdin.readLine();
                if (line != null) {
                    final var c = line.strip().toLowerCase(Locale.ROOT);
                    if (c.equals("y") || c.equals("yes") || c.equals("o") || c.equals("ok")) {
                        return true;
                    }
                }
            } catch (final IOException ignored) {
                // treat as deny
            }
            return false;
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
        width = Math.min(Math.max(width, 32), 72);
        final var rule = symbols.boxHorizontal().repeat(width);
        println(colorizer.accent(symbols.boxTop() + rule + symbols.boxCorner()));
        for (int i = 0; i < lines.size(); i++) {
            for (final var fragment : wrapLine(lines.get(i), width)) {
                final var styled = i == 0 ? colorizer.bold(padTo(fragment, width))
                        : i == 1 ? colorizer.warning(padTo(fragment, width))
                        : colorizer.status(padTo(fragment, width));
                println(colorizer.accent(symbols.boxLine()) + styled);
            }
        }
        println(colorizer.accent(symbols.boxBottom() + rule + symbols.boxCornerBottom()));
    }

    private static List<String> wrapLine(final String text, final int width) {
        if (text.isEmpty()) {
            return List.of("");
        }
        final var out = new ArrayList<String>();
        final var words = text.split("\\s+");
        final var sb = new StringBuilder();
        for (final var word : words) {
            if (sb.length() > 0 && sb.length() + 1 + word.length() > width) {
                out.add(sb.toString());
                sb.setLength(0);
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(word);
        }
        out.add(sb.toString());
        return out;
    }

    private static String padTo(final String text, final int width) {
        if (text.length() > width) {
            return text.substring(0, Math.max(0, width - 1)) + "\u2026";
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
                println(colorizer.status("your answers:"));
                for (final var question : questions) {
                    println("- " + question.prompt() + " -> " + answers.get(question.id()));
                }
                print(colorizer.status("confirm? (a=accept, e=restart, c=cancel) [a] "));
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
        println(colorizer.accent(question.prompt()));
        final var options = question.options() == null ? List.<String>of() : question.options();
        for (int i = 0; i < options.size(); i++) {
            println("  " + (i + 1) + ". " + options.get(i));
        }
        final var ownIndex = options.size(); // 1-based id = options.size()
        if (question.multiple()) {
            println("  " + (ownIndex + 1) + ". (type your own answer)");
            println(colorizer.status("enter comma-separated numbers"));
        } else {
            println("  " + (ownIndex + 1) + ". (type your own answer)");
            println(colorizer.status("enter a number, or " + (ownIndex + 1) + " then your text"));
        }
        print(colorizer.status("> "));
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
                        print(colorizer.status("your answer> "));
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
                print(colorizer.status("your answer> "));
                final var own = reader.readLine();
                return own == null ? "" : own;
            }
            return "";
        } catch (final IOException e) {
            return null;
        }
    }

    private AgentOverride agentOverride(final String text) {
        final var stripped = text.strip();
        if (stripped.startsWith("@")) {
            final var space = stripped.indexOf(' ');
            if (space > 1) {
                final var name = stripped.substring(1, space).strip();
                final var prompt = stripped.substring(space + 1).strip();
                if (agentProvider.byName(name) != null) {
                    return new AgentOverride(name, prompt);
                }
                return new AgentOverride(null, text);
            }
        }
        return new AgentOverride(null, text);
    }

    private record AgentOverride(String agentName, String prompt) {
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
        println(colorizer.accent("  " + title + ":"));
        for (final var spec : rows) {
            final var display = spec.display();
            final var desc = spec.description();
            final var pad = " ".repeat(Math.max(2, 12 - display.length()));
            println("    " + MarkdownColorizer.bold(display) + pad + desc);
        }
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
            final Object interruptHandler = Proxy.newProxyInstance(
                    CliCommand.class.getClassLoader(),
                    new Class<?>[]{handler},
                    (proxy, method, args) -> {
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
        final var now = System.currentTimeMillis();
        final var previous = lastInterruptAt;
        lastInterruptAt = now;
        if (previous > 0 && now - previous <= QUIT_WINDOW_MILLIS) {
            quitRequested = true;
            println(colorizer.status("bye"));
        } else {
            println(colorizer.status("press Ctrl+C again within 2s to quit (Ctrl-D exits too)"));
        }
    }

    private static String firstLine(final String text) {
        if (text == null) {
            return "";
        }
        final var nl = text.indexOf('\n');
        return (nl < 0 ? text : text.substring(0, nl)).strip();
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