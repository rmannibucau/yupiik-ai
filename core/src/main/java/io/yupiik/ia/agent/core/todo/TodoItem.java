package io.yupiik.ia.agent.core.todo;

import io.yupiik.fusion.framework.build.api.json.JsonModel;

import java.util.List;

@JsonModel
public record TodoItem(String content, String status) {

    public boolean isValid() {
        return content != null && !content.isBlank()
                && status != null && List.of("pending", "in_progress", "completed").contains(status);
    }
}
