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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.core.agent.AgentLoop;
import io.yupiik.ia.agent.core.shell.ConversationTranscript;
import io.yupiik.ia.agent.core.shell.SessionRuntime;
import io.yupiik.ia.agent.core.shell.ShellEnv;
import io.yupiik.ia.agent.core.shell.ShellOutcome;
import io.yupiik.ia.agent.core.shell.ShellSession;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@code /compact} option parsing and the busy deferred path: {@code force}, {@code urgent} and
 * {@code keep=<n>} must reach the queued directive exactly as typed (the destructive urgent flag is
 * opt-in and must never leak into the plain {@code /compact} form).
 */
class MaintainCommandsTest {

    /** An agent loop that records the context-operation requests it receives. */
    private static final class RecordingLoop extends AgentLoop {
        private final List<String> requests = new ArrayList<>();

        @Override
        public boolean requestUserContextOperation(
                final String action,
                final String focus,
                final int keepLast,
                final boolean force,
                final boolean urgent) {
            requests.add(action + "|" + focus + "|" + keepLast + "|" + force + "|" + urgent);
            return true;
        }
    }

    private static SlashCommand compactCommand() {
        return new MaintainCommands(null, null, null)
                .commands().stream()
                        .filter(c -> c.spec().name().equals("/compact"))
                        .findFirst()
                        .orElseThrow();
    }

    private static ShellOutcome runCompact(final String arg, final ShellSession session) {
        final var cmd = compactCommand();
        return cmd.action()
                .apply(arg, new ShellEnv(null, null, session, new ConversationTranscript()))
                .toCompletableFuture()
                .join();
    }

    private static ShellSession busySession(final RecordingLoop loop) {
        return new ShellSession()
                .busy(true)
                .runtime(new SessionRuntime(loop, null, null, null, null, null, null, null));
    }

    @Test
    void busySessionDefersTheFullOptionSet() {
        final var loop = new RecordingLoop();
        final var outcome = runCompact("urgent force keep=10", busySession(loop));
        assertEquals(
                "compaction scheduled - applied at the next agent checkpoint",
                ((ShellOutcome.Status) outcome).message());
        assertEquals(
                List.of("compact|null|10|true|true"),
                loop.requests,
                "the queued directive must carry keep/force/urgent");
    }

    @Test
    void busySessionPlainCompactQueuesNoUrgentFlag() {
        final var loop = new RecordingLoop();
        final var outcome = runCompact("", busySession(loop));
        assertEquals(
                "compaction scheduled - applied at the next agent checkpoint",
                ((ShellOutcome.Status) outcome).message());
        assertEquals(List.of("compact|null|0|false|false"), loop.requests, "a plain /compact must not imply urgent");
    }

    @Test
    void unknownTokenPrintsTheUsageLine() {
        final var outcome = runCompact("bogus", busySession(new RecordingLoop()));
        assertEquals("usage: /compact [force] [urgent] [keep=<n>]", ((ShellOutcome.Status) outcome).message());
    }

    @Test
    void negativeKeepPrintsTheUsageLine() {
        final var outcome = runCompact("keep=-1", busySession(new RecordingLoop()));
        assertEquals("usage: /compact [force] [urgent] [keep=<n>]", ((ShellOutcome.Status) outcome).message());
    }

    @Test
    void completionOffersForceUrgentAndKeep() {
        final var completion = compactCommand().completion();
        assertTrue(completion != ArgCompletion.NONE, "/compact has options so it must register a completion");
        final var items = completion.complete("", "", null).items();
        assertTrue(items.contains("force"), "completion must offer force: " + items);
        assertTrue(items.contains("urgent"), "completion must offer urgent: " + items);
        assertTrue(items.contains("keep="), "completion must offer keep=: " + items);
    }

    @Test
    void completionFiltersByTokenPrefix() {
        final var completion = compactCommand().completion();
        final var items = completion.complete("", "ur", null).items();
        assertEquals(List.of("urgent"), items, "prefix completion must narrow to the matching option");
    }
}
