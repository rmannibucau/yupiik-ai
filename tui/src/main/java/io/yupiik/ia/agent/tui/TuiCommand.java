package io.yupiik.ia.agent.tui;

import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Layout;
import dev.tamboui.layout.Rect;
import dev.tamboui.markdown.MarkdownStyles;
import dev.tamboui.markdown.MarkdownView;
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
import dev.tamboui.widgets.block.BorderType;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.checkbox.Checkbox;
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
import io.yupiik.ia.agent.core.agent.AgentEventBridge;
import io.yupiik.ia.agent.core.agent.AgentLoop;
import io.yupiik.ia.agent.core.agent.AgentProvider;
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
import io.yupiik.ia.agent.core.question.FeedbackGate;
import io.yupiik.ia.agent.core.question.Question;
import io.yupiik.ia.agent.core.question.UserFeedbackService;
import io.yupiik.ia.agent.core.runtime.VirtualThreadExecutor;
import io.yupiik.ia.agent.core.session.SessionStore;
import io.yupiik.ia.agent.core.todo.PlanFileService;
import io.yupiik.ia.agent.core.todo.PlanTodoSeeder;
import io.yupiik.ia.agent.core.todo.TodoStore;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.wiring.ProviderManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@DefaultScoped
@Command(name = "tui", description = "Interactive terminal UI session with the yca coding agent.")
public class TuiCommand implements Runnable {
    private static final String HINTS = "Enter send | Esc interrupt | drag select+copy | Ctrl+T thinking | Ctrl+Y actions | Ctrl+P todos | Ctrl+S compact | Ctrl+L clear | Ctrl+C quit";
    private static final long DEFAULT_MAX_HISTORY_CHARACTERS = 10_000_000L;
    private static final String WELCOME = "# yca\n\nType a prompt and press Enter. `/help` lists the slash commands.";
    private static final String SHORTCUTS = """
            | key | action |
            |---|---|
            | Enter | send the prompt |
            | Shift/Alt+Enter | newline |
            | Esc | clear the selection, or interrupt the running agent |
            | Mouse drag | select text and copy it to the clipboard |
            | Double/triple-click | select a word / a whole row |
            | Ctrl+Shift+C | copy the current selection again |
            | Ctrl+C | quit |
            | Ctrl+T | toggle all thinking blocks |
            | Ctrl+Y | toggle all executed tool blocks |
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
            | /agent [name] | list custom agents, or set the active agent ('' clears, Tab completes) |
            | /mode | switch mode: /mode build or /mode plan |
            | /editplan [path] | in plan mode, export the plan to a file, edit it in your editor, then press Enter to reload (Esc or click Cancel to abort) |
            | /memory | show the project memory |
            | /context [path] | show the resolved context (system prompt + session), write to path when given |
            | /permissions (all\\|none) | allow all tools without asking (all), or restore interactive approval (none) |
            | /tools [filters] | list tools per MCP server; space separated filters must all match the lowercase server/tool name |
            | /structural-code-graph | resync the structural code graph (symbols, edges and doc tree) on demand |
            | /clear | clear the transcript |
            | /quit | exit yca |
            """;

    private final TuiConf conf;
    private final TuiConfiguration tuiConfiguration;
    private final CodingAgentConfiguration configuration;
    private final AgentLoop agentLoop;
    private final AgentEventBridge bridge;
    private final PermissionService permissionService;
    private final UserFeedbackService userFeedbackService;
    private final ContextManager contextManager;
    private final SessionStore sessionStore;
    private final TodoStore todoStore;
    private final PlanTodoSeeder planTodoSeeder;
    private final PlanFileService planFileService;
    private final ChatClient chatClient;
    private final MemoryStore memoryStore;
    private final SystemPromptBuilder systemPromptBuilder;
    private final McpManager mcpManager;
    private final VirtualThreadExecutor executor;
    private final StructuralCodeGraph structuralCodeGraph;
    private final ProviderManager providerManager;
    private final AgentProvider agentProvider;
    private final TuiTranscriptModel model;
    private final TuiRunnerFactory tuiRunnerFactory;
    private final Style thoughtStyle;
    private final Style toolStyle;
    private final MarkdownStyles toolMarkdownStyles;

    public TuiCommand(final TuiConf conf, final TuiConfiguration tuiConfiguration,
                      final CodingAgentConfiguration configuration, final AgentLoop agentLoop,
                      final AgentEventBridge bridge, final PermissionService permissionService,
                      final UserFeedbackService userFeedbackService,
                      final ContextManager contextManager, final SessionStore sessionStore,
                      final TodoStore todoStore, final PlanTodoSeeder planTodoSeeder, final PlanFileService planFileService,
                      final ChatClient chatClient, final MemoryStore memoryStore,
                      final SystemPromptBuilder systemPromptBuilder, final McpManager mcpManager,
                      final VirtualThreadExecutor executor,
                      final StructuralCodeGraph structuralCodeGraph,
                      final ProviderManager providerManager,
                      final AgentProvider agentProvider,
                      final TuiTranscriptModel model,
                      final TuiRunnerFactory tuiRunnerFactory) {
        this.conf = conf;
        this.tuiConfiguration = tuiConfiguration;
        this.configuration = configuration;
        this.agentLoop = agentLoop;
        this.bridge = bridge;
        this.permissionService = permissionService;
        this.userFeedbackService = userFeedbackService;
        this.contextManager = contextManager;
        this.sessionStore = sessionStore;
        this.todoStore = todoStore;
        this.planTodoSeeder = planTodoSeeder;
        this.planFileService = planFileService;
        this.chatClient = chatClient;
        this.memoryStore = memoryStore;
        this.systemPromptBuilder = systemPromptBuilder;
        this.mcpManager = mcpManager;
        this.executor = executor;
        this.structuralCodeGraph = structuralCodeGraph;
        this.providerManager = providerManager;
        this.agentProvider = agentProvider;
        this.model = model;
        this.tuiRunnerFactory = tuiRunnerFactory;
        final var opacity = Math.min(1.0, Math.max(0.05, tuiConfiguration.thinkingOpacity()));
        this.thoughtStyle = Style.EMPTY.fg(TuiColors.blend(tuiConfiguration.text(), tuiConfiguration.backgroundPanel(), opacity));
        final var toolOpacity = Math.min(1.0, Math.max(0.05, tuiConfiguration.toolOutputOpacity()));
        final var toolFg = TuiColors.blend(tuiConfiguration.text(), tuiConfiguration.backgroundPanel(), toolOpacity);
        this.toolStyle = Style.EMPTY.fg(toolFg);
        this.toolMarkdownStyles = MarkdownStyles.builder()
                .codeBlock(MarkdownStyles.DEFAULTS.codeBlock().fg(toolFg))
                .inlineCode(MarkdownStyles.DEFAULTS.inlineCode().fg(toolFg))
                .build();
    }

    @RootConfiguration("tui")
    public record TuiConf(
            @Property(defaultValue = "false", documentation = "Use vim style key bindings.") boolean vimBindings,
            @Property(defaultValue = "true", documentation = "Show the reasoning/thinking stream by default.") boolean showThinking,
            @Property(defaultValue = "\"dark\"", documentation = "UI theme: dark or light.") String theme,
            @Property(defaultValue = "false", documentation = "Bypass permission prompts and allow all tool calls (not persisted to the transcript).") boolean permitAll) {
    }

    @Override
    public void run() {
        final var state = new UiState(model);
        state.sessionId = sessionStore.create();
        state.showThinking = conf.showThinking();
        final var transcriptConf = tuiConfiguration.transcript();
        state.model.reset();
        state.model.maxHistoryCharacters(transcriptConf == null || transcriptConf.maxHistoryCharacters() <= 0
                ? DEFAULT_MAX_HISTORY_CHARACTERS : transcriptConf.maxHistoryCharacters());
        try (final var tui = tuiRunnerFactory.create(conf)) {
            bridge.sink(event -> tui.runLater(() -> onEvent(event, state)));
            permissionService.setGate(gate(tui, state));
            userFeedbackService.setGate(feedbackGate(tui, state));
            permissionService.setPermitAll(conf.permitAll());
            state.model.status("ready");
            try {
                tui.run((event, runner) -> onKey(event, runner, state), frame -> render(frame, state));
            } finally {
                bridge.sink(null);
                permissionService.setGate(null);
                userFeedbackService.setGate(null);
                permissionService.setPermitAll(false);
            }
        } catch (final RuntimeException e) {
            throw e;
        } catch (final Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
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
            case LEFT -> toggleTab(prompt, false);
            case RIGHT -> toggleTab(prompt, true);
            case UP -> moveOption(prompt, qIndex, -1);
            case DOWN -> moveOption(prompt, qIndex, 1);
            case BACKSPACE -> toggleTab(prompt, false);
            case TAB -> prompt.focusInput(true);
            case ENTER -> toggleTab(prompt, true);
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
            default -> {
            }
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
            fq.selectedIndex(Math.floorMod((current < 0 ? 0 : current) + delta, total));
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
            default -> {
            }
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
                        ? fq.question.options().get(fq.selectedIndex()) : null;
                answers.put(fq.question.id(),
                        fq.selectedIndex() == opts && opts > 0 && !own.isEmpty() ? own : selected);
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
        }
    }

    private boolean onKey(final Event event, final TuiRunner runner, final UiState state) {
        if (event instanceof final PasteEvent paste) {
            if (state.feedbackPrompt != null) {
                feedbackInput(state.feedbackPrompt).insert(paste.text());
                return true;
            }
            state.input.insert(paste.text());
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
            if (key.isConfirm()) {
                state.approval.approved().set(true);
                state.approval.latch().countDown();
                state.approval = null;
                state.model.status("approved");
            } else if (key.isCancel() || key.isKey(KeyCode.ESCAPE)) {
                state.approval.latch().countDown();
                state.approval = null;
                state.model.status("denied");
            }
            return true;
        }
        if (key.hasCtrl() && key.hasShift() && key.isCharIgnoreCase('c')) {
            copySelection(runner, state);
            return true;
        }
        if (key.isCtrlC()) {
            runner.quit();
            return true;
        }
        if (key.hasCtrl() && key.isCharIgnoreCase('t')) {
            state.showThinking = !state.showThinking;
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
            state.model.status("tool blocks " + (state.showToolActions ? "shown" : "hidden"));
            return true;
        }
        if (key.isCancel() || key.isKey(KeyCode.ESCAPE)) {
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
                state.model.status("copied " + copied + " characters");
            } catch (final IOException e) {
                state.model.status("clipboard copy failed: " + e.getMessage());
            }
        });
    }

    private void submit(final String text, final TuiRunner runner, final UiState state) {
        if (text.startsWith("/")) {
            slash(text, runner, state);
            return;
        }
        if (state.model.busy()) {
            waiting(state, "busy - press Esc to interrupt", SpinnerStyle.CLOCK);
            return;
        }
        if (state.title == null || state.title.isBlank()) {
            state.title = TuiInputSupport.firstLine(text);
        }
        state.model.startUserTurn(text);
        state.interrupted = false;
        waiting(state, "thinking...", SpinnerStyle.CLOCK);
        final var override = agentOverride(text);
        final var agentName = override.agentName() != null ? override.agentName() : state.activeAgent;
        final var prompt = override.prompt();
        final var history = sessionStore.load(state.sessionId);
        executor.execute(() -> agentLoop.run(prompt, agentName, state.sessionId, history, () -> state.interrupted));
    }

    private TuiInputSupport.AgentOverride agentOverride(final String text) {
        return TuiInputSupport.agentOverride(text, name -> agentProvider.byName(name) != null);
    }

    private void slash(final String text, final TuiRunner runner, final UiState state) {
        state.model.transcript().append("\n\n---\n\n**You**\n\n`").append(text).append("`\n");
        switch (text) {
            case "/help" -> state.model.transcript().append(HELP);
            case "/shortcuts" -> state.model.transcript().append(SHORTCUTS);
            case "/new" -> {
                state.sessionId = sessionStore.create();
                state.model.clearConversation();
                state.activeAgent = "";
                state.model.status("new session " + state.sessionId);
            }
            case "/resume" -> resume(state);
            case "/editplan" -> editPlan("", runner, state);
            case "/compact" -> compact(runner, state);
            case "/model" -> modelCommand(text, runner, state);
            case "/provider" -> providerCommand(text, runner, state);
            case "/agent" -> agentCommand(text, runner, state);
            case "/memory" -> {
                final var memory = memoryStore.readProject();
                state.model.transcript().append("\n```text\n").append(memory.isBlank() ? "(empty project memory)" : memory)
                        .append("\n```\n");
            }
            case "/context" -> context("", state);
            case "/tools" -> toolsCommand(text, runner, state);
            case "/structural-code-graph" -> resyncGraph(runner, state);
            case "/clear" -> state.model.clearConversation();
            case "/quit", "/exit" -> runner.quit();
            default -> {
                if (text.startsWith("/model ")) {
                    modelCommand(text, runner, state);
                } else if (text.startsWith("/provider ")) {
                    providerCommand(text, runner, state);
                } else if (text.startsWith("/mode ")) {
                    mode(text.substring("/mode ".length()).strip(), runner, state);
                } else if (text.startsWith("/editplan ")) {
                    editPlan(text.substring("/editplan ".length()).strip(), runner, state);
                } else if (text.startsWith("/context ")) {
                    context(text.substring("/context ".length()).strip(), state);
                } else if (text.startsWith("/permissions ")) {
                    permissions(text.substring("/permissions ".length()).strip(), state);
                } else if (text.startsWith("/agent ")) {
                    agentCommand(text, runner, state);
                } else if (text.startsWith("/tools ")) {
                    toolsCommand(text, runner, state);
                } else {
                    state.model.status("unknown command: " + text + " (/help)");
                }
            }
        }
    }

    private void resume(final UiState state) {
        final var last = sessionStore.lastSessionId();
        if (last == null) {
            state.model.status("no session to resume");
            return;
        }
        state.sessionId = last;
        state.model.clearConversation();
        for (final var message : sessionStore.load(last)) {
            switch (message.role()) {
                case "user" -> state.model.transcript().append("\n\n---\n\n**You**\n\n").append(message.content()).append('\n');
                case "assistant" -> state.model.transcript().append("\n\n---\n\n**Assistant**\n\n").append(message.content()).append('\n');
                case "tool" -> state.model.transcript().append("\n\n*tool `").append(message.name()).append("` -> ")
                        .append(TuiInputSupport.firstLine(String.valueOf(message.content()))).append("*\n");
                default -> {
                }
            }
        }
        state.model.status("resumed session " + last);
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
                        .append(TuiInputSupport.firstLine(String.valueOf(message.content()))).append('\n');
            }
            final var context = sb.toString();
            state.model.transcript().append("\n").append(context).append('\n');
            if (arg != null && !arg.isBlank()) {
                final var target = Path.of(arg).toAbsolutePath();
                Files.createDirectories(target.getParent() == null ? Path.of(".") : target.getParent());
                Files.writeString(target, context);
                state.model.status("context written to " + target);
            } else {
                state.model.status("context shown (" + messages.size() + " messages)");
            }
            state.model.scrollBack(0);
        } catch (final IOException e) {
            state.model.status("context failed: " + e.getMessage());
        }
    }

    private void permissions(final String value, final UiState state) {
        final var v = value == null ? "" : value.strip();
        if (v.equals("all") || v.equals("unsafe")) {
            permissionService.setPermitAll(true);
            state.model.status("permissions: all tools allowed");
        } else if (v.equals("none") || v.equals("safe")) {
            permissionService.setPermitAll(false);
            state.model.status("permissions: asking for approval");
        } else {
            state.model.status("usage: /permissions all|none");
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
        state.model.status("model: " + model);
    }

    private void providerCommand(final String text, final TuiRunner runner, final UiState state) {
        final var arg = text.substring("/provider".length()).strip();
        final var providers = providerManager.providers();
        if (!arg.isEmpty()) {
            chatClient.setProvider(arg);
            state.model.status("provider: " + arg);
            return;
        }
        final var current = chatClient.providerId();
        final var sb = new StringBuilder("\n\n**Providers** (current: ").append(current).append(")**\n\n");
        if (providers.isEmpty()) {
            sb.append("(no providers configured)");
        } else {
            for (final var p : providers) {
                final var id = p.id();
                sb.append("- ").append(id.equals(current) ? "**" + id + "**" : id).append('\n');
            }
        }
        sb.append("\nUse `/provider <id>` to switch.");
        runner.runLater(() -> {
            state.model.transcript().append(sb).append('\n');
            state.model.scrollBack(0);
        });
    }

    private void agentCommand(final String text, final TuiRunner runner, final UiState state) {
        final var arg = text.substring("/agent".length()).strip();
        if (arg.isEmpty()) {
            final var agents = agentProvider.agents();
            final var sb = new StringBuilder("\n\n**Custom agents** (active: ").append(state.activeAgent.isBlank() ? "-" : state.activeAgent)
                    .append(")**\n\n");
            if (agents.isEmpty()) {
                sb.append("(no custom agents configured; add them under yca.agents in the configuration)");
            } else {
                for (final var agent : agents) {
                    sb.append("- ").append(agent.name()).append('\n');
                }
            }
            sb.append("\nUse `/agent <name>` to set the active agent, `/agent` to clear it.");
            runner.runLater(() -> {
                state.model.transcript().append(sb).append('\n');
                state.model.scrollBack(0);
            });
            return;
        }
        if (agentProvider.byName(arg) == null) {
            state.model.status("unknown agent: " + arg + " (/agent)");
            return;
        }
        state.activeAgent = arg;
        state.model.status("agent: " + arg);
    }

    private void toolsCommand(final String text, final TuiRunner runner, final UiState state) {
        final var filters = new ArrayList<String>();
        for (final var token : text.substring("/tools".length()).strip().split(" ")) {
            final var filter = token.strip().toLowerCase(Locale.ROOT);
            if (!filter.isEmpty()) {
                filters.add(filter);
            }
        }
        executor.execute(() -> {
            try {
                final var byServer = new TreeMap<>(mcpManager.toolsByServer());
                final var sb = new StringBuilder("\n\n**Tools**\n\n```text\n");
                if (byServer.isEmpty()) {
                    sb.append("(no tools available)\n");
                } else {
                    var count = 0;
                    for (final var server : byServer.entrySet()) {
                        final var lines = new ArrayList<String>();
                        final var tools = server.getValue().stream()
                                .sorted(Comparator.comparing(EmbeddedTool::name))
                                .toList();
                        for (final var tool : tools) {
                            final var root = (server.getKey() + '_' + tool.name()).toLowerCase(Locale.ROOT);
                            if (filters.stream().anyMatch(filter -> !root.contains(filter))) {
                                continue;
                            }
                            final var description = toolDescription(server.getKey(), tool);
                            lines.add(tool.name() + (description == null ? "" : ": " + description));
                        }
                        if (lines.isEmpty()) {
                            continue;
                        }
                        sb.append(server.getKey()).append('\n');
                        for (var i = 0; i < lines.size(); i++) {
                            sb.append(i == lines.size() - 1 ? "└─ " : "├─ ").append(lines.get(i)).append('\n');
                        }
                        count += lines.size();
                    }
                    if (count == 0) {
                        sb.append("(no tool matches)\n");
                    }
                }
                sb.append("```");
                runner.runLater(() -> {
                    state.model.transcript().append(sb).append('\n');
                    state.model.scrollBack(0);
                });
            } catch (final RuntimeException e) {
                runner.runLater(() -> state.model.status("tools listing failed: " + e.getMessage()));
            }
        });
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

    private void completeModel(final TuiRunner runner, final UiState state) {
        final var text = state.input.text();
        if (!text.startsWith("/model ")) {
            state.model.status("completion only applies to /model ");
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
                        state.model.status("no model matching '" + prefixNow + "'");
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
                runner.runLater(() -> state.model.status("completion failed: " + e.getMessage()));
            }
        });
    }

    private void complete(final TuiRunner runner, final UiState state) {
        final var text = state.input.text();
        if (text.startsWith("/model ") && text.length() > "/model ".length()) {
            completeModel(runner, state);
            return;
        }
        if (text.startsWith("/agent ") && text.length() > "/agent ".length()) {
            completeAgent(runner, state);
            return;
        }
        if (text.startsWith("/")) {
            completeSlash(runner, state);
            return;
        }
        state.model.status("completion only applies to / (slash) commands");
    }

    private void completeAgent(final TuiRunner runner, final UiState state) {
        final var text = state.input.text();
        final var prefix = text.substring("/agent ".length());
        final var names = agentProvider.agents().stream().map(a -> a.name()).filter(n -> !n.isBlank() && n.startsWith(prefix)).toList();
        if (names.isEmpty()) {
            state.model.status("no agent matching '" + prefix + "'");
            return;
        }
        if (!prefix.equals(state.slashCompletionBase)) {
            state.slashCompletionBase = prefix;
            state.slashCompletionOffset = 0;
        }
        final var choice = names.get(state.slashCompletionOffset % names.size());
        state.slashCompletionOffset++;
        state.input.clear();
        state.input.insert("/agent " + choice);
    }

    private void completeSlash(final TuiRunner runner, final UiState state) {
        final var text = state.input.text();
        final var prefix = text.startsWith("/") ? text.substring(1) : text;
        final var matches = TuiInputSupport.slashMatches(prefix);
        if (matches.isEmpty()) {
            state.model.status("no command matching '/" + prefix + "'");
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
        final var target = forward ? TuiInputSupport.nextWord(text, col) : TuiInputSupport.previousWord(text, col);
        final var len = text.length();
        state.input.setText(text);
        for (var i = len; i > target; i--) {
            state.input.moveCursorLeft();
        }
    }

    private void models(final TuiRunner runner, final UiState state) {
        executor.execute(() -> {
            try {
                final var ids = chatClient.models().ids();
                runner.runLater(() -> {
                    state.model.transcript().append("\n\n**Models**\n\n")
                            .append(ids.isEmpty() ? "(provider does not expose /v1/models)" : String.join("\n", ids))
                            .append('\n');
                    state.model.scrollBack(0);
                });
            } catch (final Exception e) {
                runner.runLater(() -> state.model.status("models failed: " + e.getMessage()));
            }
        });
    }

    private void mode(final String value, final TuiRunner runner, final UiState state) {
        final var mode = AgentMode.fromConfigurationString(value);
        permissionService.setMode(mode);
        state.model.status("mode: " + mode.name().toLowerCase());
        if (mode == AgentMode.BUILD && todoStore.all().isEmpty() && !state.model.busy()) {
            state.model.status("mode: build - deriving todos from the plan...");
            executor.execute(() -> {
                try {
                    final var seeded = planTodoSeeder.seedFromLastAssistant(state.sessionId);
                    runner.runLater(() -> state.model.status(seeded
                            ? "todos seeded from the plan"
                            : "no plan found to seed todos from"));
                } catch (final RuntimeException e) {
                    runner.runLater(() -> state.model.status("todo seeding failed: " + e.getMessage()));
                }
            });
        }
    }

    private void editPlan(final String arg, final TuiRunner runner, final UiState state) {
        if (permissionService.mode() != AgentMode.PLAN) {
            state.model.status("editplan is only available in plan mode (run /mode plan)");
            return;
        }
        if (state.model.busy()) {
            state.model.status("editplan is not available while busy");
            return;
        }
        final var plan = planTodoSeeder.plan(state.sessionId);
        if (plan == null || plan.isBlank()) {
            state.model.status("no plan to edit yet - produce a plan first");
            return;
        }
        final Path path;
        if (arg != null && !arg.isBlank()) {
            path = Path.of(arg).toAbsolutePath();
        } else {
            path = planFileService.defaultPath(Path.of(configuration.resolveStorageHome()),
                    state.title == null || state.title.isBlank() ? "plan" : state.title, state.sessionId);
        }
        try {
            Files.createDirectories(path.getParent() == null ? Path.of(".") : path.getParent());
            Files.writeString(path, planFileService.render(plan, todoStore.all()));
        } catch (final IOException e) {
            state.model.status("editplan failed to write " + path + ": " + e.getMessage());
            return;
        }
        state.editPlan = new PendingEditPlan(path, planFileService.render(plan, todoStore.all()), null, null);
        state.model.status("plan exported to " + path + " - edit it, save, then press Enter to reload (Esc or click Cancel to abort)");
        state.model.transcript().append("\n\n> plan exported to `").append(path)
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
        if (!Files.isRegularFile(path)) {
            state.model.status("plan file not found: " + path + " (still waiting - edit and press Enter)");
            return;
        }
        try {
            final var edited = Files.readString(path);
            if (edited.equals(pending.original())) {
                state.editPlan = null;
                state.model.status("no changes in the plan (nothing to reload)");
                return;
            }
            final var parsed = planFileService.parse(edited);
            planFileService.reload(state.sessionId, parsed);
            state.editPlan = null;
            state.model.status((parsed.todos().isEmpty() ? "plan reloaded from " : "plan and todos reloaded from ") + path);
        } catch (final IOException e) {
            state.model.status("editplan failed to read " + path + ": " + e.getMessage());
        }
    }

    private void abortEditPlan(final UiState state) {
        state.editPlan = null;
        state.model.status("plan edit cancelled");
    }

    private void renderEditPlan(final dev.tamboui.terminal.Frame frame, final UiState state) {
        final var pending = state.editPlan;
        final var accent = Color.hex(tuiConfiguration.accent());
        final var warning = Color.hex(tuiConfiguration.warning());
        final var text = Color.hex(tuiConfiguration.text());
        final var muted = Color.hex(tuiConfiguration.textMuted());
        final var width = Math.min(Math.max(60, frame.width() * 4 / 5), frame.width());
        final var height = Math.min(Math.max(10, frame.height() / 3), frame.height());
        final var rect = new Rect(Math.max(0, (frame.width() - width) / 2), Math.max(0, (frame.height() - height) / 4), width, height);
        frame.renderWidget(dev.tamboui.widgets.Clear.INSTANCE, rect);
        final var block = Block.builder()
                .title(" edit plan ")
                .borders(Borders.ALL)
                .borderType(BorderType.ROUNDED)
                .borderStyle(Style.EMPTY.fg(warning))
                .build();
        frame.renderWidget(block, rect);
        final var inner = block.inner(rect);
        if (inner.isEmpty()) {
            return;
        }
        final var splits = Layout.vertical()
                .constraints(Constraint.length(1), Constraint.length(1), Constraint.fill(), Constraint.length(1))
                .split(inner);
        frame.renderWidget(Paragraph.builder().text(Text.from(Line.from(Span.styled("Edit the file below in your preferred editor, save, then return here.", Style.EMPTY.fg(text).bold())))).build(), splits.get(0));
        frame.renderWidget(Paragraph.builder().text(Text.from(Line.from(Span.styled(ellipsize(pending.path().toString(), splits.get(1).width()), Style.EMPTY.fg(muted))))).build(), splits.get(1));
        frame.renderWidget(Paragraph.builder().text(Text.from(
                Line.from(Span.raw("Use `# Plan` for the plan text and `# Todos` for the todo list (one `- [ ]` / `- [>]` / `- [x]` item per line).").style(Style.EMPTY.fg(muted))))).build(), splits.get(2));
        final var footer = splits.get(3);
        final var per = Math.max(1, footer.width() / 2);
        final var confirmRect = new Rect(footer.x(), footer.y(), per, footer.height());
        final var cancelRect = new Rect(footer.x() + per, footer.y(), footer.width() - per, footer.height());
        pending.confirmRect(confirmRect);
        pending.cancelRect(cancelRect);
        final var confirmLine = new Rect(confirmRect.x() + 1, confirmRect.y(), confirmRect.width() - 2, confirmRect.height());
        final var cancelLine = new Rect(cancelRect.x() + 1, cancelRect.y(), cancelRect.width() - 2, cancelRect.height());
        frame.renderWidget(Paragraph.builder().text(Text.from(Line.from(Span.styled("[ Confirm (Enter) ]", Style.EMPTY.fg(accent).bold())))).build(), confirmLine);
        frame.renderWidget(Paragraph.builder().text(Text.from(Line.from(Span.styled("[ Cancel (Esc) ]", Style.EMPTY.fg(text).bold())))).build(), cancelLine);
    }

    private void resyncGraph(final TuiRunner runner, final UiState state) {
        if (state.model.busy()) {
            state.model.status("cannot resync the graph while busy");
            return;
        }
        waiting(state, "resyncing structural code graph...", SpinnerStyle.BOUNCING_BALL);
        executor.execute(() -> {
            try {
                final var result = structuralCodeGraph.resync();
                runner.runLater(() -> {
                    state.model.status("structural code graph resynced");
                    state.model.transcript().append("\n\n> structural code graph: `").append(result).append("`\n");
                });
            } catch (final RuntimeException e) {
                runner.runLater(() -> state.model.status("resync failed: " + e.getMessage()));
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
                final var history = sessionStore.load(state.sessionId);
                if (history.isEmpty()) {
                    runner.runLater(() -> state.model.status("nothing to compact"));
                    return;
                }
                sessionStore.replace(state.sessionId, contextManager.compact(history));
                runner.runLater(() -> {
                    state.model.status("session compacted");
                    state.model.transcript().append("\n\n> session compacted\n");
                });
            } catch (final RuntimeException e) {
                runner.runLater(() -> state.model.status("compaction failed: " + e.getMessage()));
            }
        });
    }

    private void render(final dev.tamboui.terminal.Frame frame, final UiState state) {
        if (state.model.runningTool() != null || activeWait(state) != null) {
            state.spinnerTick++;
        }
        state.model.trim();
        final var todosRender = todoStore.render();
        if (!todosRender.equals(state.lastTodosRender)) {
            state.lastTodosRender = todosRender;
            if (!todosRender.isBlank() && !"(empty)".equals(todosRender) && !state.showTodos) {
                state.showTodos = true;
            }
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
            renderTodos(withTodos.get(1), frame, state, todosRender);
        }
        if (state.showThinking && state.model.thinkingLength() > 0) {
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
        if (state.feedbackPrompt != null) {
            renderFeedbackPrompt(frame, state);
        }
        if (state.editPlan != null) {
            renderEditPlan(frame, state);
        }
        state.selection.highlight(frame.buffer());
        state.screenGrid = SelectionState.snapshot(frame.buffer());
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
        text.add(Line.from(Span.styled("agent ", label), Span.styled(ellipsize(state.activeAgent.isBlank() ? "-" : state.activeAgent, width - 7), value)));
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
        text.add(Line.from(Span.styled("tokens in  ", label), Span.styled(String.valueOf(state.model.promptTokens()), value)));
        text.add(Line.from(Span.styled("tokens out ", label), Span.styled(String.valueOf(state.model.completionTokens()), value)));
        text.add(Line.from(Span.styled("context ~  ", label), Span.styled(state.model.estimatedContext() + " tok (" + state.model.contextPercent() + "%)", value)));
        final var lines = Text.from(text.toArray(Line[]::new));
        final var textRect = rows.get(0);
        final var overflow = Math.max(0, lines.height() - textRect.height());
        frame.renderWidget(Paragraph.builder().text(lines).scroll(overflow).build(), textRect);
        if (!rows.get(1).isEmpty()) {
            final var percent = Math.max(0, Math.min(100, state.model.contextPercent()));
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
        final var chunks = chunks(state.model.markdown(spinnerFrame(SpinnerStyle.SQUARE_CORNERS, state.spinnerTick), WELCOME), inner.width(), state);
        final var total = chunks.stream().mapToInt(c -> c.height()).sum();
        final var maxViewOffset = Math.max(0, total - Math.max(1, inner.height()));
        final var viewOffset = Math.min(maxViewOffset, Math.max(0, maxViewOffset - state.model.scrollBack()));
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

    private List<RenderedChunk> chunks(final String md, final int width, final UiState state) {
        final var out = new ArrayList<RenderedChunk>();
        for (final var region : TuiTranscriptModel.regions(md)) {
            if (region.thought()) {
                if (!state.showThinking) {
                    continue;
                }
                final var chunk = thoughtChunk(region.text(), width);
                out.add(new RenderedChunk(chunk.height(), chunk::at));
            } else if (region.tool()) {
                final var source = state.showToolActions ? region.text() : toolHeader(region.text());
                final var chunk = toolChunk(source, width);
                out.add(new RenderedChunk(chunk.height(), chunk::at));
            } else if (!region.text().isBlank()) {
                appendChunks(out, region.text(), width);
            }
        }
        return out;
    }

    private void appendChunks(final List<RenderedChunk> out, final String md, final int width) {
        for (final var seg : SyntaxHighlighter.split(md)) {
            if (seg.type() == SyntaxHighlighter.Segment.Type.TEXT) {
                final var chunk = markdownChunk(normalizeMarkdownBreaks(seg.text()), width);
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
    }

    private MarkdownChunk thoughtChunk(final String source, final int width) {
        final var text = normalizeMarkdownBreaks(source.strip());
        final var view = MarkdownView.builder().source(text).style(thoughtStyle).styles(MarkdownStyles.DEFAULTS)
                .overflow(Overflow.WRAP_WORD).build();
        final var height = Math.max(view.computeHeight(width), 1);
        return new MarkdownChunk(text, height, thoughtStyle, MarkdownStyles.DEFAULTS);
    }

    private MarkdownChunk toolChunk(final String source, final int width) {
        final var text = source.strip();
        final var view = MarkdownView.builder().source(text).style(toolStyle).styles(toolMarkdownStyles)
                .overflow(Overflow.WRAP_WORD).build();
        final var height = Math.max(view.computeHeight(width), 1);
        return new MarkdownChunk(text, height, toolStyle, toolMarkdownStyles);
    }

    private static String toolHeader(final String regionText) {
        for (final var line : regionText.strip().split("\n")) {
            if (!line.isBlank()) {
                return line;
            }
        }
        return "";
    }

    /**
     * Converts single {@code \n} characters into CommonMark hard line breaks
     * (two trailing spaces + newline) so the default markdown renderer - which
     * treats a lone newline as a soft break collapsed into a space - displays
     * each source line on its own row. Runs of two or more consecutive newlines
     * (blank-line paragraph/heading separators) are left untouched.
     *
     * @param markdown the markdown source (a text segment; fenced code excluded)
     * @return the markdown with single newlines promoted to hard breaks
     */
    private static String normalizeMarkdownBreaks(final String markdown) {
        final var normalized = markdown.replace("\r\n", "\n");
        final var sb = new StringBuilder(normalized.length() + 16);
        int i = 0;
        while (i < normalized.length()) {
            final char c = normalized.charAt(i);
            if (c != '\n') {
                sb.append(c);
                i++;
                continue;
            }
            int j = i;
            while (j < normalized.length() && normalized.charAt(j) == '\n') {
                j++;
            }
            final int run = j - i;
            if (run == 1) {
                sb.append("  \n");
            } else {
                sb.append("\n".repeat(run));
            }
            i = j;
        }
        return sb.toString();
    }

    private static MarkdownChunk markdownChunk(final String text, final int width) {
        final var view = MarkdownView.builder().source(text).styles(MarkdownStyles.DEFAULTS)
                .overflow(Overflow.WRAP_WORD).build();
        final var height = Math.max(view.computeHeight(width), 1);
        return new MarkdownChunk(text, height, Style.EMPTY, MarkdownStyles.DEFAULTS);
    }

    private record MarkdownChunk(String source, int height, Style baseStyle, MarkdownStyles styles) {
        dev.tamboui.widget.Widget at(final int skip) {
            return MarkdownView.builder().source(source).style(baseStyle).styles(styles)
                    .overflow(Overflow.WRAP_WORD)
                    .scroll(Math.max(0, skip))
                    .build();
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
        final var text = Text.styled(state.model.thinking().toString(), thoughtStyle);
        final var offset = Math.max(0, text.height() - inner.height());
        frame.renderWidget(Paragraph.builder().text(text).scroll(offset).build(), inner);
    }

    private void renderTodos(final Rect rect, final dev.tamboui.terminal.Frame frame, final UiState state, final String todosRender) {
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
        final var render = todosRender;
        final var text = Text.from(render.isBlank() || "(empty)".equals(render) ? "(no todos)" : render);
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
                .placeholder(state.model.busy() ? "waiting for the agent..." : "type a prompt, Enter to send")
                .build();
        area.renderWithCursor(inner, frame.buffer(), state.input, frame);
    }

    private void renderCalling(final Rect rect, final dev.tamboui.terminal.Frame frame, final UiState state) {
        if (!state.model.busy()) {
            return;
        }
        final var accent = Style.EMPTY.fg(Color.hex(tuiConfiguration.accent()));
        final var muted = Style.EMPTY.fg(Color.hex(tuiConfiguration.textMuted()));
        final var hint = "esc to interrupt";
        final var halves = Layout.horizontal().constraints(Constraint.fill(), Constraint.length(hint.length() + 1)).split(rect);
        if (state.model.runningTool() != null) {
            final var name = state.model.runningToolCommand() == null || state.model.runningToolCommand().isBlank()
                    ? state.model.runningTool() : state.model.runningToolCommand();
            final var line = Line.from(Span.styled(spinnerFrame(SpinnerStyle.SQUARE_CORNERS, state.spinnerTick) + " calling " + ellipsize(name, 60), accent));
            frame.renderWidget(Paragraph.builder().text(Text.from(line)).overflow(dev.tamboui.style.Overflow.ELLIPSIS).build(), halves.get(0));
        }
        final var hintLine = Line.from(Span.styled(hint, muted));
        frame.renderWidget(Paragraph.builder().text(Text.from(hintLine)).overflow(dev.tamboui.style.Overflow.CLIP).build(), halves.get(1));
    }

    private void renderStatus(final Rect rect, final dev.tamboui.terminal.Frame frame, final UiState state) {
        final var statusColor = Color.hex(state.model.busy() ? tuiConfiguration.warning() : tuiConfiguration.success());
        final var muted = Style.EMPTY.fg(Color.hex(tuiConfiguration.textMuted()));
        final var parts = new java.util.ArrayList<Span>();
        final var frameStyle = state.model.runningTool() != null ? SpinnerStyle.SQUARE_CORNERS : activeWait(state);
        final var prefix = frameStyle == null ? "" : spinnerFrame(frameStyle, state.spinnerTick) + " ";
        parts.add(Span.styled(" " + prefix + state.model.status() + "  ", Style.EMPTY.fg(statusColor)));
        parts.add(Span.styled("model " + ellipsize(chatClient.model(), 40) + "  ", muted));
        parts.add(Span.styled(state.activeAgent.isBlank() ? "" : "agent " + state.activeAgent + "  ", muted));
        parts.add(Span.styled(modeName(), Style.EMPTY.fg(modeColor())));
        final var line = Line.from(parts.toArray(Span[]::new));
        frame.renderWidget(Paragraph.builder().text(Text.from(line)).overflow(dev.tamboui.style.Overflow.WRAP_WORD).build(), rect);
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
                Line.from(Span.styled(TuiInputSupport.firstLine(approval.detail()), Style.EMPTY.fg(Color.hex(tuiConfiguration.textMuted())))),
                Line.empty(),
                Line.from(Span.styled("Enter = allow, Esc = deny", Style.EMPTY.fg(warning))));
        frame.renderWidget(Paragraph.builder().text(text).build(), inner);
    }

    private void renderFeedbackPrompt(final dev.tamboui.terminal.Frame frame, final UiState state) {
        final var prompt = state.feedbackPrompt;
        final var accent = Style.EMPTY.fg(Color.hex(tuiConfiguration.accent()));
        final var muted = Style.EMPTY.fg(Color.hex(tuiConfiguration.textMuted()));
        final var textStyle = Style.EMPTY.fg(Color.hex(tuiConfiguration.text()));
        final var warning = Color.hex(tuiConfiguration.warning());
        final var width = Math.min(Math.max(60, frame.width() * 4 / 5), frame.width());
        final var height = Math.min(Math.max(12, 8 + prompt.questions().size() * 4), Math.max(12, frame.height() * 3 / 4));
        final var rect = new Rect(Math.max(0, (frame.width() - width) / 2), Math.max(0, (frame.height() - height) / 4), width, height);
        frame.renderWidget(dev.tamboui.widgets.Clear.INSTANCE, rect);
        final var block = Block.builder()
                .title(" user request ")
                .borders(Borders.ALL)
                .borderType(BorderType.ROUNDED)
                .borderStyle(accent)
                .build();
        frame.renderWidget(block, rect);
        final var inner = block.inner(rect);
        if (inner.isEmpty() || inner.height() < 4) {
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
        final var promptLine = isConfirmTab ? "Review your answers below, then press Enter to confirm, Backspace to go back and edit."
                : TuiInputSupport.firstLine(fq.question.prompt());
        frame.renderWidget(Paragraph.builder().text(Text.from(Line.from(Span.styled(promptLine, isConfirmTab ? textStyle : Style.EMPTY.fg(warning).bold())))).build(), content.get(0));

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
                .borderColor(Color.hex(prompt.focusInput() ? tuiConfiguration.accent() : tuiConfiguration.border()))
                .build();
        frame.renderWidget(inputBlock, inputRect);
        final var inputInner = inputBlock.inner(inputRect);
        if (!inputInner.isEmpty()) {
            final var area = TextArea.builder()
                    .placeholder(ownEntrySelected(fq)
                            ? "type your answer here" + (prompt.focusInput() ? " (Tab to exit)" : "")
                            : "or type your own answer" + (prompt.focusInput() ? " (Tab to exit)" : " (Tab to enter)"))
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

    private void renderFeedbackOptions(final dev.tamboui.terminal.Frame frame, final Rect rect,
                                       final FeedbackQuestion fq, final FeedbackPrompt prompt) {
        final var accent = Color.hex(tuiConfiguration.accent());
        final var muted = Color.hex(tuiConfiguration.textMuted());
        final var textColor = Color.hex(tuiConfiguration.text());
        final var options = fq.question.options();
        final var opts = options == null ? 0 : options.size();
        fq.optionRects().clear();
        if (opts == 0) {
            frame.renderWidget(Paragraph.builder()
                    .text(Text.from(Line.from(Span.styled("(no predefined options - use 'your own answer' below)", Style.EMPTY.fg(muted)))))
                    .build(), rect);
            return;
        }
        if (!fq.question.multiple()) {
            fq.selectedIndex(Math.max(0, Math.min(fq.selectedIndex(), opts)));
        } else {
            prompt.optionsFocus(Math.max(0, Math.min(prompt.optionsFocus(), opts)));
        }
        final var shown = Math.min(opts + 1, rect.height());
        for (int row = 0; row < shown; row++) {
            final var i = row;
            final var lineRect = new Rect(rect.x(), rect.y() + row, rect.width(), 1);
            fq.optionRects().add(lineRect);
            final var line = Line.from(buildOptionLine(fq, i, accent, muted, textColor));
            frame.renderWidget(Paragraph.builder().text(Text.from(line)).build(), lineRect);
        }
    }

    private Span[] buildOptionLine(final FeedbackQuestion fq, final int idx, final Color accent,
                                   final Color muted, final Color textColor) {
        final var opts = optionCount(fq);
        if (idx >= opts) {
            if (fq.question.multiple()) {
                final var checked = fq.ownBox().isChecked();
                return new Span[]{
                        Span.raw(checked ? "\u25a3 " : "\u25a2 ").style(checked ? Style.EMPTY.fg(accent) : Style.EMPTY.fg(muted)),
                        Span.raw("Type your own answer...").style(Style.EMPTY.fg(checked ? accent : textColor))};
            }
            final var selected = fq.selectedIndex() == idx;
            return new Span[]{
                    Span.raw("\u270e ").style(Style.EMPTY.fg(selected ? accent : muted)),
                    Span.raw("Type your own answer...")
                            .style(selected ? Style.EMPTY.fg(accent).bold() : Style.EMPTY.fg(textColor))};
        }
        final var option = fq.question.options().get(idx);
        if (fq.question.multiple()) {
            final var checked = fq.boxes()[idx].isChecked();
            return new Span[]{
                    Span.raw(checked ? "\u25a3 " : "\u25a2 ").style(checked ? Style.EMPTY.fg(accent) : Style.EMPTY.fg(muted)),
                    Span.raw(option).style(Style.EMPTY.fg(textColor))};
        }
        final var selected = fq.selectedIndex() == idx;
        return new Span[]{
                Span.raw(selected ? "\u25c9 " : "\u25cb ").style(selected ? Style.EMPTY.fg(accent) : Style.EMPTY.fg(muted)),
                Span.raw(option).style(selected ? Style.EMPTY.fg(accent).bold() : Style.EMPTY.fg(textColor))};
    }

    private void renderFeedbackSummary(final dev.tamboui.terminal.Frame frame, final Rect rect,
                                       final FeedbackPrompt prompt) {
        final var lines = new ArrayList<Line>();
        for (final var fq : prompt.questions()) {
            lines.add(Line.from(Span.styled(TuiInputSupport.firstLine(fq.question.prompt()), Style.EMPTY.fg(Color.hex(tuiConfiguration.accent())).bold())));
            final var own = fq.ownAnswer().text().strip();
            final var opts = optionCount(fq);
            var any = false;
            if (fq.question.multiple()) {
                for (int i = 0; i < fq.boxes().length && i < opts; i++) {
                    if (fq.boxes()[i].isChecked()) {
                        lines.add(Line.from(Span.styled("  - " + fq.question.options().get(i), Style.EMPTY.fg(Color.hex(tuiConfiguration.text())))));
                        any = true;
                    }
                }
                if (fq.ownBox().isChecked() && !own.isEmpty()) {
                    lines.add(Line.from(Span.styled("  - " + own, Style.EMPTY.fg(Color.hex(tuiConfiguration.text())))));
                    any = true;
                }
            } else {
                final var selected = fq.selectedIndex() >= 0 && fq.selectedIndex() < opts
                        ? fq.question.options().get(fq.selectedIndex()) : null;
                final var value = fq.selectedIndex() == opts && opts > 0 && !own.isEmpty() ? own : selected;
                if (value != null) {
                    lines.add(Line.from(Span.styled("  - " + value, Style.EMPTY.fg(Color.hex(tuiConfiguration.text())))));
                    any = true;
                }
            }
            if (!any) {
                lines.add(Line.from(Span.styled("  - (no selection)", Style.EMPTY.fg(Color.hex(tuiConfiguration.textMuted())))));
            }
            lines.add(Line.empty());
        }
        frame.renderWidget(Paragraph.builder().text(Text.from(lines.toArray(Line[]::new))).build(), rect);
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

        private FeedbackPrompt(final List<Question> raw, final CountDownLatch latch,
                               final Map<String, Object> answers, final AtomicBoolean answered) {
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
        private record Wait(String message, SpinnerStyle style) {
        }

        private final TuiTranscriptModel model;
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
        private volatile Approval approval;
        private volatile FeedbackPrompt feedbackPrompt;
        private volatile PendingEditPlan editPlan;
        private volatile String modelCompletionBase = "";
        private volatile int modelCompletionOffset;
        private volatile String slashCompletionBase = "";
        private volatile int slashCompletionOffset;
        private final SelectionState selection = new SelectionState();
        private volatile String[][] screenGrid = new String[0][0];
        private volatile int spinnerTick;
        private volatile String lastTodosRender = "";

        private UiState(final TuiTranscriptModel model) {
            this.model = model;
        }
    }
}
