package io.yupiik.ia.agent.tui;

import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Layout;
import dev.tamboui.layout.Rect;
import dev.tamboui.markdown.MarkdownView;
import dev.tamboui.style.Color;
import dev.tamboui.style.Overflow;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.MouseEvent;
import dev.tamboui.tui.event.MouseEventKind;
import dev.tamboui.tui.event.PasteEvent;
import dev.tamboui.tui.event.TickEvent;
import dev.tamboui.tui.bindings.BindingSets;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.BorderType;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.gauge.Gauge;
import dev.tamboui.widgets.input.TextArea;
import dev.tamboui.widgets.input.TextAreaState;
import dev.tamboui.widgets.paragraph.Paragraph;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.cli.Command;
import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;
import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.agent.AgentLoop;
import io.yupiik.ia.agent.core.agent.SystemPromptBuilder;
import io.yupiik.ia.agent.core.codegraph.StructuralCodeGraph;
import io.yupiik.ia.agent.core.configuration.AgentMode;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.context.ContextManager;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.memory.MemoryStore;
import io.yupiik.ia.agent.core.mcp.McpManager;
import io.yupiik.ia.agent.core.permissions.ApprovalGate;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.runtime.VirtualThreadExecutor;
import io.yupiik.ia.agent.core.session.SessionStore;
import io.yupiik.ia.agent.core.todo.TodoStore;
import io.yupiik.ia.agent.core.wiring.ProviderManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@DefaultScoped
@Command(name = "tui", description = "Interactive terminal UI session with the yca coding agent.")
public class TuiCommand implements Runnable {
    private static final String HINTS = "Enter send | Esc interrupt | Ctrl+T thinking | Ctrl+Y actions | Ctrl+P todos | Ctrl+S compact | Ctrl+L clear | Ctrl+C quit";
    private static final String WELCOME = "# yca\n\nType a prompt and press Enter. `/help` lists the slash commands.";
    private static final String SHORTCUTS = """
            | key | action |
            |---|---|
            | Enter | send the prompt |
            | Shift/Alt+Enter | newline |
            | Esc | interrupt the running agent |
            | Ctrl+C | quit |
            | Ctrl+T | toggle thinking stream |
            | Ctrl+Y | show/hide executed tool actions |
            | Ctrl+P | toggle todo list |
            | Ctrl+S | compact the context now |
            | Ctrl+L | clear the transcript |
            | PageUp/PageDown | scroll the transcript |
            | Mouse wheel | scroll the transcript |
            | Alt+B / Alt+F | move backward/forward one word |
            | Tab | complete model name or slash command |
            """;
    private static final String HELP = """

            | command | description |
            |---|---|
            | /help | show this help |
            | /shortcuts | show the keyboard shortcuts |
            | /new | start a new session |
            | /resume | resume the last session |
            | /compact | compact the session context now |
            | /model [$name] | list /v1/models, or set model (Tab completes) |
            | /mode | switch mode: /mode build or /mode plan |
            | /memory | show the project memory |
            | /context [path] | show the resolved context (system prompt + session), write to path when given |
            | /permissions (all\\|none) | allow all tools without asking (all), or restore interactive approval (none) |
            | /structural-code-graph | resync the structural code graph (symbols, edges and doc tree) on demand |
            | /clear | clear the transcript |
            | /quit | exit yca |
            """;

    private static final List<String> SLASH_COMMANDS = List.of(
            "/help", "/shortcuts", "/new", "/resume", "/compact", "/model", "/provider", "/mode", "/memory", "/context",
            "/permissions", "/structural-code-graph", "/clear", "/quit", "/exit");

    private final TuiConf conf;
    private final TuiConfiguration tuiConfiguration;
    private final CodingAgentConfiguration configuration;
    private final AgentLoop agentLoop;
    private final AgentEventBridge bridge;
    private final PermissionService permissionService;
    private final ContextManager contextManager;
    private final SessionStore sessionStore;
    private final TodoStore todoStore;
    private final ChatClient chatClient;
    private final MemoryStore memoryStore;
    private final SystemPromptBuilder systemPromptBuilder;
    private final McpManager mcpManager;
    private final VirtualThreadExecutor executor;
    private final StructuralCodeGraph structuralCodeGraph;
    private final ProviderManager providerManager;

    public TuiCommand(final TuiConf conf, final TuiConfiguration tuiConfiguration,
                      final CodingAgentConfiguration configuration, final AgentLoop agentLoop,
                      final AgentEventBridge bridge, final PermissionService permissionService,
                      final ContextManager contextManager, final SessionStore sessionStore,
                      final TodoStore todoStore, final ChatClient chatClient, final MemoryStore memoryStore,
                      final SystemPromptBuilder systemPromptBuilder, final McpManager mcpManager,
                      final VirtualThreadExecutor executor,
                      final StructuralCodeGraph structuralCodeGraph,
                      final ProviderManager providerManager) {
        this.conf = conf;
        this.tuiConfiguration = tuiConfiguration;
        this.configuration = configuration;
        this.agentLoop = agentLoop;
        this.bridge = bridge;
        this.permissionService = permissionService;
        this.contextManager = contextManager;
        this.sessionStore = sessionStore;
        this.todoStore = todoStore;
        this.chatClient = chatClient;
        this.memoryStore = memoryStore;
        this.systemPromptBuilder = systemPromptBuilder;
        this.mcpManager = mcpManager;
        this.executor = executor;
        this.structuralCodeGraph = structuralCodeGraph;
        this.providerManager = providerManager;
    }

    @RootConfiguration("tui")
    public record TuiConf(
            @Property(defaultValue = "false", documentation = "Use vim style key bindings.") boolean vimBindings,
            @Property(defaultValue = "false", documentation = "Show the reasoning/thinking stream by default.") boolean showThinking,
            @Property(defaultValue = "\"dark\"", documentation = "UI theme: dark or light.") String theme,
            @Property(defaultValue = "false", documentation = "Bypass permission prompts and allow all tool calls (not persisted to the transcript).") boolean permitAll) {
    }

    @Override
    public void run() {
        final var state = new UiState();
        state.sessionId = sessionStore.create();
        state.showThinking = conf.showThinking();
        try (final var tui = createTui()) {
            bridge.sink(event -> tui.runLater(() -> onEvent(event, state)));
            permissionService.setGate(gate(tui, state));
            permissionService.setPermitAll(conf.permitAll());
            state.status = "ready";
            try {
                tui.run((event, runner) -> onKey(event, runner, state), frame -> render(frame, state));
            } finally {
                bridge.sink(null);
                permissionService.setGate(null);
                permissionService.setPermitAll(false);
            }
        } catch (final RuntimeException e) {
            throw e;
        } catch (final Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private TuiRunner createTui() throws Exception {
        return TuiRunner.create(TuiConfig.builder()
                .mouseCapture(true)
                .tickRate(Duration.ofMillis(100))
                .bindings(conf.vimBindings() ? BindingSets.vim() : BindingSets.defaults())
                .build());
    }

    private ApprovalGate gate(final TuiRunner tui, final UiState state) {
        return (title, detail) -> {
            final var latch = new CountDownLatch(1);
            final var approved = new AtomicBoolean(false);
            tui.runLater(() -> state.approval = new Approval(title, detail, latch, approved));
            try {
                while (!latch.await(200, TimeUnit.MILLISECONDS)) {
                    if (state.interrupted) {
                        tui.runLater(() -> {
                            if (state.approval != null && state.approval.latch() == latch) {
                                state.approval = null;
                            }
                        });
                        return false;
                    }
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            return approved.get();
        };
    }

    private void onEvent(final AgentEvent event, final UiState state) {
        if (event instanceof final AgentEvent.TextDelta t) {
            state.pending.append(t.delta());
            state.scrollBack = 0;
        } else if (event instanceof final AgentEvent.ThinkingDelta t) {
            state.thinking.append(t.delta());
        } else if (event instanceof final AgentEvent.ToolStarted t) {
            state.runningTool = t.name();
            state.runningToolCommand = toolCommand(t.args());
            state.toolStream.setLength(0);
            state.spinnerTick = 0;
            state.scrollBack = 0;
        } else if (event instanceof final AgentEvent.ToolStream t) {
            if (t.name().equals(state.runningTool)) {
                state.toolStream.append(t.fragment()).append(' ');
                if (state.toolStream.length() > 1600) {
                    state.toolStream.delete(0, state.toolStream.length() - 1600);
                }
            }
        } else if (event instanceof final AgentEvent.ToolFinished t) {
            final var running = state.runningTool;
            state.runningTool = null;
            state.runningToolCommand = null;
            if (running != null && state.toolStream.length() > 0 && state.showToolActions) {
                state.transcript.append("\n\n*streaming `").append(running).append("`*\n\n```text\n")
                        .append(state.toolStream.toString().stripTrailing()).append("\n```\n");
            }
            state.toolStream.setLength(0);
            if (!t.success() && state.showToolActions) {
                state.transcript.append("> tool `").append(t.name()).append("` failed: ")
                        .append(firstLine(t.output())).append("\n");
            }
        } else if (event instanceof final AgentEvent.ToolStarted t) {
            if (state.showToolActions) {
                state.transcript.append("\n\n*executing `").append(t.name()).append("`*\n");
            }
            state.scrollBack = 0;
        } else if (event instanceof final AgentEvent.UsageUpdated u) {
            state.promptTokens = u.usage() == null ? 0 : u.usage().promptTokens();
            state.completionTokens = u.usage() == null ? 0 : u.usage().completionTokens();
            state.estimatedContext = u.estimatedContextTokens();
            state.contextPercent = (int) Math.round(u.contextPercent());
        } else if (event instanceof final AgentEvent.Compacted c) {
            state.status = "context " + c.kind() + " done";
            state.transcript.append("\n\n> context ").append(c.kind()).append(" applied\n");
        } else if (event instanceof final AgentEvent.Status s) {
            state.status = s.message();
        } else if (event instanceof final AgentEvent.TurnDone t) {
            state.transcript.append("\n\n**Assistant**\n\n").append(t.content()).append('\n');
            state.pending.setLength(0);
            state.thinking.setLength(0);
            state.scrollBack = 0;
            state.busy = false;
            state.status = "ready";
        } else if (event instanceof final AgentEvent.Error e) {
            state.transcript.append("\n\n> error: ").append(e.message()).append("\n");
            state.pending.setLength(0);
            state.busy = false;
            state.status = "error: " + e.message();
        }
    }

    private boolean onKey(final Event event, final TuiRunner runner, final UiState state) {
        if (event instanceof final PasteEvent paste) {
            state.input.insert(paste.text());
            return true;
        }
        if (event instanceof TickEvent) {
            return true;
        }
        if (event instanceof final MouseEvent mouse) {
            if (mouse.isScroll()) {
                if (mouse.kind() == MouseEventKind.SCROLL_UP) {
                    state.scrollBack += 3;
                } else if (mouse.kind() == MouseEventKind.SCROLL_DOWN) {
                    state.scrollBack = Math.max(0, state.scrollBack - 3);
                }
                return true;
            }
            return false;
        }
        if (!(event instanceof final KeyEvent key)) {
            return false;
        }
        if (state.approval != null) {
            if (key.isConfirm()) {
                state.approval.approved().set(true);
                state.approval.latch().countDown();
                state.approval = null;
                state.status = "approved";
            } else if (key.isCancel() || key.isKey(KeyCode.ESCAPE)) {
                state.approval.latch().countDown();
                state.approval = null;
                state.status = "denied";
            }
            return true;
        }
        if (key.isCtrlC()) {
            runner.quit();
            return true;
        }
        if (key.hasCtrl() && key.isCharIgnoreCase('t')) {
            state.showThinking = !state.showThinking;
            return true;
        }
        if (key.hasCtrl() && key.isCharIgnoreCase('p')) {
            if (state.showTodos && !state.showTodosCollapsed) {
                state.showTodosCollapsed = true;
            } else if (state.showTodos) {
                state.showTodos = false;
                state.showTodosCollapsed = false;
            } else {
                state.showTodos = true;
            }
            return true;
        }
        if (key.hasCtrl() && key.isCharIgnoreCase('s')) {
            compact(runner, state);
            return true;
        }
        if (key.hasCtrl() && key.isCharIgnoreCase('l')) {
            state.transcript.setLength(0);
            state.thinking.setLength(0);
            state.scrollBack = 0;
            return true;
        }
        if (key.hasCtrl() && key.isCharIgnoreCase('y')) {
            state.showToolActions = !state.showToolActions;
            state.status = "tool actions " + (state.showToolActions ? "shown" : "hidden");
            return true;
        }
        if (key.isCancel() || key.isKey(KeyCode.ESCAPE)) {
            if (state.busy) {
                state.interrupted = true;
                state.status = "interrupting...";
            }
            return true;
        }
        if (key.isPageUp()) {
            state.scrollBack += 10;
            return true;
        }
        if (key.isPageDown()) {
            state.scrollBack = Math.max(0, state.scrollBack - 10);
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
        if (key.isConfirm() && !key.hasShift() && !key.hasAlt()) {
            final var text = state.input.text().strip();
            state.input.clear();
            if (!text.isEmpty()) {
                submit(text, runner, state);
            }
            return true;
        }
        switch (key.code()) {
            case BACKSPACE -> state.input.deleteBackward();
            case DELETE -> state.input.deleteForward();
            case LEFT -> state.input.moveCursorLeft();
            case RIGHT -> state.input.moveCursorRight();
            case UP -> state.input.moveCursorUp();
            case DOWN -> state.input.moveCursorDown();
            case HOME -> state.input.moveCursorToLineStart();
            case END -> state.input.moveCursorToLineEnd();
            case CHAR -> {
                if (!key.hasCtrl()) {
                    state.input.insert(key.codePoint() > Character.MAX_VALUE ? "?" : String.valueOf((char) key.codePoint()));
                }
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private void submit(final String text, final TuiRunner runner, final UiState state) {
        if (text.startsWith("/")) {
            slash(text, runner, state);
            return;
        }
        if (state.busy) {
            state.status = "busy - press Esc to interrupt";
            return;
        }
        if (state.title == null || state.title.isBlank()) {
            state.title = firstLine(text);
        }
        state.transcript.append("\n\n---\n\n**You**\n\n").append(text).append('\n');
        state.pending.setLength(0);
        state.thinking.setLength(0);
        state.interrupted = false;
        state.busy = true;
        state.status = "thinking...";
        state.scrollBack = 0;
        final var history = sessionStore.load(state.sessionId);
        executor.execute(() -> agentLoop.run(text, state.sessionId, history, () -> state.interrupted));
    }

    private void slash(final String text, final TuiRunner runner, final UiState state) {
        state.transcript.append("\n\n---\n\n**You**\n\n`").append(text).append("`\n");
        switch (text) {
            case "/help" -> state.transcript.append(HELP);
            case "/shortcuts" -> state.transcript.append(SHORTCUTS);
            case "/new" -> {
                state.sessionId = sessionStore.create();
                state.transcript.setLength(0);
                state.thinking.setLength(0);
                state.status = "new session " + state.sessionId;
            }
            case "/resume" -> resume(state);
            case "/compact" -> compact(runner, state);
            case "/model" -> modelCommand(text, runner, state);
            case "/provider" -> providerCommand(text, runner, state);
            case "/memory" -> {
                final var memory = memoryStore.readProject();
                state.transcript.append("\n```text\n").append(memory.isBlank() ? "(empty project memory)" : memory)
                        .append("\n```\n");
            }
            case "/context" -> context("", state);
            case "/structural-code-graph" -> resyncGraph(runner, state);
            case "/clear" -> {
                state.transcript.setLength(0);
                state.thinking.setLength(0);
                state.scrollBack = 0;
            }
            case "/quit", "/exit" -> runner.quit();
            default -> {
                if (text.startsWith("/model ")) {
                    modelCommand(text, runner, state);
                } else if (text.startsWith("/provider ")) {
                    providerCommand(text, runner, state);
                } else if (text.startsWith("/mode ")) {
                    mode(text.substring("/mode ".length()).strip(), state);
                } else if (text.startsWith("/context ")) {
                    context(text.substring("/context ".length()).strip(), state);
                } else if (text.startsWith("/permissions ")) {
                    permissions(text.substring("/permissions ".length()).strip(), state);
                } else {
                    state.status = "unknown command: " + text + " (/help)";
                }
            }
        }
    }

    private void resume(final UiState state) {
        final var last = sessionStore.lastSessionId();
        if (last == null) {
            state.status = "no session to resume";
            return;
        }
        state.sessionId = last;
        state.transcript.setLength(0);
        state.thinking.setLength(0);
        state.scrollBack = 0;
        for (final var message : sessionStore.load(last)) {
            switch (message.role()) {
                case "user" -> state.transcript.append("\n\n---\n\n**You**\n\n").append(message.content()).append('\n');
                case "assistant" -> state.transcript.append("\n\n---\n\n**Assistant**\n\n").append(message.content()).append('\n');
                case "tool" -> state.transcript.append("\n\n*tool `").append(message.name()).append("` -> ")
                        .append(firstLine(String.valueOf(message.content()))).append("*\n");
                default -> {
                }
            }
        }
        state.status = "resumed session " + last;
    }

    private void context(final String arg, final UiState state) {
        try {
            final var sb = new StringBuilder();
            sb.append("**System prompt**\n\n```text\n").append(systemPromptBuilder.build()).append("\n```\n");
            final var messages = sessionStore.load(state.sessionId);
            sb.append("\n**Session ").append(state.sessionId).append(" (").append(messages.size())
                    .append(" messages)**\n\n");
            for (final var message : messages) {
                sb.append("- **").append(message.role()).append("**: ")
                        .append(firstLine(String.valueOf(message.content()))).append('\n');
            }
            final var context = sb.toString();
            state.transcript.append("\n").append(context).append('\n');
            if (arg != null && !arg.isBlank()) {
                final var target = Path.of(arg).toAbsolutePath();
                Files.createDirectories(target.getParent() == null ? Path.of(".") : target.getParent());
                Files.writeString(target, context);
                state.status = "context written to " + target;
            } else {
                state.status = "context shown (" + messages.size() + " messages)";
            }
            state.scrollBack = 0;
        } catch (final IOException e) {
            state.status = "context failed: " + e.getMessage();
        }
    }

    private void permissions(final String value, final UiState state) {
        final var v = value == null ? "" : value.strip();
        if (v.equals("all") || v.equals("unsafe")) {
            permissionService.setPermitAll(true);
            state.status = "permissions: all tools allowed";
        } else if (v.equals("none") || v.equals("safe")) {
            permissionService.setPermitAll(false);
            state.status = "permissions: asking for approval";
        } else {
            state.status = "usage: /permissions all|none";
        }
    }

    private void modelCommand(final String text, final TuiRunner runner, final UiState state) {
        final var arg = text.substring("/model".length()).strip();
        if (arg.isEmpty()) {
            models(runner, state);
            return;
        }
        final var model = arg.startsWith("$") ? arg.substring(1) : arg;
        chatClient.setModel(model);
        state.status = "model: " + model;
    }

    private void providerCommand(final String text, final TuiRunner runner, final UiState state) {
        final var arg = text.substring("/provider".length()).strip();
        final var providers = providerManager.providers();
        if (!arg.isEmpty()) {
            chatClient.setProvider(arg);
            state.status = "provider: " + arg;
            return;
        }
        final var current = chatClient.providerId();
        final var sb = new StringBuilder("\n\n**Providers** (current: ").append(current).append(")**\n\n");
        if (providers.isEmpty()) {
            sb.append("(no providers configured)");
        } else {
            for (final var p : providers) {
                sb.append("- ").append(p.id()).append(p.id().equals(current) ? "  <--" : "").append('\n');
            }
        }
        sb.append("\nUse `/provider <id>` to switch.");
        runner.runLater(() -> {
            state.transcript.append(sb).append('\n');
            state.scrollBack = 0;
        });
    }

    private void completeModel(final TuiRunner runner, final UiState state) {
        final var text = state.input.text();
        if (!text.startsWith("/model ")) {
            state.status = "completion only applies to /model ";
            return;
        }
        executor.execute(() -> {
            try {
                final var ids = chatClient.models().ids();
                runner.runLater(() -> {
                    final var current = state.input.text();
                    if (!current.startsWith("/model ")) {
                        return;
                    }
                    final var prefixNow = current.substring("/model ".length());
                    final var matches = ids.stream().filter(m -> !m.isBlank() && m.startsWith(prefixNow)).toList();
                    if (matches.isEmpty()) {
                        state.status = "no model matching '" + prefixNow + "'";
                        return;
                    }
                    if (!prefixNow.equals(state.modelCompletionBase)) {
                        state.modelCompletionBase = prefixNow;
                        state.modelCompletionOffset = 0;
                    }
                    final var choice = matches.get(state.modelCompletionOffset % matches.size());
                    state.modelCompletionOffset++;
                    state.input.clear();
                    state.input.insert("/model " + choice);
                });
            } catch (final Exception e) {
                runner.runLater(() -> state.status = "completion failed: " + e.getMessage());
            }
        });
    }

    private void complete(final TuiRunner runner, final UiState state) {
        final var text = state.input.text();
        if (text.startsWith("/model ") && text.length() > "/model ".length()) {
            completeModel(runner, state);
            return;
        }
        if (text.startsWith("/")) {
            completeSlash(runner, state);
            return;
        }
        state.status = "completion only applies to / (slash) commands";
    }

    private void completeSlash(final TuiRunner runner, final UiState state) {
        final var text = state.input.text();
        final var prefix = text.startsWith("/") ? text.substring(1) : text;
        final var matches = SLASH_COMMANDS.stream()
                .filter(c -> c.substring(1).startsWith(prefix))
                .toList();
        if (matches.isEmpty()) {
            state.status = "no command matching '/" + prefix + "'";
            return;
        }
        if (!prefix.equals(state.slashCompletionBase)) {
            state.slashCompletionBase = prefix;
            state.slashCompletionOffset = 0;
        }
        final var choice = matches.get(state.slashCompletionOffset % matches.size());
        state.slashCompletionOffset++;
        state.input.clear();
        state.input.insert(choice + " ");
    }

    private void moveCursorWord(final UiState state, final boolean forward) {
        final var text = state.input.text();
        final var col = state.input.cursorCol();
        final var target = forward ? nextWord(text, col) : previousWord(text, col);
        final var len = text.length();
        state.input.setText(text);
        for (var i = len; i > target; i--) {
            state.input.moveCursorLeft();
        }
    }

    private static int nextWord(final String s, final int col) {
        final var len = s.length();
        var i = col;
        while (i < len && s.charAt(i) == ' ') {
            i++;
        }
        while (i < len && s.charAt(i) != ' ') {
            i++;
        }
        return Math.min(i, len);
    }

    private static int previousWord(final String s, final int col) {
        var i = Math.min(col, s.length());
        if (i > 0) {
            i--;
        }
        while (i >= 0 && s.charAt(i) == ' ') {
            i--;
        }
        while (i >= 0 && s.charAt(i) != ' ') {
            i--;
        }
        return Math.max(0, i + 1);
    }

    private void models(final TuiRunner runner, final UiState state) {
        executor.execute(() -> {
            try {
                final var ids = chatClient.models().ids();
                runner.runLater(() -> {
                    state.transcript.append("\n\n**Models**\n\n")
                            .append(ids.isEmpty() ? "(provider does not expose /v1/models)" : String.join("\n", ids))
                            .append('\n');
                    state.scrollBack = 0;
                });
            } catch (final Exception e) {
                runner.runLater(() -> state.status = "models failed: " + e.getMessage());
            }
        });
    }

    private void mode(final String value, final UiState state) {
        final var mode = AgentMode.fromConfigurationString(value);
        permissionService.setMode(mode);
        state.status = "mode: " + mode.name().toLowerCase();
    }

    private void resyncGraph(final TuiRunner runner, final UiState state) {
        if (state.busy) {
            state.status = "cannot resync the graph while busy";
            return;
        }
        state.status = "resyncing structural code graph...";
        executor.execute(() -> {
            try {
                final var result = structuralCodeGraph.resync();
                runner.runLater(() -> {
                    state.status = "structural code graph resynced";
                    state.transcript.append("\n\n> structural code graph: `").append(result).append("`\n");
                });
            } catch (final RuntimeException e) {
                runner.runLater(() -> state.status = "resync failed: " + e.getMessage());
            }
        });
    }

    private void compact(final TuiRunner runner, final UiState state) {
        if (state.busy) {
            state.status = "cannot compact while busy";
            return;
        }
        state.status = "compacting...";
        executor.execute(() -> {
            try {
                final var history = sessionStore.load(state.sessionId);
                if (history.isEmpty()) {
                    runner.runLater(() -> state.status = "nothing to compact");
                    return;
                }
                sessionStore.replace(state.sessionId, contextManager.compact(history));
                runner.runLater(() -> {
                    state.status = "session compacted";
                    state.transcript.append("\n\n> session compacted\n");
                });
            } catch (final RuntimeException e) {
                runner.runLater(() -> state.status = "compaction failed: " + e.getMessage());
            }
        });
    }

    private void render(final dev.tamboui.terminal.Frame frame, final UiState state) {
        if (state.runningTool != null) {
            state.spinnerTick++;
        }
        final var root = Layout.vertical()
                .constraints(Constraint.fill(), Constraint.length(3), Constraint.length(1), Constraint.length(2))
                .split(frame.area());
        final var body = Layout.horizontal()
                .constraints(Constraint.fill(), Constraint.length(28))
                .split(root.get(0));
        renderSidebar(body.get(1), frame, state);
        var chat = accentGutter(body.get(0), frame);
        if (state.showTodos) {
            final var withTodos = Layout.horizontal()
                    .constraints(Constraint.fill(), Constraint.length(state.showTodosCollapsed ? 14 : 34))
                    .split(chat);
            chat = withTodos.get(0);
            renderTodos(withTodos.get(1), frame, state);
        }
        if (state.showThinking && state.thinking.length() > 0) {
            final var withThinking = Layout.vertical()
                    .constraints(Constraint.percentage(70), Constraint.percentage(30))
                    .split(chat);
            renderTranscript(withThinking.get(0), frame, state);
            renderThinking(withThinking.get(1), frame, state);
        } else {
            renderTranscript(chat, frame, state);
        }
        renderInput(root.get(1), frame, state);
        renderCalling(root.get(2), frame, state);
        renderStatus(root.get(3), frame, state);
        if (state.approval != null) {
            renderApproval(frame, state);
        }
    }

    private void renderSidebar(final Rect rect, final dev.tamboui.terminal.Frame frame, final UiState state) {
        final var title = state.title == null || state.title.isBlank() ? " yca " : " " + ellipsize(state.title, 24) + " ";
        final var block = block(title);
        frame.renderWidget(block, rect);
        final var inner = block.inner(rect);
        if (inner.isEmpty()) {
            return;
        }
        final var width = inner.width();
        final var label = Style.EMPTY.fg(Color.hex(tuiConfiguration.textMuted()));
        final var value = Style.EMPTY.fg(Color.hex(tuiConfiguration.text()));
        final var branch = gitBranch();
        final var rows = Layout.vertical()
                .constraints(Constraint.fill(), Constraint.length(1))
                .split(inner);
        final var text = new ArrayList<Line>();
        text.add(Line.from(Span.styled("yca", Style.EMPTY.fg(Color.hex(tuiConfiguration.accent())).bold()),
                Span.styled(" coding agent", label)));
        text.add(Line.empty());
        text.add(Line.from(Span.styled("model ", label), Span.styled(ellipsize(chatClient.model(), width - 6), value)));
        text.add(Line.from(Span.styled("provider ", label), Span.styled(ellipsize(chatClient.providerId(), width - 9), value)));
        text.add(Line.from(Span.styled("flavor ", label), Span.styled(ellipsize(chatClient.flavor().name().toLowerCase(), width - 7), value)));
        text.add(Line.from(Span.styled("cwd ", label), Span.styled(ellipsize(workingDirectory(), width - 4), value)));
        if (branch != null) {
            text.add(Line.from(Span.styled("branch ", label), Span.styled(ellipsize(branch, width - 7), value)));
        }
        text.add(Line.from(Span.styled(permissionService.isPermitAll() ? "permissions all " : "mode ", label),
                Span.styled(permissionService.isPermitAll() ? "\u26a0" : permissionService.mode().name().toLowerCase(),
                        Style.EMPTY.fg(modeColor()))));
        text.add(Line.from(Span.styled("session ", label), Span.styled(ellipsize(state.sessionId, width - 8), value)));
        text.add(Line.from(Span.styled("servers ", label)));
        for (final var status : mcpManager.serverStatuses()) {
            final var dotColor = switch (status.state()) {
                case ENABLED -> Color.hex(tuiConfiguration.success());
                case DISABLED -> Color.hex(tuiConfiguration.textMuted());
                case FAILED -> Color.hex(tuiConfiguration.error());
            };
            text.add(Line.from(Span.styled("\u25CF ", Style.EMPTY.fg(dotColor)),
                    Span.styled(ellipsize(status.name(), width - 2), value)));
        }
        text.add(Line.empty());
        text.add(Line.from(Span.styled("tokens in  ", label), Span.styled(String.valueOf(state.promptTokens), value)));
        text.add(Line.from(Span.styled("tokens out ", label), Span.styled(String.valueOf(state.completionTokens), value)));
        text.add(Line.from(Span.styled("context ~  ", label), Span.styled(state.estimatedContext + " tok (" + state.contextPercent + "%)", value)));
        final var lines = Text.from(text.toArray(Line[]::new));
        final var textRect = rows.get(0);
        final var overflow = Math.max(0, lines.height() - textRect.height());
        frame.renderWidget(Paragraph.builder().text(lines).scroll(overflow).build(), textRect);
        if (!rows.get(1).isEmpty()) {
            final var percent = Math.max(0, Math.min(100, state.contextPercent));
            final var gaugeColor = Color.hex(percent >= 80 ? tuiConfiguration.error()
                    : percent >= 65 ? tuiConfiguration.warning() : tuiConfiguration.info());
            frame.renderWidget(Gauge.builder()
                    .percent(percent)
                    .label(Span.raw(percent + "% context"))
                    .gaugeColor(gaugeColor)
                    .build(), rows.get(1));
        }
    }

    private void renderTranscript(final Rect rect, final dev.tamboui.terminal.Frame frame, final UiState state) {
        final var block = block(" conversation ");
        frame.renderWidget(block, rect);
        final var inner = block.inner(rect);
        if (inner.isEmpty()) {
            return;
        }
        final var chunks = chunks(transcriptMarkdown(state), inner.width());
        final var total = chunks.stream().mapToInt(c -> c.height()).sum();
        final var maxViewOffset = Math.max(0, total - Math.max(1, inner.height()));
        final var viewOffset = Math.min(maxViewOffset, Math.max(0, maxViewOffset - state.scrollBack));
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
    }

    private record RenderedChunk(int height, java.util.function.IntFunction<dev.tamboui.widget.Widget> supplier) {
        dev.tamboui.widget.Widget widget(final int skip) {
            return supplier.apply(skip);
        }
    }

    private List<RenderedChunk> chunks(final String md, final int width) {
        final var out = new ArrayList<RenderedChunk>();
        for (final var seg : SyntaxHighlighter.split(md)) {
            if (seg.type() == SyntaxHighlighter.Segment.Type.TEXT) {
                final var chunk = markdownChunk(seg.text(), width);
                out.add(new RenderedChunk(chunk.height(), chunk::at));
            } else {
                final var lang = seg.language();
                final var text = SyntaxHighlighter.highlight(lang, seg.text());
                final var boxed = codeBlock(lang);
                final var innerH = Math.max(text.height(), 1);
                out.add(new RenderedChunk(innerH + 2, skip -> Paragraph.builder()
                        .text(text)
                        .block(boxed)
                        .overflow(Overflow.CLIP)
                        .scroll(Math.min(skip, Math.max(0, innerH - 1)))
                        .build()));
            }
        }
        return out;
    }

    private static MarkdownChunk markdownChunk(final String text, final int width) {
        final var view = MarkdownView.builder().source(text).softLineBreaks(true).build();
        final var height = Math.max(view.computeHeight(width), 1);
        return new MarkdownChunk(text, height);
    }

    private record MarkdownChunk(String source, int height) {
        dev.tamboui.widget.Widget at(final int skip) {
            return skip > 0
                    ? MarkdownView.builder().source(source).softLineBreaks(true).scroll(skip).build()
                    : MarkdownView.builder().source(source).softLineBreaks(true).build();
        }
    }

    private Block codeBlock(final String lang) {
        return Block.builder()
                .title(lang == null || lang.isBlank() ? "" : " " + lang + " ")
                .borders(Borders.ALL)
                .borderType(BorderType.ROUNDED)
                .borderColor(Color.hex(tuiConfiguration.border()))
                .build();
    }

    private void renderThinking(final Rect rect, final dev.tamboui.terminal.Frame frame, final UiState state) {
        final var muted = Style.EMPTY.fg(Color.hex(tuiConfiguration.textMuted()));
        final var block = Block.builder()
                .title(" thinking ")
                .borders(Borders.ALL)
                .borderType(BorderType.ROUNDED)
                .borderStyle(muted)
                .build();
        frame.renderWidget(block, rect);
        final var inner = block.inner(rect);
        if (inner.isEmpty()) {
            return;
        }
        final var text = Text.styled(state.thinking.toString(), muted);
        final var offset = Math.max(0, text.height() - inner.height());
        frame.renderWidget(Paragraph.builder().text(text).scroll(offset).build(), inner);
    }

    private void renderTodos(final Rect rect, final dev.tamboui.terminal.Frame frame, final UiState state) {
        final var block = Block.builder()
                .title(" todos " + (state.showTodosCollapsed ? " (Ctrl+P to expand) " : " (Ctrl+P to collapse) "))
                .borders(Borders.ALL)
                .borderType(BorderType.ROUNDED)
                .borderStyle(Style.EMPTY.fg(Color.hex(tuiConfiguration.accent())))
                .build();
        frame.renderWidget(block, rect);
        final var inner = block.inner(rect);
        if (inner.isEmpty()) {
            return;
        }
        if (state.showTodosCollapsed) {
            return;
        }
        final var render = todoStore.render();
        final var text = Text.from(render.isBlank() ? "(no todos)" : render);
        final var overflow = Math.max(0, text.height() - inner.height());
        frame.renderWidget(Paragraph.builder().text(text).scroll(overflow).build(), inner);
    }

    private void renderInput(final Rect rect, final dev.tamboui.terminal.Frame frame, final UiState state) {
        final var withGutter = Layout.horizontal()
                .constraints(Constraint.length(2), Constraint.fill())
                .split(rect);
        frame.renderWidget(Block.builder().borders(Borders.LEFT_ONLY)
                .borderStyle(Style.EMPTY.fg(modeColor())).build(), withGutter.get(0));
        final var block = block(" prompt ");
        frame.renderWidget(block, withGutter.get(1));
        final var inner = block.inner(withGutter.get(1));
        if (inner.isEmpty()) {
            return;
        }
        final var area = TextArea.builder()
                .placeholder(state.busy ? "waiting for the agent..." : "type a prompt, Enter to send")
                .build();
        area.renderWithCursor(inner, frame.buffer(), state.input, frame);
    }

    private void renderCalling(final Rect rect, final dev.tamboui.terminal.Frame frame, final UiState state) {
        if (!state.busy) {
            return;
        }
        final var accent = Style.EMPTY.fg(Color.hex(tuiConfiguration.accent()));
        final var muted = Style.EMPTY.fg(Color.hex(tuiConfiguration.textMuted()));
        final var hint = "esc to interrupt";
        final var halves = Layout.horizontal().constraints(Constraint.fill(), Constraint.length(hint.length() + 1)).split(rect);
        if (state.runningTool != null) {
            final var name = state.runningToolCommand == null || state.runningToolCommand.isBlank()
                    ? state.runningTool : state.runningToolCommand;
            final var line = Line.from(Span.styled(spinner(state.spinnerTick) + " calling " + ellipsize(name, 60), accent));
            frame.renderWidget(Paragraph.builder().text(Text.from(line)).overflow(dev.tamboui.style.Overflow.ELLIPSIS).build(), halves.get(0));
        }
        final var hintLine = Line.from(Span.styled(hint, muted));
        frame.renderWidget(Paragraph.builder().text(Text.from(hintLine)).overflow(dev.tamboui.style.Overflow.CLIP).build(), halves.get(1));
    }

    private void renderStatus(final Rect rect, final dev.tamboui.terminal.Frame frame, final UiState state) {
        final var statusColor = Color.hex(state.busy ? tuiConfiguration.warning() : tuiConfiguration.success());
        final var muted = Style.EMPTY.fg(Color.hex(tuiConfiguration.textMuted()));
        final var parts = new java.util.ArrayList<Span>();
        parts.add(Span.styled(" " + state.status + "  ", Style.EMPTY.fg(statusColor)));
        parts.add(Span.styled("model " + ellipsize(chatClient.model(), 40) + "  ", muted));
        parts.add(Span.styled(modeName(), Style.EMPTY.fg(modeColor())));
        final var line = Line.from(parts.toArray(Span[]::new));
        frame.renderWidget(Paragraph.builder().text(Text.from(line)).overflow(dev.tamboui.style.Overflow.WRAP_WORD).build(), rect);
    }

    private static String spinner(final int tick) {
        return switch (tick % 4) {
            case 0 -> "|";
            case 1 -> "/";
            case 2 -> "-";
            default -> "\\";
        };
    }

    private void renderApproval(final dev.tamboui.terminal.Frame frame, final UiState state) {
        final var approval = state.approval;
        final var width = Math.min(Math.max(50, frame.width() * 3 / 5), frame.width());
        final var height = 7;
        final var rect = new Rect(Math.max(0, (frame.width() - width) / 2), Math.max(0, (frame.height() - height) / 3), width, height);
        frame.renderWidget(dev.tamboui.widgets.Clear.INSTANCE, rect);
        final var warning = Color.hex(tuiConfiguration.warning());
        final var block = Block.builder()
                .title(" approval required ")
                .borders(Borders.ALL)
                .borderType(BorderType.ROUNDED)
                .borderStyle(Style.EMPTY.fg(warning))
                .build();
        frame.renderWidget(block, rect);
        final var inner = block.inner(rect);
        if (inner.isEmpty()) {
            return;
        }
        final var text = Text.from(
                Line.from(Span.raw(approval.title()).bold()),
                Line.empty(),
                Line.from(Span.styled(firstLine(approval.detail()), Style.EMPTY.fg(Color.hex(tuiConfiguration.textMuted())))),
                Line.empty(),
                Line.from(Span.styled("Enter = allow, Esc = deny", Style.EMPTY.fg(warning))));
        frame.renderWidget(Paragraph.builder().text(text).build(), inner);
    }

    private Rect accentGutter(final Rect rect, final dev.tamboui.terminal.Frame frame) {
        final var parts = Layout.horizontal()
                .constraints(Constraint.length(1), Constraint.fill())
                .split(rect);
        frame.renderWidget(Block.builder().borders(Borders.LEFT_ONLY)
                .borderStyle(Style.EMPTY.fg(Color.hex(tuiConfiguration.accent()))).build(), parts.get(0));
        return parts.get(1);
    }

    private Block block(final String title) {
        return Block.builder()
                .title(title)
                .borders(Borders.ALL)
                .borderType(BorderType.ROUNDED)
                .borderColor(Color.hex(tuiConfiguration.border()))
                .build();
    }

    private boolean planMode() {
        return permissionService.mode() == AgentMode.PLAN;
    }

    private String modeName() {
        if (permissionService.isPermitAll()) {
            return "all";
        }
        return permissionService.mode().name().toLowerCase();
    }

    private Color modeColor() {
        if (permissionService.isPermitAll()) {
            return Color.hex(tuiConfiguration.permitAll());
        }
        return Color.hex(planMode() ? tuiConfiguration.warning() : tuiConfiguration.accent());
    }

    private String transcriptMarkdown(final UiState state) {
        final var sb = new StringBuilder(state.transcript);
        if (state.busy && state.pending.length() > 0) {
            sb.append("\n\n**Assistant**\n\n").append(state.pending);
        }
        if (state.runningTool != null) {
            sb.append("\n\n`").append(spinner(state.spinnerTick)).append(" running ")
                    .append(state.runningToolCommand == null || state.runningToolCommand.isBlank()
                            ? state.runningTool : state.runningToolCommand).append("`");
            if (state.toolStream.length() > 0) {
                sb.append("\n\n```text\n").append(state.toolStream.toString().stripTrailing()).append("```\n");
            }
        }
        final var md = sb.toString().strip();
        return md.isBlank() ? WELCOME : md;
    }

    private static String toolCommand(final String argsJson) {
        if (argsJson == null || argsJson.isBlank() || argsJson.equals("{}")) {
            return "";
        }
        final var sb = new StringBuilder();
        final var stripped = argsJson.strip();
        for (final var key : List.of("command", "path", "workdir")) {
            final var token = "\"" + key + "\":";
            final var idx = stripped.indexOf(token);
            if (idx < 0) {
                continue;
            }
            final var start = idx + token.length();
            final var quote = stripped.indexOf('"', start);
            if (quote < 0) {
                continue;
            }
            final var end = stripped.indexOf('"', quote + 1);
            if (end < 0) {
                continue;
            }
            sb.append(stripped, quote + 1, end).append(' ');
        }
        return sb.toString().strip();
    }

    private static String firstLine(final String text) {
        if (text == null) {
            return "";
        }
        final var idx = text.indexOf('\n');
        final var line = idx < 0 ? text : text.substring(0, idx);
        return line.length() > 120 ? line.substring(0, 120) + "..." : line;
    }

    private static String ellipsize(final String text, final int max) {
        if (text == null || text.length() <= max) {
            return text == null ? "" : text;
        }
        return max <= 3 ? text.substring(0, Math.max(0, max)) : text.substring(0, max - 3) + "...";
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

    private record Approval(String title, String detail, CountDownLatch latch, AtomicBoolean approved) {
    }

    private static final class UiState {
        private final StringBuilder transcript = new StringBuilder();
        private final StringBuilder pending = new StringBuilder();
        private final StringBuilder thinking = new StringBuilder();
        private final TextAreaState input = new TextAreaState();
        private volatile String sessionId = "";
        private volatile String title;
        private volatile String status = "";
        private volatile boolean busy;
        private volatile boolean showThinking;
        private volatile boolean showToolActions = true;
        private volatile boolean showTodos;
        private volatile boolean showTodosCollapsed;
        private volatile boolean interrupted;
        private volatile int scrollBack;
        private volatile int promptTokens;
        private volatile int completionTokens;
        private volatile int estimatedContext;
        private volatile int contextPercent;
        private volatile Approval approval;
        private volatile String modelCompletionBase = "";
        private volatile int modelCompletionOffset;
        private volatile String slashCompletionBase = "";
        private volatile int slashCompletionOffset;
        private volatile String runningTool;
        private volatile String runningToolCommand;
        private final StringBuilder toolStream = new StringBuilder();
        private volatile int spinnerTick;
    }
}
