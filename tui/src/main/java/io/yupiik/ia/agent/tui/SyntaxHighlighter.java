package io.yupiik.ia.agent.tui;

import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight keyword/token syntax highlighter used to color fenced code blocks
 * before they are rendered. It is intentionally simple: strings, comments,
 * keywords, types and numbers of the most common languages get a distinct color
 * while everything else stays plain. It never throws and falls back to a plain
 * generic tokenizer for unknown languages.
 */
public final class SyntaxHighlighter {

    private static final Pattern FENCE = Pattern.compile(
            "(?m)^\\s*```([A-Za-z0-9_+.-]*)\\s*\\n(.*?)^\\s*```\\s*$", Pattern.DOTALL);

    private static final Style STRING = token("e5c07b");
    private static final Style COMMENT = token("7f848e");
    private static final Style NUMBER = token("d19a66");
    private static final Style KEYWORD = token("c678dd");
    private static final Style TYPE = token("61afef");
    private static final Style TAG = token("e06c75");
    private static final Style ATTR = token("56b6c2");

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
        public enum Type { TEXT, CODE }
    }

    private SyntaxHighlighter() {
    }

    private static Style token(final String hex) {
        return Style.EMPTY.fg(Color.hex(hex));
    }

    private static Highlighter language(final List<String> keywords, final List<String> types) {
        return new RegexHighlighter(Pattern.compile(
                "(?<str>\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|`(?:\\\\.|[^`\\\\])*`)"
                        + "|(?<com>//[^\\n]*|/\\*.*?\\*/|#[^\\n]*|--[^\\n]*|<!--.*?-->|\\{\\{-?[^\\n]*?}})"
                        + "|(?<num>\\b\\d+(?:\\.\\d+)?\\b)"
                        + "|(?<tag><\\/?[A-Za-z][A-Za-z0-9:-]*|/?>)"
                        + "|(?<attr>[A-Za-z_:][A-Za-z0-9_:.-]*=)"
                        + "|(?<kw>(" + String.join("|", keywords) + "))"
                        + "|(?<ty>(" + String.join("|", types) + "))",
                Pattern.MULTILINE));
    }

    private static final Highlighter JAVA = language(
            List.of("public", "private", "protected", "static", "final", "class", "interface", "enum", "record",
                    "void", "return", "if", "else", "for", "while", "do", "switch", "case", "default", "break",
                    "continue", "new", "try", "catch", "finally", "throw", "throws", "extends", "implements",
                    "import", "package", "this", "super", "null", "true", "false", "var", "instanceof",
                    "synchronized", "abstract", "yield", "sealed", "permits", "assert", "native", "transient",
                    "volatile"),
            List.of("String", "int", "long", "byte", "short", "float", "double", "boolean", "char", "Object",
                    "Integer", "Long", "Boolean", "Double", "Float", "List", "Map", "Set", "Collection", "Optional"));
    private static final Highlighter TS = language(
            List.of("const", "let", "var", "function", "return", "if", "else", "for", "while", "do", "switch",
                    "case", "default", "break", "continue", "new", "try", "catch", "finally", "throw", "throws",
                    "extends", "implements", "import", "export", "from", "async", "await", "yield", "class",
                    "interface", "enum", "type", "null", "true", "false", "this", "super", "of", "instanceof",
                    "typeof", "delete", "in", "void", "readonly", "static", "public", "private", "protected"),
            List.of("string", "number", "boolean", "any", "unknown", "never", "void", "object", "Promise", "Array",
                    "Function", "Record", "Date"));
    private static final Highlighter PYTHON = language(
            List.of("def", "class", "return", "if", "elif", "else", "for", "while", "try", "except", "finally",
                    "with", "as", "import", "from", "pass", "break", "continue", "lambda", "yield", "global",
                    "nonlocal", "raise", "assert", "del", "and", "or", "not", "in", "is", "None", "True", "False"),
            List.of("str", "int", "float", "bool", "list", "dict", "tuple", "set", "bytes", "object", "Exception",
                    "Any", "Optional", "Type"));
    private static final Highlighter SHELL = language(
            List.of("if", "then", "else", "elif", "fi", "for", "while", "do", "done", "case", "esac", "function",
                    "in", "return", "exit", "export", "local", "readonly", "echo", "printf", "cd", "ls", "mkdir",
                    "rm", "cp", "mv", "touch", "cat", "grep", "sed", "awk", "find", "sudo", "git", "node", "npm",
                    "mvn", "java"),
            List.of());
    private static final Highlighter SQL = language(
            List.of("select", "from", "where", "insert", "into", "values", "update", "set", "delete", "create",
                    "table", "drop", "alter", "index", "join", "left", "right", "inner", "outer", "on", "group",
                    "by", "order", "having", "limit", "offset", "and", "or", "not", "in", "like", "between", "is",
                    "null", "as", "distinct", "union", "all", "primary", "key", "foreign", "references",
                    "constraint", "default", "grant", "revoke"),
            List.of("int", "integer", "bigint", "varchar", "text", "char", "date", "datetime", "timestamp",
                    "boolean", "decimal", "numeric", "float", "double", "serial", "uuid"));
    private static final Highlighter XML = language(
            List.of("xmlns", "version", "encoding", "standalone", "DOCTYPE", "ENTITY", "SYSTEM", "PUBLIC"),
            List.of());
    private static final Highlighter GENERIC = language(
            List.of("return", "if", "else", "for", "while", "switch", "case", "break", "continue", "class",
                    "interface", "enum", "record", "function", "def", "import", "export", "var", "let", "const",
                    "new", "try", "catch", "finally", "throw", "this", "super", "null", "true", "false", "async",
                    "await", "void"),
            List.of());

    public static List<Segment> split(final String markdown) {
        final var out = new ArrayList<Segment>();
        if (markdown == null || markdown.isBlank()) {
            return out;
        }
        final Matcher m = FENCE.matcher(markdown);
        var last = 0;
        while (m.find()) {
            final var before = markdown.substring(last, m.start());
            if (!before.isBlank()) {
                out.add(new Segment(Segment.Type.TEXT, null, before));
            }
            out.add(new Segment(Segment.Type.CODE, m.group(1) == null ? "" : m.group(1).strip(), m.group(2)));
            last = m.end();
        }
        if (last < markdown.length()) {
            final var rest = markdown.substring(last);
            if (!rest.isBlank()) {
                out.add(new Segment(Segment.Type.TEXT, null, rest));
            }
        }
        return out;
    }

    public static Text highlight(final String lang, final String code) {
        final var tokenizer = pick(lang);
        final var lines = code.replace("\r\n", "\n").split("\n", -1);
        final var result = new ArrayList<Line>(lines.length);
        for (final var line : lines) {
            result.add(Line.from(colored(tokenizer, line).toArray(Span[]::new)));
        }
        return Text.from(result);
    }

    private static List<Span> colored(final Highlighter h, final String line) {
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
        return out;
    }

    private static Style styleOf(final Matcher m) {
        if (m.group("str") != null) {
            return STRING;
        }
        if (m.group("com") != null) {
            return COMMENT;
        }
        if (m.group("num") != null) {
            return NUMBER;
        }
        if (m.group("tag") != null) {
            return TAG;
        }
        if (m.group("attr") != null) {
            return ATTR;
        }
        if (m.group("kw") != null) {
            return KEYWORD;
        }
        return TYPE;
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
        if (l.contains("xml") || l.contains("html") || l.contains("svg") || l.contains("css")) {
            return XML;
        }
        if (l.contains("javascript") || l.contains("typescript") || l.equals("js") || l.equals("ts") || l.equals("json")) {
            return TS;
        }
        return GENERIC;
    }
}