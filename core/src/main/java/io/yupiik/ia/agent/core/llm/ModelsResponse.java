package io.yupiik.ia.agent.core.llm;

import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.framework.build.api.json.JsonOthers;
import io.yupiik.fusion.framework.build.api.json.JsonProperty;
import io.yupiik.fusion.json.pointer.GenericJsonPointer;

import java.util.List;
import java.util.Map;

@JsonModel
public record ModelsResponse(List<Model> data) {

    /** Context window used when neither the provider configuration nor the /v1/models vendor extensions provide one. */
    public static final int DEFAULT_CONTEXT_WINDOW = 128_000;

    public List<String> ids() {
        return data == null ? List.of() : data.stream().map(Model::id).filter(id -> id != null && !id.isBlank()).distinct().sorted().toList();
    }

    @JsonModel
    public record Model(String id, @JsonProperty("owned_by") String ownedBy,
                        @JsonOthers Map<String, Object> others) {

        /**
         * Best effort context window extraction from the vendor extensions: flat keys first, then
         * the llama.cpp effective {@code meta.n_ctx}, then {@code meta.n_ctx_train} but only when
         * smaller than {@link #DEFAULT_CONTEXT_WINDOW} so it can only lower the estimate.
         * Detected values are totals (prompt + completion combined).
         */
        public Integer contextWindow() {
            final var primary = firstPositive("/context_window", "/model_context_window",
                    "/context_length", "/max_model_len", "/meta/n_ctx");
            if (primary != null) {
                return primary;
            }
            final var train = firstPositive("/meta/n_ctx_train");
            return train != null && train < DEFAULT_CONTEXT_WINDOW ? train : null;
        }

        private Integer firstPositive(final String... pointers) {
            for (final var pointer : pointers) {
                final var value = tryLookup(pointer);
                if (value instanceof final Number number && number.intValue() > 0) {
                    return number.intValue();
                }
            }
            return null;
        }

        private Object tryLookup(final String pointer) {
            if (others == null) {
                return null;
            }
            try {
                return new GenericJsonPointer(pointer).apply(others);
            } catch (final RuntimeException e) {
                return null;
            }
        }
    }
}
