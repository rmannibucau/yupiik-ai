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
package io.yupiik.ia.agent.github.execution;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DockerExecutionBackendTest {
    @TempDir
    Path home;

    @Test
    void rejectsProtectedAndNullToolEnvironmentBeforeAnyLaunch() throws Exception {
        final var executable = script("touch \"$0.called\"\n");
        final var backend = backend(executable);
        for (final var key : List.of(
                "HOME",
                "PATH",
                "GH_CONFIG_DIR",
                "TMPDIR",
                "GH_TOKEN",
                "GITHUB_TOKEN",
                "GITHUB_BOT_FORK_TOKEN",
                "GITHUB_SOURCE_TOKEN",
                "YCA_CONTEXT_TOKEN",
                "YCA_CONTEXT_GIT_TOKEN",
                "YCA_BROKER_URL",
                "YCA_BROKER_TOKEN",
                "JAVA_TOOL_OPTIONS",
                "JDK_JAVA_OPTIONS",
                "_JAVA_OPTIONS",
                "DOCKER_HOST",
                "DOCKER_CONFIG",
                "LD_PRELOAD",
                "LD_LIBRARY_PATH")) {
            final var environment = Map.of(key, "must-not-forward");
            assertThrows(
                    IllegalArgumentException.class,
                    () -> backend.execute(home, "yca-task", "review", Map.of(), environment),
                    key);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> backend.execute(home, "yca-task", "review", Map.of(), environment, null, Map.of()),
                    key);
        }
        final var environment = new HashMap<String, String>();
        environment.put("MAVEN_OPTS", null);
        assertThrows(
                IllegalArgumentException.class,
                () -> backend.execute(home, "yca-task", "review", Map.of(), environment));
        assertFalse(Files.exists(Path.of(executable + ".called")));
        assertFalse(Files.exists(home.resolve("execution.log")));
    }

    @Test
    void localDaemonProbeInspectsEffectiveContextWithSanitizedEnvironment() throws Exception {
        final var executable = script("""
                printf '%s\\n' "$@" > "$0.args"
                env > "$0.env"
                printf 'ignored diagnostic with credentials\\n' >&2
                printf 'unix:///run/user/10001/docker.sock\\n'
                """);
        backend(executable).requireLocalDaemon();
        assertEquals(
                List.of("context", "inspect", "--format", "{{(index .Endpoints \"docker\").Host}}"),
                Files.readAllLines(Path.of(executable + ".args")));
        final var actual = new HashMap<String, String>();
        for (final var entry : Files.readAllLines(Path.of(executable + ".env"))) {
            final int equals = entry.indexOf('=');
            if (!entry.startsWith("PWD=")) {
                actual.put(entry.substring(0, equals), entry.substring(equals + 1));
            }
        }
        assertEquals(
                DockerControlEnvironment.sanitize(new ProcessBuilder("unused")).environment(), actual);
    }

    @Test
    void localDaemonProbeRejectsStoredRemoteAndMalformedEndpoints() throws Exception {
        for (final var endpoint : List.of(
                "ssh://operator@private-host",
                "tcp://private-host:2376",
                "",
                "<no value>",
                "unix://",
                "unix:/run/docker.sock",
                "unix://remote/run/docker.sock",
                "unix://user@remote/run/docker.sock",
                "unix:///run/docker.sock?query",
                "unix:///run/docker.sock#fragment",
                "unix:///run/%00docker.sock",
                "unix:///bad%zz",
                "unix:///run/docker.sock\nunix:///another.sock")) {
            final var executable = script("printf '%s\\n' '" + endpoint + "'\n");
            final var error = assertThrows(
                    IllegalStateException.class, () -> backend(executable).requireLocalDaemon());
            assertEquals("Broker mode requires a declared local Docker Unix socket", error.getMessage());
            assertNull(error.getCause());
        }
    }

    @Test
    void localDaemonProbeRejectsNonzeroAndOversizedOutput() throws Exception {
        for (final var body : List.of(
                "printf 'unix:///run/docker.sock\\n'\nprintf 'secret diagnostic' >&2\nexit 1\n",
                "printf '%s' 'unix:///" + "a".repeat(2048) + "'\n")) {
            final var executable = script(body);
            assertThrows(IllegalStateException.class, () -> backend(executable).requireLocalDaemon());
        }
    }

    @Test
    void localDaemonProbeTimeoutKillsAndReapsClientAndDescendant() throws Exception {
        final var executable = script("""
                printf '%s' "$$" > "$0.pid"
                sleep 60 &
                printf '%s' "$!" > "$0.child"
                wait
                """);
        try {
            assertTimeoutPreemptively(
                    Duration.ofSeconds(4),
                    () -> assertThrows(
                            IllegalStateException.class,
                            () -> new DockerExecutionBackend(
                                            executable.toString(),
                                            "trusted-image",
                                            Duration.ofMillis(500),
                                            "",
                                            null,
                                            "shown")
                                    .requireLocalDaemon()));
            for (final var suffix : List.of(".pid", ".child")) {
                final long pid = Long.parseLong(Files.readString(Path.of(executable + suffix)));
                assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
            }
        } finally {
            for (final var suffix : List.of(".child", ".pid")) {
                final var file = Path.of(executable + suffix);
                if (Files.exists(file)) {
                    ProcessHandle.of(Long.parseLong(Files.readString(file))).ifPresent(ProcessHandle::destroyForcibly);
                }
            }
        }
    }

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
                                        executable.toString(),
                                        "trusted-image",
                                        Duration.ofMillis(50),
                                        "",
                                        null,
                                        "shown")
                                .execute(directory, "yca-timeout-stop-failure", "review", Map.of(), Map.of())));
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
                                null,
                                "shown")
                        .execute(directory, "yca-network-1", "review", Map.of(), Map.of()));
        final var args = Files.readAllLines(Path.of(executable + ".args"));
        assertEquals("restricted-egress", args.get(args.indexOf("--network") + 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DockerExecutionBackend(
                        "docker", "image", Duration.ofSeconds(1), "bad network", null, "shown"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"shown", "collapsed", "hidden"})
    void executesConcreteBackendWithIsolatedMountsArgumentsAndCredentials(final String showToolOutput)
            throws Exception {
        final var directory = attempt();
        assumeNonRoot(directory);
        final var executable = script("""
                printf '%s\\n' "$@" > "$0.args"
                printf '%s\\n' "$GH_TOKEN" "$GITHUB_BOT_FORK_TOKEN" "$GITHUB_SOURCE_TOKEN" "$YCA_CONTEXT_TOKEN" "$YCA_CONTEXT_GIT_TOKEN" "$HOME" > "$0.environment"
                env > "$0.env"
                printf 'executor output\\n'
                printf 'executor error\\n' >&2
                exit 7
                """);
        final var prompt = "Review this; $(touch must-not-exist) 'quoted'";
        final var toolEnvironment = Map.of(
                "MAVEN_OPTS", "-Dmaven.repo.local=/attempt/storage/cache/maven -Dliteral=$(never)",
                "npm_config_cache", "/attempt/storage/cache/npm",
                "NPM_CONFIG_USERCONFIG", "/attempt/storage/cache/npmrc",
                "GRADLE_USER_HOME", "/attempt/storage/cache/gradle",
                "PIP_CACHE_DIR", "/attempt/storage/cache/pip",
                "GOMODCACHE", "/attempt/storage/cache/go",
                "CARGO_HOME", "/attempt/storage/cache/cargo",
                "NUGET_PACKAGES", "/attempt/storage/cache/nuget");
        assertEquals(
                7,
                new DockerExecutionBackend(
                                executable.toString(),
                                "trusted-image",
                                Duration.ofSeconds(5),
                                "",
                                null,
                                null,
                                showToolOutput)
                        .execute(
                                directory,
                                "yca-task-1",
                                prompt,
                                Map.of(
                                        "GH_TOKEN", "agent-secret",
                                        "GITHUB_BOT_FORK_TOKEN", "fork-secret",
                                        "GITHUB_SOURCE_TOKEN", "source-secret",
                                        "YCA_CONTEXT_TOKEN", "context-secret",
                                        "YCA_CONTEXT_GIT_TOKEN", "context-git-secret"),
                                toolEnvironment));
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
        toolEnvironment.forEach((key, value) -> {
            assertEquals("--env", args.get(args.indexOf(key + "=" + value) - 1));
            assertFalse(args.contains(key), "Tool environment must not be inherited by name");
        });
        assertTrue(args.containsAll(List.of(
                "HOME=/attempt/storage",
                "GH_CONFIG_DIR=/attempt/storage/gh",
                "--workdir",
                "/attempt/workspace",
                "GH_TOKEN",
                "GITHUB_BOT_FORK_TOKEN",
                "GITHUB_SOURCE_TOKEN",
                "YCA_CONTEXT_TOKEN",
                "YCA_CONTEXT_GIT_TOKEN")));
        for (final var key : List.of(
                "GH_TOKEN",
                "GITHUB_BOT_FORK_TOKEN",
                "GITHUB_SOURCE_TOKEN",
                "YCA_CONTEXT_TOKEN",
                "YCA_CONTEXT_GIT_TOKEN")) {
            assertEquals("--env", args.get(args.indexOf(key) - 1));
        }
        assertEquals(
                List.of(
                        "trusted-image",
                        "run",
                        "--show-tool-output",
                        showToolOutput,
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
                args.subList(args.size() - 20, args.size()));
        for (final var secret :
                List.of("agent-secret", "fork-secret", "source-secret", "context-secret", "context-git-secret")) {
            assertFalse(args.toString().contains(secret));
            assertFalse(Files.readString(directory.resolve("execution.log")).contains(secret));
            assertFalse(
                    Files.readString(directory.resolve("configuration.json")).contains(secret));
        }
        final var environment = Files.readAllLines(Path.of(executable + ".environment"));
        assertEquals("agent-secret", environment.get(0));
        assertEquals("fork-secret", environment.get(1));
        assertEquals("source-secret", environment.get(2));
        assertEquals("context-secret", environment.get(3));
        assertEquals("context-git-secret", environment.get(4));
        // The CLI receives host credentials only in its environment, not in command arguments.
        final var allowed = List.of(
                "PATH",
                "HOME",
                "DOCKER_HOST",
                "DOCKER_CONTEXT",
                "DOCKER_CONFIG",
                "DOCKER_TLS",
                "DOCKER_TLS_VERIFY",
                "DOCKER_CERT_PATH",
                "DOCKER_API_VERSION",
                "XDG_RUNTIME_DIR",
                "XDG_CONFIG_HOME",
                "XDG_DATA_HOME",
                "XDG_STATE_HOME",
                "SSH_AUTH_SOCK",
                "GH_TOKEN",
                "GITHUB_BOT_FORK_TOKEN",
                "GITHUB_SOURCE_TOKEN",
                "YCA_CONTEXT_TOKEN",
                "YCA_CONTEXT_GIT_TOKEN",
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
        assertEquals(0, backend(executable).execute(directory, "yca-task-2", "review", Map.of(), Map.of()));
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
                                executable.toString(), "trusted-image", Duration.ofSeconds(1), "", null, "shown")
                        .execute(directory, "yca-timeout-1", "review", Map.of(), Map.of()));
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
                () -> backend(executable)
                        .execute(directory, "yca-task-3", "review", Map.of("HOST_TOKEN", "secret"), Map.of()));
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
                () -> backend(executable).execute(directory, "yca-task-4", "review", Map.of(), Map.of()));
        assertTrue(error.getMessage().contains("non-root"));
        assertFalse(Files.exists(Path.of(executable + ".invoked")));
    }

    @Test
    void stopInspectsThenKillsOnlyRunningContainer() throws Exception {
        final var executable = script("""
                printf '%s\\n' "$*" >> "$0.calls"
                env > "$0.$1.env"
                if [ "$1" = container ]; then
                  printf 'true\\n'
                fi
                """);
        backend(executable).stop("yca-task-5");
        assertEquals(
                List.of("container inspect --format {{.State.Running}} yca-task-5", "kill yca-task-5"),
                Files.readAllLines(Path.of(executable + ".calls")));
        final var expected =
                DockerControlEnvironment.sanitize(new ProcessBuilder("unused")).environment();
        for (final var phase : List.of("container", "kill")) {
            final var actual = new HashMap<String, String>();
            for (final var entry : Files.readAllLines(Path.of(executable + "." + phase + ".env"))) {
                final int equals = entry.indexOf('=');
                if (!entry.startsWith("PWD=")) {
                    actual.put(entry.substring(0, equals), entry.substring(equals + 1));
                }
            }
            assertEquals(expected, actual, phase);
        }
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
                () -> new DockerExecutionBackend(" ", "image", Duration.ofSeconds(1), "", null, "shown"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DockerExecutionBackend("docker", "", Duration.ofSeconds(1), "", null, "shown"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DockerExecutionBackend("docker", "image", Duration.ZERO, "", null, "shown"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DockerExecutionBackend("docker", "image", Duration.ofSeconds(-1), "", null, "shown"));
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
        Files.writeString(executable, """
                #!/bin/sh
                for last; do :; done
                if [ "$2" = inspect ]; then
                  case "$last" in
                        *-services|*-egress) printf 'Error: No such container: %s\\n' "$last"; exit 1 ;;
                        *-services-net|*-external) printf 'Error: No such network: %s\\n' "$last"; exit 1 ;;
                  esac
                fi
                """ + body);
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"));
        return executable;
    }

    private DockerExecutionBackend backend(final Path executable) {
        return new DockerExecutionBackend(
                executable.toString(), "trusted-image", Duration.ofSeconds(5), "", null, "shown");
    }
}
