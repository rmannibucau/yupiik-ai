package io.yupiik.ia.agent.core.tools.builtin;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.Schemas;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolResult;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.yupiik.ia.agent.core.tools.builtin.ReadFileTool.arg;

@ApplicationScoped
public class ApplyPatchTool implements EmbeddedTool {
    private static final String DEV_NULL = "/dev/null";

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
        return Schemas.object(Map.of(
                "patch", Schemas.str("The unified diff to apply.")), "patch");
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
            return ToolResult.ok(sb.isEmpty() ? "Patch applied successfully." : sb.toString().strip());
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

    private static List<String> applyHunks(final List<String> original, final List<List<String>> hunks) {
        return hunks.stream()
                .reduce(new ArrayList<>(original), ApplyPatchTool::applyHunk, (a, b) -> b);
    }

    private static List<String> applyHunk(final List<String> original, final List<String> hunk) {
        final var out = new ArrayList<String>(original.size() + 16);
        var src = 0;
        var matched = false;
        // find a start offset where the hunk's context/remove lines match
        for (var start = 0; start <= original.size(); start++) {
            out.clear();
            src = start;
            var ok = true;
            for (final var line : hunk) {
                if (line.isEmpty()) {
                    continue;
                }
                final var c = line.charAt(0);
                final var content = line.substring(1);
                if (c == ' ') {
                    if (src >= original.size() || !original.get(src).equals(content)) {
                        ok = false;
                        break;
                    }
                    src++;
                    out.add(content);
                } else if (c == '-') {
                    if (src >= original.size() || !original.get(src).equals(content)) {
                        ok = false;
                        break;
                    }
                    src++;
                } else if (c == '+') {
                    out.add(content);
                } else {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                matched = true;
                break;
            }
        }
        if (!matched) {
            throw new PatchException("Hunk context did not match the file contents");
        }
        // append remaining source lines
        for (; src < original.size(); src++) {
            out.add(original.get(src));
        }
        return out;
    }

    private static List<Change> parse(final String patch) {
        final var lines = patch.replace("\r\n", "\n").lines().toList();
        final var changes = new ArrayList<Change>();
        String oldPath = null;
        String newPath = null;
        List<List<String>> hunks = null;
        List<String> current = null;
        for (final var line : lines) {
            if (line.startsWith("--- ")) {
                if (oldPath != null) {
                    if (current != null) {
                        hunks.add(current);
                    }
                    changes.add(Change.of(oldPath, newPath, hunks));
                }
                oldPath = line.substring(4).strip();
                newPath = null;
                hunks = new ArrayList<>();
                current = null;
            } else if (line.startsWith("+++ ")) {
                newPath = line.substring(4).strip();
            } else if (line.startsWith("@@")) {
                if (current != null) {
                    hunks.add(current);
                }
                current = new ArrayList<>();
            } else if (current != null) {
                current.add(line);
            }
            // lines before any hunk (file headers) are ignored
        }
        if (oldPath != null) {
            if (current != null) {
                hunks.add(current);
            }
            changes.add(Change.of(oldPath, newPath, hunks));
        }
        return changes;
    }

    private record Change(String path, boolean deleteOnly, boolean createOnly, List<List<String>> hunks) {
        static Change of(final String oldPath, final String newPath, final List<List<String>> hunks) {
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