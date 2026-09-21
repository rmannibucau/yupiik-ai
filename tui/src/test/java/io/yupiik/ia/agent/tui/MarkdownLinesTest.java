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
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tamboui.markdown.MarkdownStyles;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import java.util.List;
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
    void tableRendersGrid() {
        final var md = """
                | a | bb |
                |---|----|
                | 1 | 22 |
                """;
        assertEquals(List.of("a  bb", "1  22"), plain(renderer.render(md, 80, Style.EMPTY, MarkdownStyles.DEFAULTS)));
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
        final var cellA =
                spans.stream().filter(s -> s.content().equals("a")).findFirst().orElseThrow();
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

    private static List<String> plain(final List<Line> lines) {
        return lines.stream().map(Line::rawContent).toList();
    }
}
