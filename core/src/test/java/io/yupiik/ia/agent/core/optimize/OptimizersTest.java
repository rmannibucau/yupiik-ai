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
package io.yupiik.ia.agent.core.optimize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.core.configuration.OptimizeConfiguration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OptimizersTest {

    @Test
    void normalizesCommands() {
        assertEquals("mvn", Optimizers.normalizeCommand("mvn"));
        assertEquals("mvnw", Optimizers.normalizeCommand("./mvnw"));
        assertEquals("mvn", Optimizers.normalizeCommand("/usr/bin/mvn.cmd"));
        assertEquals("gh", Optimizers.normalizeCommand("C:\\tools\\gh.EXE"));
        assertEquals("git", Optimizers.normalizeCommand("../git.sh"));
        assertEquals("git.bat", Optimizers.normalizeCommand("git.bat"));
        assertEquals("dummy", Optimizers.normalizeCommand("/foo/bar/dummy"));
        assertEquals("", Optimizers.normalizeCommand(null));
        assertEquals("", Optimizers.normalizeCommand("  "));
    }

    @Test
    void matchesAliasesLoosely() {
        assertTrue(Optimizers.matchesAlias("./mvnw", Set.of("mvn", "mvnd", "mvnw")));
        assertTrue(Optimizers.matchesAlias("MVN.CMD", Set.of("mvn", "mvnd", "mvnw")));
        assertTrue(Optimizers.matchesAlias("/usr/local/bin/git.exe", Set.of("git")));
        assertFalse(Optimizers.matchesAlias("prettier", Set.of("npm", "pnpm")));
        assertFalse(Optimizers.matchesAlias(null, Set.of("git")));
    }

    @Test
    void matchesHeadHandlesPreamble() {
        // plain head (existing behavior)
        assertTrue(Optimizers.matchesHead("mvn clean install", "mvn", "mvnd"));
        assertTrue(Optimizers.matchesHead("./mvnw test", "mvnw"));
        assertTrue(Optimizers.matchesHead("/usr/bin/mvn.cmd -q package", "mvn"));
        assertFalse(Optimizers.matchesHead("git status", "mvn", "mvnd"));

        // leading env assignments
        assertTrue(Optimizers.matchesHead("MY_FLAG=1 mvn -q test", "mvn", "mvnd"));
        assertTrue(Optimizers.matchesHead("HTTP_PROXY=http://x JAVA_HOME=/jdk mvn install", "mvn", "mvnd"));
        assertTrue(Optimizers.matchesHead("FOO=1 ./mvnw -q package", "mvnw"));
        // env-prefixed but not an env var (a flag/option) must not be skipped as env
        assertFalse(Optimizers.matchesHead("-DskipTests mvn install", "mvn", "mvnd"));

        // leading cd <dir> && segments
        assertTrue(Optimizers.matchesHead("cd /opt/dev/project && mvn -q test", "mvn", "mvnd"));
        assertTrue(Optimizers.matchesHead("cd /opt/dev/project && git status", "git"));
        assertTrue(Optimizers.matchesHead("cd /a && cd /b && mvn install", "mvn", "mvnd"));
        assertTrue(Optimizers.matchesHead("cd \"/my path\" && mvn -q test", "mvn", "mvnd"));
        assertTrue(Optimizers.matchesHead("cd /a && FOO=1 mvn install", "mvn", "mvnd"));
        assertTrue(Optimizers.matchesHead("FOO=1 cd /a && mvn install", "mvn", "mvnd"));

        // cd without the target command afterwards
        assertFalse(Optimizers.matchesHead("cd /somewhere", "mvn", "mvnd"));
        assertFalse(Optimizers.matchesHead("cd /somewhere &&", "mvn", "mvnd"));
    }

    @Test
    void isDisabledMatchesConfiguredNames() {
        final var maven = new OptimizeConfiguration(true, 400, 20, 10, List.of("mvn"));
        assertTrue(maven.isDisabled(List.of("mvn", "mvnd", "mvnw")));
        assertFalse(maven.isDisabled(List.of("git")));

        final var mvnw = new OptimizeConfiguration(true, 400, 20, 10, List.of("./mvnw.cmd"));
        assertTrue(mvnw.isDisabled(List.of("mvn", "mvnd", "mvnw")));

        final var tools = new OptimizeConfiguration(true, 400, 20, 10, List.of("bash"));
        assertTrue(tools.isDisabled(List.of("bash")));

        final var none = new OptimizeConfiguration(true, 400, 20, 10, List.of());
        assertFalse(none.isDisabled(List.of("mvn")));
    }

    @Test
    void displayNameDropsFusionSubclass() {
        assertEquals("GenericOutputProcessor", new GenericOutputProcessor(null).displayName());
        assertEquals("Fake", new Fake$FusionSubclass().displayName());
    }

    @Test
    void processorsDeclareCommandSpellings() {
        assertEquals(Set.of("mvn", "mvnd", "mvnw"), new MavenOutputProcessor().names());
        assertEquals(Set.of("gradle", "gradlew"), new GradleOutputProcessor().names());
        assertEquals(Set.of("git"), new GitOutputProcessor().names());
        assertEquals(Set.of("gh"), new GhOutputProcessor().names());
        assertEquals(Set.of("npm", "pnpm"), new NpmOutputProcessor().names());
        assertEquals(Set.of("prettier"), new PrettierOutputProcessor().names());
        assertEquals(Set.of("generic"), new GenericOutputProcessor(null).names());
    }

    private static class Fake$FusionSubclass extends GenericOutputProcessor {}
}
