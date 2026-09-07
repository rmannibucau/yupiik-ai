package io.yupiik.ia.agent.core.mcp;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public interface McpClient extends AutoCloseable {

    List<String> SUPPORTED_PROTOCOL_VERSIONS = List.of("2025-06-18", "2025-03-26", "2024-11-05");

    void initialize() throws IOException, InterruptedException;

    List<McpServerTool> listTools() throws IOException, InterruptedException;

    String callTool(String name, Map<String, Object> arguments) throws IOException, InterruptedException;

    @Override
    void close();
}
