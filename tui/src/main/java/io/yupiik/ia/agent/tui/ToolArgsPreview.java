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

import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Structured preview for the tool detail modal (Ctrl+O): instead of showing the
 * raw tool arguments as JSON, the meaningful payload of write/edit/patch tools
 * is rendered directly - file content (syntax-highlighted), the old/new strings
 * of an edit as a unified diff, or the applied patch itself. Everything is best
 * effort: unparseable arguments or an unknown tool fall back to {@code null}
 * and the caller keeps the historical rendering.
 */
public class ToolArgsPreview {
    private final ToolOutputFormat toolOutputFormat;
    private final SyntaxHighlighter syntaxHighlighter;
    private final DiffRenderer diffRenderer;

    public ToolArgsPreview(
            final ToolOutputFormat toolOutputFormat,
            final SyntaxHighlighter syntaxHighlighter,
            final DiffRenderer diffRenderer) {
        this.toolOutputFormat = toolOutputFormat;
        this.syntaxHighlighter = syntaxHighlighter;
        this.diffRenderer = diffRenderer;
    }

    /**
     * @return the preview lines for the tool detail modal, or {@code null} to
     *         fall back to the default output rendering
     */
    public List<Line> preview(final String name, final String argsJson, final int width, final Palette palette) {
        if (argsJson == null || argsJson.isBlank() || width < 20) {
            return null;
        }
        final var args = toolOutputFormat.parseArguments(argsJson);
        if (args == null) {
            return null;
        }
        return switch (name) {
            case "write_file" -> writeFile(args, width, palette);
            case "edit_file" -> editFile(args, width, palette);
            case "apply_patch" -> applyPatch(args, width, palette);
            default -> null;
        };
    }

    private List<Line> writeFile(final Map<String, Object> args, final int width, final Palette palette) {
        if (!(args.get("content") instanceof final String content)
                || !(args.get("path") instanceof final String path)) {
            return null;
        }
        final var lines = new ArrayList<Line>();
        lines.add(
                Line.from(Span.styled("path: ", palette.muted()), Span.styled(path, palette.bold(palette.primary()))));
        lines.add(Line.empty());
        final var lang = SyntaxHighlighter.guessLanguage(path);
        final var codeLines = syntaxHighlighter.highlightLines(lang == null ? "" : lang, content);
        lines.addAll(MarkdownLines.wrapCode(codeLines, width));
        return lines;
    }

    private List<Line> editFile(final Map<String, Object> args, final int width, final Palette palette) {
        if (!(args.get("path") instanceof final String path)) {
            return null;
        }
        final var oldString = args.get("oldString") instanceof String s ? s : "";
        final var newString = args.get("newString") instanceof String s ? s : "";
        if (oldString.isBlank() && newString.isBlank()) {
            return null;
        }
        final var patch = new StringBuilder();
        patch.append("--- a/").append(path).append('\n');
        patch.append("+++ b/").append(path).append('\n');
        for (final var line : splitLines(oldString)) {
            patch.append('-').append(line).append('\n');
        }
        for (final var line : splitLines(newString)) {
            patch.append('+').append(line).append('\n');
        }
        return diffRenderer.render(patch.toString().stripTrailing(), width, palette);
    }

    private List<Line> applyPatch(final Map<String, Object> args, final int width, final Palette palette) {
        if (!(args.get("patch") instanceof final String patch) || patch.isBlank()) {
            return null;
        }
        return diffRenderer.render(patch, width, palette);
    }

    private static List<String> splitLines(final String text) {
        return text == null || text.isEmpty()
                ? List.of()
                : List.of(text.replace("\r\n", "\n").split("\n", -1));
    }
}
