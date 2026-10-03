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

import dev.tamboui.markdown.MarkdownStyles;
import dev.tamboui.style.Color;
import dev.tamboui.style.Modifier;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MarkdownLinesTest {
    private final MarkdownLines renderer = new MarkdownLines();

    @Test
    void plainParagraph() {
        assertEquals(
                List.of("hello world"),
                plain(renderer.render("hello world", 80, Style.EMPTY, MarkdownStyles.DEFAULTS)));
    }

    @Test
    void singleNewlineIsHardBreak() {
        assertEquals(
                List.of("one", "two"), plain(renderer.render("one\ntwo", 80, Style.EMPTY, MarkdownStyles.DEFAULTS)));
    }

    @Test
    void softWrapsLongParagraph() {
        final var text = "aaaa bbbb cccc dddd eeee ffff gggg hhhh iiii";
        assertEquals(
                List.of("aaaa bbbb", "cccc dddd", "eeee ffff", "gggg hhhh", "iiii"),
                plain(renderer.render(text, 10, Style.EMPTY, MarkdownStyles.DEFAULTS)));
    }

    @Test
    void heading() {
        // block-level rhythm is preserved: one blank row between sibling blocks
        assertEquals(
                List.of("Title", "", "body"),
                plain(renderer.render("# Title\n\nbody", 80, Style.EMPTY, MarkdownStyles.DEFAULTS)));
    }

    @Test
    void blankLineBetweenParagraphs() {
        assertEquals(
                List.of("one", "", "two"),
                plain(renderer.render("one\n\ntwo", 80, Style.EMPTY, MarkdownStyles.DEFAULTS)));
    }

    @Test
    void htmlBlockContentIsNotDropped() {
        // multi-line <think>...</think> parses as an HTML block: its content must be
        // rendered literally, never silently discarded
        final var text = String.join(
                "\n",
                plain(renderer.render(
                        "<think>\nreasoning line\n</think>\n\nAnswer", 60, Style.EMPTY, MarkdownStyles.DEFAULTS)));
        assertTrue(text.contains("reasoning line"), text);
        assertTrue(text.contains("Answer"), text);
    }

    @Test
    void bulletList() {
        assertEquals(
                List.of("• one", "• two"),
                plain(renderer.render("- one\n- two", 80, Style.EMPTY, MarkdownStyles.DEFAULTS)));
    }

    @Test
    void bulletListContinuationIndentsWrappedLine() {
        assertEquals(
                List.of("• alpha beta gamma delta epsilon zeta eta", "  theta iota kappa lambda"),
                plain(renderer.render(
                        "- alpha beta gamma delta epsilon zeta eta theta iota kappa lambda",
                        45,
                        Style.EMPTY,
                        MarkdownStyles.DEFAULTS)));
    }

    @Test
    void orderedList() {
        assertEquals(
                List.of("1. one", "2. two"),
                plain(renderer.render("1. one\n2. two", 80, Style.EMPTY, MarkdownStyles.DEFAULTS)));
    }

    @Test
    void taskList() {
        assertEquals(
                List.of("[x] done", "[ ] todo"),
                plain(renderer.render("- [x] done\n- [ ] todo", 80, Style.EMPTY, MarkdownStyles.DEFAULTS)));
    }

    @Test
    void blockquotePrefixedOnEveryLine() {
        assertEquals(
                List.of("│quoted", "│second"),
                plain(renderer.render("> quoted\n> second", 80, Style.EMPTY, MarkdownStyles.DEFAULTS)));
    }

    @Test
    void thematicBreak() {
        assertEquals(List.of("─────"), plain(renderer.render("---", 5, Style.EMPTY, MarkdownStyles.DEFAULTS)));
    }

    @Test
    void inlineStylesKeepText() {
        assertEquals(
                List.of("the bold italic gone and code here"),
                plain(renderer.render(
                        "the **bold** *italic* ~~gone~~ and `code` here", 80, Style.EMPTY, MarkdownStyles.DEFAULTS)));
    }

    @Test
    void consecutiveSpacesInsideInlineFormattingAreKept() {
        // commonmark preserves 2+ spaces inside/around inline formatting, and the
        // word wrapper must too: a run of k spaces renders as k spaces (the wrapper
        // joins tokens with a single space, so each extra space needs its own empty
        // word token, even when the whole run lives inside one styled span)
        final var cases = Map.of(
                "**a  b**", "a  b",
                "*a  b*", "a  b",
                "~~a  b~~", "a  b",
                "`a  b`", "a  b",
                "**a**  **b**", "a  b",
                "**a**   **b**", "a   b",
                "a  **b**  c", "a  b  c");
        for (final var entry : cases.entrySet()) {
            assertEquals(
                    List.of(entry.getValue()),
                    plain(renderer.render(entry.getKey(), 80, Style.EMPTY, MarkdownStyles.DEFAULTS)),
                    () -> "double space must survive '" + entry.getKey() + "'");
        }
    }

    @Test
    void consecutiveSpacesKeepLengthAtTheWrapBoundary() {
        // the extra space is its own zero-width word: it must neither vanish nor push
        // the next word onto the next row when the line nearly fills the width
        assertEquals(
                List.of("aa b  tail"),
                plain(renderer.render("aa **b**  tail", 10, Style.EMPTY, MarkdownStyles.DEFAULTS)));
    }

    @Test
    void tableRendersBorderedGrid() {
        final var md = """
                | a | bb |
                |---|----|
                | 1 | 22 |
                """;
        assertEquals(
                List.of("┌───┬────┐", "│ a │ bb │", "├───┼────┤", "│ 1 │ 22 │", "└───┴────┘"),
                plain(renderer.render(md, 80, Style.EMPTY, MarkdownStyles.DEFAULTS)));
    }

    @Test
    void tableCellInlineCodeIsKept() {
        // inline code (`Code` nodes have no Text child) must not be dropped from cells
        final var md = """
                | Situation | Type résolu |
                |---|---|
                | Driver donne un type standard (`DECIMAL`, `VARCHAR`, ...) | Mapping direct (BigNumber/Integer/String/...) |
                | Driver ne sait pas (`Types.NULL`, `OTHER`, exotique) | Fallback → String (`ValueMetaBase.java:5349`) |
                | Règle dialecte explicite (SQLite expression, Oracle RAW...) | String voulu |
                """;
        final var text = String.join("\n", plain(renderer.render(md, 120, Style.EMPTY, MarkdownStyles.DEFAULTS)));
        for (final var kept : List.of(
                "DECIMAL",
                "VARCHAR",
                "Types.NULL",
                "OTHER",
                "ValueMetaBase.java:5349",
                "SQLite expression",
                "Oracle RAW",
                "BigNumber/Integer/String",
                "String voulu")) {
            assertTrue(text.contains(kept), () -> "missing " + kept + " in:\n" + text);
        }
        assertFalse(text.contains("`"), text);
    }

    @Test
    void tableCellInlineStylesKeepText() {
        final var md = """
                | a | b |
                |---|---|
                | **bold** *em* [link](https://example.com) | `code` |
                """;
        final var text = String.join("\n", plain(renderer.render(md, 80, Style.EMPTY, MarkdownStyles.DEFAULTS)));
        for (final var kept : List.of("bold", "em", "link", "code")) {
            assertTrue(text.contains(kept), () -> "missing " + kept + " in:\n" + text);
        }
        for (final var gone : List.of("**", "`", "[link]")) {
            assertFalse(text.contains(gone), () -> "unexpected " + gone + " in:\n" + text);
        }
    }

    @Test
    void tableCellInlineCodeKeepsItsStyle() {
        final var md = """
                | Situation | Type résolu |
                |---|---|
                | `DECIMAL` | Mapping `direct` |
                """;
        final var lines = renderer.render(md, 80, Style.EMPTY, MarkdownStyles.DEFAULTS);
        final Span codeSpan = lines.stream()
                .flatMap(l -> l.spans().stream())
                .filter(s -> s.content().equals("DECIMAL"))
                .findFirst()
                .orElseThrow();
        // the theme inline-code style (gray + dim) is preserved on the cell span
        assertEquals(MarkdownStyles.DEFAULTS.inlineCode(), codeSpan.style());
    }

    @Test
    void codeYieldsToTheRegionForegroundWhileLinkKeepsItsColor() {
        final var base = Style.EMPTY.fg(Color.rgb(0x11, 0x22, 0x33));
        final var lines = renderer.render("`code` and [docs](https://example.com)", 80, base, MarkdownStyles.DEFAULTS);
        final var spans = lines.stream().flatMap(l -> l.spans().stream()).toList();
        final var code = spans.stream()
                .filter(s -> s.content().equals("code"))
                .findFirst()
                .orElseThrow();
        // same composition rule as strong: the code accent fills in only on a plain base,
        // inside a colored region the region fg wins (only the dim/italic modifiers stay)
        assertEquals(base.fg(), code.style().fg(), "code fg must yield to a colored base");
        final var link = spans.stream()
                .filter(s -> s.content().equals("docs"))
                .findFirst()
                .orElseThrow();
        assertEquals(MarkdownStyles.DEFAULTS.link().fg(), link.style().fg(), "link fg must survive a colored base");
    }

    @Test
    void strongKeepsTheRegionColorOverAColoredBase() {
        final var base = Style.EMPTY.fg(Color.rgb(0x6b, 0x6b, 0x6b)).dim().italic();
        final var lines = renderer.render("**bold** plain", 80, base, MarkdownStyles.DEFAULTS);
        final var spans = lines.stream().flatMap(l -> l.spans().stream()).toList();
        final var bold = spans.stream()
                .filter(s -> s.content().equals("bold"))
                .findFirst()
                .orElseThrow();
        // inside a colored region bold keeps the region tone (bold but dimmed) instead of
        // overriding it with the strong accent color
        assertEquals(base.fg(), bold.style().fg(), "bold must keep the region color");
        assertTrue(bold.style().effectiveModifiers().contains(Modifier.BOLD), "bold must stay bold");
    }

    @Test
    void strongInAColoredBaseCellKeepsTheRegionColor() {
        final var base = Style.EMPTY.fg(Color.rgb(0x6b, 0x6b, 0x6b)).dim();
        final var lines = renderer.render("| a |\n|---|\n| **bold** |\n", 40, base, MarkdownStyles.DEFAULTS);
        final var bold = lines.stream()
                .flatMap(l -> l.spans().stream())
                .filter(s -> s.content().equals("bold"))
                .findFirst()
                .orElseThrow();
        assertEquals(base.fg(), bold.style().fg(), "cell bold must keep the region color");
        assertTrue(bold.style().effectiveModifiers().contains(Modifier.BOLD), "cell bold must stay bold");
    }

    @Test
    void tableWrapsNarrowGrid() {
        final var md = """
                | name | value       |
                |------|-------------|
                | long | alpha beta  |
                """;
        // the value column is narrowed so "alpha beta" wraps; every row stays within width
        final var lines = plain(renderer.render(md, 18, Style.EMPTY, MarkdownStyles.DEFAULTS));
        for (final var line : lines) {
            assertTrue(line.length() <= 18, line);
        }
        assertTrue(lines.stream().anyMatch(l -> l.contains("alpha")), lines.toString());
        assertTrue(lines.stream().anyMatch(l -> l.contains("beta")), lines.toString());
    }

    @Test
    void markdownLinkShowsText() {
        assertEquals(
                List.of("docs site"),
                plain(renderer.render("[docs site](https://example.com)", 80, Style.EMPTY, MarkdownStyles.DEFAULTS)));
    }

    @Test
    void wrapCodeIsCharacterExact() {
        // no word-aware rewrapping: every character is preserved, overlong rows hard-split
        final var longLine = Line.from("aaaaaaaaaa bbbbbbbbbb cccccccccc");
        assertEquals(
                List.of("aaaaaaaaaa", " bbbbbbbbb", "b cccccccc", "cc"),
                plain(MarkdownLines.wrapCode(List.of(longLine), 10)));
    }

    @Test
    void wrapCodePreservesIndentationAndRuns() {
        final var code = List.of(Line.from("    final var x = 1;    // note"));
        assertEquals(List.of("    final var x = 1;    // note"), plain(MarkdownLines.wrapCode(code, 80)));
    }

    @Test
    void wrapCodeKeepsBlankRows() {
        assertEquals(
                List.of("a", "", "b"),
                plain(MarkdownLines.wrapCode(List.of(Line.from("a"), Line.empty(), Line.from("b")), 80)));
    }

    @Test
    void baseStyleFgSurvivesHeadings() {
        final var dim = Style.EMPTY.fg(Color.rgb(0x6b, 0x6b, 0x6b));
        final var lines = renderer.render("# Title\n\nbody", 80, dim, MarkdownStyles.DEFAULTS);
        final var headingSpan = lines.stream()
                .flatMap(l -> l.spans().stream())
                .filter(s -> s.content().contains("Title"))
                .findFirst()
                .orElseThrow();
        final var rgb = headingSpan.style().fg().orElseThrow().toRgb();
        assertEquals(0x6b, rgb.r());
        assertEquals(0x6b, rgb.g());
        assertEquals(0x6b, rgb.b());
    }

    @Test
    void baseStyleFgSurvivesTables() {
        final var dim = Style.EMPTY.fg(Color.rgb(0x6b, 0x6b, 0x6b));
        final var lines = renderer.render("| a | b |\n|---|---|\n| 1 | 2 |\n", 80, dim, MarkdownStyles.DEFAULTS);
        final var spans = lines.stream().flatMap(l -> l.spans().stream()).toList();
        final var cellA = spans.stream()
                .filter(s -> s.content().strip().equals("a"))
                .findFirst()
                .orElseThrow();
        final var rgb = cellA.style().fg().orElseThrow().toRgb();
        assertEquals(0x6b, rgb.r());
        assertEquals(0x6b, rgb.g());
        assertEquals(0x6b, rgb.b());
    }

    @Test
    void indentedXmlBlockKeepsIndentationVerbatim() {
        final var xml = String.join(
                "\n",
                "Before the block.",
                "",
                "<plan>",
                "    <step>One</step>",
                "    <step>Two</step>",
                "</plan>",
                "",
                "After the block.");
        assertEquals(
                List.of(
                        "Before the block.",
                        "",
                        "<plan>",
                        "    <step>One</step>",
                        "    <step>Two</step>",
                        "</plan>",
                        "",
                        "After the block."),
                plain(renderer.render(xml, 80, Style.EMPTY, MarkdownStyles.DEFAULTS)));
    }

    @Test
    void inlineXmlMentionIsNotTreatedAsAcodeBlock() {
        final var text = "Use the <plan> to organise and the <files> to list, shown inline.";
        assertEquals(
                List.of("Use the <plan> to organise and the <files> to list, shown inline."),
                plain(renderer.render(text, 80, Style.EMPTY, MarkdownStyles.DEFAULTS)));
    }

    @Test
    void loneLessThanLineDoesNotCrash() {
        // a line that trims to "<" alone must not throw (charAt(1) guard)
        assertEquals(List.of("<"), plain(renderer.render("<", 80, Style.EMPTY, MarkdownStyles.DEFAULTS)));
    }

    @Test
    void lessThanInterleavedWithProse() {
        // splitSegments runs isXmlLine per line; a stray "<" among prose must stay prose
        assertEquals(
                List.of("before", "<", "after"),
                plain(renderer.render("before\n<\nafter", 80, Style.EMPTY, MarkdownStyles.DEFAULTS)));
    }

    @Test
    void indentedCodeBlockKeepsIndentationVerbatim() {
        // commonmark's IndentedCodeBlock.getLiteral() strips the common 4-space indent;
        // we must keep the source bytes exactly as written
        final var md = String.join(
                "\n", "Before the block.", "", "    if (a) {", "        b();", "    }", "", "After the block.");
        assertEquals(
                List.of("Before the block.", "", "    if (a) {", "        b();", "    }", "", "After the block."),
                plain(renderer.render(md, 80, Style.EMPTY, MarkdownStyles.DEFAULTS)));
    }

    @Test
    void leadingIndentedLineAtStartIsPreserved() {
        final var md = String.join("\n", "    indented", "", "text");
        assertEquals(
                List.of("    indented", "", "text"),
                plain(renderer.render(md, 80, Style.EMPTY, MarkdownStyles.DEFAULTS)));
    }

    @Test
    void interiorBlankLineInsideIndentedCodeIsKept() {
        final var md = String.join("\n", "", "    first", "", "    second", "");
        assertEquals(
                List.of("", "    first", "", "    second", ""),
                plain(renderer.render(md, 80, Style.EMPTY, MarkdownStyles.DEFAULTS)));
    }

    @Test
    void tinyWidthDoesNotHangOrCrash() {
        // a non-empty code block rendered at width <= 1 must terminate (clamped to 1),
        // wrapping per character instead of hanging or throwing
        final var md = String.join("\n", "```", "abc", "```");
        assertEquals(List.of("a", "b", "c"), plain(renderer.render(md, 1, Style.EMPTY, MarkdownStyles.DEFAULTS)));
        // wrapCode entry point is clamped too
        assertEquals(List.of("a", "b", "c"), plain(MarkdownLines.wrapCode(List.of(Line.from("abc")), 0)));
        assertEquals(List.of("a", "b", "c"), plain(MarkdownLines.wrapCode(List.of(Line.from("abc")), 1)));
    }

    @Test
    void indentedLineUnderListItemIsLeftToTheList() {
        // a nested code block inside a list item must not be stolen as a top-level block
        final var md = String.join("\n", "- item", "", "      nested code", "", "tail");
        final var rendered = String.join("\n", plain(renderer.render(md, 80, Style.EMPTY, MarkdownStyles.DEFAULTS)));
        assertTrue(rendered.contains("nested code"), rendered);
        assertTrue(rendered.contains("item"), rendered);
        assertTrue(rendered.contains("tail"), rendered);
    }

    @Test
    void indentedContinuationAfterParagraphIsNotExtracted() {
        // no blank line before: commonmark treats this as a lazy paragraph continuation
        final var md = String.join("\n", "para", "    indent");
        assertEquals(List.of("para", "indent"), plain(renderer.render(md, 80, Style.EMPTY, MarkdownStyles.DEFAULTS)));
    }

    private static List<String> plain(final List<Line> lines) {
        return lines.stream().map(Line::rawContent).toList();
    }
}
