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

import static io.yupiik.ia.agent.core.tools.builtin.ReadFileTool.arg;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.Schemas;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolResult;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

@ApplicationScoped
public class GlobTool implements EmbeddedTool {

    @Override
    public String name() {
        return "glob";
    }

    @Override
    public String description() {
        return "Find files by glob pattern (eg. `**/*.java`, `src/**/*.xml`).";
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
        final var sb = new StringBuilder();
        final var count = new AtomicInteger();
        try (final Stream<Path> walk = Files.walk(base)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> matcher.matches(
                            base.relativize(p).toString().isEmpty() ? p.getFileName() : base.relativize(p)))
                    .limit(500)
                    .forEach(p -> {
                        count.incrementAndGet();
                        sb.append(p).append('\n');
                    });
        } catch (final IOException e) {
            return ToolResult.error("Cannot walk " + base + ": " + e.getMessage());
        }
        return ToolResult.ok(count.get() + " match(es)\n" + sb);
    }
}
