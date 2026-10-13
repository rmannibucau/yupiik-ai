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
import java.util.Optional;
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
    void unwrapsCommandWrappers() {
        // plain command without a wrapper -> unchanged (empty)
        assertEquals(Optional.empty(), Optimizers.unwrapCommandWrapper(null));
        assertEquals(Optional.empty(), Optimizers.unwrapCommandWrapper("  "));
        assertEquals(Optional.empty(), Optimizers.unwrapCommandWrapper("mvn clean install"));
        assertEquals(Optional.empty(), Optimizers.unwrapCommandWrapper("./mvnw test"));

        // timeout <duration>
        assertEquals(
                Optional.of("mvn -o spotless:apply 2>&1"),
                Optimizers.unwrapCommandWrapper("timeout 120 mvn -o spotless:apply 2>&1"));
        // timeout with a leading flag
        assertEquals(
                Optional.of("git status"), Optimizers.unwrapCommandWrapper("timeout --preserve-status 30 git status"));
        // a non-numeric "duration" is not a wrapper -> unchanged
        assertEquals(Optional.empty(), Optimizers.unwrapCommandWrapper("timeout nope mvn install"));
        // bare timeout (no command after the duration) -> unchanged
        assertEquals(Optional.empty(), Optimizers.unwrapCommandWrapper("timeout 120"));

        // sudo / nohup / command
        assertEquals(Optional.of("mvn -q test"), Optimizers.unwrapCommandWrapper("sudo mvn -q test"));
        assertEquals(Optional.of("git fetch"), Optimizers.unwrapCommandWrapper("nohup git fetch"));
        assertEquals(Optional.of("./mvnw package"), Optimizers.unwrapCommandWrapper("command ./mvnw package"));

        // env with leading assignments
        assertEquals(
                Optional.of("mvn clean install"), Optimizers.unwrapCommandWrapper("env FOO=1 BAR=2 mvn clean install"));
        // bare env assignments without the env keyword are handled natively by matchesHead, so there
        // is no wrapper to strip here (empty = leave the command unchanged)
        assertEquals(Optional.empty(), Optimizers.unwrapCommandWrapper("FOO=1 mvn clean install"));

        // chained wrappers
        assertEquals(Optional.of("mvn -o apply"), Optimizers.unwrapCommandWrapper("sudo timeout 120 mvn -o apply"));
        assertEquals(Optional.of("docker ps"), Optimizers.unwrapCommandWrapper("nohup env CI=1 sudo docker ps"));

        // sh/bash -c
        assertEquals(Optional.of("mvn test"), Optimizers.unwrapCommandWrapper("sh -c \"mvn test\""));
        assertEquals(Optional.of("git status"), Optimizers.unwrapCommandWrapper("bash -c \"git status\""));
        assertEquals(
                Optional.of("mvn -o apply"),
                Optimizers.unwrapCommandWrapper("bash -c \"sudo timeout 120 mvn -o apply\""));
        // a multi-command script inside -c is left to the generic fallback
        assertEquals(Optional.empty(), Optimizers.unwrapCommandWrapper("bash -c \"mvn package && git status\""));
        assertEquals(Optional.empty(), Optimizers.unwrapCommandWrapper("sh -c \"ls | wc -l\""));

        // cd <dir> && preamble kept consistent with matchesHead
        assertEquals(Optional.of("mvn -q test"), Optimizers.unwrapCommandWrapper("cd /opt/proj && sudo mvn -q test"));
    }

    @Test
    void isDisabledMatchesConfiguredNames() {
        final var maven = new OptimizeConfiguration(true, 400, 20, 10, true, List.of("mvn"), null, null);
        assertTrue(maven.isDisabled(List.of("mvn", "mvnd", "mvnw")));
        assertFalse(maven.isDisabled(List.of("git")));

        final var mvnw = new OptimizeConfiguration(true, 400, 20, 10, true, List.of("./mvnw.cmd"), null, null);
        assertTrue(mvnw.isDisabled(List.of("mvn", "mvnd", "mvnw")));

        final var tools = new OptimizeConfiguration(true, 400, 20, 10, true, List.of("bash"), null, null);
        assertTrue(tools.isDisabled(List.of("bash")));

        final var none = new OptimizeConfiguration(true, 400, 20, 10, true, List.of(), null, null);
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
        assertEquals(Set.of("npm", "pnpm", "yarn", "bun"), new NpmOutputProcessor().names());
        assertEquals(Set.of("prettier"), new PrettierOutputProcessor().names());
        assertEquals(Set.of("generic"), new GenericOutputProcessor(null).names());
        assertEquals(
                Set.of("ls", "find", "tree", "du", "grep", "rg", "egrep", "fgrep", "ast-grep"),
                new FilesystemSearchOutputProcessor(null).names());
        assertEquals(Set.of("tsc", "eslint", "biome", "next"), new JsOutputProcessor(null).names());
        assertEquals(Set.of("kubectl"), new KubectlOutputProcessor().names());
        assertEquals(Set.of("docker", "docker-compose", "docker compose"), new DockerOutputProcessor().names());
        assertEquals(Set.of("json"), new JsonOutputProcessor(null, null).names());
    }

    @Test
    void secondTokenSkipsFlags() {
        assertEquals("build", Optimizers.secondToken("gradle --no-daemon build"));
        assertEquals("", Optimizers.secondToken("mvn"));
        assertEquals("stash", Optimizers.secondToken("git stash list"));
    }

    @Test
    void thirdTokenGetsGhSubcommand() {
        assertEquals("view", Optimizers.thirdToken("gh pr view 42"));
        assertEquals("list", Optimizers.thirdToken("gh run list"));
        assertEquals("", Optimizers.thirdToken("gh"));
        assertEquals("", Optimizers.thirdToken("gh api"));
    }

    private static class Fake$FusionSubclass extends GenericOutputProcessor {}
}
