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
package io.yupiik.ia.agent.core.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.framework.api.lifecycle.Start;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration$FusionConfigurationFactory;
import io.yupiik.ia.agent.core.runtime.VirtualThreadExecutor;
import io.yupiik.maven.knowledge.Gav;
import io.yupiik.maven.knowledge.llm.KnowledgeLlm;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class MavenRepositoryKnowledgeServiceTest {

    @TempDir
    Path tmp;

    @Test
    void asyncRefreshIndexesArtifactsAndReportsProgress() throws IOException, InterruptedException {
        final var m2 = Files.createDirectories(tmp.resolve("m2"));
        final var proj = Files.createDirectories(tmp.resolve("proj"));
        Files.writeString(proj.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion></project>");
        classesOnly(m2, "g.com", "artifact-a", "1.0", "g.com.a.Main");
        classesOnly(m2, "g.com", "artifact-b", "2.0", "g.com.b.Main");

        final var config = config(m2.toString(), proj.toString());
        final var progress = new CopyOnWriteArrayList<String>();
        final var executor = new VirtualThreadExecutor();
        final var service = new MavenRepositoryKnowledgeService(config, new StubLlm(), executor, null);
        try {
            final var ack = service.refresh(null, progress::add, null);
            assertFalse(ack.isEmpty(), "the refresh should acknowledge starting");
            assertTrue(ack.get(0).contains("started indexing"), () -> "unexpected ack: " + ack);
            assertTrue(
                    ack.stream().anyMatch(l -> l.contains("starting to index") && l.contains("g.com:artifact-a:1.0")),
                    () -> "ack should preview the artifacts: " + ack);

            final var run = service.currentIndex();
            assertTrue(run.inFlight(), "indexing should be in flight right after starting");
            assertTrue(run.awaitCompletion(15, TimeUnit.SECONDS), "indexing should have completed");
            assertEquals(run.total(), run.doneCount(), "all artifacts must have been processed");
            final var summary = run.summary();
            assertTrue(summary.contains("indexed"), () -> "summary should report indexed: " + summary);
            final var last = progress.get(progress.size() - 1);
            assertTrue(last.contains("(2/2)") || last.contains("100%"), () -> "final progress should be 100%: " + last);

            assertTrue(
                    Files.exists(m2.resolve("g/com/artifact-a/1.0/artifact-a-1.0-knowledge-index.md")),
                    () -> "artifact-a knowledge index missing; summary: " + summary);
            assertTrue(Files.exists(m2.resolve("g/com/artifact-a/1.0/artifact-a-1.0-knowledge-api.md")));
            assertTrue(Files.exists(m2.resolve("g/com/artifact-b/2.0/artifact-b-2.0-knowledge-index.md")));
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void disabledWithoutBuildFileKeepsQuiet() {
        final var m2 = tmp.resolve("m2");
        final var plain = tmp.resolve("plain");
        final var executor = new VirtualThreadExecutor();
        final var service = new MavenRepositoryKnowledgeService(
                config(m2.toString(), plain.toString()), new StubLlm(), executor, null);
        try {
            final var res = service.refresh("g.com:artifact-a:1.0", null, null);
            assertEquals(List.of("maven repository knowledge is disabled"), res);
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void statusReportsIdleThenRunProgressAndLastSummary() throws IOException, InterruptedException {
        final var m2 = Files.createDirectories(tmp.resolve("m2-status"));
        final var proj = Files.createDirectories(tmp.resolve("proj-status"));
        Files.writeString(proj.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion></project>");
        classesOnly(m2, "g.com", "artifact-a", "1.0", "g.com.a.Main");
        classesOnly(m2, "g.com", "artifact-b", "2.0", "g.com.b.Main");

        final var config = config(m2.toString(), proj.toString());
        final var executor = new VirtualThreadExecutor();
        final var service = new MavenRepositoryKnowledgeService(config, new StubLlm(), executor, null);
        try {
            assertTrue(
                    service.status().stream().anyMatch(l -> l.contains("no index has run")),
                    () -> "before any run the status should say nothing ran: " + service.status());

            service.refresh(null, null, null);
            final var run = service.currentIndex();
            assertTrue(run.inFlight(), "indexing should be in flight right after starting");
            assertTrue(
                    service.status().stream().anyMatch(l -> l.startsWith("index in progress:")),
                    () -> "while running the status should report progress: " + service.status());

            assertTrue(run.awaitCompletion(15, TimeUnit.SECONDS), "indexing should have completed");
            assertTrue(
                    service.status().stream().anyMatch(l -> l.contains("index idle") && l.contains("indexed")),
                    () -> "after the run the status should show the last summary: " + service.status());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void baselineScopesToProjectDependenciesOnly() throws IOException, InterruptedException {
        final var m2 = Files.createDirectories(tmp.resolve("m2-cohort"));
        final var proj = Files.createDirectories(tmp.resolve("proj-cohort"));
        Files.writeString(proj.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion></project>");
        classesOnly(m2, "g.com", "artifact-a", "1.0", "g.com.a.Main");
        classesOnly(m2, "g.com", "artifact-b", "2.0", "g.com.b.Main");
        classesOnly(m2, "g.com", "artifact-other", "3.0", "g.com.other.Main");

        final var config = config(m2.toString(), proj.toString());
        final var executor = new VirtualThreadExecutor();
        final var service = new MavenRepositoryKnowledgeService(config, new StubLlm(), executor, null);
        try {
            final var ack = service.refresh(null, null, null);
            assertTrue(ack.get(0).contains("indexing 2"), () -> "only project deps should be indexed: " + ack);
            assertFalse(
                    ack.stream().anyMatch(l -> l.contains("artifact-other")),
                    () -> "a local artifact not in the project deps must be ignored: " + ack);

            final var run = service.currentIndex();
            assertEquals(2, run.total(), "cohort must match project deps");
            assertTrue(run.awaitCompletion(15, TimeUnit.SECONDS), "indexing should have completed");
            assertTrue(Files.exists(m2.resolve("g/com/artifact-a/1.0/artifact-a-1.0-knowledge-index.md")));
            assertTrue(Files.exists(m2.resolve("g/com/artifact-b/2.0/artifact-b-2.0-knowledge-index.md")));
            assertFalse(Files.exists(m2.resolve("g/com/artifact-other/3.0/artifact-other-3.0-knowledge-index.md")));
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void sessionSkillBlocksRemainEmptyForLazyMountAfterIndexing() throws IOException, InterruptedException {
        final var m2 = Files.createDirectories(tmp.resolve("m2-lazy"));
        final var proj = Files.createDirectories(tmp.resolve("proj-lazy"));
        Files.writeString(proj.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion></project>");
        classesOnly(m2, "g.com", "artifact-a", "1.0", "g.com.a.Main");
        classesOnly(m2, "g.com", "artifact-b", "2.0", "g.com.b.Main");

        final var config = config(m2.toString(), proj.toString());
        final var executor = new VirtualThreadExecutor();
        final var service = new MavenRepositoryKnowledgeService(config, new StubLlm(), executor, null);
        try {
            service.refresh(null, null, null);
            final var run = service.currentIndex();
            assertTrue(run.awaitCompletion(15, TimeUnit.SECONDS), "indexing should have completed");

            service.onStart(new Start());
            assertTrue(
                    service.sessionSkillBlocks().isEmpty(),
                    "with the lazy mount design no skill block is pre-mounted into the prompt");

            final var index = service.knowledge("g.com:artifact-a:1.0", null);
            assertTrue(index.contains("## Knowledge"), () -> "on-demand index still available: " + index);
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void knowledgeReadsIndexAndReferencedSibling() throws IOException, InterruptedException {
        final var m2 = Files.createDirectories(tmp.resolve("m2-content"));
        final var proj = Files.createDirectories(tmp.resolve("proj-content"));
        Files.writeString(proj.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion></project>");
        classesOnly(m2, "g.com", "artifact-a", "1.0", "g.com.a.Main");

        final var config = config(m2.toString(), proj.toString());
        final var executor = new VirtualThreadExecutor();
        final var service = new MavenRepositoryKnowledgeService(config, new StubLlm(), executor, null);
        try {
            service.refresh(null, null, null);
            final var run = service.currentIndex();
            assertTrue(run.awaitCompletion(15, TimeUnit.SECONDS), "indexing should have completed");

            final var index = service.knowledge("g.com:artifact-a:1.0", null);
            assertTrue(index.contains("## Knowledge"), () -> "index content expected: " + index);
            final var api = service.knowledge("g.com:artifact-a:1.0", "api");
            assertTrue(
                    api.contains("Artifact API") || api.contains("No public API detected."),
                    () -> "api sibling content expected: " + api);
            assertTrue(
                    service.knowledge("g.com:artifact-a:1.0", "unknown").contains("no knowledge file"),
                    () -> "missing sibling should be reported");
            assertTrue(
                    service.knowledge("g.com:notindexed:1.0", null).contains("no knowledge indexed"),
                    () -> "unindexed coordinate should be reported");
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void knowledgeCacheInvalidatedByFileMtime() throws IOException, InterruptedException {
        final var m2 = Files.createDirectories(tmp.resolve("m2-cache"));
        final var proj = Files.createDirectories(tmp.resolve("proj-cache"));
        Files.writeString(proj.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion></project>");
        classesOnly(m2, "g.com", "artifact-a", "1.0", "g.com.a.Main");

        final var config = config(m2.toString(), proj.toString());
        final var executor = new VirtualThreadExecutor();
        final var service = new MavenRepositoryKnowledgeService(config, new StubLlm(), executor, null);
        try {
            service.refresh(null, null, null);
            final var run = service.currentIndex();
            assertTrue(run.awaitCompletion(15, TimeUnit.SECONDS), "indexing should have completed");

            final var indexFile = m2.resolve("g/com/artifact-a/1.0/artifact-a-1.0-knowledge-index.md");
            final var first = service.knowledge("g.com:artifact-a:1.0", null);
            assertTrue(first.contains("## Knowledge"), () -> "first read should succeed: " + first);

            Files.writeString(indexFile, "## Modified\nchanged content\n");
            final var future = java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 2000);
            Files.setLastModifiedTime(indexFile, future);

            final var second = service.knowledge("g.com:artifact-a:1.0", null);
            assertTrue(second.contains("## Modified"), () -> "cache should invalidate on mtime change: " + second);
        } finally {
            executor.shutdown();
        }
    }

    private void classesOnly(
            final Path m2, final String group, final String artifact, final String version, final String mainClass)
            throws IOException {
        final var dir = m2.resolve(group.replace('.', '/')).resolve(artifact).resolve(version);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(artifact + "-" + version + ".pom"), "<project/>");
        final var jar = dir.resolve(artifact + "-" + version + ".jar");
        try (final var zos = new ZipOutputStream(Files.newOutputStream(jar))) {
            zos.putNextEntry(new ZipEntry(mainClass.replace('.', '/') + ".class"));
            zos.write(new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE});
            zos.closeEntry();
        }
    }

    private static CodingAgentConfiguration config(final String localRepository, final String projectDir) {
        final var map = new HashMap<String, String>();
        map.put("yca.storageHome", "~/.yupiik/coding-agent");
        map.put("yca.workingDir", ".");
        map.put("yca.projectDir", projectDir);
        map.put("yca.mavenRepositoryKnowledge.enabled", "true");
        map.put("yca.mavenRepositoryKnowledge.localRepository", localRepository);
        map.put("yca.mavenRepositoryKnowledge.concurrency", "2");
        map.put("yca.mavenRepositoryKnowledge.batchSize", "2");
        map.put("yca.mavenRepositoryKnowledge.maxBatchChars", "-1");
        return new CodingAgentConfiguration$FusionConfigurationFactory(key -> Optional.ofNullable(map.get(key))).get();
    }

    public static final class StubLlm implements KnowledgeLlm {
        @Override
        public boolean available() {
            return true;
        }

        @Override
        public List<Gav> resolveProjectDeps() {
            return List.of(Gav.of("g.com", "artifact-a", "1.0"), Gav.of("g.com", "artifact-b", "2.0"));
        }

        @Override
        public Map<Gav, List<String>> entryPoints(final Map<Gav, String> contentByGav) {
            final var out = new HashMap<Gav, List<String>>();
            for (final var gav : contentByGav.keySet()) {
                out.put(gav, List.of("Main"));
            }
            return out;
        }

        @Override
        public List<Gav> suggestEntryGavs(final List<Gav> candidates) {
            return candidates;
        }

        @Override
        public String synthesizeSkill(
                final Gav gav, final String name, final String content, final List<String> entryPoints) {
            return """
                    ---
                    name: %s
                    description: generated for %s
                    ---
                    # %s
                    ## Entry points
                    """.formatted(name, gav.coordinate(), name);
        }
    }
}
