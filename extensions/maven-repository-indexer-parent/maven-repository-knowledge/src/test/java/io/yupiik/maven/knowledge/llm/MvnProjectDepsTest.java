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
package io.yupiik.maven.knowledge.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.yupiik.maven.knowledge.Gav;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MvnProjectDepsTest {

    @Test
    void parseOrdersProvidedThenCompileThenTestThenOther() {
        final var output = """
                [INFO] --- dependency:3.6.1:list (default-cli) @ app ---
                [INFO] The following files have been resolved:
                [INFO]    org.junit.jupiter:junit-jupiter:jar:5.10.2:test -- module junit.jupiter [auto]
                [INFO]    org.slf4j:slf4j-api:jar:2.0.13:runtime -- module org.slf4j [auto]
                [INFO]    com.ex:lib:jar:1.0:compile -- module com.ex.lib
                [INFO]    javax.servlet:javax.servlet-api:jar:4.0.1:provided -- module javax.servlet.api [auto]
                [INFO] ------------------------------------------------------------------------
                """;
        assertEquals(
                List.of(
                        Gav.of("javax.servlet", "javax.servlet-api", "4.0.1"),
                        Gav.of("com.ex", "lib", "1.0"),
                        Gav.of("org.junit.jupiter", "junit-jupiter", "5.10.2"),
                        Gav.of("org.slf4j", "slf4j-api", "2.0.13")),
                MvnProjectDeps.parse(output));
    }

    @Test
    void parseDedupsKeepingTheMostRelevantScope() {
        final var output = """
                [INFO]    com.ex:lib:jar:1.0:test
                [INFO]    com.ex:lib:jar:1.0:provided
                [INFO]    other:artifact:jar:2.0:compile
                """;
        assertEquals(
                List.of(Gav.of("com.ex", "lib", "1.0"), Gav.of("other", "artifact", "2.0")),
                MvnProjectDeps.parse(output));
    }

    @Test
    void parseFiltersNonJarAndUnknownLines() {
        final var output = """
                [INFO] Scanning for projects...
                [INFO]    com.ex:pom-only:pom:1.0:compile
                [INFO]    not a dependency line at all
                [INFO]    com.ex:kept:jar:1.0:compile
                """;
        assertEquals(List.of(Gav.of("com.ex", "kept", "1.0")), MvnProjectDeps.parse(output));
    }

    @Test
    void resolveReturnsEmptyForNonDirectory(@TempDir final Path tmp) {
        assertEquals(List.of(), MvnProjectDeps.resolve(tmp.resolve("missing"), 1000));
        assertEquals(List.of(), MvnProjectDeps.resolve(null, 1000));
    }

    @Test
    void resolveReturnsEmptyWhenMvnFails(@TempDir final Path tmp) {
        // no pom.xml in the directory: mvn fails quickly (offline) and the resolution degrades to empty
        assertEquals(List.of(), MvnProjectDeps.resolve(tmp, 120_000));
    }

    @Test
    void fingerprintIsStableAndChangesWithBuildFiles(@TempDir final Path tmp) throws Exception {
        final var pom = tmp.resolve("pom.xml");
        Files.writeString(pom, "<project/>");
        final var first = MvnProjectDeps.fingerprint(tmp);
        assertEquals(first, MvnProjectDeps.fingerprint(tmp), "fingerprint must be stable for unchanged files");
        Files.writeString(pom, "<project><!-- changed --></project>");
        org.junit.jupiter.api.Assertions.assertNotEquals(
                first, MvnProjectDeps.fingerprint(tmp), "fingerprint must change when a build file changes");
    }

    @Test
    void resolveUsesCacheWithoutRunningMvn(@TempDir final Path tmp) throws Exception {
        final var project = tmp.resolve("project");
        Files.createDirectories(project);
        // a build file exists so a fingerprint can be computed; mvn would fail (no real pom) - the cache bypasses it
        Files.writeString(project.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion></project>");
        final var cacheDir = tmp.resolve("repo");
        final var cached = List.of(Gav.of("com.ex", "cached", "1"), Gav.of("com.ex", "second", "2"));
        final var cacheFile =
                cacheDir.resolve(".yca").resolve("project-deps").resolve(MvnProjectDeps.fingerprint(project) + ".deps");
        Files.createDirectories(cacheFile.getParent());
        Files.writeString(cacheFile, "com.ex:cached:1\ncom.ex:second:2\n");

        assertEquals(cached, MvnProjectDeps.resolve(project, 120_000, cacheDir), "cache hit must skip mvn");
    }

    @Test
    void fingerprintChangeInvalidatesTheCache(@TempDir final Path tmp) throws Exception {
        final var project = tmp.resolve("project");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        // an entry for the OLD fingerprint (mismatch) -> cache must be ignored and mvn re-run (fails -> empty)
        final var cacheDir = tmp.resolve("repo");
        final var cacheFile =
                cacheDir.resolve(".yca").resolve("project-deps").resolve(MvnProjectDeps.fingerprint(project) + ".deps");
        Files.createDirectories(cacheFile.getParent());
        Files.writeString(cacheFile, "com.ex:cached:1\n");

        // change a build file so the fingerprint no longer matches the stale cache entry
        Files.writeString(project.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion></project>");
        assertEquals(
                List.of(),
                MvnProjectDeps.resolve(project, 120_000, cacheDir),
                "a stale cache entry (different fingerprint) must not be reused");
    }
}
