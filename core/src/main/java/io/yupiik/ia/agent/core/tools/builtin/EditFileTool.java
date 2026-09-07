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

import static io.yupiik.ia.agent.core.tools.Schemas.bool;
import static io.yupiik.ia.agent.core.tools.Schemas.str;
import static io.yupiik.ia.agent.core.tools.builtin.ReadFileTool.arg;
import static io.yupiik.ia.agent.core.tools.builtin.ReadFileTool.optBool;

@ApplicationScoped
public class EditFileTool implements EmbeddedTool {

    @Override
    public String name() {
        return "edit_file";
    }

    @Override
    public String description() {
        return "Replace an exact string in a file by another. oldString must match exactly once unless replaceAll is true.";
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.EDIT;
    }

    @Override
    public Map<String, Object> schema() {
        return Schemas.object(Map.of(
                "path", str("File path, absolute or relative to the working directory."),
                "oldString", str("Exact text to replace."),
                "newString", str("Replacement text."),
                "replaceAll", bool("Replace every occurrence instead of requiring an unique match.")),
                "path", "oldString", "newString");
    }

    @Override
    public ToolResult execute(final Map<String, Object> args) {
        final var path = Path.of(arg(args, "path"));
        final var oldString = arg(args, "oldString");
        final var newString = arg(args, "newString");
        if (oldString == null || oldString.isEmpty()) {
            return ToolResult.error("oldString is required");
        }
        if (newString == null) {
            return ToolResult.error("newString is required");
        }
        if (!Files.isRegularFile(path)) {
            return ToolResult.error("File not found: " + path);
        }
        try {
            final var content = Files.readString(path);
            var count = 0;
            var idx = 0;
            while ((idx = content.indexOf(oldString, idx)) >= 0) {
                count++;
                idx += oldString.length();
                if (count > 1 && !optBool(args, "replaceAll", false)) {
                    return ToolResult.error("oldString matches " + count + "+ times in " + path + ", provide more context or use replaceAll");
                }
            }
            if (count == 0) {
                return ToolResult.error("oldString not found in " + path);
            }
            Files.writeString(path, optBool(args, "replaceAll", false)
                    ? content.replace(oldString, newString)
                    : content.replaceFirst(java.util.regex.Pattern.quote(oldString), java.util.regex.Matcher.quoteReplacement(newString)));
            return ToolResult.ok("Replaced " + (optBool(args, "replaceAll", false) ? count : 1) + " occurrence(s) in " + path);
        } catch (final IOException | UncheckedIOException e) {
            return ToolResult.error("Cannot edit " + path + ": " + e.getMessage());
        }
    }
}
