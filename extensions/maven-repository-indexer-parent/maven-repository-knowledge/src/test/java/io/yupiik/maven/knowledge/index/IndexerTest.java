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
package io.yupiik.maven.knowledge.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.maven.knowledge.Gav;
import io.yupiik.maven.knowledge.config.KnowledgeConfig;
import io.yupiik.maven.knowledge.content.ContentAcquirer;
import io.yupiik.maven.knowledge.content.LocalRepository;
import io.yupiik.maven.knowledge.content.WebFetch;
import io.yupiik.maven.knowledge.llm.KnowledgeLlm;
import io.yupiik.maven.knowledge.manifest.ManifestWriter;
import io.yupiik.maven.knowledge.skill.SkillWriter;
import io.yupiik.maven.knowledge.test.FakeMavenRepo;
import io.yupiik.maven.knowledge.test.StubLlm;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IndexerTest {

    @TempDir
    Path tmp;

    private Indexer newIndexer(final KnowledgeConfig config, final KnowledgeLlm llm) {
        final var repo = new LocalRepository(tmp);
        final WebFetch webfetch = (url, timeout, bytes) -> "DOC";
        return new Indexer(
                new ContentAcquirer(repo, webfetch, config),
                new SkillWriter(tmp),
                new ManifestWriter(tmp),
                llm,
                config);
    }

    @Test
    void batchesMultipleGavsInASingleLlmCall() {
        try (final var repo = new FakeMavenRepo(tmp)) {
            for (int i = 0; i < 6; i++) {
                repo.sourcesJar(
                        "g", "lib" + i, "1", "g/lib" + i + "/Lib.java", "package g.lib" + i + "; public class Lib{}");
            }
            final var config = new KnowledgeConfig(
                    tmp, 4, 8, true, false, KnowledgeConfig.defaults(tmp).boundedFetch(), 0, null);
            final var llm = new StubLlm().entryPoints("g.lib0.Lib");
            final var indexer = newIndexer(config, llm);
            try {
                final var result = indexer.index(
                        List.of(
                                Gav.of("g", "lib0", "1"),
                                Gav.of("g", "lib1", "1"),
                                Gav.of("g", "lib2", "1"),
                                Gav.of("g", "lib3", "1"),
                                Gav.of("g", "lib4", "1"),
                                Gav.of("g", "lib5", "1")),
                        null);
                assertEquals(6, llm.totalEntryPointGavs());
                assertEquals(1, llm.entryPointCallCount(), "all GAVs must be answered in a single bulk LLM call");
                assertEquals(6, llm.lastBatchSize());
                assertEquals(6, result.indexedCount());
            } finally {
                indexer.close();
            }
        }
    }

    @Test
    void reportsBatchProgressViaBeforeBatch() {
        try (final var repo = new FakeMavenRepo(tmp)) {
            for (int i = 0; i < 6; i++) {
                repo.sourcesJar(
                        "g", "lib" + i, "1", "g/lib" + i + "/Lib.java", "package g.lib" + i + "; public class Lib{}");
            }
            // batchSize=2 forces 3 batches; -1 auto with unknown budget stays on tiny content -> still batched by
            // count/char (no budget)
            final var config = new KnowledgeConfig(
                    tmp, 4, 2, true, false, KnowledgeConfig.defaults(tmp).boundedFetch(), 0, null);
            final var llm = new StubLlm().entryPoints("g.lib0.Lib");
            final var indexer = newIndexer(config, llm);
            try {
                final var beforeEachBatch = new ArrayList<Integer>();
                final var result = indexer.index(
                        List.of(
                                Gav.of("g", "lib0", "1"),
                                Gav.of("g", "lib1", "1"),
                                Gav.of("g", "lib2", "1"),
                                Gav.of("g", "lib3", "1"),
                                Gav.of("g", "lib4", "1"),
                                Gav.of("g", "lib5", "1")),
                        null,
                        beforeBatch -> beforeEachBatch.add(beforeBatch));
                assertEquals(3, beforeEachBatch.size(), "one callback per batch");
                assertEquals(
                        List.of(0, 2, 4), beforeEachBatch, "cumulative done before each batch must be non-decreasing");
                assertEquals(6, result.indexedCount());
            } finally {
                indexer.close();
            }
        }
    }

    @Test
    void releasesAreImmutableAndSkippedWhenAlreadyIndexed() {
        try (final var repo = new FakeMavenRepo(tmp)) {
            repo.sourcesJar("g", "a", "1", "g/a/A.java", "package g.a; public class A{}")
                    .markIndexed("g", "a", "1", "a");
            final var config = KnowledgeConfig.defaults(tmp);
            final var llm = new StubLlm().entryPoints("g.a.A");
            final var indexer = newIndexer(config, llm);
            try {
                final var result = indexer.index(List.of(Gav.of("g", "a", "1")), null);
                assertEquals(0, result.indexedCount());
                assertEquals(1, result.skippedCount());
                assertEquals(0, llm.entryPointCallCount(), "no LLM call for an immutable skipped artifact");
            } finally {
                indexer.close();
            }
        }
    }

    @Test
    void snapshotsAlwaysRegenerateAndForceBypassesImmutableSkip() {
        try (final var repo = new FakeMavenRepo(tmp)) {
            repo.sourcesJar("g", "a", "2-SNAPSHOT", "g/a/A.java", "package g.a; public class A{}")
                    .markIndexed("g", "a", "2-SNAPSHOT", "a");
            final var config = KnowledgeConfig.defaults(tmp);
            final var llm = new StubLlm().entryPoints("g.a.A");
            final var indexer = newIndexer(config, llm);
            try {
                // SNAPSHOT regenerates even though already indexed
                var result = indexer.index(List.of(Gav.of("g", "a", "2-SNAPSHOT")), null);
                assertEquals(1, result.indexedCount());
            } finally {
                indexer.close();
            }
        }
        // force bypasses immutable-skip for a release
        try (final var repo = new FakeMavenRepo(tmp)) {
            repo.sourcesJar("g", "r", "1", "g/r/R.java", "package g.r; public class R{}")
                    .markIndexed("g", "r", "1", "r");
            final var config = new KnowledgeConfig(
                    tmp, 4, 8, true, true, KnowledgeConfig.defaults(tmp).boundedFetch(), 0, null);
            final var llm = new StubLlm().entryPoints("g.r.R");
            final var indexer = newIndexer(config, llm);
            try {
                var result = indexer.index(List.of(Gav.of("g", "r", "1")), null);
                assertEquals(1, result.indexedCount(), "force must regenerate a release");
            } finally {
                indexer.close();
            }
        }
    }

    @Test
    void writesSkillAndRemoteManifestInArtifactShape() throws Exception {
        try (final var repo = new FakeMavenRepo(tmp)) {
            repo.sourcesJar(
                    "com.example",
                    "widget",
                    "3",
                    "com/example/widget/Widget.java",
                    "package com.example.widget; public class Widget{}");
            final var config = KnowledgeConfig.defaults(tmp);
            final var llm = new StubLlm().entryPoints("com.example.widget.Widget");
            final var indexer = newIndexer(config, llm);
            try {
                indexer.index(List.of(Gav.of("com.example", "widget", "3")), null);
            } finally {
                indexer.close();
            }
            final var skill = repo.artifactDir("com.example", "widget", "3")
                    .resolve("yca")
                    .resolve("widget")
                    .resolve("SKILL.md");
            assertTrue(Files.isRegularFile(skill), "skill SKILL.md must be written");
            final var text = Files.readString(skill);
            assertTrue(text.startsWith("---\nname: widget\n"), "must start with YAML frontmatter name");
            assertTrue(text.contains("## Entry points"), "must contain Entry points section");
            assertTrue(text.contains("com.example.widget.Widget"), "must contain the entry point FQN");

            final var manifest = repo.artifactDir("com.example", "widget", "3").resolve("widget-3-skills.md");
            assertTrue(Files.isRegularFile(manifest), "remote manifest must be written");
            final var manifestText = Files.readString(manifest);
            assertTrue(manifestText.contains("com.example:widget:3"), "manifest should carry the coordinate");
        }
    }

    @Test
    void degradesGracefullyWhenLlmUnavailable() throws Exception {
        try (final var repo = new FakeMavenRepo(tmp)) {
            repo.sourcesJar("g", "a", "1", "g/a/A.java", "package g.a; public class A{}");
            final var config = KnowledgeConfig.defaults(tmp);
            final var llm = new StubLlm().unavailable();
            final var indexer = newIndexer(config, llm);
            try {
                final var result = indexer.index(List.of(Gav.of("g", "a", "1")), null);
                assertEquals(1, result.indexedCount(), "fallback skill written when LLM unavailable");
                final var skill = repo.artifactDir("g", "a", "1")
                        .resolve("yca")
                        .resolve("a")
                        .resolve("SKILL.md");
                assertTrue(Files.isRegularFile(skill));
                assertEquals(true, Files.readString(skill).contains("LLM was not reachable"));
            } finally {
                indexer.close();
            }
        }
    }
}
