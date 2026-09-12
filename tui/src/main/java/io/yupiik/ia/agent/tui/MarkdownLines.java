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
        final var root = parser.parse(markdown);
        final var lines = new ArrayList<Line>();
        renderNode(root, Prefix.NONE, base, styles, width, false, lines);
        return lines;
    }

    /**
     * Wraps already-styled code lines (from {@link SyntaxHighlighter#highlight}) to
     * {@code width} so long code lines stay fully visible and copyable.
     */
    public static List<Line> wrapCode(final List<Line> lines, final int width) {
        final var out = new ArrayList<Line>();
        for (final var line : lines) {
            appendWrapped(line.spans(), false, Math.max(1, width), Prefix.NONE, out);
        }
        return out;
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
            emitInline(h, prefix, base.patch(styles.heading(h.getLevel())), styles, width, breakWords, out);
        } else if (node instanceof FencedCodeBlock c) {
            emitLiteral(
                    c.getLiteral(),
                    styles.codeBlock() == null ? base : styles.codeBlock().patch(base),
                    width,
                    out,
                    true);
        } else if (node instanceof IndentedCodeBlock c) {
            emitLiteral(
                    c.getLiteral(),
                    styles.codeBlock() == null ? base : styles.codeBlock().patch(base),
                    width,
                    out,
                    true);
        } else if (node instanceof HtmlBlock h) {
            emitLiteral(textOf(h), styles.html() == null ? base : styles.html().patch(base), width, out, true);
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
            renderTable(table, prefix, width, out);
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
        for (final var child : children(node)) {
            renderNode(child, prefix, base, styles, width, breakWords, out);
        }
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
            content.add(Span.styled(
                    c.getLiteral(),
                    styles.inlineCode() == null ? base : styles.inlineCode().patch(base)));
        } else if (node instanceof Emphasis) {
            for (final var child : children(node)) {
                inline(child, prefix, base.patch(styles.emphasis()), styles, width, content, out);
            }
        } else if (node instanceof StrongEmphasis) {
            for (final var child : children(node)) {
                inline(child, prefix, base.patch(styles.strong()), styles, width, content, out);
            }
        } else if (node instanceof Strikethrough) {
            for (final var child : children(node)) {
                inline(child, prefix, base.patch(styles.strikethrough()), styles, width, content, out);
            }
        } else if (node instanceof Link link) {
            final var style = styles.link() == null ? base : styles.link().patch(base);
            for (final var child : children(link)) {
                inline(child, prefix, style, styles, width, content, out);
            }
        } else if (node instanceof Image image) {
            for (final var child : children(image)) {
                inline(child, prefix, base, styles, width, content, out);
            }
        } else if (node instanceof HtmlInline h) {
            content.add(Span.styled(
                    h.getLiteral(), styles.html() == null ? base : styles.html().patch(base)));
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

    private static void emitLiteral(
            final String literal, final Style style, final int width, final List<Line> out, final boolean breakWords) {
        if (literal == null || literal.isEmpty()) {
            return;
        }
        for (final var row : literal.split("\n", -1)) {
            appendWrapped(
                    row.isEmpty() ? List.of() : List.of(Span.styled(row, style)), breakWords, width, Prefix.NONE, out);
        }
    }

    private static String textOf(final Node node) {
        final var sb = new StringBuilder();
        for (final var n : descendants(node)) {
            if (n instanceof Text t) {
                sb.append(t.getLiteral());
            } else if (n instanceof SoftLineBreak || n instanceof HardLineBreak) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    private static void renderTable(
            final TableBlock table, final Prefix prefix, final int width, final List<Line> out) {
        final var columns = new ArrayList<Integer>();
        TableHead head = null;
        final var bodyRows = new ArrayList<TableRow>();
        for (final var block : children(table)) {
            if (block instanceof TableHead h) {
                head = h;
                for (final var row : children(h)) {
                    if (row instanceof TableRow r) {
                        collectColumns(r, columns);
                    }
                }
            } else if (block instanceof TableBody b) {
                for (final var row : children(b)) {
                    if (row instanceof TableRow r) {
                        bodyRows.add(r);
                        collectColumns(r, columns);
                    }
                }
            }
        }
        final var avail = Math.max(1, width - widthOf(prefix.first()));
        final var total = columns.stream().mapToInt(Integer::intValue).sum() + Math.max(0, columns.size() - 1) * 2;
        final var scale = total <= avail ? 1.0 : (double) avail / total;
        final var widths = new ArrayList<Integer>();
        for (final var c : columns) {
            widths.add(Math.max(1, (int) Math.round(c * scale)));
        }
        if (head != null) {
            for (final var row : children(head)) {
                if (row instanceof TableRow r) {
                    emitTableRow(cellSpans(r, true), widths, prefix, out);
                }
            }
        }
        for (final var row : bodyRows) {
            emitTableRow(cellSpans(row, false), widths, prefix, out);
        }
    }

    private static void emitTableRow(
            final List<List<Span>> cells, final List<Integer> widths, final Prefix prefix, final List<Line> out) {
        final var spans = new ArrayList<Span>(prefix.first());
        for (var i = 0; i < widths.size(); i++) {
            if (!spans.isEmpty()) {
                spans.add(Span.styled("  ", Style.EMPTY));
            }
            final var cell = i < cells.size() ? cells.get(i) : List.<Span>of();
            final var w = widthOfList(cell);
            spans.addAll(cell);
            spans.add(Span.styled(" ".repeat(Math.max(0, widths.get(i) - w)), Style.EMPTY));
        }
        out.add(Line.from(spans));
    }

    private static int widthOfList(final List<Span> spans) {
        return widthOf(spans);
    }

    private static List<List<Span>> cellSpans(final TableRow row, final boolean header) {
        final var out = new ArrayList<List<Span>>();
        for (final var cell : children(row)) {
            if (cell instanceof TableCell tc) {
                final var spans = new ArrayList<Span>();
                for (final var child : children(tc)) {
                    if (child instanceof Text t) {
                        spans.add(Span.styled(t.getLiteral(), Style.EMPTY));
                    }
                }
                out.add(spans);
            }
        }
        return out;
    }

    private static void collectColumns(final TableRow row, final List<Integer> columns) {
        var i = 0;
        for (final var cell : children(row)) {
            if (cell instanceof TableCell tc) {
                var w = 0;
                for (final var child : children(tc)) {
                    if (child instanceof Text t) {
                        w += t.getLiteral().length();
                    }
                }
                while (columns.size() <= i) {
                    columns.add(0);
                }
                columns.set(i, Math.max(columns.get(i), w));
                i++;
            }
        }
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
        return () -> new java.util.Iterator<>() {
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
        var from = 0;
        while (from < text.length()) {
            final var to = Math.min(text.length(), from + avail);
            out.add(Line.from(List.of(Span.styled(text.substring(from, to), Style.EMPTY))));
            from = to;
        }
    }

    /**
     * Splits {@code content} into whitespace-separated word tokens, each a list of
     * styled spans (a word never mixes source spans across a space).
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
                if (!parts[i].isEmpty()) {
                    current.add(Span.styled(parts[i], span.style()));
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
