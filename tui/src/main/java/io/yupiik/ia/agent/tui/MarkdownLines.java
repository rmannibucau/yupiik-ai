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

import dev.tamboui.markdown.MarkdownStyles;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.commonmark.ext.gfm.strikethrough.Strikethrough;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableBody;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableHead;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.ext.task.list.items.TaskListItemMarker;
import org.commonmark.ext.task.list.items.TaskListItemsExtension;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;
import org.commonmark.node.ThematicBreak;
import org.commonmark.parser.Parser;

/**
 * Renders a markdown text segment to pre-wrapped {@link Line} objects so the
 * visible output is exactly the selectable/copyable text and no terminal renderer
 * decides wrapping after the fact. Fenced code blocks are extracted by
 * {@link SyntaxHighlighter#split} before this renderer runs, so the remaining text
 * may still contain headings, paragraphs, lists, blockquotes, tables and inline
 * styles.
 *
 * <p>Parsing delegates to commonmark (the same engine tamboui uses) with the GFM
 * tables, task-list and strikethrough extensions enabled. Styling comes from an
 * existing {@link MarkdownStyles} theme (default or the tool-muted variant)
 * combined with a base {@link Style}, matching how tamboui applied styles before.
 *
 * <p>This renderer is pure - no terminal, buffer or {@code TuiCommand} dependency -
 * so it is unit-testable without a display. A single {@code \n} is a hard line
 * break (each source line renders on its own row), matching the previous behaviour
 * of promoting single newlines.
 */
public final class MarkdownLines {
    private final Parser parser;
    /** Stateless renderer; held as a shared instance (the render chain below is static). */
    private static final TableRenderer TABLE_RENDERER = new TableRenderer();

    public MarkdownLines() {
        parser = Parser.builder()
                .extensions(List.of(
                        StrikethroughExtension.create(), TablesExtension.create(), TaskListItemsExtension.create()))
                .build();
    }

    /**
     * Renders {@code markdown} into pre-wrapped, explicitly-styled lines.
     *
     * @param markdown the markdown source (a text segment; fenced code excluded)
     * @param width    the maximum available width per line
     * @param base     the base style applied to plain text
     * @param styles   the markdown theme (default or tool-muted)
     * @return the final lines, each not wider than {@code width}
     */
    public List<Line> render(final String markdown, final int width, final Style base, final MarkdownStyles styles) {
        final var out = new ArrayList<Line>();
        final var codeStyle =
                styles.codeBlock() == null ? base : styles.codeBlock().patch(base);
        for (final var segment : splitSegments(markdown)) {
            if (segment.block()) {
                emitLiteral(segment.text(), codeStyle, width, out);
            } else {
                final var root = parser.parse(segment.text());
                renderNode(root, Prefix.NONE, base, styles, width, false, out);
            }
        }
        return out;
    }

    /**
     * Splits {@code markdown} into prose/markdown segments and character-exact block segments.
     * A bare XML block is a contiguous run of two or more lines each starting (after whitespace)
     * with an XML tag. An indented code block is a contiguous run of two or more lines each
     * starting with four or more whitespace characters (blank lines inside the run are kept).
     * Both are delimited by blank lines or the input boundaries. Because custom tags like
     * {@code <plan>} or {@code <files>} are not block-level HTML in commonmark, and because
     * commonmark dedents indented code blocks, they would otherwise parse as a paragraph (or a
     * dedented code block) and have their indentation collapsed; extracting them here and
     * rendering through the character-exact code path keeps the structure intact while leaving
     * ordinary prose untouched.
     */
    private static List<Segment> splitSegments(final String markdown) {
        final var lines = markdown == null || markdown.isEmpty() ? new String[0] : markdown.split("\n", -1);
        final var blocks = new ArrayList<int[]>();
        var i = 0;
        while (i < lines.length) {
            // an XML-looking line is only claimed by the XML branch when it forms a
            // valid XML block; otherwise (e.g. code that happens to start with "<div>")
            // it falls through to the indented-code branch below
            if (isXmlLine(lines[i])) {
                var j = i + 1;
                while (j < lines.length && isXmlLine(lines[j])) {
                    j++;
                }
                if (isValidBlock(lines, i, j, 2)) {
                    blocks.add(new int[] {i, j});
                    i = j;
                    continue;
                }
            }
            if (isIndentedCodeLine(lines[i]) && !precededByListItem(lines, i)) {
                var j = i + 1;
                // an interior blank line stays in the code run (commonmark absorbs it too)
                while (j < lines.length && (isIndentedCodeLine(lines[j]) || lines[j].isBlank())) {
                    j++;
                }
                // stop right after the last real code line so the blank that closes the
                // block stays put for the delimiter bookkeeping below
                while (j > i + 1 && lines[j - 1].isBlank()) {
                    j--;
                }
                // unlike a paragraph interruption rule we only need a leading delimiter
                // (or start of input): commonmark also ends a code block on the first
                // non-indented line without requiring a blank after it
                final var precededByBlank = i == 0 || lines[i - 1].isBlank();
                if (precededByBlank && (j - i) >= 1) {
                    blocks.add(new int[] {i, j});
                    i = j;
                    continue;
                }
            }
            i++;
        }
        final var out = new ArrayList<Segment>();
        var cursor = 0;
        for (final var block : blocks) {
            if (block[0] > cursor) {
                out.add(new Segment(join(lines, cursor, block[0] - 1), false));
            }
            final var literal = new StringBuilder();
            if (block[0] > 0) {
                // keep the delimiting blank row so sibling blocks keep their rhythm
                literal.append('\n');
            }
            literal.append(join(lines, block[0], block[1]));
            if (block[1] < lines.length) {
                literal.append('\n');
            }
            out.add(new Segment(literal.toString(), true));
            cursor = block[1] + (block[1] < lines.length && lines[block[1]].isBlank() ? 1 : 0);
        }
        if (cursor < lines.length) {
            out.add(new Segment(join(lines, cursor, lines.length), false));
        }
        return out.isEmpty() && !markdown.isEmpty() ? List.of(new Segment(markdown, false)) : out;
    }

    private static String join(final String[] lines, final int from, final int to) {
        final var sb = new StringBuilder();
        for (var idx = from; idx < to; idx++) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(lines[idx]);
        }
        return sb.toString();
    }

    /** True when the line starts (after optional leading whitespace) with an XML tag. */
    private static boolean isXmlLine(final String line) {
        final var t = line.trim();
        if (t.isEmpty() || t.charAt(0) != '<') {
            return false;
        }
        final var idx = t.length() > 1 && t.charAt(1) == '/' ? 2 : 1;
        return idx < t.length() && (Character.isLetter(t.charAt(idx)) || t.charAt(idx) == '_');
    }

    /**
     * True when the line is a non-blank line indented by at least four columns, i.e. a line
     * commonmark would treat as (part of) an indented code block. A tab advances to the next
     * multiple of four, matching commonmark's tab-stop handling.
     */
    private static boolean isIndentedCodeLine(final String line) {
        if (line.isBlank()) {
            return false;
        }
        var col = 0;
        var i = 0;
        while (i < line.length()) {
            final var c = line.charAt(i);
            if (c == ' ') {
                col++;
            } else if (c == '\t') {
                col += 4 - (col % 4);
            } else {
                break;
            }
            i++;
        }
        return col >= 4;
    }

    /**
     * A block candidate spanning {@code lines[i..j)} (exclusive of {@code j}) is only extracted
     * when it has at least {@code minLines} lines and is delimited by a blank line (or the input
     * boundary) on both sides. This mirrors commonmark's own rule for starting an indented code
     * block (it cannot interrupt a paragraph) and keeps us from stealing list or prose content.
     */
    private static boolean isValidBlock(final String[] lines, final int i, final int j, final int minLines) {
        final var precededByBlank = i == 0 || lines[i - 1].isBlank();
        final var followedByBlank = j == lines.length || lines[j].isBlank();
        return (j - i) >= minLines && precededByBlank && followedByBlank;
    }

    /**
     * True when the last non-blank line before {@code i} is a list item marker. Indented lines
     * under a list item belong to that item (e.g. a nested code block), not to a top-level code
     * block, so they must be left to commonmark to keep the list structure intact.
     */
    private static boolean precededByListItem(final String[] lines, final int i) {
        var k = i - 1;
        while (k >= 0 && lines[k].isBlank()) {
            k--;
        }
        if (k < 0) {
            return false;
        }
        final var t = lines[k].strip();
        if (t.length() < 2) {
            return false;
        }
        final var c = t.charAt(0);
        final var bullet = (c == '-' || c == '*' || c == '+') && Character.isWhitespace(t.charAt(1));
        if (bullet) {
            return true;
        }
        var d = 0;
        while (d < t.length() && Character.isDigit(t.charAt(d))) {
            d++;
        }
        return d > 0
                && d < t.length()
                && (t.charAt(d) == '.' || t.charAt(d) == ')')
                && d + 1 < t.length()
                && Character.isWhitespace(t.charAt(d + 1));
    }

    private record Segment(String text, boolean block) {}

    /**
     * Wraps already-styled code lines (from {@link SyntaxHighlighter#highlight}) to
     * {@code width} so long code lines stay fully visible and copyable. Unlike the
     * prose wrapper, this is character-exact: leading indentation and internal
     * whitespace runs are preserved, overlong tokens are hard-split with the
     * remainder on the next row.
     */
    public static List<Line> wrapCode(final List<Line> lines, final int width) {
        final var out = new ArrayList<Line>();
        for (final var line : lines) {
            appendCodeLine(line.spans(), Math.max(1, width), out);
        }
        return out;
    }

    /**
     * Emits one logical code row, wrapping at {@code width} without ever dropping
     * or collapsing a character. An empty span list still emits a (blank) row so
     * blank lines inside code blocks survive.
     */
    private static void appendCodeLine(final List<Span> spans, final int width, final List<Line> out) {
        var cur = new ArrayList<Span>();
        var curWidth = 0;
        for (final var span : spans) {
            var text = span.content() == null ? "" : span.content();
            while (!text.isEmpty()) {
                final var room = width - curWidth;
                if (room <= 0) {
                    out.add(Line.from(cur));
                    cur = new ArrayList<>();
                    curWidth = 0;
                    continue;
                }
                final var take = Math.min(text.length(), room);
                cur.add(Span.styled(text.substring(0, take), span.style()));
                curWidth += take;
                text = text.substring(take);
            }
        }
        out.add(Line.from(cur));
    }

    private static void renderNode(
            final Node node,
            final Prefix prefix,
            final Style base,
            final MarkdownStyles styles,
            final int width,
            final boolean breakWords,
            final List<Line> out) {
        if (node instanceof Paragraph p) {
            emitInline(p, prefix, base, styles, width, breakWords, out);
        } else if (node instanceof Heading h) {
            // base wins where it carries an explicit style (e.g. a dimmed fg for thoughts),
            // the heading decorations still apply on top
            emitInline(h, prefix, styles.heading(h.getLevel()).patch(base), styles, width, breakWords, out);
        } else if (node instanceof FencedCodeBlock c) {
            emitLiteral(
                    codeLiteral(c.getLiteral()),
                    styles.codeBlock() == null ? base : styles.codeBlock().patch(base),
                    width,
                    out);
        } else if (node instanceof IndentedCodeBlock c) {
            emitLiteral(
                    codeLiteral(c.getLiteral()),
                    styles.codeBlock() == null ? base : styles.codeBlock().patch(base),
                    width,
                    out);
        } else if (node instanceof HtmlBlock h) {
            // HtmlBlock stores its content in getLiteral() (no Text children): rendering
            // the old Text-only walk (textOf) silently dropped whole blocks and inline code
            final var literal = h.getLiteral();
            emitLiteral(
                    literal == null || literal.isBlank() ? inlineText(h) : literal,
                    styles.html() == null ? base : styles.html().patch(base),
                    width,
                    out);
        } else if (node instanceof BlockQuote q) {
            final var markerStyle = styles.blockquote() == null ? Style.EMPTY : styles.blockquote();
            final var marker = Span.styled(styles.blockquotePrefix(), markerStyle.patch(base));
            final var nested =
                    Prefix.with(concat(prefix.first(), List.of(marker)), concat(prefix.cont(), List.of(marker)));
            renderChildren(q, nested, base, styles, width, breakWords, out);
        } else if (node instanceof BulletList l) {
            for (final var child : children(l)) {
                if (child instanceof ListItem li) {
                    renderListItem(li, prefix, bulletMarker(li, styles, base), base, styles, width, breakWords, out);
                }
            }
        } else if (node instanceof OrderedList l) {
            var index = l.getStartNumber();
            for (final var child : children(l)) {
                if (child instanceof ListItem li) {
                    final var marker = Span.styled(
                            index++ + String.valueOf(l.getDelimiter()) + " ", listMarkerStyle(styles, base));
                    renderListItem(li, prefix, marker, base, styles, width, breakWords, out);
                }
            }
        } else if (node instanceof ThematicBreak) {
            final var style = styles.horizontalRule() == null
                    ? base
                    : styles.horizontalRule().patch(base);
            final var avail = Math.max(1, width - widthOf(prefix.first()));
            out.add(Line.from(concat(prefix.first(), List.of(Span.styled("─".repeat(avail), style)))));
        } else if (node instanceof TableBlock table) {
            renderTable(table, prefix, base, styles, width, out);
        } else {
            renderChildren(node, prefix, base, styles, width, breakWords, out);
        }
    }

    private static Span bulletMarker(final ListItem li, final MarkdownStyles styles, final Style base) {
        final var task = taskChecked(li);
        if (task != null) {
            final var style = styles.taskChecked() == null
                    ? base
                    : (task ? styles.taskChecked() : styles.taskUnchecked()).patch(base);
            return Span.styled(task ? symbol(styles.taskCheckedSymbol()) : symbol(styles.taskUncheckedSymbol()), style);
        }
        return Span.styled("• ", listMarkerStyle(styles, base));
    }

    private static Style listMarkerStyle(final MarkdownStyles styles, final Style base) {
        return styles.listMarker() == null ? base : styles.listMarker().patch(base);
    }

    private static String symbol(final String raw) {
        return raw == null ? "[ ] " : raw.strip() + " ";
    }

    private static Boolean taskChecked(final ListItem li) {
        for (final var node : descendants(li)) {
            if (node instanceof TaskListItemMarker m) {
                return m.isChecked();
            }
        }
        return null;
    }

    private static void renderChildren(
            final Node node,
            final Prefix prefix,
            final Style base,
            final MarkdownStyles styles,
            final int width,
            final boolean breakWords,
            final List<Line> out) {
        var renderedAny = false;
        for (final var child : children(node)) {
            // blank source lines between block siblings must survive: markdown
            // only separates blocks with blank lines, so render one too
            if (renderedAny && !out.isEmpty() && !isBlankLine(out.get(out.size() - 1))) {
                out.add(Line.empty());
            }
            renderNode(child, prefix, base, styles, width, breakWords, out);
            renderedAny = true;
        }
    }

    private static boolean isBlankLine(final Line line) {
        for (final var span : line.spans()) {
            if (span.content() != null && !span.content().isBlank()) {
                return false;
            }
        }
        return true;
    }

    private static void renderListItem(
            final ListItem li,
            final Prefix prefix,
            final Span marker,
            final Style base,
            final MarkdownStyles styles,
            final int width,
            final boolean breakWords,
            final List<Line> out) {
        final var indent = Span.styled(" ".repeat(marker.width()), Style.EMPTY);
        final var first = Prefix.with(concat(prefix.first(), List.of(marker)), concat(prefix.cont(), List.of(indent)));
        final var rest = Prefix.with(concat(prefix.cont(), List.of(indent)), concat(prefix.cont(), List.of(indent)));
        var blockIndex = 0;
        for (final var child : children(li)) {
            if (!(child instanceof TaskListItemMarker)) {
                renderNode(child, blockIndex++ == 0 ? first : rest, base, styles, width, breakWords, out);
            }
        }
    }

    private static void emitInline(
            final Node block,
            final Prefix prefix,
            final Style base,
            final MarkdownStyles styles,
            final int width,
            final boolean breakWords,
            final List<Line> out) {
        final var content = new ArrayList<Span>();
        for (final var child : children(block)) {
            inline(child, prefix, base, styles, width, content, out);
        }
        appendWrapped(content, breakWords, width, prefix, out);
    }

    private static void inline(
            final Node node,
            final Prefix prefix,
            final Style base,
            final MarkdownStyles styles,
            final int width,
            final List<Span> content,
            final List<Line> out) {
        if (node instanceof Text t) {
            content.add(Span.styled(t.getLiteral(), base));
        } else if (node instanceof Code c) {
            // same composition rule as strong: the code accent fills in only when the region
            // carries no explicit foreground - on a plain base inline code is colored bold,
            // inside dimmed regions (thinking/tool output) it stays bold but takes the region
            // tone so code never outshouts the subdued prose it lives in
            final var code =
                    styles.inlineCode() == null ? base : styles.inlineCode().patch(base);
            content.add(Span.styled(c.getLiteral(), code));
        } else if (node instanceof Emphasis) {
            for (final var child : children(node)) {
                inline(child, prefix, base.patch(styles.emphasis()), styles, width, content, out);
            }
        } else if (node instanceof StrongEmphasis) {
            // the strong accent fills in only when the region carries no explicit
            // foreground: on a plain base bold is colored, inside dimmed regions
            // (thinking/tool output) it stays bold but keeps the region tone
            final var strong = styles.strong() == null ? Style.EMPTY.bold() : styles.strong();
            for (final var child : children(node)) {
                inline(child, prefix, strong.patch(base), styles, width, content, out);
            }
        } else if (node instanceof Strikethrough) {
            for (final var child : children(node)) {
                inline(child, prefix, base.patch(styles.strikethrough()), styles, width, content, out);
            }
        } else if (node instanceof Link link) {
            final var style = styles.link() == null ? base : base.patch(styles.link());
            for (final var child : children(link)) {
                inline(child, prefix, style, styles, width, content, out);
            }
        } else if (node instanceof Image image) {
            for (final var child : children(image)) {
                inline(child, prefix, base, styles, width, content, out);
            }
        } else if (node instanceof HtmlInline h) {
            content.add(Span.styled(h.getLiteral(), styles.html() == null ? base : base.patch(styles.html())));
        } else if (node instanceof TaskListItemMarker) {
            // rendered by bulletMarker for the whole item; nothing to emit here
        } else if (node instanceof SoftLineBreak || node instanceof HardLineBreak) {
            appendWrapped(content, false, width, prefix, out);
            content.clear();
        } else {
            for (final var child : children(node)) {
                inline(child, prefix, base, styles, width, content, out);
            }
        }
    }

    /**
     * The literal of a code block keeps the newline that terminates its last line; rendering it
     * verbatim emits a phantom blank row under the block, so that single terminator is dropped
     * here. An intentional empty last code line ({@code "a\n\n"}) keeps its own row, and the blank
     * row that really separates the block from its neighbours is the one the block walk inserts.
     */
    private static String codeLiteral(final String literal) {
        return literal != null && literal.endsWith("\n") ? literal.substring(0, literal.length() - 1) : literal;
    }

    private static void emitLiteral(final String literal, final Style style, final int width, final List<Line> out) {
        if (literal == null || literal.isEmpty()) {
            return;
        }
        for (final var row : literal.split("\n", -1)) {
            appendCodeLine(row.isEmpty() ? List.of() : List.of(Span.styled(row, style)), Math.max(1, width), out);
        }
    }

    private static String inlineText(final Node node) {
        final var sb = new StringBuilder();
        appendInlineText(node, sb);
        return sb.toString();
    }

    /**
     * Appends the inline text content of {@code node}: {@link Text} literals, inline
     * {@link Code} (backticks stripped, matching the paragraph renderer), {@link HtmlInline}
     * and the children of emphasis/strong/strikethrough/link/image nodes; soft and hard
     * line breaks become {@code \n}. Unlike a Text-only walk this keeps commonmark
     * {@code Code} nodes - their content lives in {@code getLiteral()} with no {@code Text}
     * child - so inline code inside table cells is no longer dropped.
     */
    private static void appendInlineText(final Node node, final StringBuilder sb) {
        if (node instanceof Text t) {
            sb.append(t.getLiteral());
        } else if (node instanceof Code c) {
            sb.append(c.getLiteral());
        } else if (node instanceof HtmlInline h) {
            sb.append(h.getLiteral());
        } else if (node instanceof SoftLineBreak || node instanceof HardLineBreak) {
            sb.append('\n');
        } else {
            for (final var child : children(node)) {
                appendInlineText(child, sb);
            }
        }
    }

    private static void renderTable(
            final TableBlock table,
            final Prefix prefix,
            final Style base,
            final MarkdownStyles styles,
            final int width,
            final List<Line> out) {
        List<TableRenderer.Cell> headCells = null;
        final var bodyRows = new ArrayList<List<TableRenderer.Cell>>();
        for (final var block : children(table)) {
            if (block instanceof TableHead h) {
                for (final var row : children(h)) {
                    if (row instanceof TableRow r) {
                        // header cells are bold like the prose headings, keeping inline styles
                        headCells = cellsOf(r, base.bold(), styles);
                    }
                }
            } else if (block instanceof TableBody b) {
                for (final var row : children(b)) {
                    if (row instanceof TableRow r) {
                        bodyRows.add(cellsOf(r, base, styles));
                    }
                }
            }
        }
        out.addAll(TABLE_RENDERER.render(headCells, bodyRows, width, prefix.first(), prefix.cont(), base));
    }

    private static List<TableRenderer.Cell> cellsOf(final TableRow row, final Style base, final MarkdownStyles styles) {
        final var out = new ArrayList<TableRenderer.Cell>();
        for (final var cell : children(row)) {
            if (cell instanceof TableCell tc) {
                // inline formats (code, emphasis, links) are kept as styled spans; a newline
                // inside a cell becomes a space so TableRenderer can wrap it cleanly
                final var raw = new ArrayList<Span>();
                for (final var span : cellInline(tc, base, styles)) {
                    final var content = span.content() == null ? "" : span.content();
                    raw.add(Span.styled(content.replace('\n', ' '), span.style()));
                }
                out.add(new TableRenderer.Cell(trimSpans(raw)));
            }
        }
        return out;
    }

    /**
     * Renders the inline content of a table cell as styled spans, mirroring the
     * prose walk: {@link Text} is buffered, {@link Code} uses the theme inline-code
     * style, strong/emphasis/strikethrough/link/html their theme style, each patched
     * over {@code base} (line/header style first); breaks become {@code \n} so the
     * caller can normalize them to spaces. Unlike {@link #inlineText(Node)} this
     * keeps the per-node styles, so inline formatting is visible inside table cells.
     */
    private static List<Span> cellInline(final Node node, final Style base, final MarkdownStyles styles) {
        final var out = new ArrayList<Span>();
        final var sb = new StringBuilder();
        cellInline(node, base, styles, out, sb);
        flushCell(out, sb, base);
        return out;
    }

    private static void cellInline(
            final Node node,
            final Style base,
            final MarkdownStyles styles,
            final List<Span> out,
            final StringBuilder sb) {
        if (node instanceof Text t) {
            sb.append(t.getLiteral());
        } else if (node instanceof Code c) {
            flushCell(out, sb, base);
            // same composition rule as the prose walk: the code accent yields to a colored base
            out.add(Span.styled(c.getLiteral(), styles.inlineCode().patch(base)));
        } else if (node instanceof HtmlInline h) {
            flushCell(out, sb, base);
            out.add(Span.styled(h.getLiteral(), base.patch(styles.html())));
        } else if (node instanceof SoftLineBreak || node instanceof HardLineBreak) {
            sb.append('\n');
        } else if (node instanceof Emphasis) {
            flushCell(out, sb, base);
            renderCellMarkup(node, base, styles.emphasis(), styles, out, false);
        } else if (node instanceof StrongEmphasis) {
            flushCell(out, sb, base);
            // like the prose walk: bold keeps the region tone when the base is colored
            renderCellMarkup(node, base, styles.strong(), styles, out, true);
        } else if (node instanceof Strikethrough) {
            flushCell(out, sb, base);
            renderCellMarkup(node, base, styles.strikethrough(), styles, out, false);
        } else if (node instanceof Link) {
            flushCell(out, sb, base);
            renderCellMarkup(node, base, styles.link(), styles, out, false);
        } else if (node instanceof Image) {
            flushCell(out, sb, base);
            renderCellMarkup(node, base, styles.link(), styles, out, false);
        } else {
            for (final var child : children(node)) {
                cellInline(child, base, styles, out, sb);
            }
        }
    }

    /** Flushes the pending plain-text buffer as one span styled with {@code base}. */
    private static void flushCell(final List<Span> out, final StringBuilder sb, final Style base) {
        if (sb.length() > 0) {
            out.add(Span.styled(sb.toString(), base));
            sb.setLength(0);
        }
    }

    /**
     * Emits the children of a markup node, each patched over the markup style (nested markups compose).
     *
     * @param baseKeepsFg true when the region tone must win over the markup color (strong in a
     *                    dimmed region stays bold but keeps the base color); false lets the markup
     *                    color win (links/code stay visible)
     */
    private static void renderCellMarkup(
            final Node node,
            final Style base,
            final Style markup,
            final MarkdownStyles styles,
            final List<Span> out,
            final boolean baseKeepsFg) {
        final var childBase = baseKeepsFg ? markup.patch(base) : base.patch(markup);
        final var nested = new ArrayList<Span>();
        final var nestedSb = new StringBuilder();
        for (final var child : children(node)) {
            cellInline(child, childBase, styles, nested, nestedSb);
        }
        flushCell(nested, nestedSb, childBase);
        out.addAll(nested);
    }

    /** Trims leading/trailing whitespace of a span list, keeping the styles of the remaining text. */
    static List<Span> trimSpans(final List<Span> spans) {
        final var text = spans.stream().map(Span::content).reduce("", String::concat);
        final var start = leading(text);
        final var end = trailing(text, text.length() - start);
        final var out = new ArrayList<Span>();
        var offset = 0;
        for (final var span : spans) {
            final var content = span.content() == null ? "" : span.content();
            var from = Math.max(0, start - offset);
            var to = Math.min(content.length(), end - offset);
            if (to > from) {
                out.add(Span.styled(content.substring(from, to), span.style()));
            }
            offset += content.length();
        }
        return out;
    }

    private static int leading(final String text) {
        var i = 0;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i;
    }

    private static int trailing(final String text, final int limit) {
        var i = text.length();
        while (i > limit && Character.isWhitespace(text.charAt(i - 1))) {
            i--;
        }
        return i;
    }

    private static Iterable<Node> descendants(final Node root) {
        final var out = new ArrayList<Node>();
        collect(root, out);
        return out;
    }

    private static void collect(final Node node, final List<Node> out) {
        out.add(node);
        for (final var child : children(node)) {
            collect(child, out);
        }
    }

    private static Iterable<Node> children(final Node node) {
        return () -> new Iterator<>() {
            private Node next = node.getFirstChild();

            @Override
            public boolean hasNext() {
                return next != null;
            }

            @Override
            public Node next() {
                final var cur = next;
                next = cur.getNext();
                return cur;
            }
        };
    }

    /**
     * Wraps a single logical line of styled spans to {@code width}, applying
     * {@code prefix.first()} on the first physical line and {@code prefix.cont()} on
     * wrapped continuation lines. A single token wider than {@code width} is left
     * alone when {@code breakWords} is false (prose) and hard-split when true (code).
     */
    private static void appendWrapped(
            final List<Span> content,
            final boolean breakWords,
            final int width,
            final Prefix prefix,
            final List<Line> out) {
        final var words = words(content);
        if (words.isEmpty()) {
            out.add(Line.from(new ArrayList<>(prefix.first())));
            return;
        }
        final var first = prefix.first();
        final var cont = prefix.cont();
        var cur = new ArrayList<Span>();
        var curWidth = 0;
        var isFirst = true;
        for (final var word : words) {
            final var prefixNow = isFirst ? first : cont;
            final var wordWidth = widthOf(word);
            final var avail = width - widthOf(prefixNow);
            if (cur.isEmpty() && breakWords && wordWidth > avail) {
                splitLongWord(prependTo(word, prefixNow), avail, out);
                isFirst = false;
                break;
            }
            if (!cur.isEmpty() && curWidth + 1 + wordWidth > avail) {
                out.add(Line.from(concat(prefixNow, cur)));
                isFirst = false;
                cur = new ArrayList<>();
                curWidth = 0;
            }
            if (!cur.isEmpty()) {
                cur.add(Span.styled(" ", Style.EMPTY));
                curWidth += 1;
            }
            cur.addAll(word);
            curWidth += wordWidth;
        }
        if (!cur.isEmpty()) {
            out.add(Line.from(concat(isFirst ? first : cont, cur)));
        }
    }

    private static List<Span> prependTo(final List<Span> word, final List<Span> prefix) {
        final var all = new ArrayList<Span>(prefix);
        all.addAll(word);
        return all;
    }

    private static void splitLongWord(final List<Span> combined, final int avail, final List<Line> out) {
        final var sb = new StringBuilder();
        for (final var s : combined) {
            sb.append(s.content());
        }
        final var text = sb.toString();
        final var chunk = Math.max(1, avail);
        var from = 0;
        while (from < text.length()) {
            final var to = Math.min(text.length(), from + chunk);
            out.add(Line.from(List.of(Span.styled(text.substring(from, to), Style.EMPTY))));
            from = to;
        }
    }

    /**
     * Splits {@code content} into whitespace-separated word tokens, each a list of
     * styled spans (a word never mixes source spans across a space). Consecutive
     * spaces survive: commonmark keeps them (inside inline formatting and in plain
     * prose alike) and the wrapper joins tokens with a single space, so each extra
     * space of a run becomes its own empty token which the joining space turns back
     * into the original run length. Leading/trailing runs stay dropped - they are
     * invisible and commonmark strips them at line boundaries anyway.
     */
    private static List<List<Span>> words(final List<Span> content) {
        final var tokens = new ArrayList<List<Span>>();
        var current = new ArrayList<Span>();
        for (final var span : content) {
            if (span.content() == null) {
                continue;
            }
            final var parts = span.content().split(" ", -1);
            for (var i = 0; i < parts.length; i++) {
                final var part = parts[i];
                if (!part.isEmpty()) {
                    current.add(Span.styled(part, span.style()));
                } else if (i > 0 && i < parts.length - 1 && !tokens.isEmpty()) {
                    // interior space run: one empty token per extra space, so the
                    // wrappers' single joining space keeps the original run length
                    // (a lone space-only span with no word ever renders nothing)
                    tokens.add(List.of());
                }
                if (i < parts.length - 1 && !current.isEmpty()) {
                    tokens.add(current);
                    current = new ArrayList<>();
                }
            }
        }
        if (!current.isEmpty()) {
            tokens.add(current);
        }
        return tokens;
    }

    private static int widthOf(final List<Span> spans) {
        var w = 0;
        for (final var s : spans) {
            w += s.width();
        }
        return w;
    }

    private static List<Span> concat(final List<Span> a, final List<Span> b) {
        final var all = new ArrayList<Span>(a.size() + b.size());
        all.addAll(a);
        all.addAll(b);
        return all;
    }

    private record Prefix(List<Span> first, List<Span> cont) {
        private static final Prefix NONE = new Prefix(List.of(), List.of());

        private static Prefix with(final List<Span> first, final List<Span> cont) {
            return new Prefix(first, cont);
        }
    }
}
