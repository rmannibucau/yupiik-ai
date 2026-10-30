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
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight keyword/token syntax highlighter used to color fenced code blocks
 * before they are rendered. It is intentionally simple: strings, comments,
 * keywords, types and numbers of the most common languages get a distinct color
 * while everything else stays plain. It never throws and falls back to a plain
 * generic tokenizer for unknown languages.
 */
public class SyntaxHighlighter {

    // Fenced code blocks are located by a linear line scanner ({@link #scanFences})
    // instead of a DOTALL-lazy backreference regex. The old `(?<body>.*?)` pattern
    // matched the whole region between the opening and closing fence by expanding
    // one character at a time and re-testing the anchored close fence over the whole
    // remaining tail on every step - quadratic on large fenced bodies (the busy path
    // is exactly a long `---text``` tool output block). The scanner walks the region
    // once line by line, so every character is examined a bounded number of times.
    // Semantics are preserved: a fence closes only on its own delimiter, an info
    // string may follow the language tag, and an opener with no close stays plain text.

    private static final int DEFAULT_MAX_LINE_LENGTH = 10_000;
    private static final int MAX_SPLIT_CACHE = 32;
    private static final int MAX_BLOCK_CACHE = 64;
    private static final int MAX_LINE_CACHE = 4_096;

    private volatile int maxLineLength = DEFAULT_MAX_LINE_LENGTH;
    private volatile CodeTheme theme = CodeTheme.ONE_DARK;

    // memoized tokenization results: rendering reruns the same stable transcript
    // blocks every frame (live thinking grows append-only), so caching makes a
    // long session render in O(tail) instead of O(transcript). Keys are plain
    // strings/records, values are immutable lines/spans safe to share between
    // frames and threads. Bound is coarse; entries are tiny.

    private record LineKey(long generation, Highlighter highlighter, String line) {}

    private record BlockKey(long generation, String language, String code) {}

    // bumped on every theme/maxLineLength change so an in-flight tokenization
    // computed with the previous settings can never be stored as "fresh" after
    // the cache was cleared (a clear-then-put race would otherwise cache stale
    // colors forever)
    private volatile long generation;

    private final Map<String, List<Segment>> splitCache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(final Map.Entry<String, List<Segment>> eldest) {
            return size() > MAX_SPLIT_CACHE;
        }
    };
    private final Map<BlockKey, List<Line>> blockCache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(final Map.Entry<BlockKey, List<Line>> eldest) {
            return size() > MAX_BLOCK_CACHE;
        }
    };
    private final Map<LineKey, List<Span>> lineCache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(final Map.Entry<LineKey, List<Span>> eldest) {
            return size() > MAX_LINE_CACHE;
        }
    };

    private interface Highlighter {
        Pattern pattern();
    }

    private static final class RegexHighlighter implements Highlighter {
        private final Pattern pattern;

        private RegexHighlighter(final Pattern pattern) {
            this.pattern = pattern;
        }

        @Override
        public Pattern pattern() {
            return pattern;
        }
    }

    public record Segment(Type type, String language, String text) {
        public enum Type {
            TEXT,
            CODE
        }
    }

    public SyntaxHighlighter() {}

    /** Sets the token color theme (see {@link CodeTheme}). */
    public SyntaxHighlighter theme(final CodeTheme theme) {
        this.theme = theme == null ? CodeTheme.ONE_DARK : theme;
        clearCaches();
        return this;
    }

    public SyntaxHighlighter maxLineLength(final int maxLineLength) {
        this.maxLineLength = Math.max(1, maxLineLength);
        clearCaches();
        return this;
    }

    private void clearCaches() {
        this.generation++;
        synchronized (splitCache) {
            splitCache.clear();
        }
        synchronized (blockCache) {
            blockCache.clear();
        }
        synchronized (lineCache) {
            lineCache.clear();
        }
    }

    private Style token(final String hex) {
        return Style.EMPTY.fg(Color.hex(hex));
    }

    private static Highlighter language(final List<String> keywords, final List<String> types) {
        return new RegexHighlighter(Pattern.compile(
                "(?<str>\"(?:\\\\.|[^\"\\\\])*+\"|'(?:\\\\.|[^'\\\\])*+'|`(?:\\\\.|[^`\\\\])*+`)"
                        + "|(?<com>//[^\\n]*+|/\\*(?:[^*]|\\*(?!/))*+\\*/|#[^\\n]*+|--[^\\n]*+|<!--(?:[^-]|-(?!-))*+-->|\\{\\{-?[^\\n]*+}})"
                        + "|(?<num>\\b\\d++(?:\\.\\d+)?+\\b)"
                        + "|(?<tag><\\/?[A-Za-z][A-Za-z0-9:-]*|/?>)"
                        + "|(?<attr>(?<![A-Za-z0-9_])[A-Za-z_:][A-Za-z0-9_:.-]*+=)"
                        + "|(?<ann>@[A-Za-z_][A-Za-z0-9_$]*)"
                        + "|(?<kw>\\b(?:" + alternation(keywords) + ")\\b)"
                        + "|(?<ty>\\b(?:" + alternation(types) + ")\\b)",
                Pattern.MULTILINE));
    }

    // an empty word list would compile to the invalid "\b(?)\b"; "(?!)" never matches
    private static String alternation(final List<String> words) {
        return words.isEmpty() ? "(?!)" : String.join("|", words);
    }

    private static final Highlighter JAVA = language(
            List.of(
                    "public",
                    "private",
                    "protected",
                    "static",
                    "final",
                    "class",
                    "interface",
                    "enum",
                    "record",
                    "void",
                    "return",
                    "if",
                    "else",
                    "for",
                    "while",
                    "do",
                    "switch",
                    "case",
                    "default",
                    "break",
                    "continue",
                    "new",
                    "try",
                    "catch",
                    "finally",
                    "throw",
                    "throws",
                    "extends",
                    "implements",
                    "import",
                    "package",
                    "this",
                    "super",
                    "null",
                    "true",
                    "false",
                    "var",
                    "instanceof",
                    "synchronized",
                    "abstract",
                    "yield",
                    "sealed",
                    "permits",
                    "assert",
                    "native",
                    "transient",
                    "volatile",
                    "strictfp"),
            List.of(
                    "String",
                    "int",
                    "long",
                    "byte",
                    "short",
                    "float",
                    "double",
                    "boolean",
                    "char",
                    "Object",
                    "Integer",
                    "Long",
                    "Boolean",
                    "Double",
                    "Float",
                    "Byte",
                    "Short",
                    "Character",
                    "CharSequence",
                    "StringBuilder",
                    "StringBuffer",
                    "List",
                    "ArrayList",
                    "LinkedList",
                    "Map",
                    "HashMap",
                    "LinkedHashMap",
                    "Set",
                    "HashSet",
                    "Collection",
                    "Optional",
                    "OptionalInt",
                    "OptionalDouble",
                    "Stream",
                    "Collectors",
                    "CompletableFuture",
                    "BigDecimal",
                    "BigInteger",
                    "Arrays",
                    "Objects",
                    "Path",
                    "Iterable"));
    private static final Highlighter TS = language(
            List.of(
                    "const",
                    "let",
                    "var",
                    "function",
                    "return",
                    "if",
                    "else",
                    "for",
                    "while",
                    "do",
                    "switch",
                    "case",
                    "default",
                    "break",
                    "continue",
                    "new",
                    "try",
                    "catch",
                    "finally",
                    "throw",
                    "throws",
                    "extends",
                    "implements",
                    "import",
                    "export",
                    "from",
                    "async",
                    "await",
                    "yield",
                    "class",
                    "interface",
                    "enum",
                    "type",
                    "null",
                    "true",
                    "false",
                    "this",
                    "super",
                    "of",
                    "instanceof",
                    "typeof",
                    "delete",
                    "in",
                    "void",
                    "readonly",
                    "static",
                    "public",
                    "private",
                    "protected"),
            List.of(
                    "string",
                    "number",
                    "boolean",
                    "any",
                    "unknown",
                    "never",
                    "void",
                    "object",
                    "Promise",
                    "Array",
                    "Function",
                    "Record",
                    "Date"));
    private static final Highlighter PYTHON = language(
            List.of(
                    "def",
                    "class",
                    "return",
                    "if",
                    "elif",
                    "else",
                    "for",
                    "while",
                    "try",
                    "except",
                    "finally",
                    "with",
                    "as",
                    "import",
                    "from",
                    "pass",
                    "break",
                    "continue",
                    "lambda",
                    "yield",
                    "global",
                    "nonlocal",
                    "raise",
                    "assert",
                    "del",
                    "and",
                    "or",
                    "not",
                    "in",
                    "is",
                    "None",
                    "True",
                    "False"),
            List.of(
                    "str",
                    "int",
                    "float",
                    "bool",
                    "list",
                    "dict",
                    "tuple",
                    "set",
                    "bytes",
                    "object",
                    "Exception",
                    "Any",
                    "Optional",
                    "Type"));
    private static final Highlighter SHELL = language(
            List.of(
                    "if",
                    "then",
                    "else",
                    "elif",
                    "fi",
                    "for",
                    "while",
                    "do",
                    "done",
                    "case",
                    "esac",
                    "function",
                    "in",
                    "return",
                    "exit",
                    "export",
                    "local",
                    "readonly",
                    "echo",
                    "printf",
                    "cd",
                    "ls",
                    "mkdir",
                    "rm",
                    "cp",
                    "mv",
                    "touch",
                    "cat",
                    "grep",
                    "sed",
                    "awk",
                    "find",
                    "sudo",
                    "git",
                    "node",
                    "npm",
                    "mvn",
                    "java"),
            List.of());
    private static final Highlighter SQL = language(
            List.of(
                    "select",
                    "from",
                    "where",
                    "insert",
                    "into",
                    "values",
                    "update",
                    "set",
                    "delete",
                    "create",
                    "table",
                    "drop",
                    "alter",
                    "index",
                    "join",
                    "left",
                    "right",
                    "inner",
                    "outer",
                    "on",
                    "group",
                    "by",
                    "order",
                    "having",
                    "limit",
                    "offset",
                    "and",
                    "or",
                    "not",
                    "in",
                    "like",
                    "between",
                    "is",
                    "null",
                    "as",
                    "distinct",
                    "union",
                    "all",
                    "primary",
                    "key",
                    "foreign",
                    "references",
                    "constraint",
                    "default",
                    "grant",
                    "revoke"),
            List.of(
                    "int",
                    "integer",
                    "bigint",
                    "varchar",
                    "text",
                    "char",
                    "date",
                    "datetime",
                    "timestamp",
                    "boolean",
                    "decimal",
                    "numeric",
                    "float",
                    "double",
                    "serial",
                    "uuid"));
    private static final Highlighter XML = language(
            List.of("xmlns", "version", "encoding", "standalone", "DOCTYPE", "ENTITY", "SYSTEM", "PUBLIC"), List.of());
    private static final Highlighter YAML = new RegexHighlighter(Pattern.compile(
            "(?<str>\"(?:\\\\.|[^\"\\\\])*+\"|'[^'\\n]*+(?:''[^'\\n]*+)*+')"
                    + "|(?<com>#[^\\n]*+)"
                    + "|(?<ty>^[ \\t]*-(?:[ \\t]|$))"
                    + "|(?<attr>^[ \\t]*[&*][A-Za-z0-9_-]+)"
                    + "|(?<tag>^[ \\t]*(?:- )?[A-Za-z_][A-Za-z0-9_. -]*+(?=:(?:[ \\t]|$)))"
                    + "|(?<kw>\\b(?:true|false|null|True|False|Null|TRUE|FALSE|NULL|yes|no|on|off|Yes|No|On|Off)\\b|~)"
                    + "|(?<num>\\b\\d++(?:\\.\\d+)?+\\b)",
            Pattern.MULTILINE));

    /**
     * Markdown source markers: code spans and links (string), bold/italic/strike
     * (type), headings, blockquotes, list markers, setext/hr rules (keyword),
     * fences and table rows (tag), numbers and HTML comments. Every token whose
     * closing delimiter can sit far away (spans, links) confirms the closer
     * inside a possessive bounded window first, so a pathological line costs
     * O(n*512) at worst instead of super-linear backtracking; line constructs
     * are anchored to the line start.
     */
    private static final Highlighter MARKDOWN = new RegexHighlighter(Pattern.compile(
            "(?<str>`(?=[^`\\n]{0,512}+`)[^`\\n]{0,512}`"
                    + "|\\[(?=[^\\]\\n]{0,512}+\\](?:\\([^)\\n]{0,512}+\\))?)[^\\]\\n]{0,512}\\](?:\\([^)\\n]{0,512}\\))?)"
                    + "|(?<com><!--(?:[^-]|-(?!-))*+-->)"
                    + "|(?<kw>^#{1,6}(?=[ \\t])"
                    + "|^[ \\t]{0,3}+>+"
                    + "|^[ \\t]{0,3}+(?:-{3,}+|\\*{3,}+|_{3,}+)(?=[ \\t]*+$)"
                    + "|^[ \\t]{0,3}+(?:[-*+]|\\d++[.)])[ \\t]++(?=\\S)"
                    + "|^[=-]{2,}+$)"
                    + "|(?<tag>^[ \\t]{0,3}+(?:```|~~~)(?=$|\\s)"
                    + "|^\\|[^\\n]*+\\|?$)"
                    + "|(?<ty>\\*\\*(?=((?:[^*\\n]|\\*(?!\\*)){0,512}+\\*\\*))(?:[^*\\n]|\\*(?!\\*)){0,512}\\*\\*"
                    + "|\\*(?!\\*)(?=([^*\\n]{0,512}+\\*(?!\\*)))[^*\\n]{0,512}\\*(?!\\*)"
                    + "|(?<![_\\w])_(?!\\s)(?=([^_\\n]{0,512}+_(?!\\w)))[^_\\n]{0,512}_(?!\\w)"
                    + "|~~(?=([^~\\n]{0,512}+~~))[^~\\n]{0,512}~~)"
                    + "|(?<num>\\b\\d++(?:\\.\\d+)?+\\b)",
            Pattern.MULTILINE));

    /**
     * Asciidoc source markers: monospace/passthrough code, links, URLs and
     * macros (string), bold/italic (type), anchors (annotation), headings and
     * attributes (keyword), line comments, block delimiters and block
     * attributions (tag), numbers. Same bounded-window discipline as
     * {@link #MARKDOWN}.
     */
    private static final Highlighter ASCIIDOC = new RegexHighlighter(Pattern.compile(
            "(?<str>`(?=[^`\\n]{0,512}+`)[^`\\n]{0,512}`"
                    + "|(?<![A-Za-z0-9])\\+(?!\\s)(?=([^+\\n]{0,512}+\\+))[^+\\n]{0,512}\\+"
                    + "|(?<![A-Za-z0-9])link:(?=([^\\s\\[\\]\\n]{0,512}+(?:\\[[^\\]]{0,512}+\\])?))[^\\s\\[\\]\\n]{0,512}(?:\\[[^\\]]{0,512}\\])?"
                    + "|(?<![A-Za-z0-9:])(?:https?|ftp|mailto)://(?=[^\\s\\[\\]\\n]{0,512}+)[^\\s\\[\\]\\n]{0,512}"
                    + "|(?=[A-Za-z0-9_]{1,64}::[^\\s\\[\\]\\n]{0,512}+\\[[^\\]]{0,512}+\\])[A-Za-z0-9_]{1,64}::[^\\s\\[\\]\\n]{0,512}\\[[^\\]]{0,512}\\])"
                    + "|(?<ty>\\*\\*(?=((?:[^*\\n]|\\*(?!\\*)){0,512}+\\*\\*))(?:[^*\\n]|\\*(?!\\*)){0,512}\\*\\*"
                    + "|\\*(?!\\*)(?=([^*\\n]{0,512}+\\*(?!\\*)))[^*\\n]{0,512}\\*(?!\\*)"
                    + "|(?<![_\\w])_(?!\\s)(?=([^_\\n]{0,512}+_(?!\\w)))[^_\\n]{0,512}_(?!\\w))"
                    + "|(?<ann>\\[\\[(?=([^\\]\\n]{0,512}+\\]\\]))[^\\]\\n]{0,512}\\]\\]"
                    + "|\\[#(?=([^\\]\\n]{0,512}+\\]))[^\\]\\n]{0,512}\\])"
                    + "|(?<com>^//[^\\n]*+)"
                    + "|(?<kw>^=++\\s(?=\\S))"
                    + "|(?<attr>^:[A-Za-z0-9_][A-Za-z0-9_.-]*+:(?=\\s|$))"
                    + "|(?<tag>^(?:-{4,}+|\\.{4,}+|\\*{4,}+|_{4,}+|={4,}+)[ \\t]*+$"
                    + "|^[ \\t]{0,3}+\\[[A-Za-z0-9_,=#. -]++\\]$)"
                    + "|(?<num>\\b\\d++(?:\\.\\d+)?+\\b)",
            Pattern.MULTILINE));

    private static final Highlighter GENERIC = language(
            List.of(
                    "return",
                    "if",
                    "else",
                    "for",
                    "while",
                    "switch",
                    "case",
                    "break",
                    "continue",
                    "class",
                    "interface",
                    "enum",
                    "record",
                    "function",
                    "def",
                    "import",
                    "export",
                    "var",
                    "let",
                    "const",
                    "new",
                    "try",
                    "catch",
                    "finally",
                    "throw",
                    "this",
                    "super",
                    "null",
                    "true",
                    "false",
                    "async",
                    "await",
                    "void"),
            List.of());

    public List<Segment> split(final String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return List.of();
        }
        synchronized (splitCache) {
            return splitLocked(markdown);
        }
    }

    private List<Segment> splitLocked(final String markdown) {
        final var cached = splitCache.get(markdown);
        if (cached != null) {
            return cached;
        }
        // Full linear fence scan (no backtracking). The streaming frame cost is O(n) for the
        // changed region; stable regions hit splitCache, and unchanged frames are short-circuited
        // by ContentKey before split is even called, so the transcript never re-parses wholesale.
        final var scanned = scanFences(markdown);
        if (!scanned.pendingTail.isBlank()) {
            scanned.committed.add(new Segment(Segment.Type.TEXT, null, scanned.pendingTail));
        }
        final var immutable = List.copyOf(scanned.committed);
        splitCache.put(markdown, immutable);
        return immutable;
    }

    private static final class ScanResult {
        final List<Segment> committed; // segments up to the last closed fence
        final String pendingTail; // text after the last closed fence (may turn into CODE later)

        private ScanResult(final List<Segment> committed, final String pendingTail) {
            this.committed = committed;
            this.pendingTail = pendingTail;
        }
    }

    /**
     * Linear, backtracking-free fence scan: walks the region line by line once, committing
     * a {@code TEXT} segment for the text before a closing fence and a {@code CODE} segment for
     * each fenced block. Every character is examined a bounded number of times, so the cost is
     * O(n) for the whole region regardless of fence count or body size.
     */
    private static ScanResult scanFences(final String markdown) {
        if (markdown.indexOf('`') < 0 && markdown.indexOf('~') < 0) {
            // no fence marker anywhere: a single pending TEXT region, no committed segment
            return new ScanResult(new ArrayList<>(), markdown);
        }
        final var committed = new ArrayList<Segment>();
        final var n = markdown.length();
        var textStart = 0;
        // monotonic cursors: the first unscanned candidate line start for each delimiter's
        // closing fence. close searches only move forward, so the whole scan is O(n).
        int btCursor = 0;
        int ttCursor = 0;
        var lineStart = 0;
        while (lineStart < n) {
            final var nl = markdown.indexOf('\n', lineStart);
            final var lineEnd = nl < 0 ? n : nl;
            // an opener needs a newline-terminated line (a trailing "```" without "\n" never opens)
            if (nl >= 0) {
                final var delim = fenceDelim(markdown, lineStart, lineEnd);
                if (delim != 0) {
                    final var lang = fenceLang(markdown, lineStart, lineEnd);
                    final var bodyStart = nl + 1;
                    final var cursor = delim == '`' ? btCursor : ttCursor;
                    final var closeStart = Math.max(cursor, bodyStart) >= n
                            ? -1
                            : nextClose(markdown, delim, Math.max(cursor, bodyStart), n);
                    if (delim == '`') {
                        btCursor = closeStart < 0 ? n : closeLineEnd(markdown, closeStart);
                    } else {
                        ttCursor = closeStart < 0 ? n : closeLineEnd(markdown, closeStart);
                    }
                    if (closeStart >= 0) {
                        if (textStart < lineStart
                                && !markdown.substring(textStart, lineStart).isBlank()) {
                            committed.add(
                                    new Segment(Segment.Type.TEXT, null, markdown.substring(textStart, lineStart)));
                        }
                        committed.add(new Segment(
                                Segment.Type.CODE, lang, codeBody(markdown.substring(bodyStart, closeStart))));
                        final var closeEnd = closeLineEnd(markdown, closeStart);
                        textStart = closeEnd;
                        lineStart = closeEnd;
                        continue;
                    }
                    // opener never closes: it is plain text; keep scanning for a later fence
                    lineStart = lineEnd + 1;
                    continue;
                }
            }
            lineStart = lineEnd + 1;
        }
        return new ScanResult(committed, markdown.substring(textStart));
    }

    /** Returns the opening-fence delimiter (`` ` `` or `~`) if the line opens a fence, else 0. */
    private static char fenceDelim(final String md, final int start, final int end) {
        var k = start;
        while (k < end && (md.charAt(k) == ' ' || md.charAt(k) == '\t')) {
            k++;
        }
        if (k + 3 <= end) {
            final var c = md.charAt(k);
            if ((c == '`' || c == '~') && md.charAt(k + 1) == c && md.charAt(k + 2) == c) {
                return c;
            }
        }
        return 0;
    }

    /** Language tag of a fence-opening line (the run of {@code [A-Za-z0-9_+.-]} right after the delimiter). */
    private static String fenceLang(final String md, final int start, final int end) {
        var k = start;
        while (k < end && (md.charAt(k) == ' ' || md.charAt(k) == '\t')) {
            k++;
        }
        k += 3; // skip the delimiter
        final var langStart = k;
        while (k < end && isLangChar(md.charAt(k))) {
            k++;
        }
        return md.substring(langStart, k);
    }

    private static boolean isLangChar(final char c) {
        return (c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9')
                || c == '_'
                || c == '+'
                || c == '.'
                || c == '-';
    }

    /**
     * Char offset right after the closing-fence line (or the region end when it is the last line).
     * The closing fence is {@code [ \t]*<delim><delim><delim>[ \t]*} alone on its line.
     */
    private static int closeLineEnd(final String md, final int closeLineStart) {
        final var nl = md.indexOf('\n', closeLineStart);
        return nl < 0 ? md.length() : nl + 1;
    }

    /** Whether the line starting at {@code lineStart} is a closing fence for {@code delim}. */
    private static boolean isCloseLine(final String md, final int lineStart, final int lineEnd, final char delim) {
        int a = lineStart;
        while (a < lineEnd && (md.charAt(a) == ' ' || md.charAt(a) == '\t')) {
            a++;
        }
        int b = lineEnd;
        while (b > a && (md.charAt(b - 1) == ' ' || md.charAt(b - 1) == '\t')) {
            b--;
        }
        return b - a == 3 && md.charAt(a) == delim && md.charAt(a + 1) == delim && md.charAt(a + 2) == delim;
    }

    /** Start of the next closing-fence line of {@code delim} at-or-after {@code from}, or -1 if none. */
    private static int nextClose(final String md, final char delim, final int from, final int n) {
        var line = from;
        while (line < n) {
            final var nl = md.indexOf('\n', line);
            final var le = nl < 0 ? n : nl;
            if (isCloseLine(md, line, le, delim)) {
                return line;
            }
            line = le + 1;
        }
        return -1;
    }

    /**
     * The captured fence body ends with the newline that terminates its last line; highlighting it
     * verbatim renders a phantom blank row under the block, so that single terminator is dropped.
     * An intentional empty last code line ({@code "code\n\n"}) keeps its own row, and the blank row
     * that really separates the block from its neighbours is the one the segment walk inserts.
     */
    private static String codeBody(final String body) {
        return body != null && body.endsWith("\n") ? body.substring(0, body.length() - 1) : body;
    }

    public Text highlight(final String lang, final String code) {
        return Text.from(highlightLines(lang, code));
    }

    /** Same tokenization as {@link #highlight(String, String)} but as a line list (cards/overlay friendly). */
    public List<Line> highlightLines(final String lang, final String code) {
        if (code == null) {
            return List.of();
        }
        final var generation = this.generation;
        final var key = new BlockKey(generation, lang == null ? "" : lang, code);
        List<Line> cached;
        synchronized (blockCache) {
            cached = blockCache.get(key);
        }
        if (cached != null) {
            return cached;
        }
        final var tokenizer = pick(lang);
        final var lines = code.replace("\r\n", "\n").split("\n", -1);
        final var result = new ArrayList<Line>(lines.length);
        for (final var line : lines) {
            result.add(Line.from(colored(tokenizer, line).toArray(Span[]::new)));
        }
        final var immutable = List.copyOf(result);
        synchronized (blockCache) {
            blockCache.put(key, immutable);
        }
        return immutable;
    }

    private List<Span> colored(final Highlighter h, final String line) {
        if (line.length() > maxLineLength) {
            return List.of(Span.raw(line));
        }
        if (line.isBlank()) {
            return List.of(Span.raw(line));
        }
        final var generation = this.generation;
        final var cacheKey = new LineKey(generation, h, line);
        List<Span> cached;
        synchronized (lineCache) {
            cached = lineCache.get(cacheKey);
        }
        if (cached != null) {
            return cached;
        }
        final var out = new ArrayList<Span>();
        final var m = h.pattern().matcher(line);
        var last = 0;
        while (m.find()) {
            if (m.start() > last) {
                out.add(Span.raw(line.substring(last, m.start())));
            }
            out.add(Span.styled(m.group(), styleOf(m)));
            last = m.end();
        }
        if (last < line.length()) {
            out.add(Span.raw(line.substring(last)));
        }
        if (out.isEmpty()) {
            out.add(Span.raw(line));
        }
        final var immutable = List.copyOf(out);
        synchronized (lineCache) {
            lineCache.put(cacheKey, immutable);
        }
        return immutable;
    }

    private Style styleOf(final Matcher m) {
        if (m.group("str") != null) {
            return token(theme.string());
        }
        if (m.group("com") != null) {
            return token(theme.comment());
        }
        if (m.group("num") != null) {
            return token(theme.number());
        }
        if (m.group("tag") != null) {
            return token(theme.tag());
        }
        if (m.pattern().namedGroups().containsKey("attr") && m.group("attr") != null) {
            return token(theme.attr());
        }
        if (m.pattern().namedGroups().containsKey("ann") && m.group("ann") != null) {
            return token(theme.annotation());
        }
        if (m.group("kw") != null) {
            return token(theme.keyword());
        }
        return token(theme.type());
    }

    private static Highlighter pick(final String lang) {
        if (lang == null || lang.isBlank()) {
            return GENERIC;
        }
        final var l = lang.toLowerCase(Locale.ROOT);
        if (l.contains("java")) {
            return JAVA;
        }
        if (l.contains("shell") || l.contains("bash") || l.equals("sh") || l.equals("zsh")) {
            return SHELL;
        }
        if (l.contains("python") || l.contains("py")) {
            return PYTHON;
        }
        if (l.contains("sql")) {
            return SQL;
        }
        if (l.equals("yaml") || l.equals("yml")) {
            return YAML;
        }
        if (l.contains("xml") || l.contains("html") || l.contains("svg") || l.contains("css")) {
            return XML;
        }
        if (l.contains("javascript")
                || l.contains("typescript")
                || l.equals("js")
                || l.equals("ts")
                || l.equals("json")) {
            return TS;
        }
        if (l.equals("md") || l.equals("markdown")) {
            return MARKDOWN;
        }
        if (l.equals("adoc") || l.equals("asciidoc")) {
            return ASCIIDOC;
        }
        return GENERIC;
    }

    /**
     * Maps a file path to a syntax-highlighting language id, or {@code null} when
     * the extension is unknown (callers then fall back to the plain style).
     */
    public static String guessLanguage(final String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        var name = path;
        final var slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        final var dot = name.lastIndexOf('.');
        if (dot < 0) {
            return null;
        }
        return switch (name.substring(dot + 1).toLowerCase(Locale.ROOT)) {
            case "java", "kt", "kts" -> "java";
            case "xml", "html", "htm", "svg", "xhtml" -> "xml";
            case "css", "scss", "less" -> "css";
            case "json", "jsonc", "json5" -> "json";
            case "yaml", "yml" -> "yaml";
            case "js", "mjs", "cjs" -> "javascript";
            case "ts", "mts", "cts" -> "typescript";
            case "py", "pyw" -> "python";
            case "sh", "bash", "zsh" -> "shell";
            case "sql" -> "sql";
            case "md", "markdown" -> "markdown";
            case "adoc", "asciidoc" -> "asciidoc";
            case "properties", "ini", "toml" -> "yaml";
            case "gradle", "groovy" -> "java";
            default -> null;
        };
    }
}
