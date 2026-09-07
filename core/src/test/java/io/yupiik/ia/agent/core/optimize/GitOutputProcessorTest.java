package io.yupiik.ia.agent.core.optimize;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitOutputProcessorTest {
    private final GitOutputProcessor processor = new GitOutputProcessor();

    @Test
    void supportsGitOnly() {
        assertTrue(processor.supports("git status"));
        assertTrue(processor.supports("git -c x=y diff"));
        assertFalse(processor.supports("mvn test"));
    }

    @Test
    void compactsDiff() {
        final var out = processor.process("git diff", List.of(
                "diff --git a/a.txt b/a.txt",
                "index 1111111..2222222 100644",
                "--- a/a.txt",
                "+++ b/a.txt",
                "@@ -1,3 +1,4 @@",
                " one",
                "+two",
                " three").stream()).toList();

        assertTrue(out.contains("diff --git a/a.txt b/a.txt"));
        assertFalse(out.stream().anyMatch(l -> l.startsWith("index ")));
        assertTrue(out.contains("@@ -1,3 +1,4 @@"));
        assertTrue(out.contains("+two"));
        assertTrue(out.stream().anyMatch(l -> l.startsWith("[processed diff of 1 file(s)")));
    }

    @Test
    void diffPassthroughWithStat() {
        final var lines = List.of("diff --git a/a.txt b/a.txt", "index 111..222 100644");
        final var out = processor.process("git diff --stat", lines.stream()).toList();
        assertEquals(lines, out);
    }

    @Test
    void compactsLog() {
        final var out = processor.process("git log", List.of(
                "commit 1234567890abcdef1234567890abcdef12345678",
                "Author: Me <me@localhost>",
                "Date:   Fri Sep 5 10:00:00 2026 +0200",
                "",
                "    fix the thing",
                "    second line",
                "    third line",
                "    fourth line dropped",
                "commit aaa",
                "Author: X <x@localhost>",
                "",
                "    another").stream()).toList();

        assertEquals(6, out.size());
        assertEquals("1234567890abc", out.get(0));
        assertEquals("  fix the thing", out.get(1));
        assertEquals("  second line", out.get(2));
        assertEquals("  third line", out.get(3));
        assertEquals("aaa", out.get(4));
        assertFalse(out.stream().anyMatch(l -> l.contains("fourth line dropped")));
        assertFalse(out.stream().anyMatch(l -> l.startsWith("Author:") || l.startsWith("Date:")));
    }

    @Test
    void filtersStatusHints() {
        final var out = processor.process("git status", List.of(
                "On branch main",
                "",
                "Changes not staged for commit:",
                "  (use \"git add <file>...\" to update what will be committed)",
                "    modified:   a.txt",
                "nothing to commit, working tree clean").stream()).toList();

        assertEquals(3, out.size());
        assertTrue(out.contains("On branch main"));
        assertTrue(out.contains("    modified:   a.txt"));
    }

    @Test
    void statusPorcelainPassthrough() {
        final var lines = List.of("## main...origin/main", " M a.txt");
        final var out = processor.process("git status --porcelain -b", lines.stream()).toList();
        assertEquals(lines, out);
    }

    @Test
    void showKeepsHeaderAndDiff() {
        final var out = processor.process("git show", List.of(
                "commit 1234567890abcdef",
                "Author: Me <me@localhost>",
                "Date:   Fri Sep 5 10:00:00 2026 +0200",
                "",
                "    fix the thing",
                "diff --git a/a.txt b/a.txt",
                "index 1111111..2222222 100644",
                "--- a/a.txt",
                "+++ b/a.txt",
                "@@ -1 +1 @@",
                "-a",
                "+b").stream()).toList();

        assertTrue(out.contains("commit 1234567890abcdef"));
        assertTrue(out.contains("diff --git a/a.txt b/a.txt"));
        assertTrue(out.contains("+b"));
        assertTrue(out.stream().anyMatch(l -> l.startsWith("Author:")));
    }
}
