package io.yupiik.ia.agent.core.llm;

import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.framework.build.api.json.JsonOthers;
import io.yupiik.fusion.framework.build.api.json.JsonProperty;

import java.util.Map;

/**
 * Anthropic messages API streaming event: {@code message} on message_start, {@code content_block}
 * on content_block_start, {@code delta} on content_block_delta (text/thinking/partial_json) and
 * message_delta (stop_reason and usage), {@code usage} on message_delta.
 */
@JsonModel
public record AnthropicStreamEvent(
        String type,
        AnthropicMessagesResponse message,
        Integer index,
        @JsonProperty("content_block") AnthropicMessagesResponse.ContentBlock contentBlock,
        Delta delta,
        AnthropicMessagesResponse.AnthropicUsage usage,
        @JsonOthers Map<String, Object> extensions) {

    @JsonModel
    public record Delta(
            String type,
            String text,
            String thinking,
            @JsonProperty("partial_json") String partialJson,
            @JsonProperty("stop_reason") String stopReason,
            @JsonProperty("stop_sequence") String stopSequence,
            @JsonOthers Map<String, Object> extensions) {
    }
}