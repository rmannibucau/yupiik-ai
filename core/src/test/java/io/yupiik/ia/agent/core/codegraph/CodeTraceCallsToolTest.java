/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.yupiik.ia.agent.core.codegraph;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CodeTraceCallsToolTest {

    private static GraphStore store() {
        return new GraphStore(
                List.of(
                        new GraphModel.Symbol("org.hop.RowSet", GraphModel.Kind.INTERFACE, "RowSet.java", 1, 5, null),
                        new GraphModel.Symbol(
                                "org.hop.RowSetImpl", GraphModel.Kind.TYPE, "RowSetImpl.java", 1, 5, null),
                        new GraphModel.Symbol("org.hop.Producer", GraphModel.Kind.TYPE, "Producer.java", 1, 5, null)),
                List.of(
                        new GraphModel.Edge("org.hop.RowSetImpl", "org.hop.RowSet", GraphModel.EdgeType.IMPLEMENTS),
                        new GraphModel.Edge("org.hop.Producer", "org.hop.RowSet", GraphModel.EdgeType.USES),
                        new GraphModel.Edge("org.hop.Producer", "org.hop.RowSetImpl", GraphModel.EdgeType.CALLS)),
                Map.of(),
                1L);
    }

    @Test
    void defaultsToCallsRelationsForCallers() {
        // direction=callers on RowSet with the default (calls): no CALLS edge targets RowSet, so neither the
        // IMPLEMENTS (RowSetImpl) nor the USES (Producer) relation is followed.
        final var result =
                new CodeTraceCallsTool(fake(store())).execute(Map.of("symbol", "RowSet", "direction", "callers"));
        assertTrue(result.success());
        final var out = result.output();
        assertFalse(out.contains("org.hop.RowSetImpl"), out);
        assertFalse(out.contains("org.hop.Producer"), out);
    }

    @Test
    void implementsExplicitlyFindsImplementors() {
        // the default does not traverse implements, but an explicit edgeTypes=implements must.
        final var result = new CodeTraceCallsTool(fake(store()))
                .execute(Map.of("symbol", "RowSet", "direction", "callers", "edgeTypes", "implements"));
        assertTrue(result.success());
        final var out = result.output();
        assertTrue(out.contains("org.hop.RowSetImpl"), out);
    }

    @Test
    void narrowsToImplementsForCallers() {
        final var result = new CodeTraceCallsTool(fake(store()))
                .execute(Map.of("symbol", "org.hop.RowSet", "direction", "callers", "edgeTypes", "implements"));
        final var out = result.output();
        assertTrue(out.contains("org.hop.RowSetImpl"), out);
        assertFalse(out.contains("org.hop.Producer"), out);
    }

    @Test
    void defaultsToCallsWhenExplicitCallsOnly() {
        // direction=callers, edgeTypes=calls on RowSetImpl -> only Producer (CALLS->RowSetImpl), not RowSetImpl?-> by
        // implements
        final var result = new CodeTraceCallsTool(fake(store()))
                .execute(Map.of("symbol", "RowSetImpl", "direction", "callers", "edgeTypes", "calls"));
        final var out = result.output();
        assertTrue(out.contains("org.hop.Producer"), out);
    }

    @Test
    void errorsWhenSymbolMissing() {
        final var result = new CodeTraceCallsTool(fake(store())).execute(Map.of("symbol", "doesNotExist"));
        assertFalse(result.success());
        assertTrue(result.output().contains("Symbol not found"));
    }

    @Test
    void resolvesFoundSymbolsToKindAndFile() {
        // decision-grade output links each found symbol to its kind and file:start-end.
        final var result = new CodeTraceCallsTool(fake(store()))
                .execute(Map.of("symbol", "org.hop.RowSet", "direction", "callers", "edgeTypes", "implements"));
        assertTrue(result.success());
        final var out = result.output();
        assertTrue(out.contains("resolved org.hop.RowSet (implements), 1 distinct symbol(s) found"), out);
        assertTrue(out.contains("- type org.hop.RowSetImpl (RowSetImpl.java:1-5)"), out);
    }

    private static StructuralCodeGraph fake(final GraphStore store) {
        return new StructuralCodeGraph() {
            @Override
            public GraphStore graph() {
                return store;
            }

            @Override
            public Path storeRoot() {
                return Path.of("/tmp/fake-code-graph");
            }
        };
    }
}
