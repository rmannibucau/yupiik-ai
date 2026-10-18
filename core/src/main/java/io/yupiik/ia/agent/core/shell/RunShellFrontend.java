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
package io.yupiik.ia.agent.core.shell;

import io.yupiik.ia.agent.core.agent.AgentEvent;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Headless frontend of the {@code run} command. Discrete events (tool
 * lifecycle, notices, errors) go through {@code java.util.logging} so levels
 * and inline formatting are handled by the log manager (yupiik-logging-jul);
 * the two continuous streams stay raw - the answer on stdout (it is the
 * command product) and the reasoning stream on stderr when enabled.
 */
public class RunShellFrontend implements ShellFrontend {
    private static final Logger LOGGER = Logger.getLogger(RunShellFrontend.class.getName());

    private final boolean showThinking;
    private final ToolOutputVisibility showToolOutput;
    private final AtomicBoolean toolStreamed = new AtomicBoolean();
    private boolean stderrNeedsNewline;
    /**
     * Actor owning the open (newline-less) stderr line, {@code null} when none:
     * a spawned sub-agent streams on its own thread while the parent turn keeps
     * going, so a thought delta from a different actor must not be appended to
     * the line the current one left mid-thought.
     */
    private String stderrLineOwner;

    public RunShellFrontend(final boolean showThinking, final ToolOutputVisibility showToolOutput) {
        this.showThinking = showThinking;
        this.showToolOutput = showToolOutput;
    }

    @Override
    public void status(final String message) {
        ensureStderrNewline();
        LOGGER.info(message);
    }

    @Override
    public void notice(final AgentEvent.Notice.Level level, final String message) {
        ensureStderrNewline();
        log(
                switch (level) {
                    case ERROR -> Level.SEVERE;
                    case WARN -> Level.WARNING;
                    case INFO -> Level.INFO;
                },
                message);
    }

    @Override
    public void transcript(final String markdown) {
        System.out.print(markdown);
    }

    @Override
    public void error(final String message) {
        ensureStderrNewline();
        LOGGER.severe(message);
    }

    @Override
    public void showThinking(final boolean value) {
        // fixed by the run configuration
    }

    @Override
    public void showToolOutput(final ToolOutputVisibility value) {
        // fixed by the run configuration
    }

    @Override
    public synchronized void onEvent(final AgentEvent event) {
        final var prefix = subagentPrefix(event);
        if (event instanceof final AgentEvent.TextDelta t) {
            System.out.print(prefix + t.delta());
        } else if (event instanceof final AgentEvent.ThinkingDelta t) {
            if (showThinking) {
                // a second voice must not splice into the line the current one left
                // open: close it so both thoughts stay readable
                if (stderrNeedsNewline && !Objects.equals(stderrLineOwner, t.subagentId())) {
                    ensureStderrNewline();
                }
                System.err.print(prefix + t.delta());
                stderrNeedsNewline = !t.delta().endsWith("\n");
                stderrLineOwner = t.subagentId();
            }
        } else if (event instanceof final AgentEvent.ToolStarted t) {
            toolStreamed.set(false);
            ensureStderrNewline();
            LOGGER.info(() -> prefix + "tool started: " + t.name());
        } else if (event instanceof final AgentEvent.ToolStream t) {
            toolStreamed.set(true);
            ensureStderrNewline();
            LOGGER.info(() -> prefix + "[" + t.name() + "] " + t.fragment());
        } else if (event instanceof final AgentEvent.ToolFinished t) {
            ensureStderrNewline();
            if (!t.success()) {
                LOGGER.warning(() -> prefix + "tool failed: " + t.name());
            } else if (showToolOutput.shown()
                    && !toolStreamed.get()
                    && t.output() != null
                    && !t.output().isBlank()) {
                LOGGER.info(() -> prefix + "[" + t.name() + "] " + t.output().stripTrailing());
            }
        } else if (event instanceof final AgentEvent.Compacted c) {
            ensureStderrNewline();
            LOGGER.info(() ->
                    prefix + "context " + c.kind() + " applied (" + c.mode().toLowerCase(Locale.ROOT) + ")");
        } else if (event
                instanceof
                AgentEvent.Notice(
                        var level,
                        String message,
                        var finishReason,
                        String sessionId,
                        String subagentId,
                        String subagentName)) {
            notice(level, prefix + message);
        } else if (event
                instanceof
                AgentEvent.Pause(
                        AgentEvent.Pause.Kind kind,
                        String message,
                        long durationMs,
                        String sessionId,
                        String subagentId,
                        String subagentName)) {
            log(kind == AgentEvent.Pause.Kind.RETRY ? Level.WARNING : Level.INFO, prefix + message);
        } else if (event
                instanceof
                AgentEvent.Status(String message, String sessionId, String subagentId, String subagentName)) {
            status(prefix + message);
        } else if (event
                instanceof AgentEvent.Error(String message, String sessionId, String subagentId, String subagentName)) {
            error(prefix + message);
        }
    }

    private void log(final Level level, final String message) {
        LOGGER.log(level, message);
    }

    /**
     * The reasoning stream writes to stderr without a trailing newline; before a
     * discrete event line is emitted, terminate the open thought line so tool/status
     * lines are not glued to the thinking output.
     */
    private void ensureStderrNewline() {
        if (stderrNeedsNewline) {
            System.err.println();
            stderrNeedsNewline = false;
            stderrLineOwner = null;
        }
    }

    private String subagentPrefix(final AgentEvent event) {
        String subagentId = null;
        String subagentName = null;
        if (event instanceof AgentEvent.TextDelta(var _, var _, String id, String name)) {
            subagentId = id;
            subagentName = name;
        } else if (event instanceof AgentEvent.ThinkingDelta(var _, var _, String id, String name)) {
            subagentId = id;
            subagentName = name;
        } else if (event instanceof AgentEvent.ToolStarted(var _, var _, var _, var _, String id, String name)) {
            subagentId = id;
            subagentName = name;
        } else if (event instanceof AgentEvent.ToolStream(var _, var _, var _, var _, String id, String name)) {
            subagentId = id;
            subagentName = name;
        } else if (event
                instanceof AgentEvent.ToolFinished(var _, var _, var _, var _, var _, var _, String id, String name)) {
            subagentId = id;
            subagentName = name;
        } else if (event instanceof AgentEvent.Compacted(var _, var _, var _, String id, String name)) {
            subagentId = id;
            subagentName = name;
        } else if (event instanceof AgentEvent.Notice(var _, var _, var _, var _, String id, String name)) {
            subagentId = id;
            subagentName = name;
        } else if (event instanceof AgentEvent.Status(var _, var _, String id, String name)) {
            subagentId = id;
            subagentName = name;
        } else if (event
                instanceof AgentEvent.Pause(var _, String message, long durationMs, String _, String id, String name)) {
            subagentId = id;
            subagentName = name;
        } else if (event instanceof AgentEvent.Error(var _, var _, String id, String name)) {
            subagentId = id;
            subagentName = name;
        }
        if (subagentId != null) {
            final var displayName =
                    subagentName != null ? subagentName : subagentId.substring(0, Math.min(8, subagentId.length()));
            return "[" + displayName + "] ";
        }
        return "";
    }
}
