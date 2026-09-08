package io.yupiik.ia.agent.tui;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyntaxHighlighterTest {
    @Test
    void splitWithoutFenceIsSingleTextSegment() {
        final var segments = SyntaxHighlighter.split("just some text\non two lines");
        assertEquals(1, segments.size());
        assertEquals(SyntaxHighlighter.Segment.Type.TEXT, segments.get(0).type());
        assertEquals("just some text\non two lines", segments.get(0).text());
    }

    @Test
    void splitWithFenceExtractsLanguageAndBody() {
        final var segments = SyntaxHighlighter.split("before\n\n```java\nint a = 1;\n```\n\nafter");
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
        final var segments = SyntaxHighlighter.split("```\nplain code\n```");
        assertEquals(1, segments.size());
        assertEquals(SyntaxHighlighter.Segment.Type.CODE, segments.get(0).type());
        assertEquals("", segments.get(0).language());
        assertEquals("plain code\n", segments.get(0).text());
    }

    @Test
    void splitSkipsBlankTextSegments() {
        final var segments = SyntaxHighlighter.split("```java\nint a;\n```");
        assertEquals(1, segments.size());
        assertEquals(SyntaxHighlighter.Segment.Type.CODE, segments.get(0).type());
    }

    @Test
    void splitHandlesNullAndBlank() {
        assertEquals(List.of(), SyntaxHighlighter.split(null));
        assertEquals(List.of(), SyntaxHighlighter.split("   \n  "));
    }

    @Test
    void highlightProducesOneLinePerSourceLine() {
        final var text = SyntaxHighlighter.highlight("java", "public class A {\n    int a;\n}");
        assertEquals(3, text.height());
        assertTrue(text.rawContent().contains("public"));
    }

    @Test
    void highlightColorsJavaKeywords() {
        final var line = SyntaxHighlighter.highlight("java", "public class A").lines().get(0);
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
        final var line = SyntaxHighlighter.highlight("java", "var s = \"str\";").lines().get(0);
        assertTrue(line.spans().stream().anyMatch(s -> s.content().contains("str") && s.style().fg().isPresent()),
                "expected the string literal to be colored");
    }

    @Test
    void highlightUnknownLanguageStillTokenizes() {
        final var text = SyntaxHighlighter.highlight("cobol", "MOVE A TO B.");
        assertEquals(1, text.height());
        assertFalse(text.isEmpty());
    }

    @Test
    void highlightEmptyCodeIsSafe() {
        assertTrue(SyntaxHighlighter.highlight("java", "").isEmpty());
    }

    @Test
    void highlightShellAndSqlAndXmlDoNotThrow() {
        assertFalse(SyntaxHighlighter.highlight("bash", "echo 'hi' # comment").isEmpty());
        assertFalse(SyntaxHighlighter.highlight("sql", "SELECT * FROM t WHERE x = 1").isEmpty());
        assertFalse(SyntaxHighlighter.highlight("xml", "<a b='c'>text</a>").isEmpty());
        assertFalse(SyntaxHighlighter.highlight("python", "def f(x):\n    return None").isEmpty());
    }
}
