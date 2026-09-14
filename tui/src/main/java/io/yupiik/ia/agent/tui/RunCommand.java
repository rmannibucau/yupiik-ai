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
import io.yupiik.ia.agent.core.agent.AgentEventBridge;
import io.yupiik.ia.agent.core.agent.AgentLoop;
import io.yupiik.ia.agent.core.agent.AgentProvider;
import io.yupiik.ia.agent.core.permissions.ApprovalGate;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.question.UserFeedbackService;
import io.yupiik.ia.agent.core.session.SessionStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@DefaultScoped
@Command(name = "run", description = "Run a single headless prompt and print the answer on stdout.")
public class RunCommand implements Runnable {
    private final RunConf conf;
    private final AgentLoop agentLoop;
    private final AgentEventBridge bridge;
    private final PermissionService permissionService;
    private final UserFeedbackService userFeedbackService;
    private final SessionStore sessionStore;
    private final AgentProvider agentProvider;

    public RunCommand(
            final RunConf conf,
            final AgentLoop agentLoop,
            final AgentEventBridge bridge,
            final PermissionService permissionService,
            final UserFeedbackService userFeedbackService,
            final SessionStore sessionStore,
            final AgentProvider agentProvider) {
        this.conf = conf;
        this.agentLoop = agentLoop;
        this.bridge = bridge;
        this.permissionService = permissionService;
        this.userFeedbackService = userFeedbackService;
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
                    value = "metrics-output-file",
                    defaultValue = "\"\"",
                    documentation =
                            "Optional path of a metrics file appended on completion (blank to disable). When set, a "
                                    + "single line is written with elapsed ms, accumulated prompt/completion/total tokens, "
                                    + "last estimated context tokens/percent, compaction and code-graph tool-call counts; "
                                    + "useful for headless benchmarks.")
            String metricsOutputFile) {}

    @Override
    public void run() {
        final var sessionId =
                conf.session() == null || conf.session().isBlank() ? sessionStore.create() : conf.session();
        final var failed = new AtomicBoolean();
        final var lastError = new AtomicReference<String>();
        final var metrics = new Metrics();
        final var started = System.nanoTime();
        bridge.sink(event -> {
            print(event, conf.showThinking());
            metrics.accept(event);
            if (event instanceof final AgentEvent.Error e) {
                failed.set(true);
                lastError.set(e.message());
            }
        });
        permissionService.setGate((title, detail, tool, args) -> {
            System.err.println("[denied] " + title + " requires an approval in headless mode (" + detail
                    + "); set yca.permissions.mode=yolo to auto allow.");
            return ApprovalGate.Result.DENY;
        });
        userFeedbackService.setGate(questions -> {
            System.err.println(
                    "[ask_user] not available in headless mode; provide the required input in your prompt and retry.");
            return null;
        });
        permissionService.setPermitAll(conf.permitAll());
        final var agentName = conf.agent() == null || conf.agent().isBlank() ? null : conf.agent();
        if (agentName != null && agentProvider.byName(agentName) == null) {
            throw new IllegalArgumentException("Unknown agent: " + agentName + " (configure one under yca.agents)");
        }
        try {
            agentLoop.run(conf.prompt(), agentName, sessionId, null, () -> false);
        } finally {
            System.out.flush();
            bridge.sink(null);
            permissionService.setGate(null);
            permissionService.setPermitAll(false);
            userFeedbackService.setGate(null);
            writeMetrics(metrics, started);
        }
        if (failed.get()) {
            throw new IllegalStateException("run failed: " + lastError.get());
        }
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
                + " codeTools=" + metrics.codeTools.get();
        try {
            Files.writeString(
                    Paths.get(conf.metricsOutputFile()), line + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            System.err.println(
                    "[error] failed to write metrics to " + conf.metricsOutputFile() + ": " + e.getMessage());
        }
    }

    private static final class Metrics {
        private final AtomicLong promptTokens = new AtomicLong();
        private final AtomicLong completionTokens = new AtomicLong();
        private final AtomicLong totalTokens = new AtomicLong();
        private final AtomicInteger compactions = new AtomicInteger();
        private final AtomicInteger codeTools = new AtomicInteger();
        private volatile long contextTokens;
        private volatile double contextPercent;

        void accept(final AgentEvent event) {
            if (event instanceof final AgentEvent.UsageUpdated u) {
                promptTokens.addAndGet(u.usage().promptTokens());
                completionTokens.addAndGet(u.usage().completionTokens());
                totalTokens.addAndGet(u.usage().totalTokens());
                contextTokens = u.estimatedContextTokens();
                contextPercent = u.contextPercent();
            } else if (event instanceof final AgentEvent.Compacted c) {
                compactions.incrementAndGet();
            } else if (event instanceof final AgentEvent.ToolStarted t) {
                if (t.name().startsWith("code_")) {
                    codeTools.incrementAndGet();
                }
            }
        }
    }

    private void print(final AgentEvent event, final boolean showThinking) {
        if (event instanceof final AgentEvent.TextDelta t) {
            System.out.print(t.delta());
        } else if (event instanceof final AgentEvent.ThinkingDelta t) {
            if (showThinking) {
                System.err.print(t.delta());
            }
        } else if (event instanceof final AgentEvent.ToolStarted t) {
            System.err.println("[tool] " + t.name());
        } else if (event instanceof final AgentEvent.ToolStream t) {
            System.err.println("[" + t.name() + "] " + t.fragment());
        } else if (event instanceof final AgentEvent.ToolFinished t) {
            if (!t.success()) {
                System.err.println("[tool failed] " + t.name());
            }
        } else if (event instanceof final AgentEvent.Compacted c) {
            System.err.println(
                    "[context] " + c.kind() + " applied (" + c.mode().toLowerCase(java.util.Locale.ROOT) + ")");
        } else if (event instanceof final AgentEvent.Notice n) {
            final var prefix =
                    switch (n.level()) {
                        case ERROR -> "[error]";
                        case WARN -> "[warning]";
                        case INFO -> "[yca]";
                    };
            System.err.println(prefix + " " + n.message());
        } else if (event instanceof final AgentEvent.Status s) {
            System.err.println("[yca] " + s.message());
        } else if (event instanceof final AgentEvent.Error e) {
            System.err.println("[error] " + e.message());
        }
    }
}
