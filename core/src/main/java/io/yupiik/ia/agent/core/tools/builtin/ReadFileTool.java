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
package io.yupiik.ia.agent.core.tools.builtin;

import static io.yupiik.ia.agent.core.tools.Schemas.str;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.Schemas;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@ApplicationScoped
public class ReadFileTool implements EmbeddedTool {

    @Override
    public String name() {
        return "read_file";
    }

    @Override
    public String description() {
        return "Read a text file, returns numbered lines (`<line>: <content>` aligned for grep correlation). "
                + "By default returns up to 2000 lines from the start; use offset (1-based line) and limit to read "
                + "more, and grep to locate content in large files first. Call this in parallel with other reads "
                + "when you know there are multiple files to inspect; avoid tiny repeated slices.";
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.READ;
    }

    @Override
    public Map<String, Object> schema() {
        return Schemas.object(
                Map.of(
                        "path", str("File path, absolute or relative to the working directory."),
                        "offset", Schemas.intProp("1-based line to start from, default 1."),
                        "limit", Schemas.intProp("Maximum number of lines to read, default 2000.")),
                "path");
    }

    @Override
    public ToolResult execute(final Map<String, Object> args) {
        final var path = Path.of(arg(args, "path"));
        if (!Files.isRegularFile(path)) {
            final var suggestion = suggestPath(path);
            return ToolResult.error(
                    "File not found: " + path + (suggestion == null ? "" : ". Did you mean: " + suggestion + "?"));
        }
        final var offset = (int) Math.max(1, optInt(args, "offset", 1));
        final var limit = (int) Math.min(10_000, optInt(args, "limit", 2000));
        return renderLines(path, offset, offset + limit - 1);
    }

    /**
     * Fuzzy recovery for a mistyped path: finds the closest existing ancestor directory and suggests the most
     * similar regular file under it, turning a wasted round-trip into a one-shot correction.
     */
    static Path suggestPath(final Path path) {
        Path anchor = path.toAbsolutePath().normalize();
        while (anchor != null && !Files.isDirectory(anchor)) {
            anchor = anchor.getParent();
        }
        if (anchor == null) {
            return null;
        }
        final var wanted =
                path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase();
        if (wanted.isEmpty()) {
            return null;
        }
        Path best = null;
        var bestScore = Integer.MAX_VALUE;
        var visited = 0;
        try (final var stream = Files.walk(anchor, 6)) {
            final var it = stream.iterator();
            while (it.hasNext() && visited < 2000) {
                final var candidate = it.next();
                if (!Files.isRegularFile(candidate)) {
                    continue;
                }
                visited++;
                final var score =
                        levenshtein(wanted, candidate.getFileName().toString().toLowerCase());
                if (score < bestScore) {
                    bestScore = score;
                    best = candidate;
                }
            }
        } catch (final IOException | UncheckedIOException e) {
            return null;
        }
        return best != null && bestScore <= Math.max(3, wanted.length() / 3) ? best : null;
    }

    static int levenshtein(final String a, final String b) {
        if (a.equals(b)) {
            return 0;
        }
        final var prev = new int[b.length() + 1];
        final var curr = new int[b.length() + 1];
        for (var j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (var i = 1; i <= a.length(); i++) {
            curr[0] = i;
            for (var j = 1; j <= b.length(); j++) {
                final var cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            System.arraycopy(curr, 0, prev, 0, prev.length);
        }
        return prev[b.length()];
    }

    /**
     * Streams lines {@code fromLine..toLine} (1-based, inclusive) without materializing the whole file.
     * Shared with {@code read_symbol} so both tools render identical, correlated line numbers.
     */
    public static ToolResult renderLines(final Path path, final int fromLine, final int toLine) {
        try (final var reader = Files.newBufferedReader(path)) {
            final var sb = new StringBuilder();
            var lineNumber = 0;
            var count = 0;
            var truncated = false;
            String line;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (lineNumber < fromLine) {
                    continue;
                }
                if (lineNumber > toLine) {
                    break;
                }
                count++;
                sb.append(String.format("%6d\t", lineNumber))
                        .append(line.length() > 2000 ? line.substring(0, 2000) + "..." : line)
                        .append('\n');
                if (sb.length() > 60_000) {
                    truncated = true;
                    break;
                }
            }
            if (truncated) {
                sb.append("... output truncated, use offset/limit to read more\n");
            } else if (line != null) {
                // stopped on the limit with more content below: point at the first unread line
                sb.append("... more lines below, use offset=")
                        .append(fromLine + count)
                        .append(" to continue\n");
            }
            return ToolResult.ok(sb.toString());
        } catch (final IOException | UncheckedIOException e) {
            return ToolResult.error("Cannot read " + path + ": " + e.getMessage());
        }
    }

    static String arg(final Map<String, Object> args, final String key) {
        return args.get(key) == null ? null : args.get(key).toString();
    }

    static long optInt(final Map<String, Object> args, final String key, final long dflt) {
        final var v = args.get(key);
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return v == null ? dflt : Long.parseLong(v.toString());
        } catch (final NumberFormatException e) {
            return dflt;
        }
    }

    static boolean optBool(final Map<String, Object> args, final String key, final boolean dflt) {
        final var v = args.get(key);
        return v instanceof Boolean b ? b : v != null && Boolean.parseBoolean(v.toString());
    }
}
