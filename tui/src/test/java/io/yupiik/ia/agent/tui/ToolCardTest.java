package io.yupiik.ia.agent.tui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolCardTest {
    @Test
    void parsesSuccessCard() {
        final var card = ToolCard.parse("*streaming `bash`: ls -la* (123 ms)\n\n```text\nfile1\nfile2\n```");
        assertEquals("bash", card.name());
        assertEquals("ls -la", card.args());
        assertFalse(card.failed());
        assertEquals(123, card.elapsedMs());
        assertEquals("file1\nfile2", card.output());
    }

    @Test
    void parsesStarlessDuration() {
        final var card = ToolCard.parse("*streaming `git diff`: --stat* (5 ms)\n\n```text\n 1 file changed\n```");
        assertEquals("git diff", card.name());
        assertEquals("--stat", card.args());
        assertEquals(5, card.elapsedMs());
    }

    @Test
    void parsesFailedCard() {
        final var card = ToolCard.parse("> tool `bash` failed: boom (42 ms)");
        assertEquals("bash", card.name());
        assertTrue(card.failed());
        assertEquals("boom", card.args());
        assertEquals(42, card.elapsedMs());
        assertEquals("", card.output());
    }

    @Test
    void failedWithoutDuration() {
        final var card = ToolCard.parse("> tool `read` failed: nope");
        assertTrue(card.failed());
        assertEquals("nope", card.args());
        assertEquals(0, card.elapsedMs());
    }

    @Test
    void nameOnlyWithoutArgs() {
        final var card = ToolCard.parse("*streaming `think`* (1 ms)\n\n```text\nthought\n```");
        assertEquals("think", card.name());
        assertEquals("", card.args());
        assertEquals(1, card.elapsedMs());
    }

    @Test
    void detectsDiff() {
        final var card = ToolCard.parse("*streaming `apply_patch`* (9 ms)\n\n```text\n--- a/File.java\n+++ b/File.java\n@@ -1,3 +1,4 @@\n-old\n+new\n context\n```");
        assertTrue(card.isDiff());
    }

    @Test
    void doesNotDetectPlainOutputAsDiff() {
        final var card = ToolCard.parse("*streaming `bash`* (7 ms)\n\n```text\nhello world\nfoo bar\n```");
        assertFalse(card.isDiff());
    }
}