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
        return "Read a text file, returns numbered lines. Use offset/limit for large files.";
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
            return ToolResult.error("File not found: " + path);
        }
        final var offset = (int) Math.max(1, optInt(args, "offset", 1));
        final var limit = (int) Math.min(10_000, optInt(args, "limit", 2000));
        try {
            final var all = Files.readAllLines(path);
            final var sb = new StringBuilder();
            var count = 0;
            var truncated = false;
            for (var i = offset; i <= all.size() && count < limit; i++, count++) {
                final var line = all.get(i - 1);
                sb.append(String.format("%6d\t", i))
                        .append(line.length() > 2000 ? line.substring(0, 2000) + "..." : line)
                        .append('\n');
                if (sb.length() > 60_000) {
                    truncated = true;
                    break;
                }
            }
            if (truncated) {
                sb.append("... output truncated, use offset/limit to read more\n");
            } else if (offset + count - 1 < all.size()) {
                sb.append("... ")
                        .append(all.size() - (offset + count - 1))
                        .append(" more lines, use offset to continue\n");
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
