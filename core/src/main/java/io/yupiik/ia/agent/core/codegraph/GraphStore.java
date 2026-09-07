package io.yupiik.ia.agent.core.codegraph;

import io.yupiik.fusion.framework.build.api.json.JsonModel;

import java.util.List;
import java.util.Map;

/**
 * Persisted shape of the structural code graph (alongside concept-node markdown files).
 */
@JsonModel
public record GraphStore(List<GraphModel.Symbol> symbols, List<GraphModel.Edge> edges,
                         Map<String, String> fingerprints, long builtAt, List<String> nodeFiles) {

    public GraphStore {
        nodeFiles = nodeFiles == null ? List.of() : nodeFiles;
    }

    public GraphStore(final List<GraphModel.Symbol> symbols, final List<GraphModel.Edge> edges,
                      final Map<String, String> fingerprints, final long builtAt) {
        this(symbols, edges, fingerprints, builtAt, List.of());
    }

    public static GraphStore empty() {
        return new GraphStore(List.of(), List.of(), Map.of(), 0L, List.of());
    }
}