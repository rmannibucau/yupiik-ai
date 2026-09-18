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

import static io.yupiik.ia.agent.core.tools.builtin.ReadFileTool.arg;
import static io.yupiik.ia.agent.core.tools.builtin.ReadFileTool.optInt;

import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.Schemas;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolResult;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class BashTool implements EmbeddedTool {
    private final Emitter emitter;

    public BashTool(final Emitter emitter) {
        this.emitter = emitter;
    }

    protected BashTool() {
        this.emitter = null;
    }

    @Override
    public String name() {
        return "bash";
    }

    @Override
    public String description() {
        return "Run a shell command (sh -c) and return its combined stdout/stderr. "
                + "IMPORTANT: this tool is for terminal operations only (git, mvn, gradle, npm, docker...). "
                + "Do NOT use it for file operations - reading, writing, editing, searching or finding files: "
                + "use the dedicated read_file, grep, glob and list_dir tools instead, they are faster and filtered. "
                + "Avoid `cd <dir> && <cmd>`: use the workdir parameter. Prefer && for dependent commands and "
                + "batch independent commands as separate bash calls in the same reply.";
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
                        "workdir", Schemas.str("Working directory, default the current one.")),
                "command");
    }

    @Override
    public ToolResult execute(final Map<String, Object> args) {
        final var command = arg(args, "command");
        if (command == null || command.isBlank()) {
            return ToolResult.error("command is required");
        }
        final var timeout = optInt(args, "timeout_seconds", 120);
        final var workdir = arg(args, "workdir");
        final var pb = new ProcessBuilder("sh", "-c", command);
        pb.redirectErrorStream(true);
        if (workdir != null && !workdir.isBlank()) {
            pb.directory(Path.of(workdir).toFile());
        }
        final Process process;
        try {
            process = pb.start();
        } catch (final IOException e) {
            return ToolResult.error("Cannot start command: " + e.getMessage());
        }
        emitter.emit(new AgentEvent.ToolStream("bash", "$ " + command));
        final var out = new StringBuilder();
        final var streamed = new java.util.concurrent.atomic.AtomicInteger();
        final var reader = new Thread(() -> {
            try (BufferedReader r =
                    new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    synchronized (out) {
                        if (out.length() < 400_000) {
                            out.append(line).append('\n');
                        }
                    }
                    if (streamed.incrementAndGet() <= 500 && emitter != null) {
                        emitter.emit(new AgentEvent.ToolStream("bash", line));
                    }
                }
            } catch (final IOException e) {
                // stream closed
            }
        });
        reader.setDaemon(true);
        reader.start();
        boolean finished;
        try {
            finished = process.waitFor(timeout, TimeUnit.SECONDS);
        } catch (final InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            return ToolResult.error("Interrupted");
        }
        if (!finished) {
            process.destroyForcibly();
            return ToolResult.error("Command timed out after " + timeout + "s\n" + snapshot(out));
        }
        try {
            reader.join(2000);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        final var code = process.exitValue();
        return ToolResult.ok(snapshot(out) + "\nExit code: " + code);
    }

    private static String snapshot(final StringBuilder out) {
        synchronized (out) {
            final var s = out.toString();
            return s.length() > 400_000 ? s.substring(0, 400_000) + "\n... output truncated" : s.stripTrailing();
        }
    }
}
