package io.yupiik.ia.agent.core.codegraph;

import io.yupiik.fusion.framework.build.api.json.JsonModel;

import java.util.List;

/**
 * Language-neutral structural description of a parsed source file. Every {@link GraphParser}
 * implementation produces this model so that the graph store, tools and enrichment pass stay
 * independent of the source language.
 */
public record GraphModel(String language, List<Symbol> symbols, List<Edge> edges) {

    public static GraphModel empty(final String language) {
        return new GraphModel(language, List.of(), List.of());
    }

    @JsonModel
    public record Symbol(String fqn, Kind kind, String file, int line, int column, String signature) {
    }

    @JsonModel
    public record Edge(String from, String to, EdgeType type) {
    }

    public enum Kind {
        TYPE,
        METHOD,
        FIELD,
        FUNCTION,
        CONSTRUCTOR,
        ENUM,
        INTERFACE,
        ANNOTATION,
        MODULE
    }

    public enum EdgeType {
        CALLS,
        IMPORTS,
        EXTENDS,
        IMPLEMENTS,
        USES
    }
}