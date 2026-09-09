package io.yupiik.ia.agent.core.cli;

/**
 * Centralizes the decorative glyphs used in CLI output. Unicode symbols are
 * used by default; switching to ASCII-only output maps every glyph to a
 * portable equivalent so scripts never see non-ASCII characters.
 */
public final class Symbols {
    private static final char[] UNICODE_SPINNER = {'\u280b', '\u2819', '\u2839', '\u2838', '\u283c', '\u2834',
            '\u2826', '\u2827', '\u2807', '\u280f'};
    private static final char[] ASCII_SPINNER = {'|', '/', '-', '\\'};

    private final boolean ascii;

    public Symbols(final boolean ascii) {
        this.ascii = ascii;
    }

    public boolean isAscii() {
        return ascii;
    }

    public String toolMarker() {
        return ascii ? "* " : "\u23fa ";
    }

    public String resultPrefix() {
        return ascii ? "-> " : "\u21b3 ";
    }

    public String thinkingMarker() {
        return ascii ? "~ " : "\u273b ";
    }

    public String infoMarker() {
        return ascii ? "[i] " : "\u2139 ";
    }

    public String currentMarker() {
        return ascii ? "*" : "\u25cf";
    }

    public String todoInProgress() {
        return ascii ? "[>] " : "[\u25b8] ";
    }

    public String todoCompleted() {
        return ascii ? "[x] " : "[\u2713] ";
    }

    public String todoPending() {
        return ascii ? "[ ] " : "[\u00b7] ";
    }

    public String diffAdd() {
        return ascii ? "+ " : "\u2795 ";
    }

    public String diffDel() {
        return ascii ? "- " : "\u2796 ";
    }

    public char[] spinner() {
        return ascii ? ASCII_SPINNER : UNICODE_SPINNER;
    }

    public String boxTop() {
        return ascii ? " +" : " \u250c";
    }

    public String boxBottom() {
        return ascii ? " +" : " \u2514";
    }

    public String boxLine() {
        return ascii ? " | " : " \u2502 ";
    }

    public String boxHorizontal() {
        return ascii ? "-" : "\u2500";
    }

    public String boxCorner() {
        return ascii ? "+" : "\u2510";
    }

    public String boxCornerBottom() {
        return ascii ? "+" : "\u2518";
    }

    public String turnProgress(final int lines, final long elapsedMillis) {
        final var seconds = elapsedMillis / 1000.0;
        if (ascii) {
            return String.format("running... %d lines, %.1fs", lines, seconds);
        }
        return String.format("running\u2026 %d lines, %.1fs", lines, seconds);
    }
}