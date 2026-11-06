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

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.shell.ConversationTranscript;
import io.yupiik.ia.agent.core.shell.ShellEnv;
import io.yupiik.ia.agent.core.shell.ShellOutcome;
import io.yupiik.ia.agent.core.shell.ShellSession;
import org.junit.jupiter.api.Test;

/** Exercises the /tools and /executing-tools inspection commands against a seeded transcript. */
class InspectCommandsTest {

    @Test
    void executingToolsRendersEachRunningToolAndItsCommand() {
        final var transcript = new ConversationTranscript();
        // two in-flight tools: one bash command, one plain tool (no command in args)
        transcript.onEvent(new AgentEvent.ToolStarted("bash", "{\"command\":\"mvn test\"}", 1, "s1", null, null));
        transcript.onEvent(new AgentEvent.ToolStarted("grep", "{}", 2, "s1", null, null));
        final var env = new ShellEnv(null, null, new ShellSession().sessionId("s1"), transcript);

        final var outcome = command(env, "/executing-tools");
        final var content = content(outcome);
        assertTrue(content.contains("mvn test"), "bash command must be shown: " + content);
        assertTrue(content.contains("grep"), "plain tool name must be shown: " + content);
    }

    @Test
    void executingToolsWithNothingRunningEmitsDurableTranscript() {
        // an empty list must be a durable Transcript (not a Status toast): on the TUI/web a status is a
        // transient, easy-to-miss notification while a Transcript is always visible and surfaces the modal.
        final var env = new ShellEnv(null, null, new ShellSession().sessionId("s1"), new ConversationTranscript());
        final var outcome = command(env, "/executing-tools");
        final var content = content(outcome);
        assertInstanceOf(
                ShellOutcome.Transcript.class, outcome, "empty list must be a transcript, not a status: " + outcome);
        assertTrue(content.startsWith("**Executing tools**"), "must keep the executing-tools marker: " + content);
        assertTrue(content.contains("(no tool executing)"), "must say no tool is executing: " + content);
    }

    private static ShellOutcome command(final ShellEnv env, final String text) {
        final var running = new InspectCommands(null)
                .commands().stream()
                        .filter(c -> text.startsWith(c.spec().name() + " ")
                                || text.equals(c.spec().name()))
                        .findFirst()
                        .orElseThrow();
        final var arg = text.strip();
        final var idx = arg.indexOf(' ');
        return running.action()
                .apply(idx < 0 ? null : arg.substring(idx + 1).strip(), env)
                .toCompletableFuture()
                .join();
    }

    private static String content(final ShellOutcome outcome) {
        if (outcome instanceof ShellOutcome.Status(String message)) {
            return message;
        }
        if (outcome instanceof ShellOutcome.Transcript(String markdown)) {
            return markdown;
        }
        if (outcome instanceof ShellOutcome.Error(String message)) {
            return message;
        }
        return String.valueOf(outcome);
    }
}
