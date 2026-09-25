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

import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.cli.Command;
import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;
import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.agent.AgentProvider;
import io.yupiik.ia.agent.core.configuration.AgentMode;
import io.yupiik.ia.agent.core.permissions.ApprovalGate;
import io.yupiik.ia.agent.core.session.SessionStore;
import io.yupiik.ia.agent.core.shell.RunShellFrontend;
import io.yupiik.ia.agent.core.shell.SessionRuntimeFactory;
import io.yupiik.ia.agent.core.shell.ShellEnv;
import io.yupiik.ia.agent.core.shell.ShellRegistry;
import io.yupiik.ia.agent.core.shell.ShellSession;
import io.yupiik.ia.agent.core.shell.ToolOutputVisibility;
import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;

@DefaultScoped
@Command(name = "run", description = "Run a single headless prompt and print the answer on stdout.")
public class RunCommand implements Runnable {
    private static final Logger LOGGER = Logger.getLogger(RunCommand.class.getName());

    private final RunConf conf;
    private final ShellRegistry shellRegistry;
    private final SessionRuntimeFactory sessionRuntimeFactory;
    private final SessionStore sessionStore;
    private final AgentProvider agentProvider;

    public RunCommand(
            final RunConf conf,
            final ShellRegistry shellRegistry,
            final SessionRuntimeFactory sessionRuntimeFactory,
            final SessionStore sessionStore,
            final AgentProvider agentProvider) {
        this.conf = conf;
        this.shellRegistry = shellRegistry;
        this.sessionRuntimeFactory = sessionRuntimeFactory;
        this.sessionStore = sessionStore;
        this.agentProvider = agentProvider;
    }

    @RootConfiguration("run")
    public record RunConf(
            @Property(required = true, documentation = "Prompt to send to the agent.")
            String prompt,

            @Property(
                    defaultValue = "\"\"",
                    documentation = "Session id to append to; a new session is created when empty.")
            String session,

            @Property(
                    defaultValue = "\"\"",
                    documentation =
                            "Name of a custom agent (from yca.agents) to use for this run. Its prompt is injected on top of the base system prompt.")
            String agent,

            @Property(defaultValue = "false", documentation = "Print reasoning deltas on stderr.")
            boolean showThinking,

            @Property(defaultValue = "false", documentation = "Bypass permission checks and allow all tool calls.")
            boolean permitAll,

            @Property(
                    defaultValue = "\"\"",
                    documentation =
                            "Agent access mode for this run: build or plan (defaults to the configured yca.mode).")
            String mode,

            @Property(
                    value = "show-tool-output",
                    defaultValue = "\"collapsed\"",
                    documentation =
                            "Tool output presentation: shown (print each non-streamed tool output), collapsed (log tool "
                                    + "calls only, no output body) or hidden (log nothing). Accepts the legacy booleans "
                                    + "true/false (true = shown, false = collapsed/off).")
            String showToolOutput,

            @Property(
                    value = "metrics-output-file",
                    defaultValue = "\"\"",
                    documentation =
                            "Optional path of a metrics file appended on completion (blank to disable). When set, a "
                                    + "single line is written with elapsed ms, accumulated prompt/completion/total tokens, "
                                    + "last estimated context tokens/percent, compaction and code-graph tool-call counts, "
                                    + "plus drill-down aggregates (LLM call count, peak/sum context, tool activity, "
                                    + "tool time and answer length); useful for headless benchmarks.")
            String metricsOutputFile,

            @Property(
                    value = "metrics-detail-file",
                    defaultValue = "\"\"",
                    documentation =
                            "Optional path of a per-event detail log appended during the run (blank to disable). Each "
                                    + "line is one event with a monotonic tick (ms since JVM start): llm (one per LLM "
                                    + "call, with context tokens/percent), toolStart/toolEnd (with tool duration), "
                                    + "compact and answer (final answer length); enables LLM-latency and context-curve "
                                    + "drill-down for benchmarks.")
            String metricsDetailFile) {}

    @Override
    public void run() {
        final var sessionId =
                conf.session() == null || conf.session().isBlank() ? sessionStore.create() : conf.session();
        final var failed = new AtomicBoolean();
        final var lastError = new AtomicReference<String>();
        final var metrics = new Metrics();
        final var started = System.nanoTime();
        final var detailLog = Detail.of(conf.metricsDetailFile());
        final var userCancelled = new AtomicBoolean();
        final var parsedToolOutput = ToolOutputVisibility.parse(conf.showToolOutput());
        final var showToolOutput = parsedToolOutput == null ? ToolOutputVisibility.COLLAPSED : parsedToolOutput;
        final var frontend = new RunShellFrontend(conf.showThinking(), showToolOutput) {
            @Override
            public void onEvent(final AgentEvent event) {
                super.onEvent(event);
                metrics.accept(event, started, detailLog);
                if (event instanceof final AgentEvent.Error e) {
                    if ("Cancelled".equals(e.message()) && userCancelled.get()) {
                        return; // explicit user interruption, not a failure
                    }
                    failed.set(true);
                    lastError.set(e.message());
                }
            }
        };
        final var shellSession = new ShellSession().sessionId(sessionId);
        final var runtime = sessionRuntimeFactory.create(sessionId);
        shellSession.runtime(runtime);
        final var shellEnv = new ShellEnv(frontend, null, shellSession, null);
        shellRegistry.register(shellEnv);
        final var permissions = runtime.permissions();
        if (conf.mode() != null && !conf.mode().isBlank()) {
            permissions.setMode(AgentMode.fromConfigurationString(conf.mode()));
        }
        permissions.setGate((title, detail, tool, args) -> {
            LOGGER.severe(() -> "denied: " + title + " requires an approval in headless mode (" + detail
                    + "); set yca.permissions.mode=yolo to auto allow.");
            return ApprovalGate.Result.DENY;
        });
        runtime.feedback().setGate(questions -> {
            LOGGER.warning(
                    "ask_user is not available in headless mode; provide the required input in your prompt and retry.");
            return null;
        });
        permissions.setPermitAll(conf.permitAll());
        final var agentName = conf.agent() == null || conf.agent().isBlank() ? null : conf.agent();
        if (agentName != null && agentProvider.byName(agentName) == null) {
            throw new IllegalArgumentException("Unknown agent: " + agentName + " (configure one under yca.agents)");
        }
        try {
            runtime.loop().run(conf.prompt(), agentName, sessionId, null, installCancelHandler(userCancelled));
        } finally {
            System.out.flush();
            shellRegistry.unregister(shellEnv);
            detailLog.close();
            writeMetrics(metrics, started);
        }
        if (failed.get()) {
            throw new IllegalStateException("run failed: " + lastError.get());
        }
    }

    /**
     * Installs a best-effort SIGINT handler so Ctrl-C cancels the running turn cooperatively instead of
     * killing the JVM mid-tool; a second Ctrl-C within 2s force-exits since cancellation is cooperative.
     * When {@code sun.misc.Signal} is unavailable the cancel supplier simply stays false.
     */
    private BooleanSupplier installCancelHandler(final AtomicBoolean userCancelled) {
        final var cancelled = new AtomicBoolean();
        final var lastInterruptAt = new long[] {0};
        try {
            final var signal = Class.forName("sun.misc.Signal");
            final var handler = Class.forName("sun.misc.SignalHandler");
            final var handle = signal.getMethod("handle", signal, handler);
            final var ctor = signal.getConstructor(String.class);
            final var interruptHandler = Proxy.newProxyInstance(
                    RunCommand.class.getClassLoader(), new Class<?>[] {handler}, (_, method, _) -> {
                        if (method.getName().equals("handle")) {
                            final var now = System.currentTimeMillis();
                            final var previous = lastInterruptAt[0];
                            lastInterruptAt[0] = now;
                            if (previous > 0 && now - previous <= 2_000L) {
                                LOGGER.severe("forced exit");
                                System.exit(130);
                            }
                            cancelled.set(true);
                            userCancelled.set(true);
                            LOGGER.warning("cancelling the current turn (ctrl-c again to force exit)...");
                        }
                        return null;
                    });
            handle.invoke(null, ctor.newInstance("INT"), interruptHandler);
        } catch (final Throwable ignored) {
            // signal handling is best-effort; headless runs keep the default JVM behavior
        }
        return cancelled::get;
    }

    private void writeMetrics(final Metrics metrics, final long started) {
        if (conf.metricsOutputFile() == null || conf.metricsOutputFile().isBlank()) {
            return;
        }
        final var elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        final var line = "elapsedMs=" + elapsedMs
                + " promptTokens=" + metrics.promptTokens.get()
                + " completionTokens=" + metrics.completionTokens.get()
                + " totalTokens=" + metrics.totalTokens.get()
                + " contextTokens=" + metrics.contextTokens
                + " contextPct=" + metrics.contextPercent
                + " compactions=" + metrics.compactions.get()
                + " codeTools=" + metrics.codeTools.get()
                + " llmCalls=" + metrics.llmCalls.get()
                + " maxContext=" + metrics.maxContext
                + " sumContext=" + metrics.sumContext.get()
                + " toolCalls=" + metrics.toolCalls.get()
                + " codeTraceCalls=" + metrics.codeTraceCalls.get()
                + " codeCheckGraph=" + metrics.codeCheckGraph.get()
                + " toolErrors=" + metrics.toolErrors.get()
                + " toolMs=" + metrics.toolMs.get()
                + " llmMs=" + Math.max(0, elapsedMs - metrics.toolMs.get())
                + " answerChars=" + metrics.answerChars.get();
        try {
            Files.writeString(
                    Paths.get(conf.metricsOutputFile()), line + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            LOGGER.severe(() -> "failed to write metrics to " + conf.metricsOutputFile() + ": " + e.getMessage());
        }
    }

    private static final class Metrics {
        private final AtomicLong promptTokens = new AtomicLong();
        private final AtomicLong completionTokens = new AtomicLong();
        private final AtomicLong totalTokens = new AtomicLong();
        private final AtomicInteger compactions = new AtomicInteger();
        private final AtomicInteger codeTools = new AtomicInteger();
        private final AtomicInteger llmCalls = new AtomicInteger();
        private final AtomicLong sumContext = new AtomicLong();
        private final AtomicInteger toolCalls = new AtomicInteger();
        private final AtomicInteger codeTraceCalls = new AtomicInteger();
        private final AtomicInteger codeCheckGraph = new AtomicInteger();
        private final AtomicInteger toolErrors = new AtomicInteger();
        private final AtomicLong toolMs = new AtomicLong();
        private final AtomicInteger answerChars = new AtomicInteger();
        private final Deque<Long> toolStack = new ArrayDeque<>();
        private final StringBuilder answer = new StringBuilder();
        private volatile long contextTokens;
        private volatile double contextPercent;
        private volatile long maxContext;
        private long prevPromptTokens;
        private long prevCompletionTokens;
        private long perCallReasoning;

        void accept(final AgentEvent event, final long started, final Detail detail) {
            if (event instanceof final AgentEvent.UsageUpdated u) {
                final var callPrompt = Math.max(0, u.usage().promptTokens() - prevPromptTokens);
                final var callCompletion = Math.max(0, u.usage().completionTokens() - prevCompletionTokens);
                prevPromptTokens = u.usage().promptTokens();
                prevCompletionTokens = u.usage().completionTokens();
                promptTokens.addAndGet(callPrompt);
                completionTokens.addAndGet(callCompletion);
                totalTokens.addAndGet(u.usage().totalTokens());
                contextTokens = u.estimatedContextTokens();
                contextPercent = u.contextPercent();
                if (u.estimatedContextTokens() > maxContext) {
                    maxContext = u.estimatedContextTokens();
                }
                sumContext.addAndGet(u.estimatedContextTokens());
                llmCalls.incrementAndGet();
                detail.llm(started, contextTokens, contextPercent, callPrompt, callCompletion, perCallReasoning);
                perCallReasoning = 0;
            } else if (event instanceof final AgentEvent.Compacted c) {
                compactions.incrementAndGet();
                detail.compact(started, c.kind(), c.mode());
            } else if (event instanceof final AgentEvent.ToolStarted t) {
                toolCalls.incrementAndGet();
                if (t.name().startsWith("code_")) {
                    codeTools.incrementAndGet();
                }
                if ("code_trace_calls".equals(t.name())) {
                    codeTraceCalls.incrementAndGet();
                } else if ("code_check_graph".equals(t.name())) {
                    codeCheckGraph.incrementAndGet();
                }
                toolStack.push(System.nanoTime());
                detail.toolStart(started, t.name());
            } else if (event instanceof final AgentEvent.ToolFinished t) {
                if (!t.success()) {
                    toolErrors.incrementAndGet();
                }
                final var start = toolStack.isEmpty() ? null : toolStack.pollFirst();
                if (start != null) {
                    final var ms = (System.nanoTime() - start) / 1_000_000L;
                    toolMs.addAndGet(ms);
                    detail.toolEnd(started, t.name(), ms);
                }
            } else if (event instanceof final AgentEvent.TextDelta t) {
                answer.append(t.delta());
            } else if (event instanceof AgentEvent.TextReset) {
                answer.setLength(0);
            } else if (event instanceof final AgentEvent.ThinkingDelta t) {
                perCallReasoning += t.delta().length();
            } else if (event instanceof final AgentEvent.TurnDone t) {
                answerChars.set(
                        t.content() == null ? answer.length() : t.content().length());
                detail.answer(
                        started,
                        t.content() == null ? answer.length() : t.content().length());
            } else if (event instanceof final AgentEvent.TurnMetrics tm) {
                detail.turnMetrics(
                        started,
                        tm.llmCalls(),
                        tm.thinkingDurationMs(),
                        tm.toolDurationSumMs(),
                        tm.toolDurationWallMs(),
                        tm.toolsRun(),
                        tm.turnDurationMs());
            }
        }
    }

    /**
     * Optional per-event detail log enabling LLM-latency and context-curve drill-down.
     */
    private static final class Detail implements AutoCloseable {
        private final BufferedWriter writer;

        private Detail(final BufferedWriter writer) {
            this.writer = writer;
        }

        static Detail of(final String path) {
            if (path == null || path.isBlank()) {
                return new Detail(null);
            }
            try {
                return new Detail(Files.newBufferedWriter(
                        Paths.get(path),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING));
            } catch (final IOException e) {
                System.err.println("[error] failed to open metrics-detail-file " + path + ": " + e.getMessage());
                return new Detail(null);
            }
        }

        private void write(final String line) {
            if (writer == null) {
                return;
            }
            try {
                writer.write(line);
                writer.write(System.lineSeparator());
            } catch (final IOException e) {
                // non-fatal for the benchmark; drop further detail logging
            }
        }

        void llm(
                final long started,
                final long context,
                final double pct,
                final long prompt,
                final long completion,
                final long reasoning) {
            write("llm t=" + (System.nanoTime() - started) / 1_000_000L
                    + " context=" + context
                    + " pct=" + pct
                    + " prompt=" + prompt
                    + " completion=" + completion
                    + " reasoningChars=" + reasoning);
        }

        void toolStart(final long started, final String name) {
            write("toolStart t=" + (System.nanoTime() - started) / 1_000_000L + " name=" + name);
        }

        void toolEnd(final long started, final String name, final long ms) {
            write("toolEnd t=" + (System.nanoTime() - started) / 1_000_000L + " name=" + name + " ms=" + ms);
        }

        void answer(final long started, final int chars) {
            write("answer t=" + (System.nanoTime() - started) / 1_000_000L + " chars=" + chars);
        }

        void compact(final long started, final String kind, final String mode) {
            write("compact t=" + (System.nanoTime() - started) / 1_000_000L + " kind=" + kind + " mode=" + mode);
        }

        void turnMetrics(
                final long started,
                final int llmCalls,
                final long thinkingMs,
                final long toolSumMs,
                final long toolWallMs,
                final int toolsRun,
                final long turnDurationMs) {
            write("turnMetrics t=" + (System.nanoTime() - started) / 1_000_000L
                    + " llmCalls=" + llmCalls
                    + " thinkingMs=" + thinkingMs
                    + " toolSumMs=" + toolSumMs
                    + " toolWallMs=" + toolWallMs
                    + " toolsRun=" + toolsRun
                    + " turnDurationMs=" + turnDurationMs);
        }

        @Override
        public void close() {
            if (writer == null) {
                return;
            }
            try {
                writer.close();
            } catch (final IOException e) {
                // ignore
            }
        }
    }
}
