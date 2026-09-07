package io.yupiik.ia.agent.core.tools.builtin;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.Schemas;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static io.yupiik.ia.agent.core.tools.builtin.ReadFileTool.optBool;
import static io.yupiik.ia.agent.core.tools.builtin.ReadFileTool.optInt;
import static io.yupiik.ia.agent.core.tools.builtin.ReadFileTool.arg;

@ApplicationScoped
public class GrepTool implements EmbeddedTool {

    private static final PathMatcher SKIP = p -> {
        final var n = p.toString();
        return n.contains("/target/") || n.contains("/.git/") || n.contains("/node_modules/") || n.contains("/build/");
    };

    @Override
    public String name() {
        return "grep";
    }

    @Override
    public String description() {
        return "Search a regex in file contents, returns `path:line: text` matches.";
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.READ;
    }

    @Override
    public Map<String, Object> schema() {
        return Schemas.object(Map.of(
                "pattern", Schemas.str("Regular expression to search."),
                "path", Schemas.str("Base directory or file, default `.`."),
                "include", Schemas.str("Glob filter on file names, eg. `*.java`."),
                "ignoreCase", Schemas.bool("Case insensitive search."),
                "maxResults", Schemas.intProp("Maximum number of matches, default 80.")), "pattern");
    }

    @Override
    public ToolResult execute(final Map<String, Object> args) {
        final var pattern = arg(args, "pattern");
        if (pattern == null || pattern.isBlank()) {
            return ToolResult.error("pattern is required");
        }
        final var regex = Pattern.compile(pattern, optBool(args, "ignoreCase", false) ? Pattern.CASE_INSENSITIVE : 0);
        final var base = Path.of(arg(args, "path") == null ? "." : arg(args, "path"));
        final var include = arg(args, "include");
        final var includeMatcher = include == null ? null : FileSystems.getDefault().getPathMatcher("glob:" + include);
        final var max = (int) Math.min(1000, Math.max(1, optInt(args, "maxResults", 80)));
        final var sb = new StringBuilder();
        final var count = new AtomicInteger();
        final var filesScanned = new AtomicInteger();
        try (final Stream<Path> walk = Files.isRegularFile(base) ? Stream.of(base) : Files.walk(base)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> !SKIP.matches(p))
                    .filter(p -> includeMatcher == null || includeMatcher.matches(p.getFileName()))
                    .forEach(p -> {
                        if (count.get() >= max) {
                            return;
                        }
                        try {
                            if (Files.size(p) > 2_000_000) {
                                return;
                            }
                            filesScanned.incrementAndGet();
                            final var lines = Files.readAllLines(p, StandardCharsets.UTF_8);
                            for (var i = 0; i < lines.size() && count.get() < max; i++) {
                                if (regex.matcher(lines.get(i)).find()) {
                                    count.incrementAndGet();
                                    final var text = lines.get(i).strip();
                                    sb.append(p).append(':').append(i + 1).append(": ")
                                            .append(text.length() > 300 ? text.substring(0, 300) + "..." : text).append('\n');
                                }
                            }
                        } catch (final IOException e) {
                            // skip unreadable/binary
                        }
                    });
        } catch (final IOException e) {
            return ToolResult.error("Cannot search " + base + ": " + e.getMessage());
        }
        if (count.get() == 0) {
            return ToolResult.ok("No match (" + filesScanned.get() + " files scanned)");
        }
        if (count.get() >= max) {
            sb.append("... results capped at ").append(max).append('\n');
        }
        return ToolResult.ok(count.get() + " match(es)\n" + sb);
    }
}
