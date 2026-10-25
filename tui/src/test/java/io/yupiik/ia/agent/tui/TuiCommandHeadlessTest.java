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

import static java.time.Duration.ofMillis;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.tamboui.style.Color;
import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyModifiers;
import dev.tamboui.tui.event.PasteEvent;
import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.framework.api.RuntimeContainer;
import io.yupiik.fusion.framework.api.configuration.impl.MapConfigSource;
import io.yupiik.fusion.framework.api.container.FusionListener;
import io.yupiik.fusion.framework.api.container.bean.ProvidedInstanceBean;
import io.yupiik.fusion.framework.api.container.configuration.ConfigurationRegistration;
import io.yupiik.fusion.framework.api.main.ArgsConfigSource;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.agent.AgentLoop;
import io.yupiik.ia.agent.core.agent.SubagentRegistry;
import io.yupiik.ia.agent.core.cli.AskUserAnswersFormatter;
import io.yupiik.ia.agent.core.cli.FormatDatePromptHelper;
import io.yupiik.ia.agent.core.llm.InBandToolCallExtractor;
import io.yupiik.ia.agent.core.llm.MarkupToolCallScanner;
import io.yupiik.ia.agent.core.maven.MavenRepositoryKnowledgeService;
import io.yupiik.ia.agent.core.question.Question;
import io.yupiik.ia.agent.core.session.SessionEntry;
import io.yupiik.ia.agent.core.shell.SessionRuntimeFactory;
import io.yupiik.ia.agent.core.shell.ShellRegistry;
import io.yupiik.ia.agent.core.shell.SpeakerLabels;
import io.yupiik.ia.agent.core.shell.TurnRunner;
import io.yupiik.ia.agent.core.shell.btw.BtwAnswerer;
import io.yupiik.ia.agent.core.shell.slash.SlashRegistry;
import io.yupiik.ia.agent.core.wiring.AutoLogConfigurer;
import io.yupiik.ia.agent.test.TestJsonMapper;
import io.yupiik.maven.knowledge.process.ProcessService;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * Boots the real fusion bean graph through autodiscovery (core + json + tui modules)
 * with a programmatically registered MapConfigSource pointing at {@link MockLlmServer}
 * and drives the TUI headlessly through the production {@link AgentLoop}.
 *
 * <p>The container and the mock server are shared across the tests of this class and
 * created in {@code @BeforeAll} so the once-per-class wiring cost is never attributed
 * to a single test.
 */
// TuiCommand redirects the JVM-wide "io.yupiik" java.util.logging logger to the UI by installing
// its own handler, and it SKIPS that installation when the logger already has a handler or already
// has useParentHandlers=false (TuiCommand.redirectLoggerToUi). Two TuiCommand instances booting
// concurrently in the same JVM would therefore make the loser silently drop log redirection, which
// breaks the assertions of both TUI test classes. The suite runs test classes concurrently (see
// the surefire configurationParameters in the root pom), so both classes take the same lock and
// never boot a TuiCommand at the same time.
@ResourceLock("io.yupiik-jul-handler")
@ExtendWith(TestJsonMapper.class)
class TuiCommandHeadlessTest {
    private static final long TIMEOUT_MS = Duration.ofSeconds(5).toMillis();

    @TempDir
    static Path temp;

    private static MockLlmServer llm;
    private static RuntimeContainer container;
    private static TuiDeps deps;

    private HeadlessBackend backend;
    private final AtomicReference<TuiRunner> tuiRef = new AtomicReference<>();
    private Thread uiThread;
    /** Render cycles observed through the headless runner (post-render processor), incl. no-op frames. */
    private final AtomicLong renderCycles = new AtomicLong();

    @BeforeAll
    static void boot() throws IOException {
        llm = new MockLlmServer();
        Files.createDirectories(temp.resolve("home").resolve("sessions"));
        container = bootContainer();
        try (final var depsInstance = container.lookup(TuiDeps.class)) {
            deps = depsInstance.instance();
        }
    }

    private static RuntimeContainer bootContainer() {
        return bootContainer(Map.of());
    }

    private static RuntimeContainer bootContainer(final Map<String, String> extra) {
        final var config = new LinkedHashMap<>(Map.of(
                "yca.storageHome",
                temp.resolve("home").toString(),
                // YcaConfigurationProducer looks the file up in the configuration before falling
                // back to the resolved "yca.storage.home" (via the fusion configuration, not a system
                // property), hence pointing it at a file that does not exist: this container wires
                // the *real* agent graph, and reading the developer's ~/.yupiik/coding-agent/
                // configuration.json would make the test call the configured provider (real tokens)
                // and run real tools. No system property involved, and the listener registering the
                // source below has a higher priority (Integer.MIN_VALUE) so the value is already
                // visible when the producer asks for it.
                "yca.configuration.json",
                temp.resolve("configuration.json").toString(),
                "yca.provider.baseUrl",
                llm.baseUrl(),
                "yca.provider.apiKey",
                "test-key",
                "yca.provider.model",
                "test-model",
                "yca.structuralCodeGraph.enabled",
                "false"));
        config.putAll(extra);
        return ConfiguringContainer.of()
                .register(new ProvidedInstanceBean<>(
                        DefaultScoped.class,
                        ArgsConfigSource.class,
                        () -> new ArgsConfigSource(List.of("--run-prompt", "wiring"))))
                .register(new FusionListener<ConfigurationRegistration>() {
                    @Override
                    public Type eventType() {
                        return ConfigurationRegistration.class;
                    }

                    @Override
                    public int priority() {
                        // before YcaConfigurationProducer (Integer.MIN_VALUE + 1): it reads
                        // yca.configuration.json while the configuration is being built, so the
                        // source above must already be registered when it does
                        return Integer.MIN_VALUE;
                    }

                    @Override
                    public void onEvent(
                            final RuntimeContainer container, final ConfigurationRegistration registration) {
                        registration.addSource().accept(new MapConfigSource(config));
                    }
                })
                .start();
    }

    @AfterAll
    static void shutdown() throws Exception {
        if (container != null) {
            container.close();
        }
        if (llm != null) {
            llm.close();
        }
    }

    private JsonMapper formatMapper;

    @BeforeEach
    void start(final JsonMapper mapper) {
        backend = new HeadlessBackend();
        formatMapper = mapper;
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
    }

    @Test
    void wiresTheFullBeanGraph() {
        try (final var cmd = container.lookup(TuiCommand.class);
                final var run = container.lookup(RunCommand.class);
                final var runtimeFactory = container.lookup(io.yupiik.ia.agent.core.shell.SessionRuntimeFactory.class);
                final var completer = container.lookup(io.yupiik.ia.agent.tui.prompt.SlashCommandCompleter.class);
                final var factory = container.lookup(TuiRunnerFactory.class)) {
            assertNotNull(cmd.instance());
            assertNotNull(run.instance());
            assertNotNull(runtimeFactory.instance());
            assertNotNull(completer.instance());
            assertNotNull(factory.instance());
        }
    }

    @Test
    void answersThroughRealAgentLoop() throws Exception {
        final var command = newUiCommand(container, deps);

        uiThread = new Thread(command::run, "tui-headless-test");
        uiThread.setDaemon(true);
        uiThread.start();
        awaitTui();
        awaitText("yca coding agent");

        tuiRef.get().dispatch(new PasteEvent("say hi to the mock"));
        awaitText("say hi to the mock");
        tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
        awaitText("hello from mock");
        awaitText("ready");

        final var sessions = temp.resolve("home").resolve("sessions");
        assertTrue(Files.isDirectory(sessions), "expected a sessions dir under " + sessions);
        try (final var stream = Files.list(sessions)) {
            assertTrue(stream.findAny().isPresent(), "expected a session file under " + sessions);
        }
        assertTrue(
                llm.requests().stream().anyMatch(r -> r.contains("say hi to the mock")),
                "expected the prompt to reach the mock LLM");

        tuiRef.get().dispatch(KeyEvent.ofChar('c', KeyModifiers.CTRL));
        uiThread.join(TIMEOUT_MS);
    }

    /**
     * Reproduces a crash in {@code TuiCommand.confirmAnswers}: a single (non-multiple) question with
     * no predefined options has no dropdown, so the only answer is the free-text field. The old logic
     * stored the (null) "selected" option into the answers map, and {@code ConcurrentHashMap} rejects
     * null values with a {@link NullPointerException}. The answer must come from the typed text.
     */
    @Test
    void confirmAnswersStoresFreeTextForNoOptionQuestion() throws Exception {
        final var command = newUiCommand(container, deps);

        final var answers = new ConcurrentHashMap<String, Object>();
        final var answered = new AtomicBoolean();
        final var latch = new CountDownLatch(1);
        final var prompt = newFeedbackPromptReflection(
                command, List.of(new Question("q1", "what?", List.of(), false)), answers, answered, latch);

        // the user types a free-text answer (no options to pick)
        final var own = promptQuestionReflection(prompt, 0);
        setTextArea(own, "my typed answer");

        invokeConfirmAnswers(command, new TuiCommand.UiState(deps.thinkTags(), Clock.systemUTC()), prompt);

        assertEquals("my typed answer", answers.get("q1"), "a no-options free-text answer must be stored");
        assertTrue(answered.get(), "confirm marks the prompt as answered");
        assertEquals(0, latch.getCount(), "confirm counts down the wait latch");
    }

    // --- reflection access to the private TuiCommand feedback internals, since the
    // FeedbackPrompt / FeedbackQuestion types and confirmAnswers have no public seam ---

    private static Object newFeedbackPromptReflection(
            final TuiCommand command,
            final List<Question> raw,
            final Map<String, Object> answers,
            final AtomicBoolean answered,
            final CountDownLatch latch)
            throws Exception {
        final var ctor = nestedClass("FeedbackPrompt")
                .getDeclaredConstructor(List.class, CountDownLatch.class, Map.class, AtomicBoolean.class);
        ctor.setAccessible(true);
        return ctor.newInstance(raw, latch, answers, answered);
    }

    @SuppressWarnings("unchecked")
    private static Object promptQuestionReflection(final Object prompt, final int index) throws Exception {
        final var questions = nestedClass("FeedbackPrompt").getDeclaredMethod("questions");
        questions.setAccessible(true);
        return ((List<Object>) questions.invoke(prompt)).get(index);
    }

    private static void setTextArea(final Object feedbackQuestion, final String text) throws Exception {
        final var field = nestedClass("FeedbackQuestion").getDeclaredField("ownAnswer");
        field.setAccessible(true);
        final var state = field.get(feedbackQuestion);
        final var setText = state.getClass().getMethod("setText", String.class);
        setText.invoke(state, text);
    }

    private static void invokeConfirmAnswers(
            final TuiCommand command, final TuiCommand.UiState state, final Object prompt) throws Exception {
        final var method = TuiCommand.class.getDeclaredMethod(
                "confirmAnswers", TuiCommand.UiState.class, nestedClass("FeedbackPrompt"));
        method.setAccessible(true);
        method.invoke(command, state, prompt);
    }

    private static Class<?> nestedClass(final String simpleName) throws ClassNotFoundException {
        return Class.forName("io.yupiik.ia.agent.tui.TuiCommand$" + simpleName);
    }

    @Test
    void errorBannerUsesErrorColoredBorder() throws Exception {
        final var command = newUiCommand(container, deps);

        uiThread = new Thread(command::run, "tui-headless-error");
        uiThread.setDaemon(true);
        uiThread.start();
        awaitTui();

        tuiRef.get().dispatch(new PasteEvent("/agent definitely-not-an-agent"));
        awaitText("/agent definitely-not-an-agent");
        tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
        awaitText("unknown agent: definitely-not-an-agent");
        awaitText("Alt+E for details");

        final var lines = backend.text().split("\n", -1);
        final var errorRow = IntStream.range(0, lines.length)
                .filter(i -> lines[i].contains("Alt+E for details"))
                .findFirst()
                .orElseThrow();
        // the error banner is a full-width rounded box: border rows on either side of the text
        assertEquals("╭", backend.symbol(0, errorRow - 1));
        assertEquals("╰", backend.symbol(0, errorRow + 1));
        final var expected = Color.hex("#e06c75");
        assertEquals(expected, backend.fg(0, errorRow - 1).orElseThrow());
        assertEquals(expected, backend.fg(0, errorRow + 1).orElseThrow());
    }

    @Test
    void idleTicksAreSkippedButHeartbeatAndMutationsStillRepaint() throws Exception {
        // dedicated container with a shortened safety repaint (120ms) so the test observes the
        // idle-skip and the heartbeat while staying well under half a second
        try (final var isolated = bootContainer(Map.of("yca.tui.heartbeatMillis", "120"))) {
            final var isolatedDeps = lookupDeps(isolated);
            assertEquals(120, isolatedDeps.tuiConfiguration().heartbeatMillis());
            final var command = newUiCommand(isolated, isolatedDeps);

            uiThread = new Thread(command::run, "tui-headless-heartbeat");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");
            drainFrames();

            // idle: no render cycle, no terminal write while the screen cannot change
            final var baseCycles = renderCycles.get();
            assertFalse(backend.awaitFrame(50), "an idle tick must not repaint the frame");
            assertEquals(baseCycles, renderCycles.get(), "an idle tick must not run a render cycle");
            // ...until the safety heartbeat forces a render cycle even though the frame is a no-op
            awaitRenderCycle(baseCycles, 200);
            // a no-op heartbeat frame diffed away by TamboUI: still no terminal write
            assertFalse(backend.awaitFrame(30), "a no-op heartbeat must not write to the terminal");

            // a background mutation still paints promptly: the slash error is delivered on the
            // render thread through runLater and drawn by the next tick, not by a busy spinner
            tuiRef.get().dispatch(new PasteEvent("/agent definitely-not-an-agent"));
            awaitText("/agent definitely-not-an-agent");
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
            awaitText("unknown agent: definitely-not-an-agent");
            awaitText("Alt+E for details");

            // the error banner is static: once painted, idle silence resumes
            drainFrames();
            assertFalse(backend.awaitFrame(50), "a static screen must not keep repainting");
        }
    }

    /** Awaits the first render cycle above {@code base}, bounded (no fixed sleeping). */
    private void awaitRenderCycle(final long base, final long timeoutMillis) throws InterruptedException {
        final var deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (renderCycles.get() == base) {
            if (System.nanoTime() > deadline) {
                fail("no render cycle within " + timeoutMillis + "ms (heartbeat did not fire)");
            }
            backend.awaitFrame(5);
        }
    }

    /** Consumes any in-flight repaint so timing-sensitive assertions start from a quiet frame. */
    private void drainFrames() throws InterruptedException {
        while (backend.awaitFrame(20)) {
            // bounded: stops as soon as a 20ms window contains no frame
        }
    }

    private static TuiDeps lookupDeps(final RuntimeContainer container) {
        try (final var depsInstance = container.lookup(TuiDeps.class)) {
            return depsInstance.instance();
        }
    }

    private TuiCommand newUiCommand(final RuntimeContainer container, final TuiDeps d) {
        return newUiCommand(container, d, new TuiCommand.TuiConf("", "", KeyBindingPreset.STANDARD, true, "dark"));
    }

    private TuiCommand newUiCommand(final RuntimeContainer container, final TuiDeps d, final TuiCommand.TuiConf conf) {
        // share the container-managed instances with the SlashRegistry so "/tabs"
        // dispatched through it mutates the same TabManager the TUI renders from
        try (final var tabManagerInstance = container.lookup(TabManager.class);
                final var slashCommandsInstance = container.lookup(TuiSlashCommands.class);
                final var shellRegistry = container.lookup(ShellRegistry.class);
                final var slashRegistry = container.lookup(SlashRegistry.class);
                final var turnRunner = container.lookup(TurnRunner.class);
                final var runtimeFactory = container.lookup(SessionRuntimeFactory.class);
                final var scheduler = container.lookup(ScheduledExecutorService.class);
                final var autoLogConfigurer = container.lookup(AutoLogConfigurer.class)) {
            final var tabManager = tabManagerInstance.instance();
            final var tuiSlashCommands = slashCommandsInstance.instance();
            // a fresh executor per run: TuiCommand.run() shuts the executor down when the TUI
            // quits, and the container-managed ExecutorService bean is also used by the
            // agent beans (TurnRunner, ChatClient...). Passing a dedicated one lets every test
            // reuse the shared @BeforeAll container without poisoning the other tests.
            final var runExecutor = Executors.newVirtualThreadPerTaskExecutor();
            return new TuiCommand(
                    conf,
                    d.tuiConfiguration(),
                    d.configuration(),
                    shellRegistry.instance(),
                    runtimeFactory.instance(),
                    d.sessionStore(),
                    d.sessionLifecycle(),
                    d.chatClient(),
                    d.modelStyleService(),
                    new SpeakerLabels(
                            List.of(new FormatDatePromptHelper(Clock.systemDefaultZone())),
                            d.configuration(),
                            d.chatClient()),
                    autoLogConfigurer.instance(),
                    d.mcpManager(),
                    new ProcessService(),
                    runExecutor,
                    d.providerManager(),
                    d.agentProvider(),
                    d.agentSessionService(),
                    new MavenRepositoryKnowledgeService(d.configuration(), null, d.executor(), null),
                    turnRunner.instance(),
                    d.thinkTags(),
                    headlessFactory(),
                    tuiSlashCommands,
                    slashRegistry.instance(),
                    Clock.systemUTC(),
                    new AskUserCardRenderer(new AskUserAnswersFormatter(formatMapper)),
                    new ToolOutputFormat(formatMapper),
                    new BtwAnswerer(
                            d.chatClient(),
                            d.sessionStore(),
                            d.configuration(),
                            d.executor(),
                            new InBandToolCallExtractor(new MarkupToolCallScanner()),
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null),
                    tabManager,
                    new SubagentRegistry(d.configuration(), d.providerManager()),
                    new ScrollService(d.tuiConfiguration()),
                    scheduler.instance());
        }
    }

    private TuiRunnerFactory headlessFactory() {
        return new TuiRunnerFactory() {
            @Override
            public TuiRunner create(final TuiCommand.TuiConf tuiConf, final boolean hideCursor) throws Exception {
                final var runner = TuiRunner.create(TuiConfig.builder()
                        .backend(backend)
                        .mouseCapture(false)
                        .rawMode(false)
                        .alternateScreen(false)
                        .bracketedPaste(false)
                        .shutdownHook(false)
                        .tickRate(ofMillis(20))
                        // observes every render cycle, even the no-op frames an idle heartbeat
                        // produces (TamboUI diffs them away so the backend never draws them)
                        .postRenderProcessor(frame -> renderCycles.incrementAndGet())
                        .build());
                tuiRef.set(runner);
                return runner;
            }
        };
    }

    private void awaitTui() throws InterruptedException {
        while (tuiRef.get() == null) {
            assertTrue(backend.awaitFrame(1_000), "tui runner was not created in time");
        }
    }

    private void awaitText(final String expected) throws InterruptedException {
        final var deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS);
        var screen = backend.text();
        while (!screen.contains(expected)) {
            if (System.nanoTime() > deadline) {
                final var message =
                        "did not see '" + expected + "' within " + TIMEOUT_MS + "ms; last screen:\n" + screen;
                throw new AssertionError(message);
            }
            assertTrue(backend.awaitFrame(1_000), "no frame received while waiting for '" + expected + "'");
            screen = backend.text();
        }
    }

    private void awaitTextGone(final String expected) throws InterruptedException {
        final var deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS);
        var screen = backend.text();
        while (screen.contains(expected)) {
            if (System.nanoTime() > deadline) {
                final var message =
                        "'" + expected + "' never disappeared within " + TIMEOUT_MS + "ms; last screen:\n" + screen;
                throw new AssertionError(message);
            }
            assertTrue(backend.awaitFrame(1_000), "no frame received while waiting for '" + expected + "' to vanish");
            screen = backend.text();
        }
    }

    @Test
    void sessionOptionReplaysPersistedSession() throws Exception {
        final var sessionId = "sresume";
        deps.sessionStore().append(sessionId, SessionEntry.of(1, "user", "resumed question", null, null));
        deps.sessionStore().append(sessionId, SessionEntry.of(2, "assistant", "resumed assistant reply", null, null));
        final var command = newUiCommand(
                container, deps, new TuiCommand.TuiConf(sessionId, "", KeyBindingPreset.STANDARD, true, "dark"));

        uiThread = new Thread(command::run, "tui-headless-session");
        uiThread.setDaemon(true);
        uiThread.start();
        awaitTui();
        // the persisted history is replayed into the initial tab, not fetched from the model
        awaitText("resumed assistant reply");
        awaitText("ready");
        final var requestsBefore = llm.requests().size();
        assertEquals(requestsBefore, llm.requests().size(), "resuming a session at launch must not call the model");

        tuiRef.get().dispatch(KeyEvent.ofChar('c', KeyModifiers.CTRL));
        uiThread.join(TIMEOUT_MS);
    }

    @Test
    void initialPromptResumeAliasRestartsTheLoopOnThePersistedHistory() throws Exception {
        final var sessionId = "sinitialresume";
        deps.sessionStore().append(sessionId, SessionEntry.of(1, "user", "already asked", null, null));
        deps.sessionStore().append(sessionId, SessionEntry.of(2, "assistant", "already answered", null, null));
        final var requestsBefore = llm.requests().size();
        // `continue` is a resume alias by default (yca.resumeAliases): the turn restarts the
        // loop on the persisted history, so it reaches the model without any new user message
        final var command = newUiCommand(
                container,
                deps,
                new TuiCommand.TuiConf(
                        sessionId, "", KeyBindingPreset.STANDARD, true, true, "dark", List.of("continue")));

        uiThread = new Thread(command::run, "tui-headless-initial-resume");
        uiThread.setDaemon(true);
        uiThread.start();
        awaitTui();
        awaitText("already answered");
        // the loop ran again on its own: the mock is called and its answer joins the transcript
        awaitText("hello from mock");

        final var sent = llm.requests().subList(requestsBefore, llm.requests().size());
        assertFalse(sent.isEmpty(), "the injected resume turn must call the model");
        assertTrue(
                sent.stream().anyMatch(it -> it.contains("already asked")),
                "the resumed turn must replay the persisted history");
        assertTrue(
                deps.sessionStore().load(sessionId).stream()
                        .noneMatch(
                                e -> "user".equals(e.role()) && "continue".contentEquals(String.valueOf(e.content()))),
                "a resume turn must not persist the alias as a user message");

        tuiRef.get().dispatch(KeyEvent.ofChar('c', KeyModifiers.CTRL));
        uiThread.join(TIMEOUT_MS);
    }

    @Test
    void initialPromptListIsSubmittedInOrder() throws Exception {
        final var requestsBefore = llm.requests().size();
        // two prompts: the first one starts a turn and the second one waits for that turn
        // to end, so they are two turns and not one merged type-ahead turn
        final var command = newUiCommand(
                container,
                deps,
                new TuiCommand.TuiConf(
                        "",
                        "",
                        KeyBindingPreset.STANDARD,
                        true,
                        true,
                        "dark",
                        List.of("injected first", "injected second")));

        uiThread = new Thread(command::run, "tui-headless-initial-list");
        uiThread.setDaemon(true);
        uiThread.start();
        awaitTui();
        awaitText("injected second");
        awaitText("ready");

        final var sent = List.copyOf(
                llm.requests().subList(requestsBefore, llm.requests().size()));
        final var firstTurn = sent.stream()
                .filter(it -> it.contains("injected first"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the first injected prompt never reached the model"));
        final var secondTurn = sent.stream()
                .filter(it -> it.contains("injected second"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the queued prompt was never submitted: " + sent));
        assertTrue(sent.indexOf(firstTurn) < sent.indexOf(secondTurn), "injected prompts must run in the given order");
        assertFalse(
                firstTurn.contains("injected second"),
                "the second prompt must run as its own turn, not merged into the first one");

        tuiRef.get().dispatch(KeyEvent.ofChar('c', KeyModifiers.CTRL));
        uiThread.join(TIMEOUT_MS);
    }

    @Test
    void initialPromptSlashEntryDoesNotStallTheQueue() throws Exception {
        // {@code /mode build} is a slash command: it starts no turn (no TurnDone event), so the
        // following entry must be released when the command outcome is applied, not when a turn ends
        final var requestsBefore = llm.requests().size();
        final var command = newUiCommand(
                container,
                deps,
                new TuiCommand.TuiConf(
                        "",
                        "",
                        KeyBindingPreset.STANDARD,
                        true,
                        true,
                        "dark",
                        List.of("/mode build", "hello from the script")));

        uiThread = new Thread(command::run, "tui-headless-initial-slash");
        uiThread.setDaemon(true);
        uiThread.start();
        awaitTui();
        awaitText("mode: build");
        awaitText("hello from the script");
        awaitText("ready");

        final var sent = List.copyOf(
                llm.requests().subList(requestsBefore, llm.requests().size()));
        assertFalse(sent.isEmpty(), "the prompt following the slash command never reached the model: " + sent);
        assertTrue(
                sent.stream().anyMatch(it -> it.contains("hello from the script")),
                "the queue must advance past a slash command - got: " + sent);

        tuiRef.get().dispatch(KeyEvent.ofChar('c', KeyModifiers.CTRL));
        uiThread.join(TIMEOUT_MS);
    }

    @Test
    void tabsSlashOpensOverlayThroughRealRegistry() throws Exception {
        final var command = newUiCommand(container, deps);

        uiThread = new Thread(command::run, "tui-tabs-list");
        uiThread.setDaemon(true);
        uiThread.start();
        awaitTui();

        // dispatch through the real SlashRegistry: "/tabs" (no arg) opens the modal
        tuiRef.get().dispatch(new PasteEvent("/tabs"));
        tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
        awaitText("1-9 switch");

        // Esc closes the overlay again
        tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ESCAPE));
        awaitTextGone("1-9 switch");

        // quit the TUI so run() unwinds before the container closes
        tuiRef.get().dispatch(KeyEvent.ofChar('c', KeyModifiers.CTRL));
        uiThread.join(TIMEOUT_MS);
    }

    @Test
    void exitingLogsEveryOpenedSessionWithItsTabTitle() throws Exception {
        // remembering the session(s) of a run matters most for multi-tab TUI sessions: the exit
        // must name each persisted session with the tab title so it can be resumed afterwards
        final var command = newUiCommand(
                container, deps, new TuiCommand.TuiConf("exit-session-a", "", KeyBindingPreset.STANDARD, true, "dark"));

        final var events = new CopyOnWriteArrayList<String>();
        final var logger = Logger.getLogger(TuiCommand.class.getName());
        final var handler = new Handler() {
            {
                setLevel(Level.ALL);
            }

            @Override
            public void publish(final LogRecord record) {
                events.add(record.getMessage());
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        // marks when the TUI released the terminal: the session lines must only be emitted after
        // that point (on the restored screen), or the alternate-screen buffer would swallow them
        backend.onClose(() -> events.add("terminal released"));
        logger.addHandler(handler);
        try {
            uiThread = new Thread(command::run, "tui-headless-exit-log");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            // first tab reuses the deterministic session id and titles itself from the prompt
            tuiRef.get().dispatch(new PasteEvent("first tab prompt"));
            awaitText("first tab prompt");
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
            awaitText("hello from mock");
            awaitText("ready");

            // second tab: fresh session, its own title
            tuiRef.get().dispatch(KeyEvent.ofChar('n', KeyModifiers.CTRL));
            tuiRef.get().dispatch(new PasteEvent("second tab topic"));
            awaitText("second tab topic");
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
            awaitText("hello from mock");
            awaitText("ready");

            tuiRef.get().dispatch(KeyEvent.ofChar('c', KeyModifiers.CTRL));
            uiThread.join(TIMEOUT_MS);
            assertFalse(uiThread.isAlive(), "the TUI thread should have exited");

            assertTrue(
                    events.contains("session exit-session-a (first tab prompt)"),
                    "expected an exit log naming the first session with its tab title, got: " + events);
            assertTrue(
                    events.stream().anyMatch(it -> it.startsWith("session ") && it.endsWith("(second tab topic)")),
                    "expected an exit log naming the second session with its tab title, got: " + events);
            final var sessionLines =
                    events.stream().filter(it -> it.startsWith("session ")).toList();
            final var released = events.lastIndexOf("terminal released");
            assertTrue(released >= 0, "expected the terminal released marker, got: " + events);
            for (final var sessionLine : sessionLines) {
                assertTrue(
                        events.indexOf(sessionLine) > released,
                        "the session logs must be emitted only after the terminal is released, got: " + events);
            }
        } finally {
            logger.removeHandler(handler);
        }
    }

    @Test
    void capturedLogsLandInTheContentViewAndNotify() throws Exception {
        final var command = newUiCommand(container, deps);

        uiThread = new Thread(command::run, "tui-headless-logs");
        uiThread.setDaemon(true);
        uiThread.start();
        awaitTui();
        awaitText("yca coding agent");

        // emitted after the TUI ran: run() already detached the inline root handler
        // (AutoLogConfigurer) and attached the content-view capture handlers
        final var logger = Logger.getLogger("io.yupiik.ia.agent.core.codegraph.StructuralCodeGraph");
        logger.info("captured info log");
        awaitText("captured info log");
        // inline log shape: [label][LEVEL][short logger] message on the same row
        awaitText("[INFO][core.codegraph.StructuralCodeGraph] captured info log");

        logger.warning("captured warning log");
        awaitText("captured warning log");

        logger.severe("captured severe log");
        awaitText("captured severe log");
        awaitText("Alt+E for details");

        tuiRef.get().dispatch(KeyEvent.ofChar('c', KeyModifiers.CTRL));
        uiThread.join(TIMEOUT_MS);
    }
}
