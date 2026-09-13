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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.maven.knowledge.Gav;
import io.yupiik.maven.knowledge.KnowledgeFactory;
import io.yupiik.maven.knowledge.config.KnowledgeConfig;
import io.yupiik.maven.knowledge.content.ContentAcquirer;
import io.yupiik.maven.knowledge.content.LocalRepository;
import io.yupiik.maven.knowledge.content.WebFetch;
import io.yupiik.maven.knowledge.llm.KnowledgeLlm;
import io.yupiik.maven.knowledge.test.FakeMavenRepo;
import io.yupiik.maven.knowledge.test.StubLlm;
import io.yupiik.maven.knowledge.write.KnowledgeWriter;
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
        final var writer = new KnowledgeWriter(tmp);
        return new Indexer(
                new ContentAcquirer(repo, webfetch, config), writer, new KnowledgeFactory(writer), llm, config);
    }

    @Test
    void indexesAllGavsDeterministicallyWithoutLlm() {
        try (final var repo = new FakeMavenRepo(tmp)) {
            for (int i = 0; i < 6; i++) {
                repo.sourcesJar(
                        "g", "lib" + i, "1", "g/lib" + i + "/Lib.java", "package g.lib" + i + "; public class Lib{}");
            }
            final var config = new KnowledgeConfig(
                    tmp, 4, 8, true, false, KnowledgeConfig.defaults(tmp).boundedFetch(), 0, null);
            final var llm = new StubLlm();
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
                assertEquals(6, result.indexedCount());
                assertFalse(llm.wasInvoked(), "no LLM call when useLlm is false");
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
            final var config = new KnowledgeConfig(
                    tmp, 4, 2, true, false, KnowledgeConfig.defaults(tmp).boundedFetch(), 0, null);
            final var llm = new StubLlm();
            final var indexer = newIndexer(config, llm);
            try {
                final var beforeBatch = new ArrayList<Integer>();
                final var result = indexer.index(
                        List.of(
                                Gav.of("g", "lib0", "1"),
                                Gav.of("g", "lib1", "1"),
                                Gav.of("g", "lib2", "1"),
                                Gav.of("g", "lib3", "1"),
                                Gav.of("g", "lib4", "1"),
                                Gav.of("g", "lib5", "1")),
                        null,
                        beforeBatch::add);
                assertEquals(3, beforeBatch.size(), "one callback per batch");
                assertEquals(List.of(0, 2, 4), beforeBatch, "cumulative done before each batch must be non-decreasing");
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
            final var llm = new StubLlm();
            final var indexer = newIndexer(config, llm);
            try {
                final var result = indexer.index(List.of(Gav.of("g", "a", "1")), null);
                assertEquals(0, result.indexedCount());
                assertEquals(1, result.skippedCount());
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
            final var llm = new StubLlm();
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
            final var llm = new StubLlm();
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
    void writesKnowledgeSetInFlatArtifactShape() throws Exception {
        try (final var repo = new FakeMavenRepo(tmp)) {
            repo.sourcesJar(
                    "com.example",
                    "widget",
                    "3",
                    "com/example/widget/Widget.java",
                    "package com.example.widget; public class Widget{}");
            final var config = KnowledgeConfig.defaults(tmp);
            final var llm = new StubLlm();
            final var indexer = newIndexer(config, llm);
            try {
                indexer.index(List.of(Gav.of("com.example", "widget", "3")), null);
            } finally {
                indexer.close();
            }
            final var dir = repo.artifactDir("com.example", "widget", "3");
            assertTrue(Files.isRegularFile(dir.resolve("widget-3-knowledge-index.md")), "index must be written");
            assertTrue(Files.isRegularFile(dir.resolve("widget-3-knowledge-api.md")), "api sibling must be written");
            assertTrue(
                    Files.isRegularFile(dir.resolve("widget-3-knowledge-sources-0.md")),
                    "sources-0 sibling must be written");
            final var index = Files.readString(dir.resolve("widget-3-knowledge-index.md"));
            assertTrue(index.startsWith("---\nname: widget\n"), "must start with YAML frontmatter name");
            assertTrue(index.contains("## Knowledge"), "must list knowledge siblings");
            assertTrue(index.contains("`api`"), "must reference the api sibling");
            assertTrue(index.contains("`sources-0`"), "must reference the sources-0 sibling");
            assertFalse(index.contains("## Skill"), "no Skill notice when useLlm is off");
            // no legacy files must be produced
            assertFalse(
                    Files.isRegularFile(dir.resolve("widget-3-skills.md")),
                    "legacy remote manifest must not be written");
            assertFalse(Files.isRegularFile(dir.resolve("yca/widghter/SKILL.md")));
        }
    }

    @Test
    void writesDeterministicKnowledgeWhenLlmUnavailable() throws Exception {
        try (final var repo = new FakeMavenRepo(tmp)) {
            repo.sourcesJar("g", "a", "1", "g/a/A.java", "package g.a; public class A{}");
            final var config = KnowledgeConfig.defaults(tmp).withUseLlm(true);
            final var llm = new StubLlm().unavailable();
            final var indexer = newIndexer(config, llm);
            try {
                final var result = indexer.index(List.of(Gav.of("g", "a", "1")), null);
                assertEquals(1, result.indexedCount(), "knowledge written even when LLM unavailable");
                final var index = repo.artifactDir("g", "a", "1").resolve("a-1-knowledge-index.md");
                assertTrue(Files.isRegularFile(index));
                assertFalse(index.toString().contains("LLM was not reachable"));
            } finally {
                indexer.close();
            }
        }
    }

    @Test
    void ignoresPlaceholderLlmOutputWhenUseLlm() throws Exception {
        try (final var repo = new FakeMavenRepo(tmp)) {
            repo.sourcesJar("g", "a", "1", "g/a/A.java", "package g.a; public class A{}");
            final var config = KnowledgeConfig.defaults(tmp).withUseLlm(true);
            final var llm = new StubLlm().skill("{\"skill\":\"SKILL.md\"}");
            final var indexer = newIndexer(config, llm);
            try {
                final var result = indexer.index(List.of(Gav.of("g", "a", "1")), null);
                assertEquals(1, result.indexedCount());
                final var dir = repo.artifactDir("g", "a", "1");
                // placeholder JSON shell must be rejected, so no skill sibling and no Skill notice
                assertFalse(Files.isRegularFile(dir.resolve("a-1-knowledge-skill-a.md")));
                final var index = Files.readString(dir.resolve("a-1-knowledge-index.md"));
                assertFalse(index.contains("## Skill"));
            } finally {
                indexer.close();
            }
        }
    }

    @Test
    void stopsAcquiringWhenRunContentBudgetExhausted() {
        try (final var repo = new FakeMavenRepo(tmp)) {
            repo.sourcesJar("g", "a", "1", "g/a/A.java", "package g.a; public class A{}")
                    .sourcesJar("g", "b", "1", "g/b/B.java", "package g.b; public class B{}");
            final var config = new KnowledgeConfig(
                    tmp, 4, 8, true, false, KnowledgeConfig.defaults(tmp).boundedFetch(), 0, null, 1024 * 1024, 40);
            final var llm = new StubLlm();
            final var indexer = newIndexer(config, llm);
            try {
                final var result = indexer.index(List.of(Gav.of("g", "a", "1"), Gav.of("g", "b", "1")), null);
                // first artifact consumed the whole run budget, second is skipped without being acquired
                assertEquals(1, result.indexedCount());
                assertEquals(1, result.skippedCount());
                final var skippedReasons = result.items().stream()
                        .filter(IndexResult::skipped)
                        .map(IndexResult::reason)
                        .toList();
                assertEquals(1, skippedReasons.size());
                assertTrue(
                        skippedReasons.get(0).contains("run content budget"),
                        "remaining artifacts must be skipped after the run budget is exhausted");
            } finally {
                indexer.close();
            }
        }
    }
}
