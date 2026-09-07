package io.yupiik.ia.agent.core.llm;

import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.framework.build.api.json.JsonProperty;

import java.util.List;

@JsonModel
public record ChatCompletionResponse(String id, String model, List<Choice> choices, Usage usage) {

    public Message firstMessage() {
        return choices == null || choices.isEmpty() || choices.get(0).message() == null ? null : choices.get(0).message();
    }

    @JsonModel
    public record Choice(Message message, @JsonProperty("finish_reason") String finishReason) {
    }
}
