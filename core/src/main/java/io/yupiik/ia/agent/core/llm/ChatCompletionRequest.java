package io.yupiik.ia.agent.core.llm;

import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.framework.build.api.json.JsonOthers;
import io.yupiik.fusion.framework.build.api.json.JsonProperty;

import java.util.List;
import java.util.Map;

@JsonModel
public record ChatCompletionRequest(String model, List<Message> messages, Double temperature, Boolean stream,
                                    List<Tool> tools,
                                    @JsonProperty("tool_choice") String toolChoice,
                                    @JsonProperty("max_tokens") Integer maxTokens,
                                    @JsonProperty("reasoning_effort") String reasoningEffort,
                                    @JsonOthers Map<String, Object> others) {
}
