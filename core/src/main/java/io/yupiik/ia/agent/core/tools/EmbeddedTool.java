package io.yupiik.ia.agent.core.tools;

import java.util.Map;

public interface EmbeddedTool {

    String name();

    String description();

    ToolCategory category();

    Map<String, Object> schema();

    default boolean readonly() {
        return false;
    }

    ToolResult execute(Map<String, Object> args);
}
