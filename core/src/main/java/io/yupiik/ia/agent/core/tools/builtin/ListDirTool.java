package io.yupiik.ia.agent.core.tools.builtin;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.Schemas;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;

import static io.yupiik.ia.agent.core.tools.builtin.ReadFileTool.arg;
import static io.yupiik.ia.agent.core.tools.builtin.ReadFileTool.optInt;

@ApplicationScoped
public class ListDirTool implements EmbeddedTool {

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
        final var path = Path.of(arg(args, "path") == null ? "." : arg(args, "path"));
        final var depth = (int) Math.min(3, Math.max(1, optInt(args, "depth", 1)));
        if (!Files.isDirectory(path)) {
            return ToolResult.error("Not a directory: " + path);
        }
        final var sb = new StringBuilder();
        final var count = new int[]{0};
        try (final var walk = Files.walk(path, depth)) {
            walk.sorted(Comparator.comparing(Path::toString))
                    .skip(1)
                    .forEach(p -> {
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
