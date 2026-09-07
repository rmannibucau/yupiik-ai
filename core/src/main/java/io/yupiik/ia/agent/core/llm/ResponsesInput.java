package io.yupiik.ia.agent.core.llm;

import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.framework.build.api.json.JsonProperty;
import io.yupiik.fusion.framework.build.api.json.JsonOthers;

import java.util.Map;

@JsonModel
public record ResponsesInput(String type, String role, Object content, String name,
                             @JsonProperty("call_id") String callId,
                             @JsonProperty("output") String output,
                             @JsonProperty("arguments") String arguments,
                             @JsonProperty("id") String id,
                             @JsonOthers Map<String, Object> others) {

    public static ResponsesInput message(final String role, final String text) {
        return new ResponsesInput("message", role, text, null, null, null, null, null, null);
    }

    public static ResponsesInput functionCall(final String callId, final String name, final String arguments) {
        return new ResponsesInput("function_call", null, null, null, callId, null, arguments, null, Map.of("name", name));
    }

    public static ResponsesInput functionCallOutput(final String callId, final String output) {
        return new ResponsesInput("function_call_output", null, null, null, callId, output, null, null, null);
    }
}
