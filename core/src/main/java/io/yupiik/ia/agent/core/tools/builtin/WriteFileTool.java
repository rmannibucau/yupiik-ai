package io.yupiik.ia.agent.core.tools.builtin;

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

import static io.yupiik.ia.agent.core.tools.Schemas.str;
import static io.yupiik.ia.agent.core.tools.builtin.ReadFileTool.arg;

@ApplicationScoped
public class WriteFileTool implements EmbeddedTool {

    @Override
    public String name() {
        return "write_file";
    }

    @Override
    public String description() {
        return "Write content to a file, creating it (and parent directories) if needed. Overwrites the whole file.";
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.EDIT;
    }

    @Override
    public Map<String, Object> schema() {
        return Schemas.object(Map.of(
                "path", str("File path, absolute or relative to the working directory."),
                "content", str("Full file content to write.")), "path", "content");
    }

    @Override
    public ToolResult execute(final Map<String, Object> args) {
        final var path = Path.of(arg(args, "path"));
        final var content = arg(args, "content");
        if (content == null) {
            return ToolResult.error("content is required");
        }
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path, content);
            final var lines = content.isEmpty() ? 0 : content.split("\n", -1).length;
            return ToolResult.ok("Wrote " + lines + " lines to " + path);
        } catch (final IOException | UncheckedIOException e) {
            return ToolResult.error("Cannot write " + path + ": " + e.getMessage());
        }
    }
}
