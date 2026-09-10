package io.yupiik.ia.agent.core.llm;

import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.framework.build.api.json.JsonProperty;

/** Anthropic tool declaration; {@code inputSchema} is a JSON schema. */
@JsonModel
public record AnthropicTool(
        String name,
        String description,
        @JsonProperty("input_schema") Object inputSchema) {
}