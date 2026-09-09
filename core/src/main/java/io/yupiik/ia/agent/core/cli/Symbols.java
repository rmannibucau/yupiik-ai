package io.yupiik.ia.agent.core.cli;

/**
 * Centralizes the decorative glyphs used in CLI output. Unicode symbols are
 * used by default; switching to ASCII-only output maps every glyph to a
 * portable equivalent so scripts never see non-ASCII characters.
 */
public final class Symbols {
    private static final char[] UNICODE_SPINNER = {'⠋', '⠙', '⠹', '⠸', '⠼',
            '⠴', '⠦', '⠧', '⠇', '⠏'};
    private static final char[] ASCII_SPINNER = {'|', '/', '-', '\\'};

    private final boolean ascii;

    public Symbols(final boolean ascii) {
        this.ascii = ascii;
    }

    public String toolMarker() {
        return ascii ? "* " : "⏺ ";
    }

    public String resultPrefix() {
        return ascii ? "-> " : "↳ ";
    }

    public String infoMarker() {
        return ascii ? "[i] " : "ℹ ";
    }

    public String currentMarker() {
        return ascii ? "*" : "●";
    }

    public String todoInProgress() {
        return ascii ? "[>] " : "[▸] ";
    }

    public String todoCompleted() {
        return ascii ? "[x] " : "[✓] ";
    }

    public String todoPending() {
        return ascii ? "[ ] " : "[·] ";
    }

    public String diffAdd() {
        return ascii ? "+ " : "➕ ";
    }

    public String diffDel() {
        return ascii ? "- " : "➖ ";
    }

    public char[] spinner() {
        return ascii ? ASCII_SPINNER : UNICODE_SPINNER;
    }

    public String boxTop() {
        return ascii ? " +" : " ┌";
    }

    public String boxBottom() {
        return ascii ? " +" : " └";
    }

    public String boxLine() {
        return ascii ? " | " : " │ ";
    }

    public String boxHorizontal() {
        return ascii ? "-" : "─";
    }

    public String boxCorner() {
        return ascii ? "+" : "┐";
    }

    public String boxCornerBottom() {
        return ascii ? "+" : "┘";
    }
}