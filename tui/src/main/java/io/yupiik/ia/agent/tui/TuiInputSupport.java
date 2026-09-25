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

import dev.tamboui.text.CharWidth;
import java.util.List;

/**
 * Pure input helpers of the TUI: slash command registry, word motions and
 * first-line extraction. Extracted from the TUI command so they can be unit
 * tested without a terminal.
 */
public final class TuiInputSupport {
    public static final List<String> SLASH_COMMANDS = List.of(
            "/help",
            "/shortcuts",
            "/new",
            "/resume",
            "/compact",
            "/model",
            "/provider",
            "/mode",
            "/memory",
            "/context",
            "/permissions",
            "/thinking",
            "/tool-output",
            "/structural-code-graph",
            "/maven-repository-knowledge",
            "/mcp",
            "/tools",
            "/agent",
            "/clear",
            "/quit",
            "/exit",
            "/session-id",
            "/session-flush",
            "/session-housekeeping");

    private TuiInputSupport() {}

    public static int nextWord(final String s, final int col) {
        final var len = s.length();
        var i = col;
        while (i < len && s.charAt(i) == ' ') {
            i++;
        }
        while (i < len && s.charAt(i) != ' ') {
            i++;
        }
        return Math.min(i, len);
    }

    public static int previousWord(final String s, final int col) {
        if (s.isEmpty() || col <= 0) {
            return 0;
        }
        var i = Math.min(col, s.length()) - 1;
        while (i >= 0 && i < s.length() && s.charAt(i) == ' ') {
            i--;
        }
        while (i >= 0 && i < s.length() && s.charAt(i) != ' ') {
            i--;
        }
        return i + 1;
    }

    /**
     * Character index (relative to the fragment) at the given display column: the
     * boundary of the char whose start cell is nearest to (but not past) {@code x}.
     * A click on the second cell of a wide (2-cell) char lands on that char's
     * start; a column past the fragment end maps to the fragment length.
     */
    public static int columnAtDisplayWidth(final String fragment, final int x) {
        if (fragment == null || fragment.isEmpty() || x <= 0) {
            return 0;
        }
        var width = 0;
        var index = 0;
        while (index < fragment.length()) {
            final var codePoint = fragment.codePointAt(index);
            final var charWidth = CharWidth.of(codePoint);
            if (width + charWidth > x) {
                return index;
            }
            width += charWidth;
            index += Character.charCount(codePoint);
        }
        return fragment.length();
    }

    public static String firstLine(final String text) {
        if (text == null) {
            return "";
        }
        final var idx = text.indexOf('\n');
        final var line = idx < 0 ? text : text.substring(0, idx);
        return line.length() > 120 ? line.substring(0, 120) + "..." : line;
    }
}
