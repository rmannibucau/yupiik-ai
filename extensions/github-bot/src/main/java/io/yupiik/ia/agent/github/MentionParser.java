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
package io.yupiik.ia.agent.github;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** Parses the first unquoted, non-code bot mention, excluding task-marked bot output. */
public final class MentionParser {
    private MentionParser() {}

    public static Optional<Invocation> parse(final String body, final String botLogin) {
        if (body == null || botLogin == null || botLogin.isBlank() || body.contains("<!-- yca-task:")) {
            return Optional.empty();
        }
        final var visible = visibleText(body);
        final var mention = Pattern.compile("(?i)(?<![\\w@/])@" + Pattern.quote(botLogin) + "(?![\\w-]|\\[bot\\])")
                .matcher(visible);
        if (!mention.find()) {
            return Optional.empty();
        }
        final var command = Pattern.compile("^[ \\t]+(solve|review|help)(?=$|\\s)", Pattern.CASE_INSENSITIVE)
                .matcher(visible.substring(mention.end()));
        if (!command.find()) {
            return Optional.empty();
        }
        final var remainder = visible.substring(mention.end() + command.end()).strip();
        return Optional.of(new Invocation(command.group(1).toLowerCase(Locale.ROOT), remainder));
    }

    private static String visibleText(final String body) {
        final var result = new StringBuilder();
        char fence = 0;
        int fenceLength = 0;
        int inline = 0;
        for (final var line : body.split("\\R", -1)) {
            final var trimmed = line.stripLeading();
            if (trimmed.startsWith(">")) {
                result.append('\n');
                continue;
            }
            final char first = trimmed.isEmpty() ? 0 : trimmed.charAt(0);
            int run = 0;
            if (first == '`' || first == '~') {
                while (run < trimmed.length() && trimmed.charAt(run) == first) {
                    run++;
                }
            }
            if (fence != 0) {
                if (first == fence
                        && run >= fenceLength
                        && trimmed.substring(run).isBlank()) {
                    fence = 0;
                }
                result.append('\n');
                continue;
            }
            if (run >= 3 && inline == 0) {
                fence = first;
                fenceLength = run;
                result.append('\n');
                continue;
            }
            for (int i = 0; i < line.length(); ) {
                if (line.charAt(i) == '`') {
                    int end = i + 1;
                    while (end < line.length() && line.charAt(end) == '`') {
                        end++;
                    }
                    final int size = end - i;
                    if (inline == 0) {
                        inline = size;
                    } else if (inline == size) {
                        inline = 0;
                    }
                    result.append(' ');
                    i = end;
                } else {
                    result.append(inline == 0 ? line.charAt(i) : ' ');
                    i++;
                }
            }
            result.append('\n');
        }
        return result.toString();
    }

    public record Invocation(String kind, String request) {}
}
