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
    private final boolean showToolOutput;
    private final AtomicBoolean toolStreamed = new AtomicBoolean();

    public RunShellFrontend(final boolean showThinking, final boolean showToolOutput) {
        this.showThinking = showThinking;
        this.showToolOutput = showToolOutput;
    }

    @Override
    public void status(final String message) {
        LOGGER.info(message);
    }

    @Override
    public void notice(final AgentEvent.Notice.Level level, final String message) {
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
        LOGGER.severe(message);
    }

    @Override
    public void showThinking(final boolean value) {
        // fixed by the run configuration
    }

    @Override
    public void showToolOutput(final boolean value) {
        // fixed by the run configuration
    }

    @Override
    public void onEvent(final AgentEvent event) {
        if (event instanceof final AgentEvent.TextDelta t) {
            System.out.print(t.delta());
        } else if (event instanceof final AgentEvent.ThinkingDelta t) {
            if (showThinking) {
                System.err.print(t.delta());
            }
        } else if (event instanceof final AgentEvent.ToolStarted t) {
            toolStreamed.set(false);
            LOGGER.info(() -> "tool started: " + t.name());
        } else if (event instanceof final AgentEvent.ToolStream t) {
            toolStreamed.set(true);
            LOGGER.info(() -> "[" + t.name() + "] " + t.fragment());
        } else if (event instanceof final AgentEvent.ToolFinished t) {
            if (!t.success()) {
                LOGGER.warning(() -> "tool failed: " + t.name());
            } else if (showToolOutput
                    && !toolStreamed.get()
                    && t.output() != null
                    && !t.output().isBlank()) {
                LOGGER.info(() -> "[" + t.name() + "] " + t.output().stripTrailing());
            }
        } else if (event instanceof final AgentEvent.Compacted c) {
            LOGGER.info(() -> "context " + c.kind() + " applied (" + c.mode().toLowerCase(Locale.ROOT) + ")");
        } else if (event instanceof AgentEvent.Notice(var level, String message, var finishReason)) {
            notice(level, message);
        } else if (event instanceof AgentEvent.Status(String message)) {
            status(message);
        } else if (event instanceof AgentEvent.Error(String message)) {
            error(message);
        }
    }

    private void log(final Level level, final String message) {
        LOGGER.log(level, message);
    }
}
