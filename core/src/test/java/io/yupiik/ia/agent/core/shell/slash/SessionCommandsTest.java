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
package io.yupiik.ia.agent.core.shell.slash;

import static java.util.concurrent.CompletableFuture.completedStage;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.permissions.ApprovalGate;
import io.yupiik.ia.agent.core.question.FeedbackGate;
import io.yupiik.ia.agent.core.session.SessionStore;
import io.yupiik.ia.agent.core.shell.ConversationTranscript;
import io.yupiik.ia.agent.core.shell.ShellEnv;
import io.yupiik.ia.agent.core.shell.ShellFrontend;
import io.yupiik.ia.agent.core.shell.ShellIO;
import io.yupiik.ia.agent.core.shell.ShellOutcome;
import io.yupiik.ia.agent.core.shell.ShellSession;
import io.yupiik.ia.agent.core.shell.ToolOutputVisibility;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

class SessionCommandsTest {
    private final List<String> picked = new ArrayList<>();

    private ShellEnv env() {
        return new ShellEnv(
                new ShellFrontend() {
                    @Override
                    public void status(final String message) {}

                    @Override
                    public void notice(final AgentEvent.Notice.Level level, final String message) {}

                    @Override
                    public void transcript(final String markdown) {}

                    @Override
                    public void error(final String message) {}

                    @Override
                    public void showThinking(final boolean value) {}

                    @Override
                    public void showToolOutput(final ToolOutputVisibility value) {}
                },
                new ShellIO() {
                    @Override
                    public CompletionStage<Optional<String>> readLine(final String prompt) {
                        return completedStage(Optional.empty());
                    }

                    @Override
                    public CompletionStage<Boolean> confirm(final String question) {
                        return completedStage(true);
                    }

                    @Override
                    public CompletionStage<Optional<String>> pick(final String title, final List<String> options) {
                        picked.addAll(options);
                        return completedStage(Optional.empty());
                    }

                    @Override
                    public ApprovalGate approvalGate() {
                        return null;
                    }

                    @Override
                    public FeedbackGate feedbackGate() {
                        return null;
                    }
                },
                new ShellSession(),
                new ConversationTranscript());
    }

    private ShellOutcome resume(final ShellEnv env) {
        final var store = new SessionStore() {
            @Override
            public List<SessionInfo> list() {
                // deliberately unsorted: the command must order most-recent first
                return List.of(
                        new SessionInfo("old", 1000, 10),
                        new SessionInfo("new", 3000, 20),
                        new SessionInfo("mid", 2000, 30));
            }
        };
        final var commands = new SessionCommands(store, null);
        final var cmd = commands.commands().stream()
                .filter(c -> c.spec().name().equals("/resume"))
                .findFirst()
                .orElseThrow();
        return cmd.action().apply("", env).toCompletableFuture().join();
    }

    @Test
    void resumePicksSessionsMostRecentFirst() {
        final var outcome = resume(env());
        // the io.pick returns empty: the wizard reports a cancellation, no resume happens
        assertEquals("resume cancelled", ((ShellOutcome.Status) outcome).message());
        assertEquals(3, picked.size());
        assertTrue(picked.get(0).startsWith("new"), picked.toString());
        assertTrue(picked.get(1).startsWith("mid"), picked.toString());
        assertTrue(picked.get(2).startsWith("old"), picked.toString());
    }

    @Test
    void resumeWithNoSessionsReportsStatus() {
        final var store = new SessionStore() {};
        final var commands = new SessionCommands(store, null);
        final var cmd = commands.commands().stream()
                .filter(c -> c.spec().name().equals("/resume"))
                .findFirst()
                .orElseThrow();
        final var outcome = cmd.action().apply("", env()).toCompletableFuture().join();
        assertEquals("no session to resume", ((ShellOutcome.Status) outcome).message());
        assertTrue(picked.isEmpty(), "the picker must not open when there is nothing to pick");
    }
}
