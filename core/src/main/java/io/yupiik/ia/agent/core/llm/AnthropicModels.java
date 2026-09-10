package io.yupiik.ia.agent.core.llm;

import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.framework.build.api.json.JsonProperty;

import java.util.List;

/** Anthropic {@code /models} response. */
@JsonModel
public record AnthropicModels(
        List<Model> data,
        @JsonProperty("has_more") Boolean hasMore,
        @JsonProperty("first_id") String firstId,
        @JsonProperty("last_id") String lastId) {

    @JsonModel
    public record Model(
            String type,
            String id,
            @JsonProperty("display_name") String displayName,
            @JsonProperty("created_at") String createdAt,
            @JsonProperty("max_input_tokens") Integer maxInputTokens,
            @JsonProperty("max_tokens") Integer maxTokens) {
    }
}