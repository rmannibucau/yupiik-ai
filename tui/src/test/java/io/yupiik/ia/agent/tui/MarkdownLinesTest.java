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

import dev.tamboui.markdown.MarkdownStyles;
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
        assertEquals(
                List.of("Title", "body"),
                plain(renderer.render("# Title\n\nbody", 80, Style.EMPTY, MarkdownStyles.DEFAULTS)));
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
    void wrapCodeSplitsLongLines() {
        final var longLine = Line.from("aaaaaaaaaa bbbbbbbbbb cccccccccc");
        assertEquals(
                List.of("aaaaaaaaaa", "bbbbbbbbbb", "cccccccccc"),
                plain(MarkdownLines.wrapCode(List.of(longLine), 10)));
    }

    private static List<String> plain(final List<Line> lines) {
        return lines.stream().map(Line::rawContent).toList();
    }
}
