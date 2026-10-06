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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ToolOutputStoreTest {
    @Test
    void recordsAndRetrievesByOrdinal() {
        final var store = new ToolOutputStore();
        store.record(3, new ToolOutputStore.Entry("mvn", "test", "output-3", false, 12));
        assertEquals("output-3", store.get(3).output());
        assertEquals("mvn", store.get(3).name());
        assertTrue(store.get(3).elapsedMs() == 12);
        assertEquals(3, store.latestOrdinal());
    }

    @Test
    void missingOrdinalReturnsNull() {
        assertNull(new ToolOutputStore().get(42));
    }

    @Test
    void negativeOrdinalIgnored() {
        final var store = new ToolOutputStore();
        store.record(-1, new ToolOutputStore.Entry("x", "", "y", false, 0));
        assertEquals(0, store.size());
        assertEquals(-1, store.latestOrdinal());
    }

    @Test
    void ringEvictsOldest() {
        final var store = new ToolOutputStore(2, 1024);
        store.record(1, new ToolOutputStore.Entry("a", "", "1", false, 0));
        store.record(2, new ToolOutputStore.Entry("b", "", "2", false, 0));
        store.record(3, new ToolOutputStore.Entry("c", "", "3", false, 0));
        assertNull(store.get(1));
        assertEquals("2", store.get(2).output());
        assertEquals("3", store.get(3).output());
        assertEquals(2, store.size());
    }

    @Test
    void oversizedOutputKeepsTail() {
        final var store = new ToolOutputStore(8, 10);
        store.record(1, new ToolOutputStore.Entry("a", "", "0123456789ABCDEF", false, 0));
        final var output = store.get(1).output();
        assertTrue(output.startsWith("6789ABCDEF"));
        assertTrue(output.contains("truncated"));
        assertTrue(output.length() < 40);
    }

    @Test
    void failedFlagPreserved() {
        final var store = new ToolOutputStore();
        store.record(1, new ToolOutputStore.Entry("a", "", "boom", true, 0));
        assertTrue(store.get(1).failed());
    }

    @Test
    void reRecordSameOrdinalReplaces() {
        final var store = new ToolOutputStore();
        store.record(1, new ToolOutputStore.Entry("a", "", "first", false, 0));
        store.record(1, new ToolOutputStore.Entry("a", "", "second", false, 0));
        assertEquals("second", store.get(1).output());
        assertEquals(1, store.size());
    }

    @Test
    void runningEntryFlaggedAndReplacedOnFinish() {
        final var store = new ToolOutputStore();
        // the TUI records a placeholder at start (elapsedMs < 0 => running)
        store.record(1, new ToolOutputStore.Entry("bash", "{\"command\":\"mvn test\"}", "", false, -1L));
        final var running = store.get(1);
        assertTrue(running.running(), "elapsedMs < 0 must mark a still-running call");
        assertEquals("bash", running.name());
        assertFalse(running.failed());

        // the ToolFinished event overwrites the same ordinal with the real output
        store.record(
                1, new ToolOutputStore.Entry("bash", "{\"command\":\"mvn test\"}", "BUILD SUCCESS", false, 1_234L));
        final var done = store.get(1);
        assertFalse(done.running(), "the finished record must not be running");
        assertEquals("BUILD SUCCESS", done.output());
        assertEquals(1_234L, done.elapsedMs());
        assertEquals(1, store.size(), "the placeholder must be replaced, not stacked");
        assertEquals(1, store.latestOrdinal());
    }

    @Test
    void pendingOutputRendersWithoutCrashing() {
        // the overlay builds a body from the running entry's empty output
        final var store = new ToolOutputStore();
        store.record(2, new ToolOutputStore.Entry("bash", "{}", "", false, -1L));
        assertNotNull(store.get(2));
        assertTrue(store.get(2).output().isEmpty());
    }
}
