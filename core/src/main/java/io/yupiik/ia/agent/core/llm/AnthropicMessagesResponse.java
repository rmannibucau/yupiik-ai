package io.yupiik.ia.agent.core.llm;

import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.framework.build.api.json.JsonProperty;

import java.util.List;

/** Anthropic messages API response. */
@JsonModel
public record AnthropicMessagesResponse(
        String id,
        String type,
        String role,
        List<ContentBlock> content,
        String model,
        @JsonProperty("stop_reason") String stopReason,
        @JsonProperty("stop_sequence") String stopSequence,
        AnthropicUsage usage) {

    /** A text, thinking or tool use ({@code id}/{@code name}/{@code input}) block. */
    @JsonModel
    public record ContentBlock(String type, String text, String thinking, String id, String name, Object input) {
    }

    @JsonModel
    public record AnthropicUsage(
            @JsonProperty("input_tokens") Long inputTokens,
            @JsonProperty("output_tokens") Long outputTokens) {
    }
}