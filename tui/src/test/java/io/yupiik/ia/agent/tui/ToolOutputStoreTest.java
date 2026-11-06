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
        assertEquals(12, store.get(3).elapsedMs());
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
    void reRecordingExistingOrdinalNeverRotatesEviction() {
        // the reported bug: each stream chunk re-records the same running ordinal, and the
        // unconditional ring push rotated the eviction order, evicting older tools around it
        final var store = new ToolOutputStore(2, 1024);
        store.record(1, new ToolOutputStore.Entry("bash", "{}", "0", false, -1L));
        store.record(2, new ToolOutputStore.Entry("ls", "", "2", false, 0));
        // stream 200 chunks on ordinal 1: must update in place, never push the ring
        for (int i = 1; i <= 200; i++) {
            store.record(1, new ToolOutputStore.Entry("bash", "{}", "chunk-" + i, false, -1L));
        }
        assertNotNull(store.get(1), "ordinal 1 must still be retained after 200 stream chunks");
        assertNotNull(store.get(2), "ordinal 2 must not be evicted by stream chunks of ordinal 1");
        assertEquals(2, store.size(), "re-records must not stack entries");
        assertEquals("chunk-200", store.get(1).output());
    }

    @Test
    void reRecordKeepsFirstInsertionSlotInFifoOrder() {
        final var store = new ToolOutputStore(2, 1024);
        store.record(1, new ToolOutputStore.Entry("a", "", "1", false, 0));
        store.record(2, new ToolOutputStore.Entry("b", "", "2", false, 0));
        // re-record 1 (like a finish): must not move it past 2 in the eviction order
        store.record(1, new ToolOutputStore.Entry("a", "", "1-final", false, 5));
        assertEquals("1-final", store.get(1).output());
        // 3 evicts the oldest = 1, not 2, because 1 kept its original slot
        store.record(3, new ToolOutputStore.Entry("c", "", "3", false, 0));
        assertNull(store.get(1), "the updated ordinal 1 must still be the FIFO head and evict first");
        assertEquals("2", store.get(2).output());
        assertEquals("3", store.get(3).output());
    }

    @Test
    void maxTotalCharsEvictsOldestUntilBudgetFits() {
        final var store = new ToolOutputStore(128, 1024, 20);
        store.record(1, new ToolOutputStore.Entry("a", "", "1111111111", false, 0)); // 10
        store.record(2, new ToolOutputStore.Entry("b", "", "2222222222", false, 0)); // 10
        store.record(3, new ToolOutputStore.Entry("c", "", "3333333333", false, 0)); // 10
        // total is 30 > 20: evict oldest until <= 20 -> drops 1, keeps 2 and 3 (20 chars)
        assertNull(store.get(1));
        assertNotNull(store.get(2));
        assertNotNull(store.get(3));
        assertEquals(2, store.size());
        assertEquals(20, store.totalChars());
        // re-recording an existing ordinal that grows past the budget: 2 goes from 10 to 12 chars,
        // total would be 22 > 20 -> evict the oldest (2) until the budget fits -> only 3 remains
        store.record(2, new ToolOutputStore.Entry("b", "", "222222222222", false, 0)); // 12
        assertNull(store.get(2), "growing an existing entry past the total budget evicts the oldest first");
        assertEquals("3333333333", store.get(3).output());
        assertEquals(10, store.totalChars());
    }

    @Test
    void maxTotalCharsZeroMeansUnboundedByTotal() {
        final var store = new ToolOutputStore(128, 1024, 0);
        for (int i = 1; i <= 5; i++) {
            store.record(i, new ToolOutputStore.Entry("a", "", "1234567890", false, 0));
        }
        assertEquals(5, store.size());
        assertEquals(50, store.totalChars());
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

    @Test
    void streamAppendsToRunningEntryUntilItReplacesIt() {
        final var store = new ToolOutputStore();
        store.record(1, new ToolOutputStore.Entry("bash", "{\"command\":\"mvn test\"}", "", false, -1L));
        // each ToolStream chunk is recorded under the same ordinal; the running entry
        // accumulates the live tail without stacking new ring entries
        store.record(1, new ToolOutputStore.Entry("bash", "{\"command\":\"mvn test\"}", "compiling", false, -1L));
        store.record(
                1, new ToolOutputStore.Entry("bash", "{\"command\":\"mvn test\"}", "compiling\nBUILD", false, -1L));
        final var running = store.get(1);
        assertTrue(running.running());
        assertEquals("compiling\nBUILD", running.output());
        assertEquals(1, store.size(), "stream chunks must not stack entries");
    }

    @Test
    void streamAppendIsCappedToMaxOutputChars() {
        final var store = new ToolOutputStore(4, 16);
        store.record(1, new ToolOutputStore.Entry("bash", "{}", "", false, -1L));
        store.record(1, new ToolOutputStore.Entry("bash", "{}", "0123456789abcdef", false, -1L));
        store.record(1, new ToolOutputStore.Entry("bash", "{}", "0123456789abcdefghijklmnopqrstuv", false, -1L));
        final var output = store.get(1).output();
        assertTrue(output.length() <= 16 + "\n... older output truncated".length(), "output=" + output);
        assertTrue(output.endsWith("\n... older output truncated"), output);
    }
}
