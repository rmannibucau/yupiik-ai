package io.yupiik.ia.agent.core.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownColorizerTest {
    @Test
    void disabledColorizerReturnsInputUnchanged() {
        final var colorizer = new MarkdownColorizer(false);
        assertEquals("# Title", colorizer.colorizeLine("# Title"));
        assertEquals("- item", colorizer.colorizeLine("- item"));
        assertEquals("final var i = 0;", colorizer.colorizeLine("final var i = 0;"));
    }

    @Test
    void headingsAndInlineCodeAreColored() {
        final var colorizer = new MarkdownColorizer(true);
        final var heading = colorizer.colorizeLine("# Title");
        assertTrue(heading.contains("Title"));
        assertTrue(heading.contains("\u001b[1m"));
        final var bullet = colorizer.colorizeLine("- use `yca cli` today");
        assertTrue(bullet.contains("`yca cli`"));
        assertTrue(bullet.contains("\u001b["));
    }

    @Test
    void fencedCodeStateIsTrackedPerLine() {
        final var colorizer = new MarkdownColorizer(true);
        assertEquals("```java", colorizer.colorizeLine("```java"));
        assertTrue(colorizer.colorizeLine("final var s = \"x\"; // comment").contains("\u001b["));
        assertEquals("```", colorizer.colorizeLine("```"));
        assertEquals("plain again", colorizer.colorizeLine("plain again"));
    }

    @Test
    void blockquotesAndBoldAndLinksAreRendered() {
        final var colorizer = new MarkdownColorizer(true);
        assertTrue(colorizer.colorizeLine("> quoted").contains("quoted"));
        assertTrue(colorizer.colorizeLine("a **bold** word").contains("\u001b[1mbold\u001b[0m"));
        assertTrue(colorizer.colorizeLine("see [docs](https://yupiik.io)").contains("https://yupiik.io"));
    }

    @Test
    void styleHelpersWrapWithSgr() {
        assertTrue(MarkdownColorizer.success("ok").startsWith("\u001b["));
        assertTrue(MarkdownColorizer.success("ok").endsWith("\u001b[0m"));
        assertTrue(MarkdownColorizer.diffAdd("+ x").startsWith("\u001b["));
        assertTrue(MarkdownColorizer.diffDel("- x").startsWith("\u001b["));
        assertTrue(MarkdownColorizer.dim("x").startsWith("\u001b[2m"));
        assertTrue(MarkdownColorizer.thinking("x").startsWith("\u001b[38;5;240m"));
        assertTrue(MarkdownColorizer.thinking("x").endsWith("\u001b[0m"));
        assertTrue(MarkdownColorizer.info("id").startsWith("\u001b[38;5;253m"),
                "info uses the bright body grey so user-facing ids are legible, unlike the muted status()");
        assertTrue(MarkdownColorizer.info("id").endsWith("\u001b[0m"));
    }

    @Test
    void disabledColorizerLeavesNewStyleOutputUnstyled() {
        final var colorizer = new MarkdownColorizer(false);
        assertEquals("final var i = 0;", colorizer.colorizeLine("final var i = 0;"));
        assertEquals("# Title", colorizer.colorizeLine("# Title"));
    }
}
