package io.yupiik.ia.agent.core.llm;

import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.framework.build.api.json.JsonProperty;

import java.util.List;

@JsonModel
public record ChatChunk(String id, String model, List<ChunkChoice> choices, Usage usage) {

    @JsonModel
    public record ChunkChoice(int index, ChunkDelta delta, @JsonProperty("finish_reason") String finishReason) {
    }

    @JsonModel
    public record ChunkDelta(Object content,
                             @JsonProperty("reasoning_content") String reasoningContent,
                             @JsonProperty("reasoning") String reasoning,
                             @JsonProperty("tool_calls") List<ToolCall> toolCalls) {
    }
}
