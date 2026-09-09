package io.yupiik.ia.agent.core.llm;

import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.framework.build.api.json.JsonOthers;
import io.yupiik.fusion.framework.build.api.json.JsonProperty;

import java.util.List;
import java.util.Map;

@JsonModel
public record ResponsesRequest(String model, List<ResponsesInput> input, String instructions,
                               Boolean stream, Boolean store,
                               List<ResponsesTool> tools,
                               @JsonProperty("tool_choice") String toolChoice,
                               Map<String, Object> reasoning,
                               @JsonOthers Map<String, Object> others) {
}
