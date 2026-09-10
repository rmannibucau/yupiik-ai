package io.yupiik.ia.agent.core.llm;

import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.framework.build.api.json.JsonOthers;
import io.yupiik.fusion.framework.build.api.json.JsonProperty;

import java.util.List;
import java.util.Map;

/** Anthropic messages API request. */
@JsonModel
public record AnthropicMessagesRequest(
        String model,
        @JsonProperty("max_tokens") Integer maxTokens,
        Object system,
        List<AnthropicMessage> messages,
        Double temperature,
        @JsonProperty("top_p") Double topP,
        @JsonProperty("top_k") Integer topK,
        @JsonProperty("stop_sequences") List<String> stopSequences,
        Boolean stream,
        List<AnthropicTool> tools,
        @JsonProperty("tool_choice") Map<String, Object> toolChoice,
        Map<String, Object> metadata,
        @JsonOthers Map<String, Object> extensions) {
}