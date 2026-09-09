package io.yupiik.ia.agent.core.session;

import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.framework.build.api.json.JsonOthers;
import io.yupiik.ia.agent.core.llm.ToolCall;

import java.util.List;
import java.util.Map;

@JsonModel
public record SessionEntry(long at, String role, Object content, List<ToolCall> toolCalls,
                           String toolCallId, String name, String event,
                           @JsonOthers Map<String, Object> others) {

    public static SessionEntry of(final long at, final String role, final Object content,
                                  final List<ToolCall> toolCalls, final String toolCallId) {
        return new SessionEntry(at, role, content, toolCalls, toolCallId, null, null, null);
    }
}
