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
package io.yupiik.ia.agent.core.tools.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.ia.agent.core.configuration.TestConfigs;
import io.yupiik.maven.knowledge.process.ProcessService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression coverage for the bash tree-kill: a command that spawns a foreground child and waits
 * ({@code sh -c 'sleep 30 & echo $! > pid; wait'}) must not leave the child running after the
 * bash tool times out or is interrupted. Uses pid-file polling and a short timeout so the test is
 * fast and never sleeps on the real 30s.
 */
class BashToolProcessTest {

    @Test
    void timeoutKillsTheWholeTree(@TempDir final Path tmp) throws Exception {
        Assumptions.assumeTrue(!System.getProperty("os.name", "").toLowerCase().contains("win"));
        final var service = new ProcessService();
        service.reapGrace(200);
        final var bash = new BashTool(NOOP, service);
        final var pidFile = tmp.resolve("child.pid");

        final var command = "sh -c 'sleep 30 & echo $! > " + pidFile + "; wait'";
        final var result = bash.execute(Map.of("command", command, "timeout_seconds", "1"), context());
        assertFalse(result.success(), "the command must time out: " + result.output());

        final var childPid = awaitPid(pidFile);
        assertNotNull(childPid, "the child must have been spawned before the timeout");
        awaitGone(childPid);
        assertTrue(service.trackedCount() == 0, "no process may stay tracked after the timeout");
    }

    @Test
    void defaultsToTheSessionProjectDir(@TempDir final Path tmp) throws Exception {
        Assumptions.assumeTrue(!System.getProperty("os.name", "").toLowerCase().contains("win"));
        final var projectDir = tmp.resolve("project");
        Files.createDirectories(projectDir);
        final var sentinel = tmp.resolve("pwd.txt");
        final var bash = new BashTool(NOOP, new ProcessService());
        final var ctx = new io.yupiik.ia.agent.core.tools.ToolContext(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                io.yupiik.ia.agent.core.runtime.Cancellations.NEVER,
                null,
                null,
                projectDir);

        final var result = bash.execute(Map.of("command", "pwd > " + sentinel), ctx);
        assertTrue(result.success(), "the command must run: " + result.output());
        assertEquals(
                projectDir.toAbsolutePath().normalize().toString(),
                Files.readString(sentinel).strip(),
                "without an explicit workdir the session project dir must be the cwd");
    }

    @Test
    void explicitWorkdirWinsOverTheSessionProjectDir(@TempDir final Path tmp) throws Exception {
        Assumptions.assumeTrue(!System.getProperty("os.name", "").toLowerCase().contains("win"));
        final var projectDir = tmp.resolve("project");
        final var override = tmp.resolve("override");
        Files.createDirectories(projectDir);
        Files.createDirectories(override);
        final var sentinel = tmp.resolve("pwd.txt");
        final var bash = new BashTool(NOOP, new ProcessService());
        final var ctx = new io.yupiik.ia.agent.core.tools.ToolContext(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                io.yupiik.ia.agent.core.runtime.Cancellations.NEVER,
                null,
                null,
                projectDir);

        final var result = bash.execute(Map.of("command", "pwd > " + sentinel, "workdir", override.toString()), ctx);
        assertTrue(result.success(), "the command must run: " + result.output());
        assertEquals(
                override.toAbsolutePath().normalize().toString(),
                Files.readString(sentinel).strip(),
                "an explicit workdir must override the session project dir");
    }

    @Test
    void missingExplicitWorkdirReturnsAnActionableError() {
        Assumptions.assumeTrue(!System.getProperty("os.name", "").toLowerCase().contains("win"));
        final var bash = new BashTool(NOOP, new ProcessService());
        final var missing = java.nio.file.Path.of("/definitely/not/here/" + System.nanoTime());
        final var result = bash.execute(Map.of("command", "pwd", "workdir", missing.toString()), context());
        assertFalse(result.success(), "a missing explicit workdir must be rejected: " + result.output());
        assertTrue(
                result.output().contains(missing.toString()),
                "the error must name the missing path: " + result.output());
    }

    @Test
    void missingSessionDirFallsBackToTheConfiguredWorkdirWithANotice(@TempDir final Path tmp) throws Exception {
        Assumptions.assumeTrue(!System.getProperty("os.name", "").toLowerCase().contains("win"));
        final var configDir = tmp.resolve("config");
        Files.createDirectories(configDir);
        final var sentinel = tmp.resolve("pwd.txt");
        final var bash = new BashTool(
                NOOP, new ProcessService(), TestConfigs.codingAgent(Map.of("yca.workingDir", configDir.toString())));
        final var missingProject = tmp.resolve("project");
        // project dir recorded but gone: only the configured dir remains as a live candidate
        final var ctx = contextWithProjectDir(missingProject.toString());

        final var result = bash.execute(Map.of("command", "pwd > " + sentinel), ctx);
        assertTrue(result.success(), "the command must fall back and run: " + result.output());
        assertEquals(
                configDir.toAbsolutePath().normalize().toString(),
                Files.readString(sentinel).strip(),
                "the configured working dir must be used when the session project dir is stale");
        assertTrue(
                result.output().contains("not found") && result.output().contains("using"),
                "the deviation must be surfaced in the output: " + result.output());
    }

    @Test
    void missingSessionDirWithNoConfigFallsBackToTheJvmCwdWithANotice(@TempDir final Path tmp) throws Exception {
        Assumptions.assumeTrue(!System.getProperty("os.name", "").toLowerCase().contains("win"));
        final var bash = new BashTool(NOOP, new ProcessService()); // no configured fallback
        final var missingProject = tmp.resolve("gone");
        final var sentinel = tmp.resolve("pwd.txt");
        final var ctx = contextWithProjectDir(missingProject.toString());

        final var result = bash.execute(Map.of("command", "pwd > " + sentinel), ctx);
        assertTrue(result.success(), "the command must run in the JVM cwd: " + result.output());
        assertTrue(
                Files.readString(sentinel).strip().startsWith("/"),
                "the JVM working directory must be an absolute path: " + result.output());
        assertTrue(
                result.output().contains("JVM working directory"),
                "the JVM-cwd fallback must be reported: " + result.output());
    }

    private static io.yupiik.ia.agent.core.tools.ToolContext contextWithProjectDir(final String projectDir) {
        final var dir = java.nio.file.Path.of(projectDir).toAbsolutePath().normalize();
        return new io.yupiik.ia.agent.core.tools.ToolContext(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                io.yupiik.ia.agent.core.runtime.Cancellations.NEVER,
                null,
                null,
                dir);
    }

    private static io.yupiik.ia.agent.core.tools.ToolContext context() {
        return io.yupiik.ia.agent.core.tools.ToolContext.empty();
    }

    private static Long awaitPid(final Path pidFile) throws Exception {
        final var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.exists(pidFile)) {
                final var raw = Files.readString(pidFile).trim();
                if (!raw.isEmpty()) {
                    return Long.parseLong(raw);
                }
            }
            Thread.sleep(10);
        }
        return null;
    }

    private static void awaitGone(final long pid) throws InterruptedException {
        final var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (!ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("bash child " + pid + " survived the tree kill");
    }

    private static final Emitter NOOP = new Emitter() {
        @Override
        public <T> void emit(final T event) {
            // no-op
        }
    };
}
