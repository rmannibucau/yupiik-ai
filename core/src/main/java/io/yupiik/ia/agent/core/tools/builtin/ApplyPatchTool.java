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

import static io.yupiik.ia.agent.core.tools.ToolArgs.arg;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.Schemas;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class ApplyPatchTool implements EmbeddedTool {
    private static final String DEV_NULL = "/dev/null";
    /** How far a hunk may drift from its declared position before being rejected. */
    private static final int ANCHOR_FUZZ = 5;

    private final CodingAgentConfiguration configuration;

    public ApplyPatchTool(final CodingAgentConfiguration configuration) {
        this.configuration = configuration;
    }

    protected ApplyPatchTool() {
        this.configuration = null;
    }

    @Override
    public String name() {
        return "apply_patch";
    }

    @Override
    public String description() {
        return "Apply a unified diff patch to the project files. Uses `---`/`+++` file headers with `@@` hunks; `/dev/null` creates or deletes a file. Fails on context mismatch without modifying anything.";
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.EDIT;
    }

    @Override
    public Map<String, Object> schema() {
        return Schemas.object(Map.of("patch", Schemas.str("The unified diff to apply.")), "patch");
    }

    @Override
    public ToolResult execute(final Map<String, Object> args) {
        final var patch = arg(args, "patch");
        if (patch == null || patch.isBlank()) {
            return ToolResult.error("patch is required");
        }
        final var base = configuration.resolveProjectDir();
        try {
            final var changes = parse(patch);
            final var applied = new ArrayList<String>();
            final var created = new ArrayList<String>();
            final var deleted = new ArrayList<String>();
            for (final var change : changes) {
                final var path = resolve(base, change.path());
                if (change.deleteOnly()) {
                    if (Files.deleteIfExists(path)) {
                        deleted.add(display(path));
                    }
                    continue;
                }
                if (path.getParent() != null) {
                    Files.createDirectories(path.getParent());
                }
                final var target = Files.isRegularFile(path)
                        ? new ArrayList<>(Files.readAllLines(path, StandardCharsets.UTF_8))
                        : new ArrayList<String>();
                final var result = applyHunks(target, change.hunks());
                Files.write(path, result, StandardCharsets.UTF_8);
                if (change.createOnly()) {
                    created.add(display(path));
                } else {
                    applied.add(display(path));
                }
            }
            final var sb = new StringBuilder();
            appendSummary(sb, "Applied", applied);
            appendSummary(sb, "Created", created);
            appendSummary(sb, "Deleted", deleted);
            return ToolResult.ok(
                    sb.isEmpty() ? "Patch applied successfully." : sb.toString().strip());
        } catch (final PatchException e) {
            return ToolResult.error(e.getMessage());
        } catch (final Exception e) {
            return ToolResult.error("Cannot apply patch: " + e.getMessage());
        }
    }

    private static void appendSummary(final StringBuilder sb, final String label, final List<String> items) {
        if (items.isEmpty()) {
            return;
        }
        sb.append(label).append(' ').append(items.size()).append(" file(s):\n");
        items.forEach(p -> sb.append("  ").append(p).append('\n'));
    }

    private static String display(final Path path) {
        return path.toString();
    }

    private static Path resolve(final Path base, final String raw) {
        var clean = raw;
        if (clean.startsWith("a/") || clean.startsWith("b/")) {
            clean = clean.substring(2);
        }
        if (DEV_NULL.equals(clean)) {
            throw new PatchException("Unexpected /dev/null path header");
        }
        final var candidate = base.resolve(clean).normalize();
        if (!candidate.startsWith(base)) {
            throw new PatchException("Patch path escapes the project directory: " + raw);
        }
        return candidate;
    }

    private static List<String> applyHunks(final List<String> original, final List<Hunk> hunks) {
        final var lines = new ArrayList<>(original);
        var drift = 0;
        for (final var hunk : hunks) {
            applyHunk(lines, hunk, drift);
            drift += hunk.newCount - hunk.oldCount;
        }
        return lines;
    }

    private static void applyHunk(final List<String> lines, final Hunk hunk, final int drift) {
        final var start = resolveStart(lines, hunk, drift);
        final var out = new ArrayList<String>(lines.size() + 8);
        out.addAll(lines.subList(0, start));
        var src = start;
        var removedCount = 0;
        var additionsCount = 0;
        for (final var line : hunk.lines) {
            if (line.isEmpty()) {
                continue;
            }
            final var c = line.charAt(0);
            final var content = line.substring(1);
            if (c == ' ') {
                out.add(lines.get(src));
                src++;
            } else if (c == '-') {
                if (src >= lines.size() || !lines.get(src).equals(content)) {
                    throw new PatchException("Hunk context did not match the file contents");
                }
                src++;
                removedCount++;
            } else if (c == '+') {
                out.add(content);
                additionsCount++;
            } else {
                throw new PatchException("Malformed hunk line: " + line);
            }
        }
        if (hunk.newCount != hunk.oldCount - removedCount + additionsCount) {
            throw new PatchException("Hunk adds " + additionsCount + " lines and removes " + removedCount
                    + " but the @@ header declares old " + hunk.oldCount + " new " + hunk.newCount);
        }
        out.addAll(lines.subList(src, lines.size()));
        lines.clear();
        lines.addAll(out);
    }

    /**
     * Resolves where the hunk binds. Anchored hunks bind at their declared
     * position (plus the drift of the previously applied hunks), with a small
     * fuzz window; anything else is rejected instead of scanning the whole
     * file - a hunk that matched far from its anchor used to silently drop the
     * lines before the binding point (the head-truncation bug). Hunks without
     * positions (bare {@code @@}) are only applied when their body matches at
     * exactly one offset, so a repeated block can never be spliced at the
     * wrong occurrence.
     */
    private static int resolveStart(final List<String> lines, final Hunk hunk, final int drift) {
        if (hunk.oldStart != null) {
            final var declared = Math.max(0, hunk.oldStart - 1 + drift);
            if (matchesAt(lines, declared, hunk) == hunk.oldCount) {
                return declared;
            }
            for (var offset = Math.max(0, declared - ANCHOR_FUZZ);
                    offset <= Math.min(lines.size(), declared + ANCHOR_FUZZ);
                    offset++) {
                if (matchesAt(lines, offset, hunk) == hunk.oldCount) {
                    return offset;
                }
            }
            throw new PatchException("Hunk context did not match the file contents around line " + (declared + 1));
        }
        var found = -1;
        for (var offset = 0; offset <= lines.size(); offset++) {
            if (matchesAt(lines, offset, hunk) == hunk.oldCount) {
                if (found >= 0) {
                    throw new PatchException("Hunk context is ambiguous: it matches at lines " + (found + 1) + " and "
                            + (offset + 1) + "; add explicit positions to the @@ header");
                }
                found = offset;
            }
        }
        if (found < 0) {
            throw new PatchException("Hunk context did not match the file contents");
        }
        return found;
    }

    /** Returns how many context/removal lines of the hunk match at {@code start}, or -1 on mismatch. */
    private static int matchesAt(final List<String> lines, final int start, final Hunk hunk) {
        var consumed = 0;
        var idx = start;
        for (final var line : hunk.lines) {
            if (line.isEmpty()) {
                continue;
            }
            final var c = line.charAt(0);
            if (c == ' ' || c == '-') {
                if (idx >= lines.size() || !lines.get(idx).equals(line.substring(1))) {
                    return -1;
                }
                idx++;
                consumed++;
            } else if (c != '+') {
                return -1;
            }
        }
        return consumed;
    }

    private static List<Change> parse(final String patch) {
        final var lines = patch.replace("\r\n", "\n").lines().toList();
        final var changes = new ArrayList<Change>();
        String oldPath = null;
        String newPath = null;
        List<Hunk> hunks = null;
        String currentHeader = null;
        List<String> current = null;
        for (final var line : lines) {
            if (line.startsWith("--- ")) {
                if (oldPath != null) {
                    if (current != null) {
                        hunks.add(Hunk.of(currentHeader, current));
                    }
                    changes.add(Change.of(oldPath, newPath, hunks));
                }
                oldPath = line.substring(4).strip();
                newPath = null;
                hunks = new ArrayList<>();
                currentHeader = null;
                current = null;
            } else if (line.startsWith("+++ ")) {
                newPath = line.substring(4).strip();
            } else if (line.startsWith("@@")) {
                if (current != null) {
                    hunks.add(Hunk.of(currentHeader, current));
                }
                currentHeader = line;
                current = new ArrayList<>();
            } else if (current != null) {
                current.add(line);
            }
            // lines before any hunk (file headers) are ignored
        }
        if (oldPath != null) {
            if (current != null) {
                hunks.add(Hunk.of(currentHeader, current));
            }
            changes.add(Change.of(oldPath, newPath, hunks));
        }
        return changes;
    }

    /**
     * A parsed hunk. {@code oldStart}/{@code newStart} are 1-based line numbers
     * from the {@code @@ -oldStart,oldCount +newStart,newCount @@} header;
     * {@code oldStart} is null when the header carried no positions (bare
     * {@code @@}), in which case the counts are derived from the body.
     */
    private record Hunk(Integer oldStart, int oldCount, int newCount, List<String> lines) {
        static Hunk of(final String header, final List<String> body) {
            final var parsed = header == null ? null : positions(header);
            if (parsed != null) {
                return new Hunk(parsed[0], parsed[1], parsed[2], List.copyOf(body));
            }
            var oldCount = 0;
            var newCount = 0;
            for (final var line : body) {
                if (!line.isEmpty()) {
                    final var c = line.charAt(0);
                    if (c == ' ') { // context lines exist on both sides of the diff
                        oldCount++;
                        newCount++;
                    } else if (c == '+') {
                        newCount++;
                    } else if (c == '-') {
                        oldCount++;
                    }
                }
            }
            return new Hunk(null, oldCount, newCount, List.copyOf(body));
        }

        /** Returns {oldStart, oldCount, newCount}, or null when the header carries no positions. */
        private static int[] positions(final String header) {
            Integer oldStart = null;
            Integer oldCount = null;
            Integer newCount = null;
            for (final var token : header.split(" ")) {
                final var t = token.replace("@", "").strip();
                if (t.isEmpty()) {
                    continue;
                }
                final var isOld = t.startsWith("-");
                final var isNew = t.startsWith("+");
                if (!isOld && !isNew) {
                    continue;
                }
                final var pair = t.substring(1).split(",", 2);
                try {
                    if (isOld) {
                        oldStart = Integer.parseInt(pair[0]);
                        oldCount = pair.length == 2 ? Integer.parseInt(pair[1]) : 1;
                    } else {
                        newCount = pair.length == 2 ? Integer.parseInt(pair[1]) : 1;
                    }
                } catch (final NumberFormatException nfe) {
                    throw new PatchException("Malformed hunk header: " + header);
                }
            }
            if (oldStart == null || oldCount == null || newCount == null) {
                return null;
            }
            return new int[] {oldStart, oldCount, newCount};
        }
    }

    private record Change(String path, boolean deleteOnly, boolean createOnly, List<Hunk> hunks) {
        static Change of(final String oldPath, final String newPath, final List<Hunk> hunks) {
            final boolean deleteOnly = DEV_NULL.equals(newPath);
            final boolean createOnly = DEV_NULL.equals(oldPath);
            final var path = createOnly || deleteOnly ? (deleteOnly ? oldPath : newPath) : newPath;
            return new Change(path, deleteOnly, createOnly, hunks == null ? List.of() : hunks);
        }
    }

    private static final class PatchException extends RuntimeException {
        private PatchException(final String message) {
            super(message);
        }
    }
}
