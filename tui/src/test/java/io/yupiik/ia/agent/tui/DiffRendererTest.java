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

import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import java.util.List;
import org.junit.jupiter.api.Test;

class DiffRendererTest {
    private final DiffRenderer renderer = new DiffRenderer();

    private static String plain(final Line line) {
        final var sb = new StringBuilder();
        for (final var span : line.spans()) {
            sb.append(span.content());
        }
        return sb.toString();
    }

    @Test
    void detectsHunkBasedDiffs() {
        final var diff = """
                @@ -1,2 +1,2 @@
                -old line
                +new line
                """;
        assertTrue(renderer.looksLikeDiff(diff));
        assertFalse(renderer.looksLikeDiff("just some text\nwith + signs\nonly"));
    }

    @Test
    void detectsFileHeaderDiffs() {
        final var diff = """
                --- a/file.txt
                +++ b/file.txt
                -old
                +new
                """;
        assertTrue(renderer.looksLikeDiff(diff));
    }

    @Test
    void rejectsPlainText() {
        assertFalse(renderer.looksLikeDiff("hello world"));
        assertFalse(renderer.looksLikeDiff(""));
    }

    @Test
    void detectsOpenCodeOperationsAndDoesNotDuplicateExistingStats() {
        assertTrue(renderer.looksLikeDiff("*** Begin Patch\n*** Delete File: a\n*** End Patch"));
        assertEquals(
                "@@ section (+1 -1)",
                plain(renderer.render("@@ section (+99 -99)\n-old\n+new", 100, PaletteTest.palette())
                        .getFirst()));
    }

    @Test
    void rendersHunkCounter() {
        final var diff = """
                @@ -1,3 +1,4 @@
                -a
                +b
                 context
                """;
        final var lines = renderer.render(diff, 80, PaletteTest.palette());
        assertTrue(lines.stream().map(DiffRendererTest::plain).anyMatch(l -> l.contains("+1") && l.contains("-1")));
    }

    @Test
    void rendersFileHeaders() {
        final var diff = """
                diff --git a/Foo.java b/Foo.java
                --- a/Foo.java
                +++ b/Foo.java
                -x
                +y
                """;
        final var lines = renderer.render(diff, 80, PaletteTest.palette());
        final var texts = lines.stream().map(DiffRendererTest::plain).toList();
        assertTrue(texts.stream().anyMatch(l -> l.startsWith("diff --git")));
        assertTrue(texts.stream().anyMatch(l -> l.startsWith("+++ ")));
    }

    @Test
    void addsAndDelsKeptColored() {
        final var diff = """
                @@ -1,1 +1,1 @@
                -removed content
                +added content
                """;
        final var lines = renderer.render(diff, 80, PaletteTest.palette());
        final var texts = lines.stream().map(DiffRendererTest::plain).toList();
        assertTrue(texts.stream().anyMatch(l -> l.startsWith(" -") && l.contains("removed content")));
        assertTrue(texts.stream().anyMatch(l -> l.startsWith(" +") && l.contains("added content")));
    }

    @Test
    void longLinesAreClipped() {
        final var diff = "@@ -1 +1 @@\n-" + "x".repeat(200) + "\n+" + "y".repeat(200);
        final var lines = renderer.render(diff, 40, PaletteTest.palette());
        for (final var line : lines) {
            assertTrue(plain(line).length() <= 40);
        }
    }

    @Test
    void contextLinesUntouched() {
        final var lines = renderer.render(" plain context\n", 80, PaletteTest.palette());
        assertEquals(
                List.of(" plain context"),
                lines.stream().map(DiffRendererTest::plain).toList());
    }

    @Test
    void unequalReplacementLengthsEmphasizeEachSidesOwnRange() {
        final var palette = PaletteTest.palette();
        for (final var pair : List.of(List.of("OLD", "LONGER"), List.of("LONGER", "NEW"))) {
            final var lines = renderer.render(
                    "-prefix " + pair.get(0) + " suffix\n+prefix " + pair.get(1) + " suffix", 100, palette);
            assertEquals(
                    pair.get(0),
                    lines.get(0).spans().stream()
                            .filter(span -> span.style()
                                    .equals(palette.style(palette.error())
                                            .bold()
                                            .underlined()))
                            .map(Span::content)
                            .reduce("", String::concat));
            assertEquals(
                    pair.get(1),
                    lines.get(1).spans().stream()
                            .filter(span -> span.style()
                                    .equals(palette.style(palette.success())
                                            .bold()
                                            .underlined()))
                            .map(Span::content)
                            .reduce("", String::concat));
        }
    }

    @Test
    void countersStopAtFileAndOpenCodeOperationBoundaries() {
        final var diff = "--- a/a\n+++ b/a\n@@ first\n-x\n+y\n--- a/b\n+++ b/b\n-z\n+w\n"
                + "*** Update File: c\n@@ second\n-old\n+new\n*** Add File: d\n+extra\n*** End Patch";
        final var texts = renderer.render(diff, 100, PaletteTest.palette()).stream()
                .map(DiffRendererTest::plain)
                .toList();
        assertTrue(texts.contains("@@ first (+1 -1)"), texts.toString());
        assertTrue(texts.contains("@@ second (+1 -1)"), texts.toString());
        assertTrue(texts.contains("*** Update File: c (+1 -1)"), texts.toString());
        assertTrue(texts.contains("*** Add File: d (+1 -0)"), texts.toString());
    }

    @Test
    void emptyDiffRendersNothing() {
        assertTrue(renderer.render("", 80, PaletteTest.palette()).isEmpty());
    }

    @Test
    void spanStyledContentPreserved() {
        final var lines = renderer.render("+kept", 80, PaletteTest.palette());
        assertEquals(1, lines.size());
        final var spans = lines.getFirst().spans();
        assertFalse(spans.isEmpty());
        assertTrue(spans.stream().map(Span::content).reduce("", String::concat).contains("kept"));
    }
}
