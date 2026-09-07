package io.yupiik.ia.agent.core.llm;

import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.framework.build.api.json.JsonProperty;

import java.util.List;

@JsonModel
public record ModelsResponse(List<Model> data) {

    public List<String> ids() {
        return data == null ? List.of() : data.stream().map(Model::id).filter(id -> id != null && !id.isBlank()).distinct().sorted().toList();
    }

    @JsonModel
    public record Model(String id, @JsonProperty("owned_by") String ownedBy) {
    }
}
