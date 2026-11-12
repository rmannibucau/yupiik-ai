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

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.kubernetes.client.KubernetesClient;
import io.yupiik.fusion.kubernetes.client.KubernetesClientConfiguration;
import io.yupiik.ia.agent.github.store.TaskStore;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Process-bounded dispatcher. Short durable transitions never encompass network/executor work.
 * All workers sharing a home must use the same parallelism (1..64). Stop all workers before
 * changing it, including when switching to/from the legacy exclusive worker.lock protocol.
 * Sidecar lock files must not be deleted: their stable inodes are crash-released leases.
 */
public final class Worker {
    private static final Pattern PULL_NUMBER = Pattern.compile("[0-9]+");
    private static final Pattern TASK_ID = Pattern.compile("[a-zA-Z0-9-]+");
    private static final Pattern FORK_OWNER = Pattern.compile("[A-Za-z0-9-]+");

    private final GithubBotConfiguration conf;
    private final TaskStore store;
    private final GitHubClient github;
    private final JsonMapper json;
    private final DockerExecutionBackend backend;
    private final Path attemptRoot;
    private final NonoExecutionBackend nono;
    private final WorkerOptions options;
    private final ExecutorConfiguration execution;

    public Worker(
            final GithubBotConfiguration conf,
            final WorkerOptions options,
            final TaskStore store,
            final GitHubClient github,
            final JsonMapper json,
            final ExecutorConfiguration execution) {
        this.conf = conf;
        this.options = options;
        this.store = store;
        this.github = github;
        this.json = json;
        this.execution = execution;
        this.attemptRoot = execution.attemptRoot().isBlank()
                ? null
                : Path.of(execution.attemptRoot()).toAbsolutePath().normalize();
        final var stateRoot = Path.of(conf.home()).toAbsolutePath().normalize();
        if (attemptRoot != null && (attemptRoot.startsWith(stateRoot) || stateRoot.startsWith(attemptRoot))) {
            throw new IllegalArgumentException("Attempt root and dispatcher state must be disjoint");
        }
        final var timeout = Duration.ofSeconds(execution.timeoutSeconds());
        switch (execution.backend()) {
            case DOCKER -> {
                if (execution.docker().egress() != null
                        && !execution.relay().bind().isBlank()) {
                    throw new IllegalArgumentException(
                            "Docker enforced egress cannot reach the private credential relay; use a public HTTPS provider or disable one mode");
                }
                this.backend = new DockerExecutionBackend(
                        execution.docker().executable(),
                        execution.docker().image(),
                        timeout,
                        execution.docker().network(),
                        execution.docker().egress());
                this.nono = null;
            }
            case NONO -> {
                if (execution.nono().agentExecutable().isBlank()
                        || execution.nono().runtimeRoot().isBlank()) {
                    throw new IllegalArgumentException("Nono requires a trusted agent executable and runtime root");
                }
                this.backend = null;
                this.nono = new NonoExecutionBackend(
                        json,
                        execution.nono().executable(),
                        Path.of(execution.nono().agentExecutable()),
                        Path.of(execution.nono().runtimeRoot()),
                        Path.of(conf.home()),
                        timeout,
                        Duration.ofSeconds(execution.commandTimeoutSeconds()));
            }
            case KUBERNETES -> {
                if (attemptRoot == null) {
                    throw new IllegalArgumentException("Kubernetes requires a dedicated attempt root");
                }
                this.backend = null;
                this.nono = null;
            }
            default -> throw new IllegalArgumentException("Unsupported executor backend");
        }
    }

    private KubernetesClient kubernetesClient() {
        final var settings = execution.kubernetes();
        final var configuration = new KubernetesClientConfiguration()
                .setToken(settings.token())
                .setCertificates(settings.certificates())
                .setSkipTls(settings.skipTls());
        if (!settings.master().isBlank()) {
            configuration.setMaster(settings.master());
        }
        if (!settings.kubeconfig().isBlank()) {
            final var path = Path.of(settings.kubeconfig()).toAbsolutePath().normalize();
            if (!path.getFileName().toString().endsWith(".json")) {
                throw new IllegalArgumentException(
                        "github.executor.kubernetes.kubeconfig must be JSON without exec authentication");
            }
            try {
                final var content = json.fromString(KubeconfigDocument.class, Files.readString(path));
                if (content == null
                        || content.users() == null
                        || content.users().stream()
                                .anyMatch(entry -> entry == null
                                        || entry.user() == null
                                        || entry.user().containsKey("exec")
                                        || entry.user().containsKey("auth-provider"))) {
                    throw new IllegalArgumentException(
                            "Kubernetes kubeconfig requires static credentials, not external authentication plugins");
                }
            } catch (final IOException e) {
                throw new IllegalArgumentException("Cannot read Kubernetes kubeconfig");
            }
            configuration.setKubeconfig(path);
        }
        if (!settings.privateKey().isBlank()) {
            configuration.setPrivateKey(settings.privateKey());
        }
        if (!settings.privateKeyCertificate().isBlank()) {
            configuration.setPrivateKeyCertificate(settings.privateKeyCertificate());
        }
        return new KubernetesClient(configuration);
    }

    private KubernetesExecutionBackend kubernetesBackend(final KubernetesClient client) {
        final var settings = execution.kubernetes();
        return new KubernetesExecutionBackend(
                json,
                client,
                settings.namespace(),
                settings.claim(),
                settings.image(),
                attemptRoot,
                settings.uid(),
                settings.gid(),
                Duration.ofSeconds(execution.timeoutSeconds()),
                Duration.ofSeconds(execution.commandTimeoutSeconds()),
                settings.serviceAccount());
    }

    /** One invocation processes one task, keeping poll scheduling independent from agent latency. */
    public void run(final String requestedId) {
        if (options.maxRetries() < 0 || execution.timeoutSeconds() <= 0 || execution.commandTimeoutSeconds() <= 0) {
            throw new IllegalArgumentException("Invalid execution budget");
        }
        if (options.parallelism() < 1 || options.parallelism() > 64) {
            throw new IllegalArgumentException("Worker parallelism must be between 1 and 64");
        }
        final var home = Path.of(conf.home()).toAbsolutePath().normalize();
        try (final var slot = acquireSlot(home)) {
            if (slot == null) {
                return;
            }
            // Snapshot only candidates. A candidate is authoritative only after leasing and rereading it.
            final var candidates = store.transaction(state -> state.list().stream()
                    .filter(it -> requestedId == null || requestedId.isBlank() || requestedId.equals(it.get("id")))
                    .filter(this::eligible)
                    .map(it -> String.valueOf(it.get("id")))
                    .toList());
            for (final var id : candidates) {
                // IDs are store-generated UUIDs, never paths supplied by callers.
                if (!TASK_ID.matcher(id).matches()) {
                    throw new IllegalStateException("Invalid task identity");
                }
                try (final var lease = tryAcquire(home.resolve("task-" + id + ".lock"))) {
                    if (lease == null) {
                        continue;
                    }
                    final var task = store.transaction(state -> {
                        final var value = state.tasks().get(id);
                        return value == null ? null : new LinkedHashMap<>(TaskStore.map(value));
                    });
                    if (task == null || !eligible(task)) {
                        continue;
                    }
                    process(home, task);
                    return;
                }
            }
        } catch (final IOException e) {
            throw new IllegalStateException("Worker storage failure", e);
        }
    }

    private boolean eligible(final Map<String, Object> task) {
        return Set.of("pending", "running", "publishing").contains(String.valueOf(task.get("state")))
                || (Set.of("done", "failed").contains(String.valueOf(task.get("state")))
                        && task.containsKey("attemptDirectory"));
    }

    private Lease acquireSlot(final Path home) throws IOException {
        if (options.parallelism() == 1) {
            return tryAcquire(home.resolve("worker.lock"));
        }
        for (int slot = 0; slot < options.parallelism(); slot++) {
            final var lease = tryAcquire(home.resolve("worker-" + slot + ".lock"));
            if (lease != null) {
                return lease;
            }
        }
        return null;
    }

    private Lease tryAcquire(final Path path) throws IOException {
        final var channel =
                FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        boolean acquired = false;
        try {
            final var lock = channel.tryLock();
            if (lock == null) {
                return null;
            }
            acquired = true;
            return new Lease(channel, lock);
        } catch (final OverlappingFileLockException e) {
            // Same-JVM contention is local to this lease, not to other slots or candidates.
            return null;
        } finally {
            if (!acquired) {
                channel.close();
            }
        }
    }

    private record Lease(FileChannel channel, FileLock lock) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            try (channel) {
                lock.release();
            }
        }
    }

    private void process(final Path home, final Map<String, Object> task) {
        new BotAudit(home, json).record("worker-selected", task);
        final var id = String.valueOf(task.get("id"));
        if (Set.of("done", "failed").contains(String.valueOf(task.get("state")))) {
            // A crash after the terminal transition must not strand credential-bearing attempt data.
            // Terminal tasks were already stopped before publication; never execute or publish them again.
            if (task.get("attemptDirectory") instanceof String directory) {
                cleanup(task, directory);
                store.transaction(state -> {
                    state.task(id).remove("attemptDirectory");
                    return null;
                });
            }
            return;
        }
        if ("running".equals(task.get("state"))) {
            recover(task);
        }
        final var current = store.transaction(state -> new LinkedHashMap<>(state.task(id)));
        if ("publishing".equals(current.get("state"))) {
            publish(current);
            return;
        }
        if (current.get("attemptDirectory") instanceof String previous) {
            cleanup(current, previous);
            store.transaction(state -> {
                state.task(id).remove("attemptDirectory");
                return null;
            });
        }
        if (!authorized(current)) {
            new BotAudit(Path.of(conf.home()), json).record("authorization-denied", current);
            throw new IllegalStateException("Execution blocked: the requesting user is no longer authorized");
        }
        try {
            final var prior = existingPullRequest(current);
            final Map<String, Object> result;
            if ("help".equals(current.get("kind"))) {
                result = Map.of("summary", "Help requested.");
            } else if (prior != null) {
                result = Map.of(
                        "schemaVersion",
                        1,
                        "taskId",
                        id,
                        "summary",
                        "Reconciled the task pull request from a previous attempt.",
                        "prUrl",
                        prior);
            } else {
                result = execute(current);
            }
            stage(id, result);
        } catch (final RuntimeException e) {
            final var interruptedTask = store.transaction(state -> new LinkedHashMap<>(state.task(id)));
            if ("running".equals(interruptedTask.get("state"))) {
                // A failed stop deliberately escapes: never retry or clean up an uncertain runtime.
                stopRuntime(interruptedTask);
            }
            failedAttempt(id, e);
        }
        final var completed = store.transaction(state -> new LinkedHashMap<>(state.task(id)));
        if ("publishing".equals(completed.get("state"))) {
            publish(completed);
        }
    }

    private boolean authorized(final Map<String, Object> task) {
        if (task.get("actorId") != null
                && Arrays.stream(conf.authorization().userIds().split(","))
                        .map(String::trim)
                        .noneMatch(id -> id.equals(String.valueOf(task.get("actorId"))))) {
            return false;
        }
        if (task.get("actorId") != null
                && !new TeamAuthorization(github, conf.authorization().teams()).allows(task.get("actorId"), null)) {
            return false;
        }
        return true;
    }

    private void recover(final Map<String, Object> task) {
        // Persisted runtime identity precedes launch. An uncertain stop blocks recovery, not starts another agent.
        stopRuntime(task);
        try {
            stage(String.valueOf(task.get("id")), readResult(task));
        } catch (final IOException | IllegalArgumentException e) {
            failedAttempt(String.valueOf(task.get("id")), e);
        }
    }

    private void stopRuntime(final Map<String, Object> task) {
        if (!(task.get("runtimeId") instanceof String identity)
                || !identity.equals("yca-" + task.get("id") + "-" + task.get("attempts"))) {
            throw new IllegalStateException("Recovery requires the persisted task-bound runtime identity");
        }
        if (!execution
                .backend()
                .name()
                .equalsIgnoreCase(String.valueOf(task.getOrDefault("executionBackend", "docker")))) {
            throw new IllegalStateException("Restore the persisted execution backend before recovery");
        }
        if (task.containsKey("executionFingerprint")
                && !executionFingerprint().equals(task.get("executionFingerprint"))) {
            throw new IllegalStateException("Restore the persisted executor configuration before recovery");
        }
        if (task.get("runtimeId") instanceof String runtime) {
            switch (execution.backend()) {
                case DOCKER -> backend.stop(runtime);
                case NONO -> nono.stop(runtime);
                case KUBERNETES -> {
                    try (final var client = kubernetesClient()) {
                        kubernetesBackend(client).stop(runtime);
                    }
                }
            }
        }
    }

    private Map<String, Object> execute(final Map<String, Object> task) {
        final var id = String.valueOf(task.get("id"));
        Path directory = null;
        try {
            final int attempt = ((Number) task.get("attempts")).intValue() + 1;
            final var runtime = "yca-" + id + "-" + attempt;
            final var config = options.agentConfiguration().isBlank()
                    ? Path.of(conf.home()).resolve("agent-configuration.json")
                    : Path.of(options.agentConfiguration());
            directory =
                    new AttemptPreparation(json).prepare(config.toAbsolutePath().normalize(), attemptRoot);
            if (!options.agentCommenting() && "solve".equals(task.get("kind"))) {
                if (options.forkOwner().isBlank() || !options.cacheRoot().isBlank()) {
                    throw new IllegalArgumentException(
                            "Strict solve requires a fork owner and cannot use the anonymous clone cache");
                }
                final var context = new GithubPrivateContext(github, json).stageSolve(task, directory);
                store.transaction(state -> {
                    final var actual = state.task(id);
                    if (actual.containsKey("strictSolveForkOwner")
                            && !options.forkOwner().equals(actual.get("strictSolveForkOwner"))) {
                        throw new IllegalStateException("Restore the pinned strict solve fork owner");
                    }
                    actual.put("strictSolveForkOwner", options.forkOwner());
                    actual.put("strictSolveBaseSha", context.baseSha());
                    actual.put("strictSolveBaseBranch", context.baseBranch());
                    return null;
                });
                task.put("strictSolveForkOwner", options.forkOwner());
                task.put("strictSolveBaseSha", context.baseSha());
                task.put("strictSolveBaseBranch", context.baseBranch());
            }
            if (!options.agentCommenting() && "review".equals(task.get("kind"))) {
                new GithubContextSnapshot(github, json).stage(task, directory);
                if (options.privateSource()) {
                    new GithubPrivateContext(github, json).stage(task, directory);
                }
            }
            if (!options.cacheRoot().isBlank()) {
                if (options.privateSource()) {
                    throw new IllegalArgumentException(
                            "Private source staging cannot share the anonymous clone-cache destination");
                }
                if (!"https://api.github.com".equals(conf.api())) {
                    throw new IllegalArgumentException("Anonymous clone cache supports public github.com only");
                }
                final var cache = Path.of(options.cacheRoot()).toAbsolutePath().normalize();
                final var stateRoot = Path.of(conf.home()).toAbsolutePath().normalize();
                if (cache.startsWith(stateRoot)
                        || stateRoot.startsWith(cache)
                        || (attemptRoot != null && (cache.startsWith(attemptRoot) || attemptRoot.startsWith(cache)))) {
                    throw new IllegalArgumentException("Clone cache must be disjoint from state and attempt roots");
                }
                new RepositoryCache(cache, options.cacheGit(), Duration.ofSeconds(execution.timeoutSeconds()))
                        .stage("https://github.com/" + task.get("repository") + ".git", directory.resolve("workspace"));
            }
            final var credentials = new LinkedHashMap<String, String>();
            final var agentToken = System.getenv(conf.auth().agentTokenEnv());
            if (options.agentCommenting() && agentToken != null && !agentToken.isBlank()) {
                credentials.put("GH_TOKEN", agentToken);
            }
            final var forkToken = System.getenv("GITHUB_BOT_FORK_TOKEN");
            if (options.agentCommenting() && forkToken != null && !forkToken.isBlank()) {
                credentials.put("GITHUB_BOT_FORK_TOKEN", forkToken);
            }
            if ("solve".equals(task.get("kind"))) {
                if (options.forkOwner().isBlank()
                        || (options.agentCommenting() && (agentToken == null || agentToken.isBlank()))) {
                    throw new IllegalArgumentException(
                            "Solving requires a configured fork owner and separate agent token");
                }
                if (options.provisionFork()) {
                    new ForkProvisioner(github, store)
                            .ensure(id, String.valueOf(task.get("repository")), options.forkOwner());
                } else {
                    verifyFork(String.valueOf(task.get("repository")));
                }
            }
            final var attemptPath = directory.toString();
            // Once launch becomes possible, absence of a queue reference is not proof of termination.
            Files.delete(directory.resolve(".prepared-only"));
            store.transaction(state -> {
                final var actual = state.task(id);
                actual.put("state", "running");
                actual.put("attempts", attempt);
                actual.put("runtimeId", runtime);
                actual.put("executionBackend", execution.backend().name().toLowerCase(Locale.ROOT));
                actual.put("executionFingerprint", executionFingerprint());
                actual.put("attemptDirectory", attemptPath);
                actual.put("startedAt", Instant.now().toString());
                return null;
            });
            task.put("attempts", attempt);
            task.put("attemptDirectory", attemptPath);
            new BotAudit(Path.of(conf.home()), json).record("execution-starting", task);
            final var prompt =
                    new AttemptPreparation(json).prompt(task, options.forkOwner(), options.agentCommenting());
            final int exit;
            try (final var relay = execution.relay().bind().isBlank()
                            ? null
                            : new AttemptRelay(
                                    directory.resolve("configuration.json"),
                                    json,
                                    execution.relay().bind(),
                                    execution.relay().advertised());
                    final var broker = !options.agentCommenting() && "review".equals(task.get("kind"))
                            ? new GithubContextBroker(github, json, task, directory)
                            : null) {
                exit = switch (execution.backend()) {
                    case DOCKER -> backend.execute(directory, runtime, prompt, credentials);
                    case NONO -> nono.execute(directory, runtime, prompt, credentials);
                    case KUBERNETES -> {
                        try (final var client = kubernetesClient()) {
                            yield kubernetesBackend(client).execute(directory, runtime, prompt, credentials);
                        }
                    }
                };
            }
            if (exit != 0) {
                throw new IllegalArgumentException("Agent exited unsuccessfully");
            }
            // A client process exiting is not sufficient evidence that its remote runtime stopped.
            stopRuntime(store.transaction(state -> new LinkedHashMap<>(state.task(id))));
            return readResult(task);
        } catch (final IOException | RuntimeException e) {
            // Provisioning failure occurred before durable launch; remove secrets/workspace immediately.
            if (directory != null
                    && !store.<Boolean>transaction(
                            state -> "running".equals(state.task(id).get("state")))) {
                try {
                    AttemptPreparation.delete(directory);
                } catch (final IOException suppressed) {
                    e.addSuppressed(suppressed);
                }
            }
            if (e instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            throw new IllegalArgumentException("Execution filesystem failure", e);
        }
    }

    private Map<String, Object> readResult(final Map<String, Object> task) throws IOException {
        if (!(task.get("attemptDirectory") instanceof String directory)) {
            throw new IllegalArgumentException("No recoverable execution result");
        }
        final var path = Path.of(directory).resolve("output/result.json");
        Path parent = path.toAbsolutePath().normalize().getRoot();
        for (final var segment : path.toAbsolutePath().normalize()) {
            parent = parent.resolve(segment);
            if (Files.isSymbolicLink(parent)) {
                throw new IllegalArgumentException("Symbolic structured result paths are forbidden");
            }
        }
        final long resultLimit = task.containsKey("strictSolveBaseSha") ? 6L * 1024 * 1024 : 1_000_000;
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > resultLimit) {
            throw new IllegalArgumentException("No bounded structured result");
        }
        final AgentResult parsed;
        try {
            parsed = json.fromString(AgentResult.class, Files.readString(path));
        } catch (final IllegalStateException e) {
            throw new IllegalArgumentException("Invalid structured result", e);
        }
        if (parsed == null) {
            throw new IllegalArgumentException("Structured result must be a JSON object");
        }
        final var result = parsed.toMap();
        if (!(result.get("schemaVersion") instanceof Number version)
                || version.intValue() != 1
                || version.doubleValue() != 1
                || !task.get("id").equals(result.get("taskId"))
                || (!(result.get("summary") instanceof String summary) || summary.isBlank())
                        && (!(result.get("failure") instanceof String failure) || failure.isBlank())) {
            throw new IllegalArgumentException("Structured result schema/task identity mismatch");
        }
        return result;
    }

    private void verifyFork(final String repository) {
        if (!FORK_OWNER.matcher(options.forkOwner()).matches()) {
            throw new IllegalArgumentException("Invalid fork owner");
        }
        final var fork = TaskStore.map(
                github.get("/repos/" + options.forkOwner() + "/" + repository.split("/")[1]));
        if (!Boolean.TRUE.equals(fork.get("fork"))
                || !(fork.get("parent") instanceof Map<?, ?>)
                || !repository.equalsIgnoreCase(
                        String.valueOf(TaskStore.map(fork.get("parent")).get("full_name")))) {
            throw new IllegalArgumentException("Pre-provisioned fork does not belong to the expected upstream");
        }
    }

    private String existingPullRequest(final Map<String, Object> task) {
        if (!"solve".equals(task.get("kind"))
                || !options.agentCommenting()
                || task.containsKey("strictSolveBaseSha")
                || ((Number) task.get("attempts")).intValue() == 0
                || options.forkOwner().isBlank()) {
            return null;
        }
        final var head =
                URLEncoder.encode(options.forkOwner() + ":github-bot/" + task.get("id"), StandardCharsets.UTF_8);
        String next = "/repos/" + task.get("repository") + "/pulls?state=open&head=" + head + "&per_page=100";
        while (next != null) {
            final var page = github.page(next);
            for (final var raw : (List<?>) page.body()) {
                final var pr = TaskStore.map(raw);
                if (validPullRequest(task, pr)) {
                    return String.valueOf(pr.get("html_url"));
                }
            }
            next = page.next();
        }
        return null;
    }

    private boolean validPullRequest(final Map<String, Object> task, final Map<String, Object> pr) {
        if (!"open".equals(pr.get("state"))
                || !(pr.get("head") instanceof Map<?, ?>)
                || !(pr.get("base") instanceof Map<?, ?>)) {
            return false;
        }
        final var head = TaskStore.map(pr.get("head"));
        final var base = TaskStore.map(pr.get("base"));
        if (!(head.get("repo") instanceof Map<?, ?>) || !(base.get("repo") instanceof Map<?, ?>)) {
            return false;
        }
        final var repo = String.valueOf(task.get("repository"));
        final var fork = options.forkOwner() + "/" + repo.split("/")[1];
        return ("github-bot/" + task.get("id")).equals(head.get("ref"))
                && fork.equalsIgnoreCase(
                        String.valueOf(TaskStore.map(head.get("repo")).get("full_name")))
                && repo.equalsIgnoreCase(
                        String.valueOf(TaskStore.map(base.get("repo")).get("full_name")));
    }

    private void stage(final String id, final Map<String, Object> result) {
        store.transaction(state -> {
            final var task = state.task(id);
            task.put("result", result);
            task.put("state", "publishing");
            return null;
        });
    }

    private void failedAttempt(final String id, final Exception failure) {
        // Errors are intentionally classified, never copied from secret-bearing tool logs to GitHub.
        store.transaction(state -> {
            final var task = state.task(id);
            if ("publishing".equals(task.get("state"))) {
                return null;
            }
            final int attempts = ((Number) task.get("attempts")).intValue();
            if (attempts >= 1 + options.maxRetries() || !"running".equals(task.get("state"))) {
                task.put("state", "publishing");
                task.put(
                        "result",
                        Map.of(
                                "failure",
                                "I could not complete the task because executor setup or execution failed. The"
                                        + " operator can inspect the task diagnostics."));
            } else {
                task.put("state", "pending");
            }
            task.put("lastError", failure.getClass().getSimpleName());
            return null;
        });
        new BotAudit(Path.of(conf.home()), json)
                .record("execution-failed", store.transaction(state -> new LinkedHashMap<>(state.task(id))));
    }

    private void publish(final Map<String, Object> task) {
        final var id = String.valueOf(task.get("id"));
        final var prefix = "/repos/" + task.get("repository") + "/issues/" + task.get("number") + "/comments";
        final var marker = "<!-- yca-task:" + id + " -->";
        final boolean discussion = "discussion".equals(task.get("sourceType"));
        String next = discussion ? null : prefix + "?per_page=100";
        while (next != null) {
            final var page = github.page(next);
            for (final var raw : (List<?>) page.body()) {
                final var comment = TaskStore.map(raw);
                if (String.valueOf(comment.get("body")).contains(marker)
                        && comment.get("user") instanceof Map<?, ?>
                        && conf.login()
                                .equalsIgnoreCase(String.valueOf(
                                        TaskStore.map(comment.get("user")).get("login")))) {
                    finish(
                            task,
                            "commentUrl",
                            String.valueOf(comment.get("html_url")),
                            Boolean.TRUE.equals(task.get("publicationFailure")));
                    return;
                }
            }
            next = page.next();
        }
        if (!discussion && task.containsKey("publicationIntent")) {
            throw new IllegalStateException("Ambiguous final comment; reconcile previous write before retrying");
        }
        // Existing comments may be reconciled after revocation, but no new privileged write is allowed.
        // Preserve staged results and intents so an operator can restore authorization or reconcile them.
        if (!authorized(task)) {
            new BotAudit(Path.of(conf.home()), json).record("authorization-denied", task);
            throw new IllegalStateException("Publication blocked: the requesting user is no longer authorized");
        }
        final var result = TaskStore.map(task.get("result"));
        boolean failure = result.containsKey("failure");
        String outcome = failure
                ? "I could not complete the requested task. The operator can inspect the task diagnostics."
                : "";
        if (!failure && "help".equals(task.get("kind"))) {
            outcome = "Mention `@" + conf.login() + " review` on a pull request, or `@" + conf.login()
                    + " solve <request>` on an issue or enabled Discussion.";
        } else if (!failure && "review".equals(task.get("kind"))) {
            // The native review is the response. Do not announce it in a second comment.
            final var reviewUrl = new ReviewPublisher(github, store, conf.login()).publish(task, result);
            finish(task, "reviewUrl", reviewUrl, false);
            return;
        } else if (!failure && "solve".equals(task.get("kind")) && task.containsKey("strictSolveBaseSha")) {
            outcome = "I opened a pull request: "
                    + new StrictSolvePublisher(github, store, json, options.forkOwner()).publish(task, result);
        } else if (!failure && result.get("prUrl") instanceof String url) {
            final var expected = "https://github.com/" + task.get("repository") + "/pull/";
            if (url.startsWith(expected)
                    && PULL_NUMBER.matcher(url.substring(expected.length())).matches()) {
                final var pr = TaskStore.map(
                        github.get("/repos/" + task.get("repository") + "/pulls/" + url.substring(expected.length())));
                if (validPullRequest(task, pr)) {
                    outcome = "I opened a pull request: " + url;
                }
            }
        }
        if (outcome.isEmpty()) {
            outcome = "I failed to produce an independently verified pull request or review.";
            failure = true;
        }
        final boolean failed = failure;
        if (discussion) {
            store.transaction(state -> {
                state.task(id).put("publicationFailure", failed);
                return null;
            });
            final var url = new DiscussionPublisher(github, store, conf.login()).publish(task, marker + "\n" + outcome);
            finish(task, "commentUrl", url, failed);
            return;
        }
        store.transaction(state -> {
            state.task(id).put("publicationIntent", marker);
            state.task(id).put("publicationFailure", failed);
            return null;
        });
        new BotAudit(Path.of(conf.home()), json)
                .record("publication-starting", store.transaction(state -> new LinkedHashMap<>(state.task(id))));
        final var posted = TaskStore.map(github.post(prefix, new GitHubClient.CommentRequest(marker + "\n" + outcome)));
        finish(task, "commentUrl", String.valueOf(posted.get("html_url")), failed);
    }

    private void finish(
            final Map<String, Object> task, final String responseKey, final String responseUrl, final boolean failed) {
        final var id = String.valueOf(task.get("id"));
        store.transaction(state -> {
            final var actual = state.task(id);
            actual.put("state", failed ? "failed" : "done");
            actual.put(responseKey, responseUrl);
            actual.put("completedAt", Instant.now().toString());
            actual.remove("result");
            return null;
        });
        if (task.get("attemptDirectory") instanceof String directory) {
            cleanup(task, directory);
            store.transaction(state -> {
                state.task(id).remove("attemptDirectory");
                return null;
            });
        }
        new BotAudit(Path.of(conf.home()), json)
                .record("completed", store.transaction(state -> new LinkedHashMap<>(state.task(id))));
    }

    private String executionFingerprint() {
        // Client settings can contain PEM credentials. Persist only a digest, never the configuration.
        final var identity = execution.toString();
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8)));
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void cleanup(final Map<String, Object> task, final String directory) {
        if ("nono".equalsIgnoreCase(String.valueOf(task.get("executionBackend")))) {
            if (nono == null
                    || (task.containsKey("executionFingerprint")
                            && !executionFingerprint().equals(task.get("executionFingerprint")))) {
                throw new IllegalStateException("Restore Nono executor settings before private-state cleanup");
            }
            if (!(task.get("runtimeId") instanceof String runtime)) {
                throw new IllegalStateException("Nono runtime identity required for private-state cleanup");
            }
            nono.cleanupStoppedState(runtime);
        }
        cleanup(directory);
    }

    private void cleanup(final String directory) {
        final var path = Path.of(directory).toAbsolutePath().normalize();
        if (!path.getParent()
                        .equals((attemptRoot == null ? Path.of(System.getProperty("java.io.tmpdir")) : attemptRoot)
                                .toAbsolutePath()
                                .normalize())
                || !path.getFileName().toString().startsWith("yca-github-")) {
            throw new IllegalStateException("Unexpected cleanup path");
        }
        try {
            AttemptPreparation.delete(path);
        } catch (final IOException e) {
            throw new IllegalStateException("Attempt cleanup failed", e);
        }
    }
}
