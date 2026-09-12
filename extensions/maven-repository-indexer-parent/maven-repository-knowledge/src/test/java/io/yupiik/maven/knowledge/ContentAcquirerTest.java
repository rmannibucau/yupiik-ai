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
package io.yupiik.maven.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.yupiik.maven.knowledge.config.KnowledgeConfig;
import io.yupiik.maven.knowledge.content.ContentAcquirer;
import io.yupiik.maven.knowledge.content.ContentKind;
import io.yupiik.maven.knowledge.content.LocalRepository;
import io.yupiik.maven.knowledge.test.FakeMavenRepo;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContentAcquirerTest {

    @TempDir
    Path tmp;

    @Test
    void prefersSourcesOverJavadoc() {
        try (final var repo = new FakeMavenRepo(tmp)) {
            repo.sourcesJar("g", "a", "1", "g/a/A.java", "package g.a; public class A { public void run(){} }")
                    .javadocJar("g", "a", "1", "index-all.html", "<html><body>A docs</body></html>")
                    .pom("g", "a", "1", null);
            final var config = KnowledgeConfig.defaults(tmp);
            final var acquirer = new ContentAcquirer(new LocalRepository(tmp), (url, timeout, bytes) -> "DOC", config);
            assertEquals(
                    ContentKind.SOURCES, acquirer.acquire(Gav.of("g", "a", "1")).kind());
        }
    }

    @Test
    void resolvesJavadocWhenNoSources() {
        try (final var repo = new FakeMavenRepo(tmp)) {
            repo.javadocJar("g", "b", "1", "index-all.html", "<html><body>B docs</body></html>")
                    .pom("g", "b", "1", null);
            final var config = KnowledgeConfig.defaults(tmp);
            final var acquirer = new ContentAcquirer(new LocalRepository(tmp), (url, timeout, bytes) -> "DOC", config);
            final var acquired = acquirer.acquire(Gav.of("g", "b", "1"));
            assertEquals(ContentKind.JAVADOC, acquired.kind());
        }
    }

    @Test
    void fetchesPomUrlWhenNoJarsButSkipsGitHostsAndNonDocUrls() {
        try (final var repo = new FakeMavenRepo(tmp)) {
            repo.pom("g", "c", "1", "https://docs.example.com/c/");
            repo.pom("g", "d", "1", "https://github.com/org/repo");
            final var config = KnowledgeConfig.defaults(tmp);
            final var acquirer = new ContentAcquirer(new LocalRepository(tmp), (url, timeout, bytes) -> "DOC", config);
            assertEquals(
                    ContentKind.DOC, acquirer.acquire(Gav.of("g", "c", "1")).kind(), "doc host should be webfetched");
            assertEquals(
                    ContentKind.NONE,
                    acquirer.acquire(Gav.of("g", "d", "1")).kind(),
                    "git url must be skipped and no jar present -> none");
        }
    }

    @Test
    void webfetchFailureIsOptionalAndNeverBlocks() {
        try (final var repo = new FakeMavenRepo(tmp)) {
            repo.pom("g", "f", "1", "https://docs.example.com/f/");
            final var config = KnowledgeConfig.defaults(tmp);
            final var acquirer = new ContentAcquirer(
                    new LocalRepository(tmp),
                    (url, timeout, bytes) -> {
                        throw new java.io.IOException("connection refused (mocked)");
                    },
                    config);
            assertEquals(
                    ContentKind.NONE,
                    acquirer.acquire(Gav.of("g", "f", "1")).kind(),
                    "a webfetch failure must fall through, not throw");
        }
    }

    @Test
    void webfetchRuntimeFailureFallsThroughToClasses() {
        try (final var repo = new FakeMavenRepo(tmp)) {
            repo.pom("g", "f2", "1", "https://docs.example.com/f2/").classesJar("g", "f2", "1", "g.f2.Main");
            final var config = KnowledgeConfig.defaults(tmp);
            final var acquirer = new ContentAcquirer(
                    new LocalRepository(tmp),
                    (url, timeout, bytes) -> {
                        throw new IllegalStateException("unexpected runtime failure");
                    },
                    config);
            final var acquired = acquirer.acquire(Gav.of("g", "f2", "1"));
            assertEquals(
                    ContentKind.CLASSES,
                    acquired.kind(),
                    "an unexpected webfetch runtime failure must not block, classes listing must be used");
        }
    }

    @Test
    void fallsBackToClassesListing() {
        try (final var repo = new FakeMavenRepo(tmp)) {
            repo.classesJar("g", "e", "1", "g.e.Main").pom("g", "e", "1", null);
            final var config = KnowledgeConfig.defaults(tmp);
            final var acquirer = new ContentAcquirer(new LocalRepository(tmp), (url, timeout, bytes) -> "DOC", config);
            final var acquired = acquirer.acquire(Gav.of("g", "e", "1"));
            assertEquals(ContentKind.CLASSES, acquired.kind());
            assertEquals(true, acquired.content().contains("g.e.Main"), "listing should contain the class");
        }
    }

    @Test
    void reportsNoneForAbsentArtifact() {
        final var config = KnowledgeConfig.defaults(tmp);
        final var acquirer = new ContentAcquirer(new LocalRepository(tmp), (url, timeout, bytes) -> "DOC", config);
        assertEquals(
                ContentKind.NONE, acquirer.acquire(Gav.of("missing", "x", "9")).kind());
    }
}
