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

class MavenRepositoryKnowledgeServiceTest {

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

            final var run = service.currentIndex();
            assertTrue(run.inFlight(), "indexing should be in flight right after starting");
            final var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (run.inFlight() && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            assertFalse(run.inFlight(), "indexing should have completed");
            assertEquals(run.total(), run.doneCount(), "all artifacts must have been processed");
            final var summary = run.summary();
            assertTrue(summary.contains("indexed"), () -> "summary should report indexed: " + summary);
            final var last = progress.get(progress.size() - 1);
            assertTrue(last.contains("(2/2)") || last.contains("100%"), () -> "final progress should be 100%: " + last);

            assertTrue(
                    Files.exists(m2.resolve("g/com/artifact-a/1.0/yca/artifact-a/SKILL.md")),
                    () -> "artifact-a SKILL.md missing; summary: " + summary);
            assertTrue(Files.exists(m2.resolve("g/com/artifact-b/2.0/yca/artifact-b/SKILL.md")));
            assertTrue(Files.exists(m2.resolve("g/com/artifact-a/1.0/artifact-a-1.0-skills.md")));
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

    private static final class StubLlm implements KnowledgeLlm {
        @Override
        public boolean available() {
            return true;
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
