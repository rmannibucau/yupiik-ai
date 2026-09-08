package io.yupiik.ia.agent.tui;

import dev.tamboui.style.Color;

public final class TuiColors {
    private TuiColors() {
    }

    public static Color blend(final String fgHex, final String bgHex, final double opacity) {
        final var fg = parseHex(fgHex);
        final var bg = parseHex(bgHex);
        return Color.rgb(
                (int) Math.round(fg[0] * opacity + bg[0] * (1 - opacity)),
                (int) Math.round(fg[1] * opacity + bg[1] * (1 - opacity)),
                (int) Math.round(fg[2] * opacity + bg[2] * (1 - opacity)));
    }

    public static int[] parseHex(final String hex) {
        final var clean = hex.strip();
        final var start = clean.startsWith("#") ? 1 : 0;
        return new int[]{
                Integer.parseInt(clean.substring(start, start + 2), 16),
                Integer.parseInt(clean.substring(start + 2, start + 4), 16),
                Integer.parseInt(clean.substring(start + 4, start + 6), 16)};
    }
}
