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

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.github.model.AgentConfigurationDocument;
import io.yupiik.ia.agent.github.store.TaskStore;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Linux nono adapter using supervised sessions, never raw PIDs for recovery.
 * Requires current nono session support and pathname AF_UNIX mediation. Unsupported
 * kernels/versions must reject the generated profile; no fallback is attempted.
 * runtimeRoot is a dedicated, credential-free, read-only runtime distribution (including
 * the agent, JDK, libraries and tools); stateRoot is private dispatcher state, outside
 * both the runtime and attempts. Run under a dedicated non-root uid, not a human uid.
 * Missing/ambiguous session state is never evidence of termination: stop fails closed.
 * Runtime IDs are single-use. Sanitized /attempt configuration paths are staged locally;
 * the original trusted configuration is not modified.
 */
public final class NonoExecutionBackend {
    private static final Pattern SESSION_ID = Pattern.compile("[a-f0-9]{16}");
    private static final Pattern RUNTIME_ID = Pattern.compile("yca-[a-zA-Z0-9-]+");

    private static final int MAX_OUTPUT = 8 * 1024 * 1024;
    private final JsonMapper json;
    private final String executable;
    private final Path agent;
    private final Path runtimeRoot;
    private final Path stateRoot;
    private final Duration timeout;
    private final Duration commandTimeout;

    public NonoExecutionBackend(
            final JsonMapper json,
            final String executable,
            final Path agent,
            final Path runtimeRoot,
            final Path stateRoot,
            final Duration timeout,
            final Duration commandTimeout) {
        if (json == null
                || executable.isBlank()
                || timeout.isNegative()
                || timeout.isZero()
                || commandTimeout.isNegative()
                || commandTimeout.isZero()) {
            throw new IllegalArgumentException("Runtime and positive budgets required");
        }
        this.json = json;
        this.executable = executable;
        this.runtimeRoot = checked(runtimeRoot, true);
        this.stateRoot = checked(stateRoot, true);
        this.agent = checked(agent, false);
        if (!this.agent.startsWith(this.runtimeRoot)
                || this.runtimeRoot.getParent() == null
                || this.runtimeRoot.startsWith(this.stateRoot)
                || this.stateRoot.startsWith(this.runtimeRoot)) {
            throw new IllegalArgumentException("Dedicated runtime and disjoint private state required");
        }
        this.timeout = timeout;
        this.commandTimeout = commandTimeout;
    }

    public int execute(
            final Path directory, final String runtimeId, final String prompt, final Map<String, String> credentials) {
        validateRuntimeIdentity(runtimeId);
        if (!Set.of(
                        "GH_TOKEN",
                        "GITHUB_BOT_FORK_TOKEN",
                        "GITHUB_SOURCE_TOKEN",
                        "YCA_CONTEXT_TOKEN",
                        "YCA_CONTEXT_GIT_TOKEN")
                .containsAll(credentials.keySet())) {
            throw new IllegalArgumentException("Unsupported executor credential variable");
        }
        final var attempt = checked(directory, true);
        if (attempt.startsWith(stateRoot)
                || stateRoot.startsWith(attempt)
                || attempt.startsWith(runtimeRoot)
                || runtimeRoot.startsWith(attempt)) {
            throw new IllegalArgumentException("Attempt, runtime and state must be disjoint");
        }
        final var state = stateRoot.resolve(runtimeId);
        try {
            if (!System.getProperty("os.name", "").equals("Linux")) {
                throw new IllegalStateException("This backend requires Linux pathname socket mediation");
            }
            if (!Files.getPosixFilePermissions(stateRoot).equals(PosixFilePermissions.fromString("rwx------"))) {
                throw new IllegalStateException("Nono state root must have mode 0700");
            }
            if (((Number) Files.getAttribute(attempt, "unix:uid")).longValue() == 0
                    || ((Number) Files.getAttribute(stateRoot, "unix:uid")).longValue() == 0) {
                throw new IllegalStateException("Run the dispatcher as a dedicated non-root user");
            }
            for (final var name : List.of("storage", "workspace", "output")) {
                checked(attempt.resolve(name), true);
            }
            final var configuration = checked(attempt.resolve("configuration.json"), false);
            Files.createDirectory(
                    state, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            // Durable, exclusive intent precedes launch. A crash in this window requires operator recovery.
            Files.writeString(
                    state.resolve("intent"),
                    attempt.toString(),
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.SYNC);
            final var staged = state.resolve("configuration.json");
            final var document = json.fromString(AgentConfigurationDocument.class, Files.readString(configuration));
            final var agentConfiguration = document.configuration();
            agentConfiguration.remove("run");
            Files.writeString(
                    staged,
                    json.toString(new AgentConfigurationDocument(
                            TaskStore.map(relocate(agentConfiguration, attempt)),
                            TaskStore.map(relocate(document.completionSettings(), attempt)))),
                    StandardOpenOption.CREATE_NEW);
            final var profile = state.resolve("profile.json");
            final var env = Map.of(
                    "HOME",
                    attempt.resolve("storage").toString(),
                    "GH_CONFIG_DIR",
                    attempt.resolve("storage/gh").toString(),
                    "TMPDIR",
                    attempt.resolve("storage/tmp").toString());
            Files.createDirectory(attempt.resolve("storage/tmp"));
            final var allowed = new ArrayList<>(List.of("PATH"));
            allowed.addAll(credentials.keySet());
            Files.writeString(
                    profile,
                    json.toString(new Profile(
                            new ProfileMeta("yca-agent"),
                            new ProfileGroups(
                                    List.of(),
                                    List.of(
                                            "system_read_macos",
                                            "system_read_linux_core",
                                            "system_write_macos",
                                            "system_write_linux",
                                            "user_tools",
                                            "homebrew_macos",
                                            "homebrew_linux")),
                            new ProfileWorkdir("none"),
                            new ProfileFilesystem(
                                    List.of(runtimeRoot.toString()),
                                    List.of(staged.toString()),
                                    List.of(
                                            attempt.resolve("storage").toString(),
                                            attempt.resolve("workspace").toString(),
                                            attempt.resolve("output").toString())),
                            new ProfileSecurity("isolated", "isolated", "shared_memory_only", false),
                            new ProfileLinux("pathname"),
                            new ProfileEnvironment(allowed, env))),
                    StandardOpenOption.CREATE_NEW);
            final var command = List.of(
                    "run",
                    "--profile",
                    profile.toString(),
                    "--no-audit",
                    "--no-diagnostics",
                    "--",
                    agent.toString(),
                    "run",
                    "--yca-configuration-json",
                    staged.toString(),
                    "--yca-configuration-project-enabled",
                    "false",
                    "--yca-storage-home",
                    attempt.resolve("storage").toString(),
                    "--yca-workingDir",
                    attempt.resolve("workspace").toString(),
                    "--result-file",
                    attempt.resolve("output/result.json").toString(),
                    "--result-required-fields",
                    "taskId,summary|failure",
                    "--result-max-bytes",
                    "6291456",
                    "--prompt",
                    prompt.replace("/attempt/", attempt + "/"));
            try (final var log =
                    Files.newOutputStream(attempt.resolve("execution.log"), StandardOpenOption.CREATE_NEW)) {
                final var result = invoke(state, attempt.resolve("workspace"), command, credentials, timeout, log);
                // Even a successful CLI exit is not sufficient if the registry is missing/ambiguous.
                confirmExited(state);
                return result.code();
            } catch (final RuntimeException e) {
                try {
                    stop(runtimeId);
                } catch (final RuntimeException stopFailure) {
                    e.addSuppressed(stopFailure);
                }
                throw e;
            }
        } catch (final IOException e) {
            throw new IllegalStateException("Nono launch failed; preserve private state for recovery", e);
        }
    }

    public void stop(final String runtimeId) {
        validateRuntimeIdentity(runtimeId);
        final var state = checked(stateRoot.resolve(runtimeId), true);
        final var record = session(state);
        if (!"exited".equals(record.status())) {
            final var id = record.session_id();
            final var value = id;
            if (value == null
                    || value.length() != 16
                    || !SESSION_ID.matcher(value).matches()) {
                throw new IllegalStateException("Invalid nono session identity; termination uncertain");
            }
            final var stopped = invoke(
                    state,
                    state,
                    List.of("stop", value, "--timeout", Long.toString(Math.max(1, commandTimeout.toSeconds() / 2))),
                    Map.of(),
                    commandTimeout,
                    null);
            if (stopped.code() != 0) {
                throw new IllegalStateException("Nono stop failed; termination uncertain");
            }
        }
        confirmExited(state);
    }

    /** Removes credential-bearing private state only after the session registry proves a non-reconciled exit. */
    public void cleanupStoppedState(final String runtimeId) {
        validateRuntimeIdentity(runtimeId);
        final var state = stateRoot.resolve(runtimeId);
        if (Files.notExists(state, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        confirmExited(checked(state, true));
        try {
            AttemptPreparation.delete(state);
        } catch (final IOException e) {
            throw new IllegalStateException("Cannot remove terminated Nono private state", e);
        }
    }

    private void confirmExited(final Path state) {
        final var record = session(state);
        // -1 is nono's reconciled/missing-supervisor status, not proof its children stopped.
        if (!"exited".equals(record.status()) || record.exit_code() == null || record.exit_code() < 0) {
            throw new IllegalStateException("Nono termination unconfirmed; preserve attempt and private state");
        }
    }

    private Session session(final Path state) {
        final var result = invoke(state, state, List.of("ps", "--all", "--json"), Map.of(), commandTimeout, null);
        if (result.code() != 0) {
            throw new IllegalStateException("Nono registry unavailable; termination uncertain");
        }
        final var parsed = json.fromString(
                Sessions.class, "{\"sessions\":" + new String(result.output(), StandardCharsets.UTF_8) + "}");
        final var records = parsed.sessions();
        if (records == null || records.size() != 1 || records.getFirst() == null) {
            throw new IllegalStateException("Missing or ambiguous nono session; termination uncertain");
        }
        return records.getFirst();
    }

    private Result invoke(
            final Path state,
            final Path cwd,
            final List<String> args,
            final Map<String, String> credentials,
            final Duration budget,
            final OutputStream log) {
        final var command = new ArrayList<String>();
        command.add(executable);
        command.addAll(args);
        Process process = null;
        Thread reader = null;
        try {
            final var builder =
                    new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true);
            builder.environment().clear();
            builder.environment()
                    .putAll(Map.of(
                            "HOME",
                            state.toString(),
                            "XDG_CONFIG_HOME",
                            state.resolve("config").toString(),
                            "XDG_STATE_HOME",
                            state.resolve("state").toString(),
                            "PATH",
                            runtimeRoot.resolve("bin").toString()));
            builder.environment().putAll(credentials);
            process = builder.start();
            process.getOutputStream().close();
            final var running = process;
            final var output = new CompletableFuture<byte[]>();
            reader = Thread.ofVirtual().start(() -> {
                try (final var stream = running.getInputStream();
                        final var buffer = new ByteArrayOutputStream()) {
                    final var bytes = new byte[8192];
                    int retained = 0;
                    int count;
                    while (!Thread.currentThread().isInterrupted()) {
                        // Avoid a blocking read: a descendant can retain stdout after the CLI dies.
                        // This is the sole reader of the local Process pipe.
                        int available = stream.available();
                        if (available == 0) {
                            if (!running.isAlive()) {
                                available = stream.available();
                                if (available == 0) {
                                    break;
                                }
                            } else {
                                Thread.sleep(10);
                                continue;
                            }
                        }
                        count = stream.read(bytes, 0, Math.min(bytes.length, available));
                        if (count < 0) {
                            break;
                        }
                        final int keep = Math.min(count, MAX_OUTPUT - retained);
                        if (log == null) {
                            buffer.write(bytes, 0, keep);
                        } else {
                            log.write(bytes, 0, keep);
                        }
                        retained += keep;
                    }
                    output.complete(buffer.toByteArray());
                } catch (final Exception e) {
                    output.completeExceptionally(e);
                }
            });
            final long end = System.nanoTime() + budget.toNanos();
            if (!process.waitFor(budget.toNanos(), TimeUnit.NANOSECONDS)) {
                throw new IllegalStateException("Nono timeout; session termination required");
            }
            return new Result(
                    process.exitValue(), output.get(Math.max(1, end - System.nanoTime()), TimeUnit.NANOSECONDS));
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Nono interrupted; session termination required", e);
        } catch (final Exception e) {
            throw new IllegalStateException("Nono operation failed; termination uncertain", e);
        } finally {
            if (process != null) {
                process.destroyForcibly();
                if (reader != null) {
                    reader.interrupt();
                }
                for (final var stream :
                        List.of(process.getOutputStream(), process.getInputStream(), process.getErrorStream())) {
                    try {
                        stream.close();
                    } catch (final IOException ignored) {
                        // Local pipe cleanup does not confirm supervised session termination.
                    }
                }
                boolean interrupted = Thread.interrupted();
                final long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
                try {
                    while (reader != null && reader.isAlive() && System.nanoTime() < end) {
                        try {
                            reader.join(Duration.ofNanos(Math.max(1, end - System.nanoTime())));
                        } catch (final InterruptedException e) {
                            interrupted = true;
                        }
                    }
                    if (reader != null && reader.isAlive()) {
                        throw new IllegalStateException("Nono output reader did not stop; termination uncertain");
                    }
                } finally {
                    if (interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
    }

    private Object relocate(final Object value, final Path attempt) {
        if (value instanceof String text) {
            return text.equals("/attempt") ? attempt.toString() : text.replace("/attempt/", attempt + "/");
        }
        if (value instanceof Map<?, ?> map) {
            final var result = new LinkedHashMap<String, Object>();
            map.forEach((key, item) -> result.put(key.toString(), relocate(item, attempt)));
            return result;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(item -> relocate(item, attempt)).toList();
        }
        return value;
    }

    private static Path checked(final Path path, final boolean directory) {
        final var absolute = path.toAbsolutePath().normalize();
        Path current = absolute.getRoot();
        for (final var part : absolute) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException("Symbolic runtime paths are forbidden");
            }
        }
        if (directory
                ? !Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)
                : !Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("Missing runtime path; termination uncertain");
        }
        return absolute;
    }

    private static void validateRuntimeIdentity(final String id) {
        if (id == null || id.length() > 63 || !RUNTIME_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Invalid execution identity");
        }
    }

    @JsonModel
    public record Profile(
            @Property(documentation = "Sandbox profile identity.")
            ProfileMeta meta,

            @Property(documentation = "Explicit capability groups.")
            ProfileGroups groups,

            @Property(documentation = "Working directory policy.")
            ProfileWorkdir workdir,

            @Property(documentation = "Filesystem grants.") ProfileFilesystem filesystem,

            @Property(documentation = "Process isolation policy.")
            ProfileSecurity security,

            @Property(documentation = "Linux-specific mediation.")
            ProfileLinux linux,

            @Property(documentation = "Allowed and set environment variables.")
            ProfileEnvironment environment) {}

    @JsonModel
    public record ProfileMeta(
            @Property(documentation = "Profile name.") String name) {}

    @JsonModel
    public record ProfileGroups(
            @Property(documentation = "Included capability groups.")
            List<String> include,

            @Property(documentation = "Excluded capability groups.")
            List<String> exclude) {}

    @JsonModel
    public record ProfileWorkdir(
            @Property(documentation = "Implicit working-directory access.")
            String access) {}

    @JsonModel
    public record ProfileFilesystem(
            @Property(documentation = "Read-only directory roots.")
            List<String> read,

            @Property(documentation = "Read-only files.") List<String> read_file,

            @Property(documentation = "Read-write directory roots.")
            List<String> allow) {}

    @JsonModel
    public record ProfileSecurity(
            @Property(documentation = "Signal isolation mode.")
            String signal_mode,

            @Property(documentation = "Process information isolation mode.")
            String process_info_mode,

            @Property(documentation = "IPC isolation mode.") String ipc_mode,

            @Property(documentation = "Whether capability elevation is permitted.")
            boolean capability_elevation) {}

    @JsonModel
    public record ProfileLinux(
            @Property(documentation = "Unix socket mediation mode.")
            String af_unix_mediation) {}

    @JsonModel
    public record ProfileEnvironment(
            @Property(documentation = "Inherited variable names.")
            List<String> allow_vars,

            @Property(documentation = "Explicit sandbox variables indexed by name.")
            Map<String, String> set_vars) {}

    @JsonModel
    public record Sessions(
            @Property(documentation = "Supervised session registry entries.")
            List<Session> sessions) {}

    @JsonModel
    public record Session(
            @Property(documentation = "Supervised session identifier.")
            String session_id,

            @Property(documentation = "Nono lifecycle status.")
            String status,

            @Property(documentation = "Confirmed process exit code; negative means uncertain termination.")
            Integer exit_code) {}

    private record Result(int code, byte[] output) {}
}
