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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ToolRunTest {

    private ToolCard card(
            final long ordinal, final String name, final String args, final long elapsed, final boolean failed) {
        return new ToolCard(name, args, "", failed, elapsed, false, ordinal);
    }

    @Test
    void groupSingle() {
        final var run = ToolRun.of(List.of(new ToolRun.Member(card(1, "bash", "grep x", 10, false), "r1")));
        assertEquals(1, run.size());
        assertEquals(1, run.id());
    }

    @Test
    void countsByCommandAndFailures() {
        final var run = ToolRun.of(List.of(
                new ToolRun.Member(card(1, "bash", "grep x", 10, false), "r1"),
                new ToolRun.Member(card(2, "bash", "grep x", 20, false), "r2"),
                new ToolRun.Member(card(3, "read_file", "a.java", 5, true), "r3")));
        assertEquals(3, run.size());
        assertEquals(2, run.commandCounts().get("grep x")); // same args -> one command bucket
        assertEquals(1, run.commandCounts().get("a.java"));
        assertEquals(1, run.failures());
        assertEquals(35, run.elapsedMs());
    }

    @Test
    void runningCardNeverExtends() {
        final var builder = ToolRun.builder(new ToolRun.Member(card(1, "bash", "ls", 10, false), "r1"));
        // a running (still streaming) card must not join an existing run
        assertFalse(ToolRun.of(List.of(new ToolRun.Member(card(2, "bash", "sleep 5", 0, false), "r2")))
                .canExtend(new ToolCard("bash", "sleep 5", "", false, 0, true, 2)));
    }

    @Test
    void idIsFirstOrdinal() {
        final var run = ToolRun.of(List.of(
                new ToolRun.Member(card(7, "bash", "ls", 1, false), "r7"),
                new ToolRun.Member(card(9, "bash", "pwd", 1, false), "r9")));
        assertEquals(7, run.id());
    }

    @Test
    void canExtendRejectsRunningAndNull() {
        final var finished = ToolRun.of(List.of(new ToolRun.Member(card(1, "bash", "ls", 1, false), "r1")));
        assertTrue(finished.canExtend(card(2, "bash", "pwd", 1, false)));
        assertFalse(finished.canExtend(new ToolCard("bash", "sleep 5", "", false, 0, true, 3)));
        assertFalse(finished.canExtend(null));
    }

    @Test
    void summaryOnEmptyNeverBreaks() {
        final var run = ToolRun.of(List.of(new ToolRun.Member(card(1, "bash", "ls", 0, false), "r1")));
        assertEquals(1, run.size());
    }
}
