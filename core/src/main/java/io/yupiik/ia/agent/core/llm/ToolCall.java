package io.yupiik.ia.agent.core.llm;

import io.yupiik.fusion.framework.build.api.json.JsonModel;

@JsonModel
public record ToolCall(String id, String type, FunctionCall function, Integer index) {

    @JsonModel
    public record FunctionCall(String name, String arguments) {
    }
}
