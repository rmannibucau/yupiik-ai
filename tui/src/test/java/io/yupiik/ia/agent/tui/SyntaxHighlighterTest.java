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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SyntaxHighlighterTest {
    private final SyntaxHighlighter highlighter = new SyntaxHighlighter();

    private final SyntaxHighlighter shortHighlighter = new SyntaxHighlighter().maxLineLength(2000);

    @Test
    void splitWithoutFenceIsSingleTextSegment() {
        final var segments = highlighter.split("just some text\non two lines");
        assertEquals(1, segments.size());
        assertEquals(SyntaxHighlighter.Segment.Type.TEXT, segments.get(0).type());
        assertEquals("just some text\non two lines", segments.get(0).text());
    }

    @Test
    void splitWithFenceExtractsLanguageAndBody() {
        final var segments = highlighter.split("before\n\n```java\nint a = 1;\n```\n\nafter");
        assertEquals(3, segments.size());
        assertEquals(SyntaxHighlighter.Segment.Type.TEXT, segments.get(0).type());
        assertEquals("before", segments.get(0).text().strip());
        assertEquals(SyntaxHighlighter.Segment.Type.CODE, segments.get(1).type());
        assertEquals("java", segments.get(1).language());
        assertEquals("int a = 1;\n", segments.get(1).text());
        assertEquals(SyntaxHighlighter.Segment.Type.TEXT, segments.get(2).type());
        assertEquals("after", segments.get(2).text().strip());
    }

    @Test
    void splitFenceWithoutLanguageUsesEmptyLanguage() {
        final var segments = highlighter.split("```\nplain code\n```");
        assertEquals(1, segments.size());
        assertEquals(SyntaxHighlighter.Segment.Type.CODE, segments.get(0).type());
        assertEquals("", segments.get(0).language());
        assertEquals("plain code\n", segments.get(0).text());
    }

    @Test
    void splitSkipsBlankTextSegments() {
        final var segments = highlighter.split("```java\nint a;\n```");
        assertEquals(1, segments.size());
        assertEquals(SyntaxHighlighter.Segment.Type.CODE, segments.get(0).type());
    }

    @Test
    void splitHandlesNullAndBlank() {
        assertEquals(List.of(), highlighter.split(null));
        assertEquals(List.of(), highlighter.split("   \n  "));
    }

    @Test
    void highlightProducesOneLinePerSourceLine() {
        final var text = highlighter.highlight("java", "public class A {\n    int a;\n}");
        assertEquals(3, text.height());
        assertTrue(text.rawContent().contains("public"));
    }

    @Test
    void highlightColorsJavaKeywords() {
        final var line = highlighter.highlight("java", "public class A").lines().get(0);
        final Optional<dev.tamboui.style.Color> keyword = line.spans().stream()
                .filter(s -> s.content().equals("public"))
                .findFirst()
                .flatMap(s -> s.style().fg());
        assertTrue(keyword.isPresent(), "expected the keyword to carry a foreground color");
        assertEquals(0xc6, keyword.get().toRgb().r());
        assertEquals(0x78, keyword.get().toRgb().g());
        assertEquals(0xdd, keyword.get().toRgb().b());
    }

    @Test
    void highlightColorsStrings() {
        final var line =
                highlighter.highlight("java", "var s = \"str\";").lines().get(0);
        assertTrue(
                line.spans().stream()
                        .anyMatch(s ->
                                s.content().contains("str") && s.style().fg().isPresent()),
                "expected the string literal to be colored");
    }

    @Test
    void highlightUnknownLanguageStillTokenizes() {
        final var text = highlighter.highlight("cobol", "MOVE A TO B.");
        assertEquals(1, text.height());
        assertFalse(text.isEmpty());
    }

    @Test
    void highlightEmptyCodeIsSafe() {
        assertTrue(highlighter.highlight("java", "").isEmpty());
    }

    @Test
    void highlightShellAndSqlAndXmlDoNotThrow() {
        assertFalse(highlighter.highlight("bash", "echo 'hi' # comment").isEmpty());
        assertFalse(highlighter.highlight("sql", "SELECT * FROM t WHERE x = 1").isEmpty());
        assertFalse(highlighter.highlight("xml", "<a b='c'>text</a>").isEmpty());
        assertFalse(
                highlighter.highlight("python", "def f(x):\n    return None").isEmpty());
    }

    @Test
    void escapedStringsAreStillHighlightedAsStrings() {
        final var escaped = highlighter.highlight("java", "var s = \"a\\\"b\";").lines().get(0).spans().stream()
                .filter(s -> s.content().contains("a"))
                .filter(s -> s.content().contains("b"))
                .findFirst();
        assertTrue(escaped.isPresent(), "expected the escaped string to be present");
        assertTrue(escaped.get().style().fg().isPresent(), "expected the escaped string literal to be colored");
    }

    @Test
    void unclosedEscapedStringDoesNotThrow() {
        for (final var q : new String[] {"\"", "'", "`"}) {
            assertDoesNotThrow(() -> shortHighlighter.highlight("python", q + "\\\\".repeat(9_000)));
            assertDoesNotThrow(() -> shortHighlighter.highlight("javascript", q + "a".repeat(9_000)));
        }
    }

    @Test
    void unclosedMultiLineCommentDoesNotThrow() {
        assertDoesNotThrow(() -> shortHighlighter.highlight("java", "/*" + "a".repeat(9_000)));
        assertDoesNotThrow(() -> shortHighlighter.highlight("xml", "<!--" + "a".repeat(9_000)));
    }

    @Test
    void overLongLineFallsBackToPlain() {
        final var longLine = "var s = \"x\"; " + "a".repeat(10_500);
        final var line = shortHighlighter.highlight("java", longLine).lines().get(0);
        assertEquals(1, line.spans().size());
        assertEquals(longLine, line.spans().get(0).content());
        assertTrue(line.spans().get(0).style().fg().isEmpty(), "over-long line should not be tokenized");
    }
}
