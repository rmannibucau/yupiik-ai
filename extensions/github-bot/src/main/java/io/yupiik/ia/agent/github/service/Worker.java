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
package io.yupiik.ia.agent.github.service;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.kubernetes.client.KubernetesClient;
import io.yupiik.fusion.kubernetes.client.KubernetesClientConfiguration;
import io.yupiik.ia.agent.github.client.GitHubClient;
import io.yupiik.ia.agent.github.configuration.ExecutorConfiguration;
import io.yupiik.ia.agent.github.configuration.GithubBotConfiguration;
import io.yupiik.ia.agent.github.configuration.WorkerOptions;
import io.yupiik.ia.agent.github.context.GithubContextBroker;
import io.yupiik.ia.agent.github.context.GithubContextSnapshot;
import io.yupiik.ia.agent.github.context.GithubPrivateContext;
import io.yupiik.ia.agent.github.execution.AttemptPreparation;
import io.yupiik.ia.agent.github.execution.AttemptRelay;
import io.yupiik.ia.agent.github.execution.DockerExecutionBackend;
import io.yupiik.ia.agent.github.execution.KubernetesExecutionBackend;
import io.yupiik.ia.agent.github.execution.NonoExecutionBackend;
import io.yupiik.ia.agent.github.execution.RepositoryCache;
import io.yupiik.ia.agent.github.model.AgentResult;
import io.yupiik.ia.agent.github.model.KubeconfigDocument;
import io.yupiik.ia.agent.github.model.TaskDocument;
import io.yupiik.ia.agent.github.model.TaskKind;
import io.yupiik.ia.agent.github.model.TaskState;
import io.yupiik.ia.agent.github.store.MutableTask;
import io.yupiik.ia.agent.github.store.TaskStore;
import java.io.IOException;
import java.math.BigDecimal;
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
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * Process-bounded dispatcher. Short durable transitions never encompass network/executor work.
 * All workers sharing a home must use the same parallelism (1..64). Stop all workers before
 * changing it, including when switching to/from the legacy exclusive worker.lock protocol.
 * Sidecar lock files must not be deleted: their stable inodes are crash-released leases.
 */
@ApplicationScoped
public class Worker {
    private static final Pattern PULL_NUMBER = Pattern.compile("[0-9]+");
    private static final Pattern TASK_ID = Pattern.compile("[a-zA-Z0-9-]+");
    private static final Pattern FORK_OWNER = Pattern.compile("[A-Za-z0-9-]+");

    private final GithubBotConfiguration conf;
    private final TaskStore store;
    private final GitHubClient github;
    private final JsonMapper json;
    private final ReentrantLock initialization = new ReentrantLock();
    private volatile boolean initialized;
    private DockerExecutionBackend backend;
    private Path attemptRoot;
    private NonoExecutionBackend nono;
    private final ExecutorConfiguration execution;
    private final BotAudit audit;
    private final Clock clock;
    private final TeamAuthorization authorization;
    private final AttemptPreparation preparation;
    private final GithubPrivateContext privateContext;
    private final GithubContextSnapshot snapshot;
    private final ForkProvisioner forks;
    private final ReviewPublisher reviews;
    private final StrictSolvePublisher strictSolve;
    private final DiscussionPublisher discussions;

    public Worker(
            final GithubBotConfiguration conf,
            final TaskStore store,
            final GitHubClient github,
            final JsonMapper json,
            final ExecutorConfiguration execution,
            final BotAudit audit,
            final TeamAuthorization authorization,
            final AttemptPreparation preparation,
            final GithubPrivateContext privateContext,
            final GithubContextSnapshot snapshot,
            final ForkProvisioner forks,
            final ReviewPublisher reviews,
            final StrictSolvePublisher strictSolve,
            final DiscussionPublisher discussions,
            final Clock clock) {
        this.conf = conf;
        this.store = store;
        this.github = github;
        this.json = json;
        this.clock = clock;
        this.execution = execution;
        this.audit = audit;
        this.authorization = authorization;
        this.preparation = preparation;
        this.privateContext = privateContext;
        this.snapshot = snapshot;
        this.forks = forks;
        this.reviews = reviews;
        this.strictSolve = strictSolve;
        this.discussions = discussions;
    }

    private void initializeBackend() {
        initialization.lock();
        try {
            if (!initialized) {
                configureBackend();
                initialized = true;
            }
        } finally {
            initialization.unlock();
        }
    }

    private void configureBackend() {
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
    public void run(final String requestedId, final WorkerOptions options) {
        initializeBackend();
        if (options.maxRetries() < 0 || execution.timeoutSeconds() <= 0 || execution.commandTimeoutSeconds() <= 0) {
            throw new IllegalArgumentException("Invalid execution budget");
        }
        if (options.parallelism() < 1 || options.parallelism() > 64) {
            throw new IllegalArgumentException("Worker parallelism must be between 1 and 64");
        }
        store.initialize();
        final var home = Path.of(conf.home()).toAbsolutePath().normalize();
        try (final var slot = acquireSlot(home, options)) {
            if (slot == null) {
                return;
            }
            // Snapshot only candidates. A candidate is authoritative only after leasing and rereading it.
            final var candidates = store.transaction(state -> state.list().stream()
                    .filter(it -> requestedId == null || requestedId.isBlank() || requestedId.equals(it.id()))
                    .filter(this::eligible)
                    .map(it -> String.valueOf(it.id()))
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
                        return value == null ? null : value.document();
                    });
                    if (task == null || !eligible(task)) {
                        continue;
                    }
                    process(home, task, options);
                    return;
                }
            }
        } catch (final IOException e) {
            throw new IllegalStateException("Worker storage failure", e);
        }
    }

    private boolean eligible(final TaskDocument task) {
        return Set.of(TaskState.pending, TaskState.running, TaskState.publishing)
                        .contains(task.state())
                || (Set.of(TaskState.done, TaskState.failed).contains(task.state())
                        && (task.attemptDirectory() != null));
    }

    private Lease acquireSlot(final Path home, final WorkerOptions options) throws IOException {
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

    private void process(final Path home, final TaskDocument task, final WorkerOptions options) {
        audit.record("worker-selected", task);
        final var id = String.valueOf(task.id());
        if (Set.of(TaskState.done, TaskState.failed).contains(task.state())) {
            // A crash after the terminal transition must not strand credential-bearing attempt data.
            // Terminal tasks were already stopped before publication; never execute or publish them again.
            if (task.attemptDirectory() != null) {
                final var directory = task.attemptDirectory();
                cleanup(task, directory);
                store.transaction(state -> {
                    state.task(id).attemptDirectory(null);
                    return null;
                });
            }
            return;
        }
        if ((task.state() == TaskState.running)) {
            recover(task, options);
        }
        final var current = store.transaction(state -> state.task(id).document());
        if ((current.state() == TaskState.publishing)) {
            publish(current, options);
            return;
        }
        if (current.attemptDirectory() != null) {
            final var previous = current.attemptDirectory();
            cleanup(current, previous);
            store.transaction(state -> {
                state.task(id).attemptDirectory(null);
                return null;
            });
        }
        if (!authorized(current)) {
            audit.record("authorization-denied", current);
            throw new IllegalStateException("Execution blocked: the requesting user is no longer authorized");
        }
        var stage = "reconciliation";
        try {
            final var prior = existingPullRequest(current, options);
            final AgentResult result;
            if ((current.kind() == TaskKind.help)) {
                result = new AgentResult(null, null, "Help requested.", null, null, null, null, null, null, null);
            } else if (prior != null) {
                result = new AgentResult(
                        BigDecimal.ONE,
                        id,
                        "Reconciled the task pull request from a previous attempt.",
                        null,
                        prior,
                        null,
                        null,
                        null,
                        null,
                        null);
            } else {
                stage = "execution";
                result = execute(current, options);
            }
            stage = "result-staging";
            stage(id, result);
        } catch (final RuntimeException e) {
            final var interruptedTask =
                    store.transaction(state -> state.task(id).document());
            if (!"execution".equals(stage)) {
                retainFailure(interruptedTask, stage, e, null);
            }
            if ((interruptedTask.state() == TaskState.running)) {
                // A failed stop deliberately escapes: never retry or clean up an uncertain runtime.
                stopRuntime(interruptedTask);
            }
            failedAttempt(id, e, options);
        }
        final var completed = store.transaction(state -> state.task(id).document());
        if ((completed.state() == TaskState.publishing)) {
            publish(completed, options);
        }
    }

    private boolean authorized(final TaskDocument task) {
        if (task.actorId() != null
                && Arrays.stream(conf.authorization().userIds().split(","))
                        .map(String::trim)
                        .noneMatch(id -> id.equals(String.valueOf(task.actorId())))) {
            return false;
        }
        if (task.actorId() != null && !authorization.allows(task.actorId(), null)) {
            return false;
        }
        return true;
    }

    private void recover(final TaskDocument task, final WorkerOptions options) {
        // Persisted runtime identity precedes launch. An uncertain stop blocks recovery, not starts another agent.
        stopRuntime(task);
        try {
            stage(String.valueOf(task.id()), readResult(task));
        } catch (final IOException | IllegalArgumentException e) {
            retainFailure(task, "recovery-result", e, null);
            failedAttempt(String.valueOf(task.id()), e, options);
        }
    }

    private void stopRuntime(final TaskDocument task) {
        try {
            stopRuntimeChecked(task);
        } catch (final RuntimeException e) {
            retainFailure(task, "runtime-stop", e, null);
            throw e;
        }
    }

    private void stopRuntimeChecked(final TaskDocument task) {
        final var identity = task.runtimeId();
        if (identity == null || !identity.equals("yca-" + task.id() + "-" + task.attempts())) {
            throw new IllegalStateException("Recovery requires the persisted task-bound runtime identity");
        }
        if (!execution
                .backend()
                .name()
                .equalsIgnoreCase(
                        String.valueOf((task.executionBackend() == null ? "docker" : task.executionBackend())))) {
            throw new IllegalStateException("Restore the persisted execution backend before recovery");
        }
        if ((task.executionFingerprint() != null) && !executionFingerprint().equals(task.executionFingerprint())) {
            throw new IllegalStateException("Restore the persisted executor configuration before recovery");
        }
        if (task.runtimeId() != null) {
            final var runtime = task.runtimeId();
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

    private AgentResult execute(final TaskDocument initialTask, final WorkerOptions options) {
        var task = initialTask;
        final var id = task.id();
        Path directory = null;
        var stage = "preparation";
        Integer exitCode = null;
        final int attempt = task.attempts() + 1;
        try {
            final var runtime = "yca-" + id + "-" + attempt;
            final var config = options.agentConfiguration().isBlank()
                    ? Path.of(conf.home()).resolve("agent-configuration.json")
                    : Path.of(options.agentConfiguration());
            directory = preparation.prepare(config.toAbsolutePath().normalize(), attemptRoot);
            if (!options.agentCommenting() && task.kind() == TaskKind.review) {
                stage = "runtime-preflight";
                GithubContextBroker.verifyRuntime(directory.resolve("workspace"));
            }
            stage = "source-staging";
            if (!options.agentCommenting() && (task.kind() == TaskKind.solve)) {
                if (options.forkOwner().isBlank() || !options.cacheRoot().isBlank()) {
                    throw new IllegalArgumentException(
                            "Strict solve requires a fork owner and cannot use the anonymous clone cache");
                }
                final var context = privateContext.stageSolve(task, directory);
                store.transaction(state -> {
                    final var actual = state.task(id);
                    if ((actual.strictSolveForkOwner() != null)
                            && !options.forkOwner().equals(actual.strictSolveForkOwner())) {
                        throw new IllegalStateException("Restore the pinned strict solve fork owner");
                    }
                    actual.strictSolveForkOwner(options.forkOwner());
                    actual.strictSolveBaseSha(context.baseSha());
                    actual.strictSolveBaseBranch(context.baseBranch());
                    return null;
                });
                task = store.transaction(state -> state.task(id).document());
            }
            if (!options.agentCommenting() && (task.kind() == TaskKind.review)) {
                snapshot.stage(task, directory);
                if (options.privateSource()) {
                    privateContext.stage(task, directory);
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
                        .stage("https://github.com/" + task.repository() + ".git", directory.resolve("workspace"));
            }
            stage = "credentials-and-fork";
            final var credentials = new LinkedHashMap<String, String>();
            final var agentToken = System.getenv(conf.auth().agentTokenEnv());
            if (options.agentCommenting() && agentToken != null && !agentToken.isBlank()) {
                credentials.put("GH_TOKEN", agentToken);
            }
            final var forkToken = System.getenv("GITHUB_BOT_FORK_TOKEN");
            if (options.agentCommenting() && forkToken != null && !forkToken.isBlank()) {
                credentials.put("GITHUB_BOT_FORK_TOKEN", forkToken);
            }
            if ((task.kind() == TaskKind.solve)) {
                if (options.forkOwner().isBlank()
                        || (options.agentCommenting() && (agentToken == null || agentToken.isBlank()))) {
                    throw new IllegalArgumentException(
                            "Solving requires a configured fork owner and separate agent token");
                }
                if (options.provisionFork()) {
                    forks.ensure(id, String.valueOf(task.repository()), options.forkOwner());
                } else {
                    verifyFork(String.valueOf(task.repository()), options);
                }
            }
            stage = "launch-preparation";
            final var attemptPath = directory.toString();
            // Once launch becomes possible, absence of a queue reference is not proof of termination.
            Files.delete(directory.resolve(".prepared-only"));
            store.transaction(state -> {
                final var actual = state.task(id);
                actual.state(TaskState.running);
                actual.attempts(attempt);
                actual.runtimeId(runtime);
                actual.executionBackend(execution.backend().name().toLowerCase(Locale.ROOT));
                actual.executionFingerprint(executionFingerprint());
                actual.attemptDirectory(attemptPath);
                actual.startedAt(clock.instant().toString());
                return null;
            });
            task = store.transaction(state -> state.task(id).document());
            audit.record("execution-starting", task);
            final var prompt = preparation.prompt(task, options.forkOwner(), options.agentCommenting());
            final int exit;
            stage = "relay-and-context";
            try (final var relay = execution.relay().bind().isBlank()
                            ? null
                            : new AttemptRelay(
                                    directory.resolve("configuration.json"),
                                    json,
                                    execution.relay().bind(),
                                    execution.relay().advertised());
                    final var broker = !options.agentCommenting() && (task.kind() == TaskKind.review)
                            ? new GithubContextBroker(github, json, task, directory)
                            : null) {
                stage = "execution";
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
            exitCode = exit;
            if (exit != 0) {
                throw new IllegalArgumentException("Agent exited unsuccessfully");
            }
            // A client process exiting is not sufficient evidence that its remote runtime stopped.
            stage = "runtime-stop";
            stopRuntime(store.transaction(state -> state.task(id).document()));
            stage = "result-validation";
            return readResult(task);
        } catch (final IOException | RuntimeException e) {
            // Capture before either provisioning cleanup or the outer stop/retry/publication path.
            // Early failures have no durable attempt directory or runtime yet.
            final var failed =
                    new MutableTask(store.transaction(state -> state.task(id).document()));
            failed.attempts(attempt);
            if (failed.state() != TaskState.running) {
                failed.runtimeId(null);
            }
            if (directory != null) {
                failed.attemptDirectory(directory.toString());
            }
            retainFailure(failed.document(), stage, e, exitCode);
            // Provisioning failure occurred before durable launch; remove secrets/workspace immediately.
            if (directory != null
                    && !store.<Boolean>transaction(
                            state -> TaskState.running == state.task(id).state())) {
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

    private AgentResult readResult(final TaskDocument task) throws IOException {
        final var directory = task.attemptDirectory();
        if (directory == null) {
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
        final long resultLimit = (task.strictSolveBaseSha() != null) ? 6L * 1024 * 1024 : 1_000_000;
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
        if (parsed.schemaVersion() == null
                || parsed.schemaVersion().compareTo(BigDecimal.ONE) != 0
                || !task.id().equals(parsed.taskId())
                || (parsed.summary() == null || parsed.summary().isBlank())
                        && (parsed.failure() == null || parsed.failure().isBlank())) {
            throw new IllegalArgumentException("Structured result schema/task identity mismatch");
        }
        return parsed;
    }

    private void verifyFork(final String repository, final WorkerOptions options) {
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

    private String existingPullRequest(final TaskDocument task, final WorkerOptions options) {
        if (!(task.kind() == TaskKind.solve)
                || !options.agentCommenting()
                || (task.strictSolveBaseSha() != null)
                || ((Number) task.attempts()).intValue() == 0
                || options.forkOwner().isBlank()) {
            return null;
        }
        final var head = URLEncoder.encode(options.forkOwner() + ":github-bot/" + task.id(), StandardCharsets.UTF_8);
        String next = "/repos/" + task.repository() + "/pulls?state=open&head=" + head + "&per_page=100";
        while (next != null) {
            final var page = github.page(next);
            for (final var raw : (List<?>) page.body()) {
                final var pr = TaskStore.map(raw);
                if (validPullRequest(task, pr, options)) {
                    return String.valueOf(pr.get("html_url"));
                }
            }
            next = page.next();
        }
        return null;
    }

    private boolean validPullRequest(
            final TaskDocument task, final Map<String, Object> pr, final WorkerOptions options) {
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
        final var repo = String.valueOf(task.repository());
        final var fork = options.forkOwner() + "/" + repo.split("/")[1];
        return ("github-bot/" + task.id()).equals(head.get("ref"))
                && fork.equalsIgnoreCase(
                        String.valueOf(TaskStore.map(head.get("repo")).get("full_name")))
                && repo.equalsIgnoreCase(
                        String.valueOf(TaskStore.map(base.get("repo")).get("full_name")));
    }

    private void stage(final String id, final AgentResult result) {
        if (result.failure() != null) {
            retainFailure(
                    store.transaction(state -> state.task(id).document()),
                    "agent-result",
                    new IllegalArgumentException(result.failure()),
                    0);
        }
        store.transaction(state -> {
            final var task = state.task(id);
            task.result(result);
            task.state(TaskState.publishing);
            return null;
        });
    }

    private void failedAttempt(final String id, final Exception failure, final WorkerOptions options) {
        // Errors are intentionally classified, never copied from secret-bearing tool logs to GitHub.
        store.transaction(state -> {
            final var task = state.task(id);
            if (task.state() == TaskState.publishing) {
                return null;
            }
            final int attempts = ((Number) task.attempts()).intValue();
            if (attempts >= 1 + options.maxRetries() || task.state() != TaskState.running) {
                task.state(TaskState.publishing);
                task.result(new AgentResult(
                        null,
                        null,
                        null,
                        "I could not complete the task because executor setup or execution failed. The operator can inspect the task diagnostics.",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null));
            } else {
                task.state(TaskState.pending);
            }
            task.lastError(failure.getClass().getSimpleName());
            return null;
        });
        audit.record(
                "execution-failed", store.transaction(state -> state.task(id).document()));
    }

    private void publish(final TaskDocument task, final WorkerOptions options) {
        try {
            publishChecked(task, options);
        } catch (final RuntimeException e) {
            final var current = store.transaction(state -> state.task(task.id()).document());
            if (current.lastFailure() == null || Objects.equals(current.lastFailure(), task.lastFailure())) {
                retainFailure(current, "publication", e, null);
            }
            throw e;
        }
    }

    private void publishChecked(final TaskDocument task, final WorkerOptions options) {
        final var id = String.valueOf(task.id());
        final var prefix = "/repos/" + task.repository() + "/issues/" + task.number() + "/comments";
        final var marker = "<!-- yca-task:" + id + " -->";
        final boolean discussion = "discussion".equals(task.sourceType());
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
                            Boolean.TRUE.equals(task.publicationFailure()));
                    return;
                }
            }
            next = page.next();
        }
        if (!discussion && (task.publicationIntent() != null)) {
            throw new IllegalStateException("Ambiguous final comment; reconcile previous write before retrying");
        }
        // Existing comments may be reconciled after revocation, but no new privileged write is allowed.
        // Preserve staged results and intents so an operator can restore authorization or reconcile them.
        if (!authorized(task)) {
            audit.record("authorization-denied", task);
            throw new IllegalStateException("Publication blocked: the requesting user is no longer authorized");
        }
        final var result = task.result();
        boolean failure = result.failure() != null;
        String outcome = failure
                ? "I could not complete the requested task. The operator can inspect the task diagnostics."
                : "";
        if (!failure && (task.kind() == TaskKind.help)) {
            outcome = "Mention `@" + conf.login() + " review` on a pull request, or `@" + conf.login()
                    + " solve <request>` on an issue or enabled Discussion.";
        } else if (!failure && (task.kind() == TaskKind.review)) {
            // The native review is the response. Do not announce it in a second comment.
            final var reviewUrl = reviews.publish(task, result);
            finish(task, "reviewUrl", reviewUrl, false);
            return;
        } else if (!failure && (task.kind() == TaskKind.solve) && (task.strictSolveBaseSha() != null)) {
            outcome = "I opened a pull request: " + strictSolve.publish(task, result, options.forkOwner());
        } else if (!failure && result.prUrl() != null) {
            final var url = result.prUrl();
            final var expected = "https://github.com/" + task.repository() + "/pull/";
            if (url.startsWith(expected)
                    && PULL_NUMBER.matcher(url.substring(expected.length())).matches()) {
                final var pr = TaskStore.map(
                        github.get("/repos/" + task.repository() + "/pulls/" + url.substring(expected.length())));
                if (validPullRequest(task, pr, options)) {
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
                state.task(id).publicationFailure(failed);
                return null;
            });
            final var url = discussions.publish(task, marker + "\n" + outcome);
            finish(task, "commentUrl", url, failed);
            return;
        }
        store.transaction(state -> {
            state.task(id).publicationIntent(marker);
            state.task(id).publicationFailure(failed);
            return null;
        });
        audit.record(
                "publication-starting",
                store.transaction(state -> state.task(id).document()));
        final var posted = TaskStore.map(github.post(prefix, new GitHubClient.CommentRequest(marker + "\n" + outcome)));
        finish(task, "commentUrl", String.valueOf(posted.get("html_url")), failed);
    }

    private void finish(
            final TaskDocument task, final String responseKey, final String responseUrl, final boolean failed) {
        final var id = String.valueOf(task.id());
        store.transaction(state -> {
            final var actual = state.task(id);
            actual.state(failed ? TaskState.failed : TaskState.done);
            switch (responseKey) {
                case "commentUrl" -> actual.commentUrl(responseUrl);
                case "reviewUrl" -> actual.reviewUrl(responseUrl);
                default -> throw new IllegalArgumentException("Unsupported response field: " + responseKey);
            }
            actual.completedAt(clock.instant().toString());
            actual.result(null);
            return null;
        });
        if (task.attemptDirectory() != null) {
            final var directory = task.attemptDirectory();
            cleanup(task, directory);
            store.transaction(state -> {
                state.task(id).attemptDirectory(null);
                return null;
            });
        }
        audit.record("completed", store.transaction(state -> state.task(id).document()));
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

    private void cleanup(final TaskDocument task, final String directory) {
        try {
            cleanupChecked(task, directory);
        } catch (final RuntimeException e) {
            retainFailure(task, "cleanup", e, null);
            throw e;
        }
    }

    private void cleanupChecked(final TaskDocument task, final String directory) {
        if ("nono".equalsIgnoreCase(String.valueOf(task.executionBackend()))) {
            if (nono == null
                    || ((task.executionFingerprint() != null)
                            && !executionFingerprint().equals(task.executionFingerprint()))) {
                throw new IllegalStateException("Restore Nono executor settings before private-state cleanup");
            }
            final var runtime = task.runtimeId();
            if (runtime == null) {
                throw new IllegalStateException("Nono runtime identity required for private-state cleanup");
            }
            nono.cleanupStoppedState(runtime);
        }
        cleanup(directory);
    }

    private void retainFailure(
            final TaskDocument task, final String stage, final Throwable failure, final Integer exitCode) {
        String diagnosticFile = null;
        try {
            diagnosticFile =
                    new TaskDiagnostics(Path.of(conf.home()), json, clock).capture(task, stage, failure, exitCode);
        } catch (final IOException | RuntimeException captureFailure) {
            // Do not mask the original error, retain credentials, or print a potentially secret-bearing message.
        }
        final var detail = new TaskDocument.FailureDetail(
                stage,
                failure.getClass().getSimpleName(),
                switch (stage) {
                    case "preparation" -> "Unable to prepare the private attempt configuration.";
                    case "runtime-preflight" ->
                        "Strict reviews require a dispatcher runtime and filesystem with SecureDirectoryStream support.";
                    case "source-staging" -> "Unable to stage repository context or source.";
                    case "credentials-and-fork" -> "Unable to prepare execution credentials or fork.";
                    case "launch-preparation" -> "Unable to prepare the execution launch.";
                    case "relay-and-context" -> "Unable to initialize the attempt relay or context broker.";
                    case "execution" ->
                        exitCode == null
                                ? "Executor launch or execution failed."
                                : "Agent process returned a non-zero exit status.";
                    case "runtime-stop" -> "Unable to confirm runtime termination; recovery remains blocked.";
                    case "result-validation", "recovery-result" -> "Unable to load a valid bounded task result.";
                    case "agent-result" -> "The agent reported a task failure.";
                    case "publication" -> "Unable to publish or reconcile the task response.";
                    case "cleanup" -> "Unable to remove private attempt data.";
                    default -> "Unable to reconcile or stage the task result.";
                },
                clock.instant().toString(),
                task.attempts(),
                exitCode,
                diagnosticFile,
                diagnosticFile != null);
        try {
            store.transaction(state -> {
                final var actual = state.task(task.id());
                actual.lastFailure(detail);
                actual.lastError(failure.getClass().getSimpleName());
                return null;
            });
        } catch (final RuntimeException metadataFailure) {
            // Diagnostic metadata is best-effort too. Let the original stop/cleanup/error policy proceed;
            // its normal queue transitions still fail closed if state storage remains unavailable.
        }
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
