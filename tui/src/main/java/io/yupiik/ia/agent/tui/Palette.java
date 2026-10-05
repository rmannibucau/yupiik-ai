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

import dev.tamboui.style.Color;
import dev.tamboui.style.Style;

/**
 * Resolved color palette. In "dark" theme the configured values are used
 * verbatim; in "light" theme the base foreground/background/border colors are
 * inverted for readability and the accent/status colors are remapped to darker
 * WCAG-safe variants, because the dark-theme tones are too pale on white.
 *
 * <p>When the {@code NO_COLOR} environment variable is set (any non-empty
 * value, see https://no-color.org/), the palette turns monochrome: every
 * style helper resolves to the terminal default so the UI stays usable on
 * limited terminals and for screen-reader pipelines.
 */
public record Palette(
        String accent,
        String primary,
        String success,
        String warning,
        String error,
        String info,
        String text,
        String textMuted,
        String border,
        String borderActive,
        String backgroundPanel,
        String yolo,
        boolean mono) {

    public static Palette from(final String theme, final TuiConfiguration c) {
        return from(theme, c, noColorRequested());
    }

    public static Palette from(final String theme, final TuiConfiguration c, final boolean mono) {
        if (!"light".equalsIgnoreCase(theme)) {
            return new Palette(
                    c.accent(),
                    c.primary(),
                    c.success(),
                    c.warning(),
                    c.error(),
                    c.info(),
                    c.text(),
                    c.textMuted(),
                    c.border(),
                    c.borderActive(),
                    c.backgroundPanel(),
                    c.yolo(),
                    mono);
        }
        // on light backgrounds the theme's dark accent tones are too pale to read (the dark
        // peach/brown-orange sit under 3:1 on white), so the light palette remaps every status
        // color to a darker, WCAG-safe variant while text/border/panel are inverted as today
        return new Palette(
                "#7c5cd6", "#b55227", "#2f7a43", "#8a621f", "#d1383d", "#1f7a88", "#1a1a1a", "#6b6b6b", "#c0c0c0",
                "#808080", "#ffffff", c.yolo(), mono);
    }

    /** @return true when NO_COLOR is set to a non-empty value. */
    public static boolean noColorRequested() {
        final var value = System.getenv("NO_COLOR");
        return value != null && !value.isBlank();
    }

    /** @return the resolved color, or the terminal default in mono mode. */
    public Color color(final String hex) {
        return mono ? Color.RESET : Color.hex(hex);
    }

    /** @return a foreground style, or the plain style in mono mode. */
    public Style style(final String hex) {
        return mono ? Style.EMPTY : Style.EMPTY.fg(Color.hex(hex));
    }

    /** @return a bold foreground style, or the plain style in mono mode. */
    public Style bold(final String hex) {
        return mono ? Style.EMPTY : Style.EMPTY.fg(Color.hex(hex)).bold();
    }

    /** @return the main text style. */
    public Style textStyle() {
        return style(text);
    }

    /** @return the muted (secondary) text style. */
    public Style muted() {
        return style(textMuted);
    }

    /** @return an opacity-blended foreground style over the panel background. */
    public Style blended(final String hex, final double opacity) {
        return mono ? Style.EMPTY : Style.EMPTY.fg(TuiColors.blend(hex, backgroundPanel, opacity));
    }
}
