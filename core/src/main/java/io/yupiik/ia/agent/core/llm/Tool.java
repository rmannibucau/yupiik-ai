package io.yupiik.ia.agent.core.llm;

import io.yupiik.fusion.framework.build.api.json.JsonModel;

import java.util.Map;

@JsonModel
public record Tool(String type, ToolFunction function) {

    public static Tool function(final String name, final String description, final Map<String, Object> parameters) {
        return new Tool("function", new ToolFunction(name, description, parameters));
    }
}
