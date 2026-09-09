package io.yupiik.ia.agent.tui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptHistoryTest {
    @Test
    void storesMostRecentFirstDeduplicated() {
        final var h = new PromptHistory(10);
        h.add("first");
        h.add("second");
        h.add("first");
        assertEquals(3, h.size());
        assertEquals("first", h.entries().get(0));
        h.add("first");
        assertEquals(3, h.size(), "consecutive duplicates are dropped");
    }

    @Test
    void ignoresBlank() {
        final var h = new PromptHistory(10);
        h.add("   ");
        h.add("");
        assertEquals(0, h.size());
    }

    @Test
    void olderPullWalksHistoryAndRestoresDraft() {
        final var h = new PromptHistory(10);
        h.add("a");
        h.add("b");
        h.add("c");
        assertEquals("c", h.olderPush("draft"));
        assertEquals("b", h.olderPush("draft"));
        assertEquals("a", h.olderPush("draft"));
        assertNull(h.olderPush("draft"), "no more older entries");
    }

    @Test
    void newerPullWalksBackDownToDraft() {
        final var h = new PromptHistory(10);
        h.add("a");
        h.add("b");
        assertEquals("b", h.olderPush("draft"));
        assertEquals("a", h.olderPush("draft"));
        assertEquals("b", h.newerPull("draft"));
        assertEquals("draft", h.newerPull("draft"), "restores the in-progress draft at baseline");
    }

    @Test
    void addWhileNavigatingResets() {
        final var h = new PromptHistory(10);
        h.add("a");
        h.add("b");
        h.olderPush("draft");
        assertTrue(h.navigating());
        h.add("c");
        assertTrue(!h.navigating(), "add resets navigation");
    }

    @Test
    void capsSize() {
        final var h = new PromptHistory(3);
        h.add("1");
        h.add("2");
        h.add("3");
        h.add("4");
        assertEquals(3, h.size());
        assertEquals("4", h.entries().get(0));
    }

    @Test
    void persistsRoundTrip(@TempDir final Path dir) {
        final var file = dir.resolve("history.txt");
        final var h = new PromptHistory(10);
        h.add("one");
        h.add("two");
        h.save(file);
        final var loaded = PromptHistory.load(file, 10);
        assertEquals(2, loaded.size());
        assertEquals("two", loaded.entries().get(0));
        assertEquals("one", loaded.entries().get(1));
    }

    @Test
    void loadMissingFileIsEmpty(@TempDir final Path dir) {
        final var loaded = PromptHistory.load(dir.resolve("nope.txt"), 10);
        assertEquals(0, loaded.size());
    }
}