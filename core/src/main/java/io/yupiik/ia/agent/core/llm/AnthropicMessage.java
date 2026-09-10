package io.yupiik.ia.agent.core.llm;

import io.yupiik.fusion.framework.build.api.json.JsonModel;

/** An Anthropic message: {@code content} is a string or a list of content blocks. */
@JsonModel
public record AnthropicMessage(String role, Object content) {
}