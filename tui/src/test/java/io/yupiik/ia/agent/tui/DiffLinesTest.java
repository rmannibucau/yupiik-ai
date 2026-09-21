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

class DiffLinesTest {
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
        assertTrue(DiffLines.looksLikeDiff(diff));
        assertFalse(DiffLines.looksLikeDiff("just some text\nwith + signs\nonly"));
    }

    @Test
    void detectsFileHeaderDiffs() {
        final var diff = """
                --- a/file.txt
                +++ b/file.txt
                -old
                +new
                """;
        assertTrue(DiffLines.looksLikeDiff(diff));
    }

    @Test
    void rejectsPlainText() {
        assertFalse(DiffLines.looksLikeDiff("hello world"));
        assertFalse(DiffLines.looksLikeDiff(""));
    }

    @Test
    void rendersHunkCounter() {
        final var diff = """
                @@ -1,3 +1,4 @@
                -a
                +b
                 context
                """;
        final var lines = DiffLines.render(diff, 80, PaletteTest.palette());
        assertTrue(lines.stream().map(DiffLinesTest::plain).anyMatch(l -> l.contains("+1") && l.contains("-1")));
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
        final var lines = DiffLines.render(diff, 80, PaletteTest.palette());
        final var texts = lines.stream().map(DiffLinesTest::plain).toList();
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
        final var lines = DiffLines.render(diff, 80, PaletteTest.palette());
        final var texts = lines.stream().map(DiffLinesTest::plain).toList();
        assertTrue(texts.stream().anyMatch(l -> l.startsWith(" -") && l.contains("removed content")));
        assertTrue(texts.stream().anyMatch(l -> l.startsWith(" +") && l.contains("added content")));
    }

    @Test
    void longLinesAreClipped() {
        final var diff = "@@ -1 +1 @@\n-" + "x".repeat(200) + "\n+" + "y".repeat(200);
        final var lines = DiffLines.render(diff, 40, PaletteTest.palette());
        for (final var line : lines) {
            assertTrue(plain(line).length() <= 40);
        }
    }

    @Test
    void contextLinesUntouched() {
        final var lines = DiffLines.render(" plain context\n", 80, PaletteTest.palette());
        assertEquals(
                List.of(" plain context"),
                lines.stream().map(DiffLinesTest::plain).toList());
    }

    @Test
    void emptyDiffRendersNothing() {
        assertTrue(DiffLines.render("", 80, PaletteTest.palette()).isEmpty());
    }

    @Test
    void spanStyledContentPreserved() {
        final var lines = DiffLines.render("+kept", 80, PaletteTest.palette());
        assertEquals(1, lines.size());
        final var spans = lines.getFirst().spans();
        assertFalse(spans.isEmpty());
        assertTrue(spans.stream().map(Span::content).reduce("", String::concat).contains("kept"));
    }
}
