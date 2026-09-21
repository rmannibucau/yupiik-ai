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
package io.yupiik.ia.agent.core.cli;

import io.yupiik.fusion.json.JsonMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns raw tool-call data into short, human-readable descriptions for the
 * CLI. Parsing relies on an injected {@link JsonMapper}; methods return plain
 * text (no ANSI styling - callers add color), so they degrade gracefully for
 * unknown or MCP tools by truncating the raw JSON.
 */
public final class ToolPresenter {
    private static final int RAW_TRUNCATE = 120;

    private final JsonMapper mapper;
    private final AskUserAnswersFormatter askUserAnswersFormatter;

    public ToolPresenter(final JsonMapper mapper) {
        this.mapper = mapper;
        this.askUserAnswersFormatter = new AskUserAnswersFormatter(mapper);
    }

    /**
     * One-line, human summary of a tool invocation, eg. {@code Bash(mvn -q test)}
     * or {@code Edit(src/main/java/Foo.java)}. Unknown tools fall back to a
     * truncated rendering of the raw arguments.
     */
    public String summarize(final String name, final String argsJson) {
        final var args = parse(argsJson);
        return switch (name == null ? "" : name) {
            case "bash" -> "Bash(" + compact(str(args, "command")) + suffixWorkdir(args) + ")";
            case "read_file" -> "Read(" + compact(str(args, "path")) + suffixRead(args) + ")";
            case "write_file" -> "Write(" + compact(str(args, "path")) + ")";
            case "edit_file" -> "Edit(" + compact(str(args, "path")) + ")";
            case "grep" -> "Search(" + shortString(str(args, "pattern")) + " in " + compact(str(args, "path")) + ")";
            case "glob" -> "Find(" + compact(str(args, "pattern")) + ")";
            case "list_dir" -> "List(" + compact(str(args, "path")) + ")";
            case "write_todo" -> "Todos(" + todoCount(argsJson) + ")";
            case "memory_write" -> "Memory(" + compact(str(args, "phase")) + ")";
            case "ask_user" -> "Ask(" + shortString(str(args, "question")) + ")";
            case "" -> "(unknown tool)";
            default -> name + "(" + truncate(argsJson) + ")";
        };
    }

    /**
     * Produces a small diff preview for edit/write tools, or an empty list when
     * the tool is not an edit/write call or there is nothing to diff. Lines are
     * plain text prefixed with {@code +}/{@code -}; callers apply color.
     */
    public List<String> diff(final String name, final String argsJson, final int maxLines) {
        final var args = parse(argsJson);
        if (name == null) {
            return List.of();
        }
        return switch (name) {
            case "edit_file" -> editDiff(args, maxLines);
            case "write_file" -> writeDiff(args, maxLines);
            default -> List.of();
        };
    }

    /**
     * One-line digest of a finished tool: first meaningful line on failure, the
     * last non-empty output line otherwise (covers {@code BUILD SUCCESS}, git,
     * maven and similar tail-significant commands).
     */
    public String result(final String name, final String output, final boolean success) {
        if (output == null || output.isBlank()) {
            return success ? "done" : "failed";
        }
        // ask_user answers read better as key: value pairs than a truncated JSON line
        if ("ask_user".equals(name)) {
            final var answers = askUserAnswersFormatter.answers(output);
            if (answers != null) {
                final var rendered = new StringBuilder();
                for (final var answer : answers) {
                    if (rendered.length() > 0) {
                        rendered.append('\n');
                    }
                    rendered.append(answer.key()).append(": ").append(answer.value());
                }
                return rendered.toString();
            }
        }
        if (success) {
            return truncate(lastMeaningfulLine(output));
        }
        return truncate(firstMeaningfulLine(output));
    }

    private Map<String, Object> parse(final String argsJson) {
        if (argsJson == null || argsJson.isBlank()) {
            return Map.of();
        }
        try {
            final var parsed = mapper.fromString(Object.class, argsJson);
            if (parsed instanceof Map<?, ?> map) {
                final var out = new java.util.LinkedHashMap<String, Object>();
                map.forEach((k, v) -> out.put(String.valueOf(k), v));
                return out;
            }
        } catch (final RuntimeException e) {
            // unparseable args; fall back to empty so summaries degrade gracefully
        }
        return Map.of();
    }

    private String suffixRead(final Map<String, Object> args) {
        final var offset = str(args, "offset");
        final var limit = str(args, "limit");
        final var sb = new StringBuilder();
        if (offset != null && !offset.isBlank()) {
            sb.append(" +").append(offset);
        }
        if (limit != null && !limit.isBlank()) {
            sb.append(": ").append(limit).append(" lines");
        }
        return sb.toString();
    }

    private String suffixWorkdir(final Map<String, Object> args) {
        final var workdir = str(args, "workdir");
        if (workdir == null || workdir.isBlank() || workdir.equals(".")) {
            return "";
        }
        return " · " + compact(workdir);
    }

    private static int todoCount(final String argsJson) {
        if (argsJson == null || argsJson.isBlank()) {
            return 0;
        }
        var count = 0;
        var i = 0;
        while ((i = argsJson.indexOf("\"content\"", i)) >= 0) {
            count++;
            i += "\"content\"".length();
        }
        return count;
    }

    private static List<String> editDiff(final Map<String, Object> args, final int maxLines) {
        final var oldString = strRaw(args, "oldString");
        final var newString = strRaw(args, "newString");
        if (oldString == null && newString == null) {
            return List.of();
        }
        final var out = new ArrayList<String>();
        if (oldString != null && !oldString.isBlank()) {
            for (final var line : oldString.split("\n", -1)) {
                if (out.size() >= maxLines) {
                    out.add("... more");
                    return out;
                }
                out.add("- " + line);
            }
        }
        if (newString != null && !newString.isBlank()) {
            for (final var line : newString.split("\n", -1)) {
                if (out.size() >= maxLines) {
                    out.add("... more");
                    return out;
                }
                out.add("+ " + line);
            }
        }
        return out;
    }

    private static List<String> writeDiff(final Map<String, Object> args, final int maxLines) {
        final var content = strRaw(args, "content");
        if (content == null || content.isBlank()) {
            return List.of();
        }
        final var out = new ArrayList<String>();
        final var lines = content.split("\n", -1);
        final var shown = Math.min(lines.length, maxLines);
        for (int i = 0; i < shown; i++) {
            out.add("+ " + lines[i]);
        }
        if (lines.length > shown) {
            out.add("... +" + (lines.length - shown) + " more");
        }
        return out;
    }

    private static String str(final Map<String, Object> args, final String key) {
        final var v = args.get(key);
        return v == null ? null : String.valueOf(v).replace('\n', ' ').strip();
    }

    private static String strRaw(final Map<String, Object> args, final String key) {
        final var v = args.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static String truncate(final String s) {
        if (s == null) {
            return "";
        }
        final var t = s.strip();
        if (t.length() <= RAW_TRUNCATE) {
            return t;
        }
        return t.substring(0, RAW_TRUNCATE) + "...";
    }

    private static String compact(final String s) {
        if (s == null || s.isBlank()) {
            return "?";
        }
        final var t = s.strip();
        if (t.length() <= 60) {
            return t;
        }
        return t.substring(0, 60) + "...";
    }

    private static String shortString(final String s) {
        if (s == null) {
            return "?";
        }
        final var t = s.strip();
        if (t.length() <= 24) {
            return t;
        }
        return t.substring(0, 24) + "...";
    }

    private static String lastMeaningfulLine(final String output) {
        final var lines = output.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            final var l = lines[i].strip();
            if (!l.isBlank()) {
                return l;
            }
        }
        return output.strip();
    }

    private static String firstMeaningfulLine(final String output) {
        for (final var line : output.split("\n")) {
            final var l = line.strip();
            if (!l.isBlank()) {
                return l;
            }
        }
        return output.strip();
    }
}
