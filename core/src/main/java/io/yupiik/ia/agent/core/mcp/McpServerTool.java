package io.yupiik.ia.agent.core.mcp;

import java.util.Map;

public record McpServerTool(String name, String description, Map<String, Object> inputSchema) {
}
