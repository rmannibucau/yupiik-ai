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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SyntaxHighlighterTest {
    private final SyntaxHighlighter highlighter = new SyntaxHighlighter();

    private final SyntaxHighlighter shortHighlighter = new SyntaxHighlighter().maxLineLength(2000);

    private final SyntaxHighlighter longHighlighter = new SyntaxHighlighter().maxLineLength(50_000);

    private static String concat(final List<dev.tamboui.text.Line> lines) {
        return lines.stream()
                .flatMap(l -> l.spans().stream())
                .map(dev.tamboui.text.Span::content)
                .reduce("", String::concat);
    }

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
        assertEquals("int a = 1;", segments.get(1).text());
        assertEquals(SyntaxHighlighter.Segment.Type.TEXT, segments.get(2).type());
        assertEquals("after", segments.get(2).text().strip());
    }

    @Test
    void splitFenceWithoutLanguageUsesEmptyLanguage() {
        final var segments = highlighter.split("```\nplain code\n```");
        assertEquals(1, segments.size());
        assertEquals(SyntaxHighlighter.Segment.Type.CODE, segments.get(0).type());
        assertEquals("", segments.get(0).language());
        assertEquals("plain code", segments.get(0).text());
    }

    @Test
    void tildesDoNotCloseBacktickFence() {
        // a fence discussing fences: the ~~~ line must not truncate the block
        final var segments = highlighter.split("```java\n\ncode\n~~~\nafter\n```");
        assertEquals(1, segments.size());
        assertEquals(SyntaxHighlighter.Segment.Type.CODE, segments.get(0).type());
        assertEquals("java", segments.get(0).language());
        assertTrue(segments.get(0).text().contains("~~~"), "body must keep the tilde line");
        assertEquals("\ncode\n~~~\nafter", segments.get(0).text());
    }

    @Test
    void tildeFenceIsHighlightedAndClosesOnlyOnTildes() {
        final var segments = highlighter.split("before\n\n~~~sql\nSELECT 1;\n~~~\n\nafter");
        assertEquals(3, segments.size());
        assertEquals(SyntaxHighlighter.Segment.Type.CODE, segments.get(1).type());
        assertEquals("sql", segments.get(1).language());
        assertEquals("SELECT 1;", segments.get(1).text());
    }

    @Test
    void fenceInfoStringMayCarryExtraTokens() {
        final var segments = highlighter.split("```java title=App.java\nint a;\n```");
        assertEquals(1, segments.size());
        assertEquals("java", segments.get(0).language());
        assertEquals("int a;", segments.get(0).text());
    }

    @Test
    void splitSkipsBlankTextSegments() {
        final var segments = highlighter.split("```java\nint a;\n```");
        assertEquals(1, segments.size());
        assertEquals(SyntaxHighlighter.Segment.Type.CODE, segments.get(0).type());
    }

    @Test
    void fenceBodyDropsTheTerminatingNewLine() {
        // the newline closing the last code line belongs to the fence, not to the code: keeping it
        // highlights one phantom trailing empty line, which renders as a blank row after the block
        assertEquals("code", highlighter.split("```\ncode\n```").get(0).text());
        assertEquals("code", highlighter.split("```\ncode\n```\nnext").get(0).text());
        assertEquals("code", highlighter.split("```\ncode\n```").get(0).text());
        // an empty fence body stays empty (no lone "\n" left behind)
        assertEquals("", highlighter.split("```\n```").get(0).text());
        // internal blank lines are source and must survive
        assertEquals(
                "one\n\ntwo", highlighter.split("```\none\n\ntwo\n```").get(0).text());
        assertEquals(
                List.of("one", "", "two"),
                highlighter.highlightLines("", "one\n\ntwo").stream()
                        .map(dev.tamboui.text.Line::rawContent)
                        .toList());
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
    void highlightColorsJavaAnnotations() {
        final var line = highlighter
                .highlight("java", "@Override\n@Deprecated void run()")
                .lines()
                .get(0);
        final Optional<dev.tamboui.style.Color> annotation = line.spans().stream()
                .filter(s -> s.content().equals("@Override"))
                .findFirst()
                .flatMap(s -> s.style().fg());
        assertTrue(annotation.isPresent(), "expected the annotation to carry a foreground color");
        assertEquals(0xc5, annotation.get().toRgb().r());
        assertEquals(0x86, annotation.get().toRgb().g());
        assertEquals(0xc0, annotation.get().toRgb().b());
    }

    @Test
    void highlightColorsJavaTypes() {
        final var line = highlighter
                .highlight("java", "CompletableFuture<String> f;")
                .lines()
                .get(0);
        final Optional<dev.tamboui.style.Color> type = line.spans().stream()
                .filter(s -> s.content().equals("CompletableFuture"))
                .findFirst()
                .flatMap(s -> s.style().fg());
        assertTrue(type.isPresent(), "expected the type to carry a foreground color");
        assertEquals(0x61, type.get().toRgb().r());
        assertEquals(0xaf, type.get().toRgb().g());
        assertEquals(0xef, type.get().toRgb().b());
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

    @Test
    void yamlIsHighlighted() {
        final var lines = highlighter.highlightLines("yaml", "key: value\n# comment\n- item\nflag: true");
        assertEquals(4, lines.size());
        assertTrue(
                lines.stream()
                        .flatMap(l -> l.spans().stream())
                        .anyMatch(s -> s.style().fg().isPresent()),
                "expected at least one styled span");
        final var text = lines.stream()
                .flatMap(l -> l.spans().stream())
                .map(dev.tamboui.text.Span::content)
                .reduce("", String::concat);
        assertTrue(text.contains("key: value"), text);
        assertTrue(text.contains("flag: true"), text);
    }

    @Test
    void highlightLinesReturnsCachedInstanceOnRepeat() {
        final var first = highlighter.highlightLines("java", "public class A {\n    int a;\n}");
        final var second = highlighter.highlightLines("java", "public class A {\n    int a;\n}");
        assertSame(first, second, "identical blocks must reuse the cached result");
        assertSame(first.get(0), second.get(0), "identical lines must reuse the cached line");
        final var other = highlighter.highlightLines("java", "public class B {\n}");
        assertNotSame(first, other, "a different block must not alias the cache entry");
    }

    @Test
    void splitReturnsCachedInstanceOnRepeat() {
        final var first = highlighter.split("```java\nint a;\n```");
        final var second = highlighter.split("```java\nint a;\n```");
        assertSame(first, second, "identical markdown must reuse the cached segments");
    }

    @Test
    void themeChangeInvalidatesCachedHighlights() {
        final var oneDark = highlighter.highlightLines("java", "public class A");
        highlighter.theme(CodeTheme.ONE_LIGHT);
        final var oneLight = highlighter.highlightLines("java", "public class A");
        assertNotSame(oneDark, oneLight, "a theme change must re-tokenize cached blocks");
        final Optional<dev.tamboui.style.Color> keyword = oneLight.get(0).spans().stream()
                .filter(s -> s.content().equals("public"))
                .findFirst()
                .flatMap(s -> s.style().fg());
        assertTrue(keyword.isPresent(), "expected the keyword to carry a foreground color");
        assertEquals(0xa6, keyword.get().toRgb().r());
        assertEquals(0x26, keyword.get().toRgb().g());
        assertEquals(0xa4, keyword.get().toRgb().b());
    }

    @Test
    void maxLineLengthChangeInvalidatesCachedHighlights() {
        final var longLine = "var s = \"x\"; " + "a".repeat(10_500);
        final var plain = shortHighlighter.highlightLines("java", longLine).get(0);
        assertEquals(1, plain.spans().size());
        assertEquals(longLine, concat(List.of(plain)));
        shortHighlighter.maxLineLength(20_000);
        final var tokenized = shortHighlighter.highlightLines("java", longLine).get(0);
        assertNotSame(plain, tokenized, "a maxLineLength change must re-tokenize cached blocks");
        assertTrue(tokenized.spans().size() > 1, "expected tokenization after the maxLineLength bump");
    }

    @Test
    void keywordsDoNotMatchInsideWords() {
        final var publicity = highlighter.highlightLines("java", "publicity").get(0);
        assertFalse(
                publicity.spans().stream().anyMatch(s -> s.content().equals("public")),
                "public must not match inside publicity");
        assertEquals("publicity", concat(List.of(publicity)));

        final var index = highlighter.highlightLines("typescript", "index").get(0);
        assertFalse(index.spans().stream().anyMatch(s -> s.content().equals("in")), "in must not match inside index");
        assertEquals("index", concat(List.of(index)));

        final var print = highlighter.highlightLines("java", "print").get(0);
        assertFalse(print.spans().stream().anyMatch(s -> s.content().equals("int")), "int must not match inside print");
        assertEquals("print", concat(List.of(print)));

        final var forLine =
                highlighter.highlightLines("typescript", "for (const x in y)").get(0);
        final var inCount =
                forLine.spans().stream().filter(s -> s.content().equals("in")).count();
        assertEquals(1, inCount, "keyword in must be matched exactly once");
        assertEquals("for (const x in y)", concat(List.of(forLine)));
    }

    @Test
    void attributesAreStillHighlightedAfterPossessiveStar() {
        final var xml = highlighter.highlightLines("xml", "<a id=\"x\"></a>").get(0);
        assertTrue(
                xml.spans().stream()
                        .anyMatch(
                                s -> s.content().equals("id=") && s.style().fg().isPresent()),
                "expected the id= attribute to be colored");
        final var assignment = highlighter.highlightLines("javascript", "a=b;").get(0);
        assertTrue(
                assignment.spans().stream()
                        .anyMatch(
                                s -> s.content().equals("a=") && s.style().fg().isPresent()),
                "expected the a= attribute to be colored");
    }

    @Test
    void highlightLinesIsThreadSafe() {
        final var errors = new java.util.concurrent.ConcurrentLinkedQueue<Throwable>();
        final var threads = new java.util.ArrayList<Thread>();
        for (var i = 0; i < 8; i++) {
            final var toggle = i % 2 == 0;
            threads.add(new Thread(() -> {
                for (var j = 0; j < 250; j++) {
                    try {
                        if (toggle && j % 50 == 0) {
                            highlighter.theme(j % 100 == 0 ? CodeTheme.ONE_LIGHT : CodeTheme.ONE_DARK);
                        }
                        final var lines = highlighter.highlightLines("java", "public class A { int a; }");
                        assertEquals(
                                "public class A { int a; }",
                                concat(lines),
                                "concurrent tokenization corrupted the line");
                        final var segments = highlighter.split("```java\nint a;\n```");
                        assertEquals(
                                SyntaxHighlighter.Segment.Type.CODE,
                                segments.get(0).type());
                    } catch (final Throwable t) {
                        errors.add(t);
                    }
                }
            }));
        }
        threads.forEach(Thread::start);
        for (final var t : threads) {
            try {
                t.join();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted", e);
            }
        }
        assertTrue(errors.isEmpty(), () -> "concurrent highlight failed: " + errors);
    }

    @Test
    void messyLongLineRendersWithinBudget() {
        assertTimeoutPreemptively(
                Duration.ofSeconds(5),
                () -> {
                    final var line = "\"a\\\"b\" key=value 010203040506070809 "
                            .repeat(700)
                            .concat("a".repeat(9_000));
                    final var lines = longHighlighter.highlightLines("java", line);
                    assertEquals(line, concat(lines));
                },
                "the possessive-quantified tokenizer must stay linear on messy long lines");
    }

    @Test
    void guessLanguageMapsExtensionsAndIgnoresUnknown() {
        assertEquals("java", SyntaxHighlighter.guessLanguage("src/main/java/Foo.java"));
        assertEquals("java", SyntaxHighlighter.guessLanguage("Foo.KT"), "extensions are case-insensitive");
        assertEquals("xml", SyntaxHighlighter.guessLanguage("pom.xml"));
        assertEquals("json", SyntaxHighlighter.guessLanguage("/data/config.json5"));
        assertEquals("yaml", SyntaxHighlighter.guessLanguage("build.yaml"));
        assertEquals("javascript", SyntaxHighlighter.guessLanguage("app.js"));
        assertEquals("typescript", SyntaxHighlighter.guessLanguage("app.ts"));
        assertEquals("python", SyntaxHighlighter.guessLanguage("script.py"));
        assertEquals("shell", SyntaxHighlighter.guessLanguage("run.sh"));
        assertEquals("sql", SyntaxHighlighter.guessLanguage("query.sql"));
        assertEquals("markdown", SyntaxHighlighter.guessLanguage("README.md"));
        assertNull(SyntaxHighlighter.guessLanguage("Makefile"), "extension-less files are unknown");
        assertNull(SyntaxHighlighter.guessLanguage("noext"));
        assertNull(SyntaxHighlighter.guessLanguage(null));
        assertNull(SyntaxHighlighter.guessLanguage(""));
    }
}
