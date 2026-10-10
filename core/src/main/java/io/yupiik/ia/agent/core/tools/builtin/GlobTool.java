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
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.FileIgnore;
import io.yupiik.ia.agent.core.tools.Schemas;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolResult;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class GlobTool implements EmbeddedTool {

    private static final int MAX_RESULTS = 500;
    private static final List<String> SKIP_PARTS = List.of("/target/", "/.git/", "/node_modules/", "/build/", "/.yca/");

    @Override
    public String name() {
        return "glob";
    }

    @Override
    public String description() {
        return "Fast file pattern matching tool (eg. `**/*.java`, `src/**/*.xml`), skipping build output and ignored "
                + "directories. Use this instead of bash find/ls. You can call multiple tools in a single reply: "
                + "speculatively batch several potentially useful searches.";
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.READ;
    }

    @Override
    public Map<String, Object> schema() {
        return Schemas.object(
                Map.of(
                        "pattern", Schemas.str("Glob pattern, eg. `**/*.java`."),
                        "path", Schemas.str("Base directory, default `.`.")),
                "pattern");
    }

    @Override
    public ToolResult execute(final Map<String, Object> args) {
        final var pattern = arg(args, "pattern");
        final var base = Path.of(arg(args, "path") == null ? "." : arg(args, "path"));
        if (pattern == null || pattern.isBlank()) {
            return ToolResult.error("pattern is required");
        }
        final var matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        // glob semantics: '**/' requires a directory component, but agents expect `**/*.txt` to also
        // match files directly under the base - tolerate the zero-directory case
        final var flatMatcher = pattern.startsWith("**/")
                ? FileSystems.getDefault().getPathMatcher("glob:" + pattern.substring(3))
                : null;
        final var ignore = FileIgnore.of(FileIgnore.gitRoot(base), null);
        final var matches = new ArrayList<String>();
        final var truncated = new boolean[] {false};
        try {
            Files.walkFileTree(base, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs) {
                    if (!dir.equals(base) && skip(dir, ignore)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) {
                    if (skip(file, ignore)) {
                        return FileVisitResult.CONTINUE;
                    }
                    final var rel = base.relativize(file);
                    final var relPath = rel.toString().isEmpty() ? file.getFileName() : rel;
                    if (matcher.matches(relPath) || (flatMatcher != null && flatMatcher.matches(relPath))) {
                        if (matches.size() >= MAX_RESULTS) {
                            truncated[0] = true;
                            return FileVisitResult.TERMINATE;
                        }
                        matches.add(file.toString());
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (final IOException e) {
            return ToolResult.error("Cannot walk " + base + ": " + e.getMessage());
        }
        final var sb = new StringBuilder(matches.size() + " match(es)\n");
        for (final var m : matches) {
            sb.append(m).append('\n');
        }
        if (truncated[0]) {
            sb.append("... results capped at ").append(MAX_RESULTS).append('\n');
        }
        return ToolResult.ok(sb.toString());
    }

    private static boolean skip(final Path p, final FileIgnore ignore) {
        final var n = p.toString();
        for (final var part : SKIP_PARTS) {
            if (n.contains(part)) {
                return true;
            }
        }
        return ignore.isIgnored(p.toAbsolutePath().normalize());
    }
}
