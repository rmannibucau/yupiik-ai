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
package io.yupiik.ia.agent.acp;

import io.yupiik.fusion.framework.build.api.json.JsonModel;
import java.util.List;
import java.util.Map;

/** Stable ACP v1 wire structures. Object appears only at method-dependent JSON-RPC union boundaries. */
public final class AcpModels {
    private AcpModels() {}

    @JsonModel
    public record Envelope(String jsonrpc, Object id, String method, Object params, Object result, RpcError error) {}

    @JsonModel
    public record RpcError(int code, String message) {}

    @JsonModel
    public record ErrorResponse(String jsonrpc, Object id, RpcError error) {}

    @JsonModel
    public record Empty() {}

    @JsonModel
    public record Initialize(Integer protocolVersion, Map<String, Object> clientCapabilities, Info clientInfo) {}

    @JsonModel
    public record ClientCapabilities(Fs fs, Boolean terminal) {}

    @JsonModel
    public record Fs(Boolean readTextFile, Boolean writeTextFile) {}

    @JsonModel
    public record Info(String name, String title, String version) {}

    @JsonModel
    public record Initialized(
            int protocolVersion, AgentCapabilities agentCapabilities, List<AuthMethod> authMethods, Info agentInfo) {}

    @JsonModel
    public record AuthMethod(String id, String name, String description, String type, List<String> args) {}

    @JsonModel
    public record AgentCapabilities(
            boolean loadSession,
            PromptCapabilities promptCapabilities,
            McpCapabilities mcpCapabilities,
            Map<String, Object> sessionCapabilities) {}

    @JsonModel
    public record PromptCapabilities(boolean image, boolean audio, boolean embeddedContext) {}

    @JsonModel
    public record McpCapabilities(boolean http, boolean sse) {}

    @JsonModel
    public record NewSession(String cwd, List<McpServer> mcpServers) {}

    @JsonModel
    public record LoadSession(String sessionId, String cwd, List<McpServer> mcpServers) {}

    @JsonModel
    public record ListSessions(String cwd, String cursor) {}

    @JsonModel
    public record SetModel(String sessionId, String modelId) {}

    @JsonModel
    public record McpServer(
            String type,
            String name,
            String command,
            List<String> args,
            List<Env> env,
            String url,
            List<HttpHeader> headers) {
        public McpServer(
                final String type,
                final String name,
                final String command,
                final List<String> args,
                final List<Env> env) {
            this(type, name, command, args, env, null, null);
        }
    }

    @JsonModel
    public record HttpHeader(String name, String value) {}

    @JsonModel
    public record Env(String name, String value) {}

    @JsonModel
    public record Created(String sessionId, Modes modes, List<ConfigOption> configOptions) {}

    @JsonModel
    public record ConfigValue(String value, String name, String description) {}

    @JsonModel
    public record ConfigOption(
            String id, String name, String category, String type, String currentValue, List<ConfigValue> options) {}

    @JsonModel
    public record SetConfig(String sessionId, String configId, Object value) {}

    @JsonModel
    public record ConfigResult(List<ConfigOption> configOptions) {}

    @JsonModel
    public record Loaded(Modes modes, List<ConfigOption> configOptions) {}

    @JsonModel
    public record ConfigUpdate(String sessionUpdate, List<ConfigOption> configOptions) {}

    @JsonModel
    public record Modes(String currentModeId, List<Mode> availableModes) {}

    @JsonModel
    public record Mode(String id, String name, String description) {}

    @JsonModel
    public record Session(String sessionId) {}

    @JsonModel
    public record SetMode(String sessionId, String modeId) {}

    @JsonModel
    public record Prompt(String sessionId, List<Content> prompt) {}

    @JsonModel
    public record Content(
            String type, String text, String uri, String name, String title, String description, String mimeType) {
        public static Content text(final String value) {
            return new Content("text", value, null, null, null, null, null);
        }
    }

    @JsonModel
    public record PromptResult(String stopReason) {}

    @JsonModel
    public record UpdateParams(String sessionId, Object update) {}

    @JsonModel
    public record ChunkUpdate(String sessionUpdate, Content content) {}

    @JsonModel
    public record ToolUpdate(
            String sessionUpdate,
            String toolCallId,
            String title,
            String kind,
            String status,
            List<ToolContent> content) {}

    @JsonModel
    public record ModeUpdate(String sessionUpdate, String currentModeId) {}

    @JsonModel
    public record ToolContent(String type, Content content) {}

    @JsonModel
    public record ToolCall(String toolCallId, String title, String kind, String status, Map<String, Object> rawInput) {}

    @JsonModel
    public record Permission(String sessionId, ToolCall toolCall, List<PermissionOption> options) {}

    @JsonModel
    public record PermissionOption(String optionId, String name, String kind) {}

    @JsonModel
    public record PermissionResult(Outcome outcome) {}

    @JsonModel
    public record Outcome(String outcome, String optionId) {}
}
