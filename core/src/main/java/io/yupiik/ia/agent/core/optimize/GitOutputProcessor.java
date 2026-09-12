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
package io.yupiik.ia.agent.core.optimize;

import static io.yupiik.ia.agent.core.optimize.Optimizers.matchesHead;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

@ApplicationScoped
public class GitOutputProcessor implements OutputProcessor {
    private static final int MAX_HUNK_LINES = 8;
    private static final int MAX_LOG_BODY = 3;
    private static final java.util.regex.Pattern WHITESPACE = java.util.regex.Pattern.compile("\\s+");

    @Override
    public boolean supports(final String command) {
        return matchesHead(command, "git");
    }

    @Override
    public int order() {
        return 12;
    }

    @Override
    public Set<String> names() {
        return Set.of("git");
    }

    @Override
    public Stream<String> process(final String command, final Stream<String> lines) {
        final var sub = subCommand(command);
        return switch (sub) {
            case "diff" ->
                Optimizers.containsFlag(
                                command,
                                "-p",
                                "-u",
                                "--patch",
                                "--stat",
                                "--numstat",
                                "--word-diff",
                                "--name-only",
                                "--name-status")
                        ? lines
                        : processDiff(lines);
            case "log" ->
                Optimizers.containsFlag(
                                command,
                                "-p",
                                "-u",
                                "--patch",
                                "--stat",
                                "--format",
                                "--pretty",
                                "--oneline",
                                "--graph")
                        ? lines
                        : processLog(lines, false);
            case "show" ->
                Optimizers.containsFlag(command, "--stat", "--format", "--pretty", "--name-only", "--name-status")
                        ? lines
                        : processShow(lines);
            case "status" -> Optimizers.containsFlag(command, "--porcelain") ? lines : processStatus(lines);
            default -> lines;
        };
    }

    private static String subCommand(final String command) {
        final var parts = WHITESPACE.split(command.strip());
        if (parts.length < 2) {
            return "";
        }
        var idx = 1;
        if (parts[idx].startsWith("-") && idx + 1 < parts.length) {
            idx++;
        }
        return parts[idx];
    }

    private Stream<String> processDiff(final Stream<String> lines) {
        final var all = lines.toList();
        final var out = new ArrayList<String>();
        var inHunk = false;
        var hunkLines = 0;
        var hunkSkipped = 0;
        var files = 0;
        var dropped = 0;
        for (final var line : all) {
            if (line.startsWith("diff --git ")) {
                files++;
                inHunk = false;
                out.add("");
                out.add(line);
                continue;
            }
            if (line.startsWith("index ")
                    || line.startsWith("new file mode")
                    || line.startsWith("deleted file mode")
                    || line.startsWith("similarity index")
                    || line.startsWith("rename ")) {
                dropped++;
                continue;
            }
            if (line.startsWith("@@")) {
                inHunk = true;
                hunkLines = 0;
                hunkSkipped = 0;
                out.add(line);
                continue;
            }
            if (inHunk) {
                if (hunkLines < MAX_HUNK_LINES) {
                    hunkLines++;
                    out.add(line);
                } else {
                    hunkSkipped++;
                }
                continue;
            }
            out.add(line);
        }
        dropped += hunkSkipped;
        if (hunkSkipped > 0) {
            out.add("... +" + hunkSkipped + " more lines in last hunk");
        }
        if (files > 0 && dropped > 0) {
            out.add("[processed diff of " + files
                    + " file(s); run the command with --no-pager and without the optimizer for the full output]");
        }
        return out.stream();
    }

    private Stream<String> processLog(final Stream<String> lines, final boolean withDiff) {
        final var out = new ArrayList<String>();
        final var buffer = new ArrayList<String>();
        var body = 0;
        for (final var raw : lines.toList()) {
            if (raw.startsWith("commit ")) {
                flushLogEntry(out, buffer);
                buffer.clear();
                body = 0;
                buffer.add(raw.substring("commit ".length(), Math.min(20, raw.length())));
                continue;
            }
            if (raw.startsWith("Author:") || raw.startsWith("Date:") || raw.startsWith("Merge:") || raw.isBlank()) {
                continue;
            }
            if (body < MAX_LOG_BODY) {
                buffer.add("  " + raw.strip());
                body++;
            }
        }
        flushLogEntry(out, buffer);
        return out.stream();
    }

    private void flushLogEntry(final List<String> out, final List<String> buffer) {
        if (buffer.isEmpty()) {
            return;
        }
        final var sha = buffer.get(0);
        out.add(sha);
        for (var i = 1; i < buffer.size(); i++) {
            out.add(buffer.get(i));
        }
    }

    private Stream<String> processShow(final Stream<String> lines) {
        final var all = lines.toList();
        final var out = new ArrayList<String>();
        var inDiff = false;
        var headerLines = 0;
        final var diffLines = new ArrayList<String>();
        for (final var line : all) {
            if (!inDiff) {
                if (line.startsWith("diff --git ")) {
                    inDiff = true;
                    diffLines.add(line);
                    continue;
                }
                if (headerLines < 8) {
                    out.add(line);
                    headerLines++;
                } else if (headerLines == 8) {
                    out.add("...");
                    headerLines++;
                }
                continue;
            }
            diffLines.add(line);
        }
        if (!diffLines.isEmpty()) {
            processDiff(diffLines.stream()).forEach(out::add);
        }
        return out.stream();
    }

    private Stream<String> processStatus(final Stream<String> lines) {
        return lines.filter(line -> !line.isBlank())
                .filter(line -> !line.strip().startsWith("(use "))
                .filter(line -> !line.startsWith("nothing to commit"))
                .map(Optimizers::stripAnsi);
    }
}
