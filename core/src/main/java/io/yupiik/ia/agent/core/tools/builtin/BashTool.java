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
package io.yupiik.ia.agent.core.tools.builtin;

import static io.yupiik.ia.agent.core.tools.ToolArgs.arg;
import static io.yupiik.ia.agent.core.tools.ToolArgs.optInt;

import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.Schemas;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.tools.ToolResult;
import io.yupiik.maven.knowledge.process.ProcessService;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@ApplicationScoped
public class BashTool implements EmbeddedTool {
    private final Emitter emitter;
    private final ProcessService processes;
    private final int maxOutputChars;
    /** Configured default working directory process, used when neither a workdir arg nor a session root apply. */
    private final java.nio.file.Path fallbackDir;

    public BashTool(
            final Emitter emitter, final ProcessService processes, final CodingAgentConfiguration configuration) {
        this.emitter = emitter;
        this.processes = processes;
        this.maxOutputChars = configuration.tools().maxOutputChars();
        this.fallbackDir = configuration.resolveWorkingDir().toAbsolutePath().normalize();
    }

    public BashTool(final Emitter emitter, final ProcessService processes) {
        this.emitter = emitter;
        this.processes = processes;
        this.maxOutputChars = 65536;
        this.fallbackDir = null; // no configuration: inherit the JVM working directory
    }

    protected BashTool() {
        this.emitter = null;
        this.processes = null;
        this.maxOutputChars = 65536;
        this.fallbackDir = null;
    }

    @Override
    public String name() {
        return "bash";
    }

    @Override
    public String description() {
        return "Run a shell command (sh -c) and return its combined stdout/stderr "
                + "(truncated at maxOutputChars, " + toolOutputCapHuman() + "). "
                + "For terminal operations (git, mvn, npm, docker...); file ops use the dedicated "
                + "read_file/grep/glob/list_dir tools. Use the workdir parameter instead of `cd <dir> && <cmd>`.";
    }

    private String toolOutputCapHuman() {
        return maxOutputChars >= 1000 ? (maxOutputChars / 1000) + "k chars" : maxOutputChars + " chars";
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.BASH;
    }

    @Override
    public Map<String, Object> schema() {
        return Schemas.object(
                Map.of(
                        "command", Schemas.str("The shell command to run."),
                        "timeout_seconds", Schemas.intProp("Command timeout in seconds, default 120."),
                        "workdir",
                                Schemas.str("Working directory; defaults to the session's project directory, else the"
                                        + " configured working directory.")),
                "command");
    }

    @Override
    public ToolResult execute(final Map<String, Object> args) {
        return execute(args, ToolContext.empty());
    }

    @Override
    public ToolResult execute(final Map<String, Object> args, final ToolContext context) {
        final var command = arg(args, "command");
        if (command == null || command.isBlank()) {
            return ToolResult.error("command is required");
        }
        final var timeout = optInt(args, "timeout_seconds", 120);
        final var workdir = arg(args, "workdir");
        final var pb = new ProcessBuilder("sh", "-c", command);
        pb.redirectErrorStream(true);
        // A workdir chosen by the model this turn is a fact it can act on: reject it if it does not
        // exist rather than silently running elsewhere (which would contradict what the model expects).
        // Session-level directories (project root / configured working dir) were not chosen this turn,
        // so a stale one falls through to the next live candidate with an explanatory notice.
        final var workdirNotice = new StringBuilder();
        if (workdir != null && !workdir.isBlank()) {
            final var dir = Path.of(workdir).toAbsolutePath().normalize();
            if (Files.isDirectory(dir)) {
                pb.directory(dir.toFile());
            } else {
                return ToolResult.error("workdir '" + workdir + "' does not exist");
            }
        } else {
            // no explicit workdir: honour the session's project root, else the configured working dir,
            // rather than silently inheriting the JVM launch directory (which may differ from both).
            // A candidate that no longer exists is skipped and the fallback is reported in the output
            // so the caller can see that the command did not run exactly where the session intended.
            final var candidates = candidateDirectories(context);
            java.nio.file.Path skipped = null;
            boolean used = false;
            for (final var candidate : candidates) {
                if (Files.isDirectory(candidate)) {
                    if (skipped != null) {
                        workdirNotice
                                .append("workdir '")
                                .append(skipped)
                                .append("' not found, using '")
                                .append(candidate)
                                .append('\'');
                    }
                    pb.directory(candidate.toFile());
                    used = true;
                    break;
                }
                skipped = candidate;
            }
            if (!used && skipped != null) {
                workdirNotice
                        .append("workdir '")
                        .append(skipped)
                        .append("' not found, using the JVM working directory");
            }
        }
        final Process process;
        try {
            process = pb.start();
        } catch (final IOException e) {
            return ToolResult.error("Cannot start command: " + e.getMessage());
        }
        if (processes != null) {
            processes.track(process);
        }
        emitter.emit(new AgentEvent.ToolStream(
                "bash",
                "$ " + command,
                context.ordinal(),
                context.sessionId(),
                context.subagentId(),
                context.subagentName()));
        final var out = new StringBuilder();
        final var streamed = new AtomicInteger();
        final var reader = new Thread(() -> {
            try (BufferedReader r =
                    new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    synchronized (out) {
                        if (out.length() < maxOutputChars) {
                            out.append(line).append('\n');
                        }
                    }
                    if (streamed.incrementAndGet() <= 500 && emitter != null) {
                        emitter.emit(new AgentEvent.ToolStream(
                                "bash",
                                line,
                                context.ordinal(),
                                context.sessionId(),
                                context.subagentId(),
                                context.subagentName()));
                    }
                }
            } catch (final IOException e) {
                // stream closed
            }
        });
        reader.setDaemon(true);
        reader.start();
        boolean finished;
        // deadline loop: the command is killed as soon as the running turn is cancelled so an
        // "interrupt" actually stops the subprocess instead of letting it run to completion.
        // waitFor is polled in short slices so both the deadline and the cancellation flag are
        // re-checked until the command actually finishes.
        final var deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeout);
        while (true) {
            if (context.isCancelled() || context.isCallCancelled()) {
                dispose(process);
                return ToolResult.error("Interrupted");
            }
            final var remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                finished = false;
                break;
            }
            try {
                finished =
                        process.waitFor(Math.min(100, Math.max(1, remainingNanos / 1_000_000)), TimeUnit.MILLISECONDS);
                if (finished) {
                    break;
                }
                // not finished yet: loop back to re-check cancellation/deadline before the next slice
            } catch (final InterruptedException e) {
                dispose(process);
                Thread.currentThread().interrupt();
                return ToolResult.error("Interrupted");
            }
        }
        if (!finished) {
            dispose(process);
            return ToolResult.error("Command timed out after " + timeout + "s\n"
                    + withNotice(workdirNotice, snapshot(out, maxOutputChars)));
        }
        try {
            reader.join(2000);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        final var code = process.exitValue();
        return ToolResult.ok(withNotice(workdirNotice, snapshot(out, maxOutputChars)) + "\nExit code: " + code);
    }

    /**
     * The session-level working-directory candidates to try (in precedence order) when the call carries
     * no explicit {@code workdir} arg: the session's project root, then the configured working directory.
     * Missing or null candidates are skipped by the caller via {@link java.nio.file.Files#isDirectory}.
     */
    private java.util.List<java.nio.file.Path> candidateDirectories(final ToolContext context) {
        final var candidates = new java.util.ArrayList<java.nio.file.Path>(2);
        if (context.projectDir() != null) {
            candidates.add(context.projectDir().toAbsolutePath().normalize());
        }
        if (fallbackDir != null) {
            candidates.add(fallbackDir.toAbsolutePath().normalize());
        }
        return candidates;
    }

    /** Prepends the workdir-deviation notice (when any) as the first line of the tool output. */
    private static String withNotice(final StringBuilder notice, final String output) {
        return notice.length() == 0 ? output : notice + "\n" + output;
    }

    /** Kills and reaps the whole process tree through the service (null-safe for standalone tests). */
    private void dispose(final Process process) {
        if (processes != null) {
            processes.dispose(process);
        } else if (process != null && process.isAlive()) {
            process.destroyForcibly();
        }
    }

    private static String snapshot(final StringBuilder out, final int maxOutputChars) {
        synchronized (out) {
            final var s = out.toString();
            return s.length() > maxOutputChars
                    ? s.substring(0, maxOutputChars) + "\n... output truncated"
                    : s.stripTrailing();
        }
    }
}
