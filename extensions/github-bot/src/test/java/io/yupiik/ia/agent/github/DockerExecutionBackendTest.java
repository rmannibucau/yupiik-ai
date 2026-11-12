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
package io.yupiik.ia.agent.github;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DockerExecutionBackendTest {
    @TempDir
    Path home;

    @Test
    void timeoutReturnsEvenWhenRemoteTerminationFails() throws Exception {
        final var directory = attempt();
        assumeNonRoot(directory);
        final var executable = script("if [ \"$1\" = run ]; then exec sleep 60; fi\nprintf 'unknown\\n'\n");
        assertTimeoutPreemptively(
                Duration.ofSeconds(5),
                () -> assertThrows(
                        IllegalStateException.class,
                        () -> new DockerExecutionBackend(
                                        executable.toString(), "trusted-image", Duration.ofMillis(50), "", null)
                                .execute(directory, "yca-timeout-stop-failure", "review", Map.of())));
    }

    @Test
    void passesConfiguredNetworkAsSingleArgument() throws Exception {
        final var directory = attempt();
        assumeNonRoot(directory);
        final var executable = script("printf '%s\\n' \"$@\" > \"$0.args\"\nexit 0\n");
        assertEquals(
                0,
                new DockerExecutionBackend(
                                executable.toString(),
                                "trusted-image",
                                Duration.ofSeconds(5),
                                "restricted-egress",
                                null)
                        .execute(directory, "yca-network-1", "review", Map.of()));
        final var args = Files.readAllLines(Path.of(executable + ".args"));
        assertEquals("restricted-egress", args.get(args.indexOf("--network") + 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DockerExecutionBackend("docker", "image", Duration.ofSeconds(1), "bad network", null));
    }

    @Test
    void executesConcreteBackendWithIsolatedMountsArgumentsAndCredentials() throws Exception {
        final var directory = attempt();
        assumeNonRoot(directory);
        final var executable = script("""
                printf '%s\\n' "$@" > "$0.args"
                printf '%s\\n' "$GH_TOKEN" "$GITHUB_BOT_FORK_TOKEN" "$HOME" > "$0.environment"
                env > "$0.env"
                printf 'executor output\\n'
                printf 'executor error\\n' >&2
                exit 7
                """);
        final var prompt = "Review this; $(touch must-not-exist) 'quoted'";
        assertEquals(
                7,
                backend(executable)
                        .execute(
                                directory,
                                "yca-task-1",
                                prompt,
                                Map.of("GH_TOKEN", "agent-secret", "GITHUB_BOT_FORK_TOKEN", "fork-secret")));
        final var uid = ((Number) Files.getAttribute(directory, "unix:uid")).intValue();
        final var gid = ((Number) Files.getAttribute(directory, "unix:gid")).intValue();
        final var args = Files.readAllLines(Path.of(executable + ".args"));
        final var expectedPrefix = List.of(
                "run",
                "--rm",
                "--name",
                "yca-task-1",
                "--init",
                "--user",
                uid + ":" + gid,
                "--cap-drop=ALL",
                "--security-opt=no-new-privileges",
                "--pids-limit=512",
                "--memory=4g",
                "--cpus=2",
                "--read-only",
                "--tmpfs",
                "/tmp:rw,nosuid,nodev,size=1g");
        assertEquals(expectedPrefix, args.subList(0, expectedPrefix.size()));
        for (final var name : List.of("configuration.json", "storage", "workspace", "output")) {
            final var mount = "type=bind,src=" + directory.resolve(name) + ",dst=/attempt/" + name
                    + ("configuration.json".equals(name) ? ",readonly" : "");
            assertTrue(args.contains(mount), mount);
        }
        assertEquals(4, args.stream().filter("--mount"::equals).count());
        assertTrue(args.containsAll(List.of(
                "HOME=/attempt/storage",
                "GH_CONFIG_DIR=/attempt/storage/gh",
                "--workdir",
                "/attempt/workspace",
                "GH_TOKEN",
                "GITHUB_BOT_FORK_TOKEN")));
        assertEquals(
                List.of(
                        "trusted-image",
                        "run",
                        "--yca-configuration-json",
                        "/attempt/configuration.json",
                        "--yca-configuration-project-enabled",
                        "false",
                        "--yca-storage-home",
                        "/attempt/storage",
                        "--yca-workingDir",
                        "/attempt/workspace",
                        "--result-file",
                        "/attempt/output/result.json",
                        "--result-required-fields",
                        "taskId,summary|failure",
                        "--result-max-bytes",
                        "6291456",
                        "--prompt",
                        prompt),
                args.subList(args.size() - 18, args.size()));
        assertFalse(args.stream().anyMatch(arg -> arg.contains("agent-secret") || arg.contains("fork-secret")));
        final var environment = Files.readAllLines(Path.of(executable + ".environment"));
        assertEquals("agent-secret", environment.get(0));
        assertEquals("fork-secret", environment.get(1));
        // The CLI receives host credentials only in its environment, not in command arguments.
        final var allowed = List.of(
                "PATH",
                "HOME",
                "DOCKER_HOST",
                "DOCKER_CONTEXT",
                "XDG_RUNTIME_DIR",
                "GH_TOKEN",
                "GITHUB_BOT_FORK_TOKEN",
                "PWD"); // POSIX shells can synthesize PWD.
        for (final var entry : Files.readAllLines(Path.of(executable + ".env"))) {
            assertTrue(
                    allowed.contains(entry.substring(0, entry.indexOf('='))), entry.substring(0, entry.indexOf('=')));
        }
        assertEquals("executor output\nexecutor error\n", Files.readString(directory.resolve("execution.log")));
    }

    @Test
    void boundsRetainedLogWhileDrainingOutputToCompletion() throws Exception {
        final var directory = attempt();
        assumeNonRoot(directory);
        final var executable = script("head -c 8396800 /dev/zero\nexit 0\n");
        assertEquals(0, backend(executable).execute(directory, "yca-task-2", "review", Map.of()));
        assertEquals(8L * 1024 * 1024, Files.size(directory.resolve("execution.log")));
    }

    @Test
    void timeoutInspectsAndTerminatesRuntimeWithoutRetryingExecution() throws Exception {
        final var directory = attempt();
        assumeNonRoot(directory);
        final var executable = script("""
                printf '%s\\n' "$1" >> "$0.calls"
                if [ "$1" = run ]; then
                  exec sleep 5
                fi
                printf 'false\\n'
                """);
        final var error = assertThrows(
                IllegalStateException.class,
                () -> new DockerExecutionBackend(
                                executable.toString(), "trusted-image", Duration.ofSeconds(1), "", null)
                        .execute(directory, "yca-timeout-1", "review", Map.of()));
        assertEquals("Execution timeout", error.getMessage());
        final var calls = Files.readAllLines(Path.of(executable + ".calls"));
        assertEquals("run", calls.getFirst());
        assertEquals(1, calls.stream().filter("run"::equals).count());
        assertTrue(calls.size() >= 2);
        assertTrue(calls.subList(1, calls.size()).stream().allMatch("container"::equals));
    }

    @Test
    void rejectsUnsupportedCredentialsBeforeInvokingExecutable() throws Exception {
        final var directory = attempt();
        assumeNonRoot(directory);
        final var executable = script("touch \"$0.invoked\"\n");
        assertThrows(
                IllegalArgumentException.class,
                () -> backend(executable).execute(directory, "yca-task-3", "review", Map.of("HOST_TOKEN", "secret")));
        assertFalse(Files.exists(Path.of(executable + ".invoked")));
    }

    @Test
    void refusesRootOwnedAttemptBeforeInvokingExecutable() throws Exception {
        final var directory = attempt();
        assumeTrue(
                ((Number) Files.getAttribute(directory, "unix:uid")).intValue() == 0,
                "Root ownership guard is applicable only to root-owned attempts");
        final var executable = script("touch \"$0.invoked\"\n");
        final var error = assertThrows(
                IllegalStateException.class,
                () -> backend(executable).execute(directory, "yca-task-4", "review", Map.of()));
        assertTrue(error.getMessage().contains("non-root"));
        assertFalse(Files.exists(Path.of(executable + ".invoked")));
    }

    @Test
    void stopInspectsThenKillsOnlyRunningContainer() throws Exception {
        final var executable = script("""
                printf '%s\\n' "$*" >> "$0.calls"
                if [ "$1" = container ]; then
                  printf 'true\\n'
                fi
                """);
        backend(executable).stop("yca-task-5");
        assertEquals(
                List.of("container inspect --format {{.State.Running}} yca-task-5", "kill yca-task-5"),
                Files.readAllLines(Path.of(executable + ".calls")));
    }

    @Test
    void stopAcceptsMissingOrStoppedContainersButRejectsUncertainInspection() throws Exception {
        for (final var response : List.of(
                "printf 'false\\n'\n",
                "printf 'Error: No such object: yca-task-6\\n'\nexit 1\n",
                "printf 'Error response from daemon: No such container: yca-task-6\\n'\nexit 1\n")) {
            final var executable = script("printf '%s\\n' \"$*\" >> \"$0.calls\"\n" + response);
            backend(executable).stop("yca-task-6");
            assertEquals(
                    List.of("container inspect --format {{.State.Running}} yca-task-6"),
                    Files.readAllLines(Path.of(executable + ".calls")));
        }
        final var unavailable = script("printf 'Cannot connect to Docker daemon\\n'\nexit 1\n");
        assertThrows(IllegalStateException.class, () -> backend(unavailable).stop("yca-task-6"));
        final var unexpected = script("printf 'unexpected\\n'\n");
        assertThrows(IllegalStateException.class, () -> backend(unexpected).stop("yca-task-6"));
    }

    @Test
    void missingContainerMustNameExactRuntimeAndNotHideDaemonFailures() throws Exception {
        for (final var response : List.of(
                "dial tcp: lookup docker: no such host",
                "No such object",
                "Error: No such object: yca-other",
                "Error: No such object: yca-task-6-extra",
                "Error: No such object: yca-task-6" + " ".repeat(4096) + "daemon failure",
                "Error: No such object: yca-task-6\nCannot connect to Docker daemon")) {
            final var executable = script("printf '%s\\n' '" + response + "'\nexit 1\n");
            final var error = assertThrows(
                    IllegalStateException.class, () -> backend(executable).stop("yca-task-6"), response);
            assertTrue(error.getMessage().contains("uncertain"));
        }
    }

    @Test
    void interruptionTerminatesLocalInspectAndKillClientsButDoesNotConfirmRemoteExit() throws Exception {
        for (final var phase : List.of("container", "kill")) {
            final var executable = script("""
                    if [ "$1" = '%s' ]; then
                      printf '%%s' "$$" > "$0.pid"
                      exec /bin/sleep 60
                    fi
                    printf 'true\\n'
                    """.formatted(phase));
            final var result = new CompletableFuture<Boolean>();
            final var worker = Thread.ofPlatform().start(() -> {
                try {
                    assertThrows(
                            IllegalStateException.class,
                            () -> backend(executable).stop("yca-interrupted"));
                    result.complete(Thread.currentThread().isInterrupted());
                } catch (final Throwable e) {
                    result.completeExceptionally(e);
                }
            });
            ProcessHandle client = null;
            try {
                final var pid = Path.of(executable + ".pid");
                assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
                    while (!Files.exists(pid) || Files.size(pid) == 0) {
                        Thread.sleep(10);
                    }
                });
                client = ProcessHandle.of(Long.parseLong(Files.readString(pid))).orElseThrow();
                worker.interrupt();
                assertTrue(result.get(3, TimeUnit.SECONDS), "Interrupt status must be restored");
                assertFalse(client.isAlive(), "The local Docker client must be terminated");
            } finally {
                worker.interrupt();
                if (client != null) {
                    client.destroyForcibly();
                }
                worker.join(3000);
            }
        }
    }

    @Test
    void stopRejectsInvalidIdentityAndFailedKill() throws Exception {
        final var executable = script("""
                printf '%s\\n' "$*" >> "$0.calls"
                if [ "$1" = container ]; then
                  printf 'true\\n'
                else
                  exit 1
                fi
                """);
        assertThrows(IllegalArgumentException.class, () -> backend(executable).stop("not-a-runtime"));
        assertFalse(Files.exists(Path.of(executable + ".calls")));
        assertThrows(IllegalStateException.class, () -> backend(executable).stop("yca-task-7"));
        assertEquals(2, Files.readAllLines(Path.of(executable + ".calls")).size());
    }

    @Test
    void requiresExecutableImageAndPositiveBudget() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new DockerExecutionBackend(" ", "image", Duration.ofSeconds(1), "", null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DockerExecutionBackend("docker", "", Duration.ofSeconds(1), "", null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DockerExecutionBackend("docker", "image", Duration.ZERO, "", null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DockerExecutionBackend("docker", "image", Duration.ofSeconds(-1), "", null));
    }

    private Path attempt() throws Exception {
        final var directory = Files.createDirectory(home.resolve("attempt"));
        Files.writeString(directory.resolve("configuration.json"), "{}");
        for (final var name : List.of("storage", "workspace", "output")) {
            Files.createDirectory(directory.resolve(name));
        }
        return directory;
    }

    private void assumeNonRoot(final Path directory) throws Exception {
        assumeTrue(
                ((Number) Files.getAttribute(directory, "unix:uid")).intValue() != 0,
                "The production executor deliberately rejects root-owned attempts");
    }

    private Path script(final String body) throws Exception {
        final var executable = Files.createTempFile(home, "fake-docker-", ".sh");
        Files.writeString(executable, "#!/bin/sh\n" + body);
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"));
        return executable;
    }

    private DockerExecutionBackend backend(final Path executable) {
        return new DockerExecutionBackend(executable.toString(), "trusted-image", Duration.ofSeconds(5), "", null);
    }
}
