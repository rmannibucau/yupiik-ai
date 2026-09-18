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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.framework.api.container.FusionBean;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.json.serialization.JsonCodec;
import io.yupiik.ia.agent.core.YcaCoreModule;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatCompletionRequest;
import io.yupiik.ia.agent.core.llm.ChatResult;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.runtime.StatusReporter;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StructuralCodeGraphTest {
    private static final long TIMEOUT_MS = java.time.Duration.ofSeconds(30).toMillis();

    @TempDir
    Path temp;

    @Test
    void buildsRefreshAndPersists() throws Exception {
        final var project = Files.createDirectory(temp.resolve("project"));
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        Files.writeString(project.resolve("Foo.java"), """
                package demo;
                public class Foo {
                    public int add(int a, int b) { return a + b; }
                }
                """);
        Files.createDirectory(project.resolve("other"));
        Files.writeString(project.resolve("other/Bar.java"), """
                package demo.sub;
                public class Bar {
                    public void go() { new demo.Foo().add(1, 2); }
                }
                """);

        final var config = config(project, Map.of());

        final JsonMapper mapper = newMapper();
        final var graph = new StructuralCodeGraph(
                config,
                List.of(new JavaGraphParser()),
                mapper,
                new StatusReporter(),
                new ChatClient(config, mapper, providerManager(config, mapper), Clock.systemUTC()),
                providerManager(config, mapper),
                Clock.systemUTC());

        graph.ensureReady();
        graph.flushPersist();
        final var built = graph.graph();
        assertEquals(4, built.symbols().size(), "expected Foo + Foo.add + Bar + Bar.go symbols");
        final var types = built.symbols().stream()
                .filter(s -> s.kind() == GraphModel.Kind.TYPE)
                .toList();
        assertEquals(2, types.size());
        assertTrue(built.edges().stream().anyMatch(e -> e.type() == GraphModel.EdgeType.CALLS));

        // symbols must carry populated start/end line values
        assertTrue(
                built.symbols().stream().allMatch(s -> s.startLine() > 0),
                "expected each symbol to carry a start line");
        assertTrue(
                built.symbols().stream().allMatch(s -> s.endLine() >= s.startLine()),
                "expected each symbol end line to not precede its start line");

        // store must have been persisted as the canonical compact binary index
        final var indexFile = project.resolve(".yca/generated/graph/index.bin");
        assertTrue(Files.isRegularFile(indexFile));

        // the hidden per-symbol wiring graph must be written alongside, byte-stable and sorted,
        // with graft-style path-scoped node ids and enrichment state
        final var wiringFile = project.resolve(".yca/generated/graph/.graph/wiring.json");
        assertTrue(Files.isRegularFile(wiringFile), "expected hidden .graph/wiring.json");
        final var wiring = Files.readString(wiringFile);
        assertTrue(wiring.contains("\"nodes\""), "expected wiring nodes array");
        assertTrue(wiring.contains("\"edges\""), "expected wiring edges array");
        assertTrue(wiring.contains("Foo.java#add"), "expected a path-scoped wiring node id");
        assertTrue(wiring.contains("\"summary_state\":\"pending\""), "expected a pending summary state");
        assertTrue(wiring.contains("CALLS"), "expected an edge relation in wiring graph");
        // deterministic: a no-change resync must not rewrite (and thus not churn) the wiring graph
        graph.resync();
        assertEquals(wiring, Files.readString(wiringFile), "wiring.json must be byte-stable across builds");

        // per-source-file doc tree must mirror the source paths, at the store root, extension stripped
        final var docsFoo = project.resolve(".yca/generated/graph/Foo.md");
        assertTrue(Files.isRegularFile(docsFoo), "expected doc tree to mirror Foo.java");
        final var fooDoc = Files.readString(docsFoo);
        assertTrue(fooDoc.contains("# Foo.java"), "expected source-path header in Foo doc");
        assertTrue(
                fooDoc.contains("demo.Foo.add") || fooDoc.contains("- add · method"),
                "expected method symbol in Foo doc");
        final var docsBar = project.resolve(".yca/generated/graph/other/Bar.md");
        assertTrue(Files.isRegularFile(docsBar), "expected doc tree to mirror other/Bar.java");
        assertTrue(Files.readString(docsBar).contains("# other/Bar.java"));

        // refresh: mutating a file removes its old symbols and adds new ones
        Files.writeString(project.resolve("Foo.java"), """
                package demo;
                public class Foo {
                    public int add(int a, int b) { return a + b; }
                    public int sub(int a, int b) { return a - b; }
                }
                """);
        graph.refreshFile(project.resolve("Foo.java"));
        final var after = graph.graph();
        assertTrue(
                after.symbols().stream().anyMatch(s -> s.fqn().equals("demo.Foo.sub")),
                "expected the added method symbol after refresh");
        assertTrue(
                Files.readString(docsFoo).contains("- sub · method"),
                "expected the refreshed doc to contain the added method");

        // deleting a source file removes its doc and its symbols
        Files.delete(project.resolve("Foo.java"));
        graph.refreshFile(project.resolve("Foo.java"));
        assertTrue(!Files.exists(docsFoo), "expected doc removed when source file deleted");
        final var afterDelete = graph.graph();
        assertTrue(
                afterDelete.symbols().stream().noneMatch(s -> s.fqn().startsWith("demo.Foo")),
                "deleted file symbols must be dropped by the refresh");

        // reload from disk must match in-memory state
        final var reloaded = new StructuralCodeGraph(
                config,
                List.of(new JavaGraphParser()),
                mapper,
                new StatusReporter(),
                new ChatClient(config, mapper, providerManager(config, mapper), Clock.systemUTC()),
                providerManager(config, mapper),
                Clock.systemUTC());
        reloaded.ensureReady();
        assertEquals(afterDelete.symbols().size(), reloaded.graph().symbols().size());
    }

    @Test
    void refreshIgnoresFilesOutsideProjectDir() throws Exception {
        final var project = Files.createDirectory(temp.resolve("project"));
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        Files.writeString(project.resolve("Foo.java"), "package demo;\npublic class Foo { public void run() {} }\n");
        final var outside = Files.createDirectory(temp.resolve("outside"));
        Files.writeString(outside.resolve("Bar.java"), "package outside;\npublic class Bar { public void run() {} }\n");

        final var config = config(project, Map.of());
        final JsonMapper mapper = newMapper();
        final var graph = new StructuralCodeGraph(
                config,
                List.of(new JavaGraphParser()),
                mapper,
                new StatusReporter(),
                new ChatClient(config, mapper, providerManager(config, mapper), Clock.systemUTC()),
                providerManager(config, mapper),
                Clock.systemUTC());
        graph.ensureReady();
        assertEquals(2, graph.graph().symbols().size());

        graph.refreshFile(outside.resolve("Bar.java"));
        assertTrue(
                graph.graph().symbols().stream().noneMatch(s -> s.fqn().startsWith("outside")),
                "a file outside the project dir must not be indexed");
        assertEquals(2, graph.graph().symbols().size(), "refreshing an outside file must not change the graph");

        // any nested level inside the project dir is still indexed
        Files.createDirectory(project.resolve("nested"));
        Files.writeString(
                project.resolve("nested/Baz.java"), "package nested;\npublic class Baz { public void run() {} }\n");
        graph.refreshFile(project.resolve("nested/Baz.java"));
        assertTrue(
                graph.graph().symbols().stream().anyMatch(s -> s.fqn().equals("nested.Baz")),
                "a nested file inside the project dir must be indexed");
    }

    @Test
    void skipsIndexingForUnrecognizedProjectDir() throws Exception {
        // A directory with indexable sources but no project marker (pom.xml, .yca, README, ...)
        // is not a recognized project: it must not be scanned/indexed.
        final var project = Files.createDirectory(temp.resolve("plain"));
        Files.writeString(project.resolve("Foo.java"), "package demo;\npublic class Foo { public void run() {} }\n");
        final var config = config(project, Map.of());
        final JsonMapper mapper = newMapper();
        final var graph = new StructuralCodeGraph(
                config,
                List.of(new JavaGraphParser()),
                mapper,
                new StatusReporter(),
                new ChatClient(config, mapper, providerManager(config, mapper), Clock.systemUTC()),
                providerManager(config, mapper),
                Clock.systemUTC());
        graph.ensureReady();
        assertTrue(
                graph.graph().symbols().isEmpty(),
                "a directory without a project marker must not be indexed into a structural code graph");
    }

    @Test
    void skipsIndexingForEmptyTargetProjectDir() throws Exception {
        final var project = Files.createDirectory(temp.resolve("scaffold"));
        final var config = config(project, Map.of());
        final JsonMapper mapper = newMapper();
        final var graph = new StructuralCodeGraph(
                config,
                List.of(new JavaGraphParser()),
                mapper,
                new StatusReporter(),
                new ChatClient(config, mapper, providerManager(config, mapper), Clock.systemUTC()),
                providerManager(config, mapper),
                Clock.systemUTC());
        graph.ensureReady();
        assertTrue(
                graph.graph().symbols().isEmpty(),
                "an empty target project dir must not be indexed into a structural code graph");
    }

    @Test
    void ignoreGlobsAndGitignoreExcludeFiles() throws Exception {
        final var project = Files.createDirectory(temp.resolve("proj-ignore"));
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        Files.createDirectories(project.resolve("src/main/java/demo"));
        Files.writeString(project.resolve("src/main/java/demo/A.java"), """
                package demo;
                public class A {
                    public void run() {}
                }
                """);
        // files under default-ignored dirs (pruned via SKIP_SUBTREE) must not be indexed
        Files.createDirectories(project.resolve(".yca/secret"));
        Files.writeString(
                project.resolve(".yca/secret/B.java"), "package demo;\npublic class B { public void run() {} }\n");
        Files.createDirectories(project.resolve("target"));
        Files.writeString(project.resolve("target/C.java"), "package demo;\npublic class C { public void run() {} }\n");
        Files.createDirectories(project.resolve("node_modules"));
        Files.writeString(
                project.resolve("node_modules/D.java"), "package demo;\npublic class D { public void run() {} }\n");
        // gitignore negation re-includes a file NOT under a dir-pruned subtree
        Files.writeString(project.resolve(".gitignore"), "**/*.VEN.java\n!src/main/java/demo/VEN.java\n");
        Files.writeString(project.resolve("src/main/java/demo/VEN.java"), """
                package demo;
                public class VEN {
                    public void run() {}
                }
                """);

        final var config = config(project, Map.of());
        final JsonMapper mapper = newMapper();
        final var graph = new StructuralCodeGraph(
                config,
                List.of(new JavaGraphParser()),
                mapper,
                new StatusReporter(),
                new ChatClient(config, mapper, providerManager(config, mapper), Clock.systemUTC()),
                providerManager(config, mapper),
                Clock.systemUTC());
        graph.ensureReady();

        final var symbols = graph.graph().symbols();
        assertTrue(
                symbols.stream().anyMatch(s -> s.fqn().equals("demo.A")),
                "expected the source under src/main/java to be indexed");
        assertTrue(
                symbols.stream().anyMatch(s -> s.fqn().equals("demo.VEN")),
                "expected a .gitignore-negated file to be re-included");
        assertTrue(symbols.stream().noneMatch(s -> s.fqn().equals("demo.B")), "expected .yca/** to be ignored");
        assertTrue(symbols.stream().noneMatch(s -> s.fqn().equals("demo.C")), "expected target/** to be ignored");
        assertTrue(symbols.stream().noneMatch(s -> s.fqn().equals("demo.D")), "expected node_modules/** to be ignored");
    }

    @Test
    void configIgnoreGlobsAreAddedToDefaults() throws Exception {
        final var project = Files.createDirectory(temp.resolve("proj-ignorecfg"));
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        Files.createDirectories(project.resolve("src/main/java/demo"));
        Files.writeString(project.resolve("src/main/java/demo/A.java"), """
                package demo;
                public class A {
                    public void run() {}
                }
                """);
        // a custom glob not covered by the defaults: config override adds "custom/**"
        Files.createDirectories(project.resolve("custom"));
        Files.writeString(project.resolve("custom/E.java"), "package demo;\npublic class E { public void run() {} }\n");
        Files.writeString(project.resolve("src/main/java/demo/F.java"), """
                package demo;
                public class F {
                    public void run() {}
                }
                """);

        final var config = config(project, Map.of("yca.structuralCodeGraph.ignoreGlobs", "custom/**"));

        final JsonMapper mapper = newMapper();
        final var graph = new StructuralCodeGraph(
                config,
                List.of(new JavaGraphParser()),
                mapper,
                new StatusReporter(),
                new ChatClient(config, mapper, providerManager(config, mapper), Clock.systemUTC()),
                providerManager(config, mapper),
                Clock.systemUTC());
        graph.ensureReady();

        final var symbols = graph.graph().symbols();
        assertTrue(symbols.stream().anyMatch(s -> s.fqn().equals("demo.A")), "expected demo.A indexed");
        assertTrue(symbols.stream().anyMatch(s -> s.fqn().equals("demo.F")), "expected demo.F indexed");
        assertTrue(
                symbols.stream().noneMatch(s -> s.fqn().equals("demo.E")),
                "expected a config-added ignoreGlob (custom/**) to exclude custom/E.java");
    }

    @Test
    void docTreeSkipsUnchangedAndWritesSummaryInjection() throws Exception {
        final var project = Files.createDirectory(temp.resolve("proj"));
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        Files.writeString(project.resolve("Foo.java"), "package demo;\npublic class Foo { public void run() {} }\n");
        final var config = config(project, Map.of());
        final JsonMapper mapper = newMapper();
        final var graph = new StructuralCodeGraph(
                config,
                List.of(new JavaGraphParser()),
                mapper,
                new StatusReporter(),
                new ChatClient(config, mapper, providerManager(config, mapper), Clock.systemUTC()),
                providerManager(config, mapper),
                Clock.systemUTC());
        graph.ensureReady();

        final var docRoot = Path.of(config.structuralCodeGraph().resolvedStoreDir(project.toString()))
                .toAbsolutePath()
                .normalize();
        final var model =
                new GraphModel("java", graph.graph().symbols(), graph.graph().edges());
        final var rel = "Foo.java";
        final var doc = docRoot.resolve("Foo.md");

        // writer emits the structural card (graft format: header + bullet per symbol, no frontmatter/sections)
        assertTrue(Files.isRegularFile(doc));
        final var card = Files.readString(doc);
        assertTrue(card.startsWith("# Foo.java"), "expected source-path header, got:\n" + card);
        assertTrue(card.contains("- Foo · type"), "expected a type bullet with line span");
        assertTrue(card.contains("- run · method"), "expected a method bullet");
        assertTrue(!card.contains("## Symbols"), "wiring cards must not carry a ## Symbols section");

        // idempotent: re-writing the unchanged structure must not churn the card
        final var writer = new DocTreeWriter(config, docRoot);
        writer.write(rel, model);
        assertEquals(card, Files.readString(doc), "unchanged card must stay byte-stable");

        // a file-level summary one-liner flows inline under the header when provided by enrichment
        final var enriched = new DocTreeWriter(config, docRoot).enrichment(new DocTreeWriter.Enrichment() {
            @Override
            public String fileSummary(final String relSource) {
                return "Represents a demo winner.\n";
            }

            @Override
            public String symbolOneLiner(final String relSource, final String fqn) {
                return null;
            }
        });
        enriched.write(rel, model);
        final var after = Files.readString(doc);
        assertTrue(after.contains("Represents a demo winner."), "expected the file one-liner inline");
        assertTrue(after.contains("- Foo · type"), "expected structural bullets preserved");
    }

    @Test
    void resyncPersistsEnumsWithGeneratedCodecBeans() throws Exception {
        final var project = Files.createDirectory(temp.resolve("proj-enum"));
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        Files.writeString(project.resolve("Color.java"), """
                package demo;
                public enum Color { RED, GREEN, BLUE }
                """);

        final var config = config(project, Map.of());

        // Bootstrap the JSON wiring exactly like production: JsonModule's JsonMapperBean collects
        // every JsonCodec bean registered by YcaCoreModule, which includes the generated Kind/EdgeType
        // enum json codecs. Only the codec beans are registered so no eager app listeners run.
        final var codecBeans = new YcaCoreModule()
                .beans()
                .filter(b -> JsonCodec.class.isAssignableFrom(raw(b.type())))
                .toArray(FusionBean[]::new);
        try (final var container = ConfiguringContainer.of()
                .disableAutoDiscovery(true)
                .register(codecBeans)
                .register(new io.yupiik.fusion.json.internal.framework.JsonModule())
                .start()) {
            final JsonMapper mapper = container.lookup(JsonMapper.class).instance();

            final var graph = new StructuralCodeGraph(
                    config,
                    List.of(new JavaGraphParser()),
                    mapper,
                    new StatusReporter(),
                    new ChatClient(config, mapper, providerManager(config, mapper), Clock.systemUTC()),
                    providerManager(config, mapper),
                    Clock.systemUTC());

            final String status = graph.resync();
            graph.flushPersist();

            assertTrue(status.startsWith("symbols="), "expected status to report symbols, got: " + status);
            assertTrue(status.contains("files="), "expected status to report parsed files, got: " + status);

            // the canonical compact binary index must be persisted
            final var indexFile = project.resolve(".yca/generated/graph/index.bin");
            assertTrue(Files.isRegularFile(indexFile), "expected binary code graph index to be persisted");

            // the enum-backed graph must round-trip through the binary index (no codec / graph.json involved)
            final var idx = io.yupiik.ia.agent.core.codegraph.index.CodeGraphIndex.load(indexFile);
            final var stored = idx.toStore();
            assertTrue(
                    stored.symbols().stream().anyMatch(s -> s.kind() == GraphModel.Kind.ENUM),
                    "expected an enum symbol to round-trip through the binary index");
            assertEquals(graph.graph().symbols().size(), stored.symbols().size());

            // a fresh instance re-loaded from the same binary index must reproduce the same symbols
            final var reloaded = new StructuralCodeGraph(
                    config,
                    List.of(new JavaGraphParser()),
                    mapper,
                    new StatusReporter(),
                    new ChatClient(config, mapper, providerManager(config, mapper), Clock.systemUTC()),
                    providerManager(config, mapper),
                    Clock.systemUTC());
            reloaded.ensureReady();
            assertEquals(stored.symbols().size(), reloaded.graph().symbols().size());
        }
    }

    @Test
    void deepEnrichmentPersistsPerSymbolStatsAndConcepts() throws Exception {
        final var project = Files.createDirectory(temp.resolve("proj-deep"));
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        Files.writeString(project.resolve("Foo.java"), """
                package demo;
                public class Foo {
                    public int add(int a, int b) { return a + b; }
                }
                """);

        final var config = config(
                project,
                Map.of(
                        "yca.structuralCodeGraph.enrich", "true",
                        "yca.structuralCodeGraph.model", "test-model",
                        "yca.structuralCodeGraph.deep", "true"));

        final JsonMapper mapper = newMapper();
        final var storeRoot = Path.of(config.structuralCodeGraph().resolvedStoreDir(project.toString()))
                .toAbsolutePath()
                .normalize();
        final var model = new GraphModel(
                "java",
                List.of(
                        new GraphModel.Symbol("demo.Foo", GraphModel.Kind.TYPE, "Foo.java", 1, 5, "demo.Foo"),
                        new GraphModel.Symbol(
                                "demo.Foo.add", GraphModel.Kind.METHOD, "Foo.java", 3, 4, "int add(int,int)")),
                List.of());

        final var fake = new FakeChatClient();
        final var writer = new DocTreeWriter(config, storeRoot);
        try (final var enricher =
                new DocEnricher(config, fake, writer, mapper, storeRoot, providerManager(config, mapper))) {
            writer.enrichment(enricher);
            enricher.onRewire(() -> WiringGraphWriter.write(
                    mapper,
                    storeRoot,
                    new GraphStore(
                            List.copyOf(model.symbols()),
                            List.copyOf(model.edges()),
                            Map.of(),
                            System.currentTimeMillis()),
                    enricher.cache()));

            // per-symbol deep + file summary are scheduled asynchronously
            enricher.maybeEnrich("Foo.java", model);
            await(() -> enricher.cache().file("Foo.java") != null);

            final var fileEntry = enricher.cache().file("Foo.java");
            assertEquals(DocTreeWriter.structuralDigest(model), fileEntry.digest(), "cache keyed by content hash");
            assertTrue(fileEntry.summary().contains("One file"));

            // deep per-symbol entries present and keyed by fqn (await both writes flushed to disk)
            final var symKey = enricher.cache().symbolKey("Foo.java", "demo.Foo.add");
            await(() -> enricher.cache().file("Foo.java") != null);
            await(() -> enricher.cache().symbol(symKey) != null);
            final var symEntry = enricher.cache().symbol(symKey);
            assertEquals("does addition", symEntry.summary());
            assertEquals("careful with overflow", symEntry.crux());

            // the enriched card carries the file one-liner and the per-symbol one-liner inline, no sections
            await(() -> safeRead(storeRoot.resolve("Foo.md")).contains("does addition"));
            final var card = Files.readString(storeRoot.resolve("Foo.md"));
            assertTrue(card.startsWith("# Foo.java"), "expected source-path header");
            assertTrue(card.contains("One file summary text."), "expected the file one-liner inline");
            assertTrue(card.contains("does addition"), "expected per-symbol one-liner inline");
            assertTrue(!card.contains("## Summary"), "wiring cards must not carry a ## Summary section");

            // the wiring graph re-emits with summary, crux and a ready state once enriched
            await(() -> safeRead(storeRoot.resolve(".graph/wiring.json")).contains("\"summary_state\":\"ready\""));
            final var wiring = Files.readString(storeRoot.resolve(".graph/wiring.json"));
            assertTrue(wiring.contains("does addition"), "expected summary pushed into wiring.json");
            assertTrue(wiring.contains("careful with overflow"), "expected crux pushed into wiring.json");
            assertTrue(wiring.contains("\"summary_state\":\"ready\""), "expected a ready summary_state");

            // a second enricher over the same store must hit the persistent cache (no new LLM calls)
            final var secondFake = new FakeChatClient();
            try (final var enricher2 =
                    new DocEnricher(config, secondFake, writer, mapper, storeRoot, providerManager(config, mapper))) {
                enricher2.maybeEnrich("Foo.java", model);
                await(() -> enricher2.cache().file("Foo.java") != null);
                assertEquals(0, secondFake.calls.get(), "persistent cache must avoid redundant LLM calls");
            }

            // the concept pass groups summaries into curated cards at the store root
            final var written = enricher.enrichConcepts();
            assertTrue(written >= 1, "expected at least one concept card");
            final var conceptCard = storeRoot.resolve("parsing.md");
            assertTrue(Files.isRegularFile(conceptCard), "expected concept card at store root");
            final var concept = Files.readString(conceptCard);
            assertTrue(concept.contains("slug:parsing"), "expected slug frontmatter");
            assertTrue(concept.contains("type:"), "expected type frontmatter");
            assertTrue(concept.contains("## Summary"), "expected concept summary body");
            assertTrue(Files.isRegularFile(storeRoot.resolve("INDEX.md")), "expected a repo-map index");
        }
    }

    @Test
    void patchReconcilesOfflineDeletedFiles() throws Exception {
        final var project = Files.createDirectory(temp.resolve("proj-del"));
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        Files.writeString(project.resolve("Foo.java"), "package demo;\npublic class Foo { public void run() {} }\n");
        Files.createDirectory(project.resolve("other"));
        Files.writeString(
                project.resolve("other/Bar.java"), "package demo.sub;\npublic class Bar { public void go() {} }\n");

        final var config = config(project, Map.of());
        final JsonMapper mapper = newMapper();
        final var graph = new StructuralCodeGraph(
                config,
                List.of(new JavaGraphParser()),
                mapper,
                new StatusReporter(),
                new ChatClient(config, mapper, providerManager(config, mapper), Clock.systemUTC()),
                providerManager(config, mapper),
                Clock.systemUTC());
        graph.ensureReady();
        graph.flushPersist();
        assertTrue(
                graph.graph().symbols().stream().anyMatch(s -> s.fqn().equals("demo.sub.Bar")),
                "expected Bar symbols before the deletion");

        // delete a file offline (no refreshFile event): a resync must fully reconcile the store
        Files.delete(project.resolve("other/Bar.java"));
        graph.resync();
        graph.flushPersist();

        final var after = graph.graph();
        assertTrue(
                after.symbols().stream().noneMatch(s -> s.fqn().startsWith("demo.sub")),
                "deleted file symbols must be dropped by patch");
        assertTrue(
                !after.fingerprints().containsKey("other/Bar.java"),
                "deleted file fingerprint must be dropped by patch");
        assertTrue(
                !Files.exists(project.resolve(".yca/generated/graph/other/Bar.md")),
                "deleted file doc must be removed by patch");
        final var meta = Files.readString(project.resolve(".yca/generated/graph/index.meta"));
        assertEquals(
                "#count=" + after.fingerprints().size(),
                meta.substring(0, meta.indexOf('\n')),
                "sidecar must record the indexed file count");
        assertTrue(!meta.contains("other/Bar.java"), "sidecar must forget the deleted file, got:\n" + meta);

        // a fresh session must reload the reconciled index: no ghost symbols survive
        final var reloaded = new StructuralCodeGraph(
                config,
                List.of(new JavaGraphParser()),
                mapper,
                new StatusReporter(),
                new ChatClient(config, mapper, providerManager(config, mapper), Clock.systemUTC()),
                providerManager(config, mapper),
                Clock.systemUTC());
        reloaded.ensureReady();
        assertEquals(
                after.symbols().size(),
                reloaded.graph().symbols().size(),
                "reloaded index must not carry ghost symbols for the deleted file");
    }

    @Test
    void gateDetectsOfflineAddedFilesOnSessionStart() throws Exception {
        final var project = Files.createDirectory(temp.resolve("proj-add"));
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        Files.writeString(project.resolve("Foo.java"), "package demo;\npublic class Foo { public void run() {} }\n");

        final var config = config(project, Map.of());
        final JsonMapper mapper = newMapper();
        final var built = new StructuralCodeGraph(
                config,
                List.of(new JavaGraphParser()),
                mapper,
                new StatusReporter(),
                new ChatClient(config, mapper, providerManager(config, mapper), Clock.systemUTC()),
                providerManager(config, mapper),
                Clock.systemUTC());
        built.ensureReady();

        // add a file offline: every known mtime still matches, only the #count gate can notice the addition
        Files.writeString(project.resolve("Baz.java"), "package demo;\npublic class Baz { public void stop() {} }\n");
        final var reloaded = new StructuralCodeGraph(
                config,
                List.of(new JavaGraphParser()),
                mapper,
                new StatusReporter(),
                new ChatClient(config, mapper, providerManager(config, mapper), Clock.systemUTC()),
                providerManager(config, mapper),
                Clock.systemUTC());
        reloaded.ensureReady();

        final var symbols = reloaded.graph().symbols();
        assertTrue(
                symbols.stream().anyMatch(s -> s.fqn().equals("demo.Baz")),
                "a file added offline must be indexed on session start");
        assertTrue(
                symbols.stream().anyMatch(s -> s.fqn().equals("demo.Foo")),
                "existing symbols must be preserved by the incremental patch");
        assertTrue(
                Files.isRegularFile(project.resolve(".yca/generated/graph/Baz.md")),
                "expected a doc card for the offline-added file");
    }

    @Test
    void patchRefreshesSidecarAfterMtimeDriftWithoutContentChange() throws Exception {
        final var project = Files.createDirectory(temp.resolve("proj-drift"));
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        Files.writeString(project.resolve("Foo.java"), "package demo;\npublic class Foo { public void run() {} }\n");

        final var config = config(project, Map.of());
        final JsonMapper mapper = newMapper();
        final var built = new StructuralCodeGraph(
                config,
                List.of(new JavaGraphParser()),
                mapper,
                new StatusReporter(),
                new ChatClient(config, mapper, providerManager(config, mapper), Clock.systemUTC()),
                providerManager(config, mapper),
                Clock.systemUTC());
        built.ensureReady();
        built.flushPersist();

        // touch without a content change: the gate must fail once, the hash-identical patch must refresh
        // the sidecar mtimes (snapshot taken while hashing, never after the whole build) ...
        final var newMtime =
                Files.getLastModifiedTime(project.resolve("Foo.java")).toMillis() + 60_000;
        Files.setLastModifiedTime(project.resolve("Foo.java"), java.nio.file.attribute.FileTime.fromMillis(newMtime));
        final var reloaded = new StructuralCodeGraph(
                config,
                List.of(new JavaGraphParser()),
                mapper,
                new StatusReporter(),
                new ChatClient(config, mapper, providerManager(config, mapper), Clock.systemUTC()),
                providerManager(config, mapper),
                Clock.systemUTC());
        reloaded.ensureReady();
        final var meta = Files.readString(project.resolve(".yca/generated/graph/index.meta"));
        assertTrue(
                meta.contains("Foo.java\t" + newMtime),
                "sidecar must carry the new mtime after a hash-identical patch, got:\n" + meta);

        // ... so the next session hits the fresh fast path and serves the same symbols
        final var third = new StructuralCodeGraph(
                config,
                List.of(new JavaGraphParser()),
                mapper,
                new StatusReporter(),
                new ChatClient(config, mapper, providerManager(config, mapper), Clock.systemUTC()),
                providerManager(config, mapper),
                Clock.systemUTC());
        third.ensureReady();
        assertEquals(reloaded.graph().symbols().size(), third.graph().symbols().size());
    }

    private static String safeRead(final Path p) {
        try {
            return Files.isRegularFile(p) ? Files.readString(p) : "";
        } catch (final Exception e) {
            return "";
        }
    }

    private static void await(final java.util.function.BooleanSupplier cond) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline && !cond.getAsBoolean()) {
            java.util.concurrent.locks.LockSupport.parkNanos(25_000_000L);
        }
        assertTrue(cond.getAsBoolean(), "timed out waiting for async enrichment");
    }

    /** Deterministic LLM double: no network, scripted per prompt marker. */
    private static final class FakeChatClient extends ChatClient {
        final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();

        @Override
        public ChatResult chat(final ChatCompletionRequest request) {
            calls.incrementAndGet();
            final var prompt = request.messages() == null || request.messages().isEmpty()
                    ? ""
                    : String.valueOf(request.messages().get(0).content());
            if (prompt.startsWith("You give a fellow developer one-line notes")) {
                return new ChatResult(
                        Message.assistant("summary: does addition\ncrux: careful with overflow", null), null);
            }
            if (prompt.contains("Return ONLY concise JSON")) {
                return new ChatResult(
                        Message.assistant(
                                "{\"concepts\":[{\"name\":\"Parsing\",\"slug\":\"parsing\",\"type\":\"feature\","
                                        + "\"summary\":\"Groups the demo file into a parsing node.\","
                                        + "\"sources\":[\"docs/Foo.java.md\"],"
                                        + "\"links\":[{\"to\":\"docs\",\"relation\":\"DEPENDS_ON\",\"description\":\"related\"}]}]}",
                                null),
                        null);
            }
            return new ChatResult(Message.assistant("One file summary text.", null), null);
        }
    }

    private static Class<?> raw(final java.lang.reflect.Type type) {
        if (type instanceof Class<?> c) {
            return c;
        }
        if (type instanceof java.lang.reflect.ParameterizedType pt) {
            return raw(pt.getRawType());
        }
        throw new IllegalArgumentException("unsupported type " + type);
    }

    private static JsonMapper newMapper() {
        return new io.yupiik.fusion.json.internal.JsonMapperImpl(
                List.of(
                        new GraphStore$FusionJsonCodec(),
                        new GraphModel$Symbol$FusionJsonCodec(),
                        new GraphModel$Edge$FusionJsonCodec(),
                        new DocEnricherCache$FileEntry$FusionJsonCodec(),
                        new DocEnricherCache$SymbolEntry$FusionJsonCodec(),
                        new DocEnricherCache$Data$FusionJsonCodec(),
                        new DocEnricher$ConceptDtos$FusionJsonCodec(),
                        new DocEnricher$ConceptDto$FusionJsonCodec(),
                        new DocEnricher$ConceptLinkDto$FusionJsonCodec(),
                        new io.yupiik.fusion.json.internal.codec.EnumJsonCodec<>(
                                GraphModel.Kind.class, List.of(GraphModel.Kind.values()), Enum::name),
                        new io.yupiik.fusion.json.internal.codec.EnumJsonCodec<>(
                                GraphModel.EdgeType.class, List.of(GraphModel.EdgeType.values()), Enum::name)),
                key -> Optional.empty());
    }

    private static CodingAgentConfiguration config(final Path project, final Map<String, String> structuralOverrides) {
        final var map = new HashMap<String, String>();
        map.put("yca.storageHome", project.toString());
        map.put("yca.workingDir", project.toString());
        map.put("yca.projectDir", project.toString());
        map.put("yca.provider.providers.length", "1");
        map.put("yca.provider.providers.0.id", "default");
        map.put("yca.provider.providers.0.baseUrl", "https://api.openai.com/v1");
        map.put("yca.provider.providers.0.apiKey", "");
        map.put("yca.provider.providers.0.model", "test-model");
        map.put("yca.provider.providers.0.api", "auto");
        map.put("yca.provider.providers.0.contextWindow", "128000");
        map.put("yca.provider.providers.0.timeoutSeconds", "120");
        map.put("yca.llm.retriesMaxAttempts", "3");
        map.put("yca.llm.retriesBaseDelayMs", "500");
        map.put("yca.llm.rateLimitRequestsPerMinute", "0");
        map.put("yca.llm.maxConcurrent", "4");
        map.put("yca.llm.retryOnContextOverflow", "true");
        map.put("yca.llm.maxIterations", "250");
        map.put("yca.structuralCodeGraph.maxFileBytes", String.valueOf(10_000_000));
        map.putAll(structuralOverrides);
        return io.yupiik.ia.agent.core.configuration.TestConfigs.codingAgent(map);
    }

    private static ProviderManager providerManager(final CodingAgentConfiguration cfg, final JsonMapper mapper) {
        return new ProviderManager(cfg, mapper);
    }
}
