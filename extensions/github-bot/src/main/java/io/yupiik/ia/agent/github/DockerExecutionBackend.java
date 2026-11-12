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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/** Docker CLI adapter with bounded output and no socket or host state exposed to the agent. */
public final class DockerExecutionBackend {
    private static final Pattern NETWORK_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]*");
    private static final Pattern RUNTIME_ID = Pattern.compile("yca-[a-zA-Z0-9-]+");

    private static final long MAX_LOG_BYTES = 8L * 1024 * 1024;
    private final String executable;
    private final String image;
    private final Duration timeout;
    private final String network;

    private final DockerEgress egress;

    public DockerExecutionBackend(
            final String executable,
            final String image,
            final Duration timeout,
            final String network,
            final ExecutorConfiguration.Docker.Egress egress) {
        if (executable.isBlank() || image.isBlank() || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("Docker executable, image and positive timeout required");
        }
        this.executable = executable;
        this.image = image;
        this.timeout = timeout;
        if (!network.isEmpty() && !NETWORK_NAME.matcher(network).matches()) {
            throw new IllegalArgumentException("Invalid pre-created Docker network name");
        }
        this.network = network;
        if (egress != null && !network.isEmpty()) {
            throw new IllegalArgumentException("Enforced egress cannot use a pre-created agent network");
        }
        this.egress = egress == null ? null : new DockerEgress(executable, egress, timeout);
    }

    public int execute(
            final Path directory, final String runtimeId, final String prompt, final Map<String, String> credentials) {
        if (!RUNTIME_ID.matcher(runtimeId).matches()) {
            throw new IllegalArgumentException("Invalid execution identity");
        }
        for (final var key : credentials.keySet()) {
            if (!Set.of("GH_TOKEN", "GITHUB_BOT_FORK_TOKEN").contains(key)) {
                throw new IllegalArgumentException("Unsupported executor credential variable");
            }
        }
        Process process = null;
        boolean egressStarted = false;
        try {
            final var uid = ((Number) Files.getAttribute(directory, "unix:uid")).intValue();
            final var gid = ((Number) Files.getAttribute(directory, "unix:gid")).intValue();
            if (uid == 0) {
                throw new IllegalStateException("Run the dispatcher as a dedicated non-root user");
            }
            if (egress != null) {
                egressStarted = true;
                egress.start(runtimeId);
            }
            final var command = new ArrayList<>(List.of(
                    executable,
                    "run",
                    "--rm",
                    "--name",
                    runtimeId,
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
                    "/tmp:rw,nosuid,nodev,size=1g"));
            mount(command, directory.resolve("configuration.json"), "/attempt/configuration.json", true);
            if (!network.isEmpty()) {
                command.addAll(List.of("--network", network));
            }
            if (egress != null) {
                command.addAll(egress.agentArguments(runtimeId));
            }
            mount(command, directory.resolve("storage"), "/attempt/storage", false);
            mount(command, directory.resolve("workspace"), "/attempt/workspace", false);
            mount(command, directory.resolve("output"), "/attempt/output", false);
            command.addAll(List.of(
                    "--env",
                    "HOME=/attempt/storage",
                    "--env",
                    "GH_CONFIG_DIR=/attempt/storage/gh",
                    "--workdir",
                    "/attempt/workspace"));
            for (final var key : credentials.keySet()) {
                command.addAll(List.of("--env", key));
            }
            command.addAll(List.of(
                    image,
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
                    prompt));
            final var builder = new ProcessBuilder(command).redirectErrorStream(true);
            builder.environment()
                    .keySet()
                    .removeIf(key -> !Set.of("PATH", "HOME", "DOCKER_HOST", "DOCKER_CONTEXT", "XDG_RUNTIME_DIR")
                            .contains(key));
            builder.environment().putAll(credentials);
            process = builder.start();
            final var running = process;
            final var drain = new CompletableFuture<Void>();
            Thread.ofVirtual().start(() -> {
                try (final var input = running.getInputStream();
                        final var output = Files.newOutputStream(
                                directory.resolve("execution.log"), StandardOpenOption.CREATE_NEW)) {
                    final var bytes = new byte[8192];
                    long retained = 0;
                    int count;
                    while ((count = input.read(bytes)) >= 0) {
                        final int keep = (int) Math.min(count, Math.max(0, MAX_LOG_BYTES - retained));
                        if (keep > 0) {
                            output.write(bytes, 0, keep);
                            retained += keep;
                        }
                    }
                    drain.complete(null);
                } catch (final IOException | RuntimeException e) {
                    drain.completeExceptionally(e);
                }
            });
            try {
                if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    stopContainer(runtimeId);
                    process.destroyForcibly();
                    throw new IllegalStateException("Execution timeout");
                }
                try {
                    drain.get(30, TimeUnit.SECONDS);
                } catch (final ExecutionException | TimeoutException e) {
                    throw new IllegalStateException("Executor log drain failed", e);
                }
                return process.exitValue();
            } finally {
                // Never wait for a pipe reader before killing the local client, even if remote stop failed.
                process.destroyForcibly();
                process.getInputStream().close();
            }
        } catch (final IOException e) {
            throw new IllegalStateException("Docker launch failed", e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Docker execution interrupted", e);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                if (!egressStarted) {
                    stop(runtimeId);
                }
            }
            if (egressStarted) {
                stop(runtimeId);
            }
        }
    }

    private static void mount(
            final List<String> command, final Path path, final String destination, final boolean readOnly) {
        if (path.toString().contains(",")) {
            throw new IllegalArgumentException("Docker attempt path cannot contain comma");
        }
        command.addAll(
                List.of("--mount", "type=bind,src=" + path + ",dst=" + destination + (readOnly ? ",readonly" : "")));
    }

    public void stop(final String runtimeId) {
        final boolean interrupted = Thread.interrupted();
        try {
            try {
                stopContainer(runtimeId);
            } finally {
                if (egress != null) {
                    egress.remove(runtimeId);
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void stopContainer(final String runtimeId) {
        if (!RUNTIME_ID.matcher(runtimeId).matches()) {
            throw new IllegalArgumentException("Invalid execution identity");
        }
        try {
            // Inspect first: failure is ambiguous unless Docker explicitly reports no such object.
            final var inspectFile = Files.createTempFile("yca-docker-inspect-", ".log");
            try {
                final var inspect = new ProcessBuilder(
                                executable, "container", "inspect", "--format", "{{.State.Running}}", runtimeId)
                        .redirectErrorStream(true)
                        .redirectOutput(inspectFile.toFile())
                        .start();
                try {
                    if (!inspect.waitFor(20, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Unable to inspect previous executor");
                    }
                    final String response;
                    final boolean truncated;
                    try (final var input = Files.newInputStream(inspectFile)) {
                        // One extra byte prevents a truncated diagnostic from proving absence.
                        final var bytes = input.readNBytes(4097);
                        truncated = bytes.length > 4096;
                        response = new String(bytes, StandardCharsets.UTF_8);
                    }
                    if (inspect.exitValue() != 0) {
                        if (!truncated
                                && Pattern.compile(
                                                "(?:Error: |Error response from daemon: )?No such (?:container|object): "
                                                        + Pattern.quote(runtimeId))
                                        .matcher(response.strip())
                                        .matches()) {
                            return;
                        }
                        throw new IllegalStateException("Docker unavailable; executor termination is uncertain");
                    }
                    if (response.trim().equals("false")) {
                        return;
                    }
                    if (!response.trim().equals("true")) {
                        throw new IllegalStateException("Unrecognized executor state; termination is uncertain");
                    }
                    final var kill = new ProcessBuilder(executable, "kill", runtimeId)
                            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                            .redirectError(ProcessBuilder.Redirect.DISCARD)
                            .start();
                    try {
                        if (!kill.waitFor(20, TimeUnit.SECONDS) || kill.exitValue() != 0) {
                            throw new IllegalStateException("Unable to terminate previous executor");
                        }
                    } finally {
                        closeClient(kill);
                    }
                } finally {
                    closeClient(inspect);
                }
            } finally {
                Files.deleteIfExists(inspectFile);
            }
        } catch (final IOException e) {
            throw new IllegalStateException("Unable to terminate previous executor", e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Executor termination interrupted", e);
        }
    }

    private static void closeClient(final Process process) {
        process.destroyForcibly();
        for (final var stream :
                List.of(process.getOutputStream(), process.getInputStream(), process.getErrorStream())) {
            try {
                stream.close();
            } catch (final IOException ignored) {
                // Best effort local cleanup is not proof of remote termination.
            }
        }
        boolean interrupted = Thread.interrupted();
        final long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        try {
            while (process.isAlive() && System.nanoTime() < end) {
                try {
                    process.waitFor(Math.max(1, end - System.nanoTime()), TimeUnit.NANOSECONDS);
                } catch (final InterruptedException e) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
