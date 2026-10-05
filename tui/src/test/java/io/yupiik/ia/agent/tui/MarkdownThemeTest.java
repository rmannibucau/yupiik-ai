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

import dev.tamboui.style.Color;
import dev.tamboui.style.Modifier;
import dev.tamboui.style.Style;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarkdownThemeTest {
    private final CodeTheme oneDark = CodeTheme.ONE_DARK;
    private final CodeTheme oneLight = CodeTheme.ONE_LIGHT;

    @Test
    void darkThemeStrongIsColoredBoldFromPrimary() {
        final var styles = MarkdownTheme.of(Palette.from("dark", config()), oneDark, null, null);
        final var strong = styles.strong();
        assertEquals(Color.hex("#fab283"), strong.fg().orElseThrow(), "bold must follow the primary color");
        assertTrue(strong.effectiveModifiers().contains(Modifier.BOLD), "bold is required on strong text");
    }

    @Test
    void darkThemeInlineCodeIsColoredBoldWithoutChip() {
        final var styles = MarkdownTheme.of(Palette.from("dark", config()), oneDark, null, null);
        final var code = styles.inlineCode();
        assertEquals(Color.hex(oneDark.string()), code.fg().orElseThrow(), "code follows the code theme string tone");
        assertTrue(code.effectiveModifiers().contains(Modifier.BOLD), "inline code must be bold");
        assertTrue(code.bg().isEmpty(), "inline code has no background chip");
        assertFalse(code.effectiveModifiers().contains(Modifier.DIM), "dim is dropped so code stays visible");
    }

    @Test
    void lightThemeInlineCodeFollowsTheLightCodeTheme() {
        final var styles = MarkdownTheme.of(Palette.from("light", config()), oneLight, null, null);
        assertEquals(Color.hex(oneLight.string()), styles.inlineCode().fg().orElseThrow());
    }

    @Test
    void explicitHexOverridesTheAutoResolution() {
        final var styles = MarkdownTheme.of(Palette.from("dark", config()), oneDark, "#00ff00", "#0000ff");
        assertEquals(Color.hex("#00ff00"), styles.strong().fg().orElseThrow());
        assertEquals(Color.hex("#0000ff"), styles.inlineCode().fg().orElseThrow());
    }

    @Test
    void blankOrAutoValuesFallBackToTheThemeDefaults() {
        final var blank = MarkdownTheme.of(Palette.from("dark", config()), oneDark, "", "  auto  ");
        assertEquals(Color.hex("#fab283"), blank.strong().fg().orElseThrow());
        assertEquals(Color.hex(oneDark.string()), blank.inlineCode().fg().orElseThrow());
    }

    @Test
    void monoThemeIsFullyPlain() {
        final var styles = MarkdownTheme.of(Palette.from("dark", config(), true), oneDark, null, null);
        assertEquals(Style.EMPTY, styles.strong());
        assertEquals(Style.EMPTY, styles.inlineCode());
        assertEquals(Style.EMPTY, styles.link());
        assertEquals(Style.EMPTY, styles.emphasis());
        assertEquals(Style.EMPTY, styles.heading(1));
    }

    @Test
    void renderedMarkdownUsesTheThemedStyles() {
        final var styles = MarkdownTheme.of(Palette.from("dark", config()), oneDark, null, null);
        final var lines = new MarkdownLines().render("**bold** and `code`", 80, Style.EMPTY, styles);
        final var spans = lines.stream().flatMap(l -> l.spans().stream()).toList();
        final var bold = spans.stream()
                .filter(s -> s.content().equals("bold"))
                .findFirst()
                .orElseThrow();
        assertEquals(Color.hex("#fab283"), bold.style().fg().orElseThrow());
        assertTrue(bold.style().effectiveModifiers().contains(Modifier.BOLD));
        final var code = spans.stream()
                .filter(s -> s.content().equals("code"))
                .findFirst()
                .orElseThrow();
        assertEquals(Color.hex(oneDark.string()), code.style().fg().orElseThrow());
        assertTrue(code.style().effectiveModifiers().contains(Modifier.BOLD), "inline code must be bold");
        assertTrue(code.style().bg().isEmpty(), "inline code has no background chip");
    }

    @Test
    void linkListMarkerAndHeadingsUseThePaletteInsteadOfAnsiDefaults() {
        final var styles = MarkdownTheme.of(Palette.from("dark", config()), oneDark, null, null);
        final var link = styles.link();
        assertEquals(Color.hex("#fab283"), link.fg().orElseThrow(), "link follows the primary color");
        assertTrue(link.effectiveModifiers().contains(Modifier.UNDERLINED), "link must be underlined");
        assertEquals(Color.hex("#808080"), styles.listMarker().fg().orElseThrow(), "list markers follow muted");
        final var h1 = styles.heading(1);
        assertEquals(Color.hex("#9d7cd8"), h1.fg().orElseThrow(), "heading 1 follows the accent color");
        assertTrue(h1.effectiveModifiers().contains(Modifier.BOLD), "heading 1 must be bold");
    }

    @Test
    void inlineCodeYieldsToTheRegionForegroundButStaysBold() {
        final var styles = MarkdownTheme.of(Palette.from("dark", config()), oneDark, null, null);
        final var base = Style.EMPTY.fg(Color.rgb(0x6b, 0x6b, 0x6b)).dim();
        final var lines = new MarkdownLines().render("`code`", 80, base, styles);
        final var code = lines.stream()
                .flatMap(l -> l.spans().stream())
                .filter(s -> s.content().equals("code"))
                .findFirst()
                .orElseThrow();
        // like strong, inline code yields to the region tone inside subdued regions
        // but keeps its bold weight so it never disappears into the dimmed prose
        assertEquals(Color.rgb(0x6b, 0x6b, 0x6b), code.style().fg().orElseThrow(), "code fg follows the region base");
        assertTrue(code.style().effectiveModifiers().contains(Modifier.BOLD), "inline code must stay bold");
    }

    @Test
    void boldIsDimmedOverAColoredRegionBase() {
        final var styles = MarkdownTheme.of(Palette.from("dark", config()), oneDark, null, null);
        final var base = Style.EMPTY.fg(Color.rgb(0x6b, 0x6b, 0x6b)).dim();
        final var lines = new MarkdownLines().render("**bold**", 80, base, styles);
        final var bold = lines.stream()
                .flatMap(l -> l.spans().stream())
                .filter(s -> s.content().equals("bold"))
                .findFirst()
                .orElseThrow();
        // the orange strong accent yields to the region tone: same fg as the base, still bold
        assertEquals(Color.rgb(0x6b, 0x6b, 0x6b), bold.style().fg().orElseThrow());
        assertTrue(bold.style().effectiveModifiers().contains(Modifier.BOLD), "bold must stay bold");
    }

    private static TuiConfiguration config() {
        return new TuiConfiguration(
                "#9d7cd8", "#fab283", "#7fd88f", "#f5a742", "#e06c75", "#b896d8", "#eeeeee", "#808080", -1.0, 0.22,
                0.14, 0.85, "#484848", "#606060", "#141414", "auto", "auto", 4000L, 3500L, true, "unicode", "auto",
                "auto", List.of(), null, true, 3, 10, "", "#ff5555");
    }
}
