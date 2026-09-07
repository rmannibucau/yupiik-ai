package io.yupiik.ia.agent.core.llm;

import io.yupiik.fusion.framework.build.api.json.JsonModel;

import java.util.Map;

@JsonModel
public record ResponsesTool(String type, String name, String description, Map<String, Object> parameters) {
}
