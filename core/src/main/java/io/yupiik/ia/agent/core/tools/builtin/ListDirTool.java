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
import static io.yupiik.ia.agent.core.tools.ToolArgs.optInt;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.Schemas;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.tools.ToolResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;

@ApplicationScoped
public class ListDirTool implements EmbeddedTool {

    private final CodingAgentConfiguration configuration;

    public ListDirTool(final CodingAgentConfiguration configuration) {
        this.configuration = configuration;
    }

    protected ListDirTool() {
        this.configuration = null;
    }

    @Override
    public String name() {
        return "list_dir";
    }

    @Override
    public String description() {
        return "List a directory content, one entry per line, directories end with /.";
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.READ;
    }

    @Override
    public Map<String, Object> schema() {
        return Schemas.object(Map.of(
                "path", Schemas.str("Directory path, default `.`."),
                "depth", Schemas.intProp("Recursion depth, default 1, max 3.")));
    }

    @Override
    public ToolResult execute(final Map<String, Object> args) {
        return execute(args, ToolContext.empty());
    }

    @Override
    public ToolResult execute(final Map<String, Object> args, final ToolContext context) {
        final var path = context.resolvePath(
                arg(args, "path") == null ? "." : arg(args, "path"),
                configuration == null ? null : configuration.resolveProjectDir());
        final var depth = (int) Math.min(3, Math.max(1, optInt(args, "depth", 1)));
        if (!Files.isDirectory(path)) {
            return ToolResult.error("Not a directory: " + path);
        }
        final var sb = new StringBuilder();
        final var count = new int[] {0};
        try (final var walk = Files.walk(path, depth)) {
            walk.sorted(Comparator.comparing(Path::toString)).skip(1).forEach(p -> {
                if (count[0]++ < 1000) {
                    sb.append(p).append(Files.isDirectory(p) ? "/" : "").append('\n');
                }
            });
        } catch (final IOException e) {
            return ToolResult.error("Cannot list " + path + ": " + e.getMessage());
        }
        if (count[0] > 1000) {
            sb.append("... ").append(count[0] - 1000).append(" more entries\n");
        }
        return ToolResult.ok(sb.toString());
    }
}
