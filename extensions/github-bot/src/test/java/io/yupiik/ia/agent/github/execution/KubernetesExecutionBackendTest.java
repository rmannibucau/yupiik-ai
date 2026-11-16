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

import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.json.internal.JsonMapperImpl;
import io.yupiik.fusion.kubernetes.client.KubernetesClient;
import io.yupiik.fusion.kubernetes.client.KubernetesClientConfiguration;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(15)
class KubernetesExecutionBackendTest {
    @TempDir
    Path home;

    @Test
    void rejectsProtectedAndNullToolEnvironmentBeforeClusterRequests() throws Exception {
        final var attempt = attempt();
        try (final var cluster = new Cluster();
                final var json = json();
                final var client = cluster.client()) {
            final var backend = backend(json, client);
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
                assertThrows(
                        IllegalArgumentException.class,
                        () -> backend.execute(attempt, "yca-task", "review", Map.of(), Map.of(key, "must-not-forward")),
                        key);
            }
            final var environment = new HashMap<String, String>();
            environment.put("MAVEN_OPTS", null);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> backend.execute(attempt, "yca-task", "review", Map.of(), environment));
            assertTrue(cluster.calls.isEmpty());
            assertFalse(Files.exists(attempt.resolve("execution.log")));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"shown", "collapsed", "hidden"})
    void createsHardenedJobAndSecretOverAuthenticatedHttp(final String showToolOutput) throws Exception {
        final var attempt = attempt();
        final var toolEnvironment = Map.of(
                "MAVEN_OPTS", "-Dmaven.repo.local=/attempt/storage/cache/maven -Dliteral=$(never)",
                "npm_config_cache", "/attempt/storage/cache/npm",
                "NPM_CONFIG_USERCONFIG", "/attempt/storage/cache/npmrc",
                "GRADLE_USER_HOME", "/attempt/storage/cache/gradle",
                "PIP_CACHE_DIR", "/attempt/storage/cache/pip",
                "GOMODCACHE", "/attempt/storage/cache/go",
                "CARGO_HOME", "/attempt/storage/cache/cargo",
                "NUGET_PACKAGES", "/attempt/storage/cache/nuget");
        try (final var cluster = new Cluster();
                final var json = json();
                final var client = cluster.client()) {
            assertEquals(
                    7,
                    backend(json, client, Duration.ofMillis(300), showToolOutput)
                            .execute(
                                    attempt,
                                    "yca-task",
                                    "Review '$(never)'",
                                    Map.of(
                                            "GH_TOKEN", "agent-secret",
                                            "GITHUB_BOT_FORK_TOKEN", "fork-secret",
                                            "GITHUB_SOURCE_TOKEN", "source-secret",
                                            "YCA_CONTEXT_TOKEN", "context-secret",
                                            "YCA_CONTEXT_GIT_TOKEN", "context-git-secret"),
                                    toolEnvironment));
            final var job = map(json.fromString(Object.class, cluster.createdJob));
            final var spec = map(job.get("spec"));
            assertEquals(0, ((Number) spec.get("backoffLimit")).intValue());
            assertEquals(3, ((Number) spec.get("activeDeadlineSeconds")).intValue());
            final var pod = map(map(spec.get("template")).get("spec"));
            assertEquals(false, pod.get("automountServiceAccountToken"));
            assertEquals("yca-agent", pod.get("serviceAccountName"));
            assertEquals(false, pod.get("enableServiceLinks"));
            assertEquals("Never", pod.get("restartPolicy"));
            final var agent = map(((List<?>) pod.get("containers")).getFirst());
            toolEnvironment.forEach((key, value) -> {
                final var variables = ((List<?>) agent.get("env"))
                        .stream()
                                .map(KubernetesExecutionBackendTest::map)
                                .filter(entry -> key.equals(entry.get("name")))
                                .toList();
                assertEquals(1, variables.size());
                assertEquals(value, variables.getFirst().get("value"));
                assertNull(variables.getFirst().get("valueFrom"));
            });
            final var security = map(agent.get("securityContext"));
            assertEquals(true, security.get("runAsNonRoot"));
            assertEquals(true, security.get("readOnlyRootFilesystem"));
            assertEquals(false, security.get("allowPrivilegeEscalation"));
            assertEquals(List.of("ALL"), map(security.get("capabilities")).get("drop"));
            assertEquals("RuntimeDefault", map(security.get("seccompProfile")).get("type"));
            assertTrue(map(agent.get("resources")).containsKey("limits"));
            assertEquals(
                    List.of(
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
                            "Review '$(never)'"),
                    agent.get("args"));
            final var credentials = Map.of(
                    "GH_TOKEN", "agent-secret",
                    "GITHUB_BOT_FORK_TOKEN", "fork-secret",
                    "GITHUB_SOURCE_TOKEN", "source-secret",
                    "YCA_CONTEXT_TOKEN", "context-secret",
                    "YCA_CONTEXT_GIT_TOKEN", "context-git-secret");
            assertEquals(
                    credentials,
                    map(json.fromString(Object.class, cluster.secret)).get("stringData"));
            for (final var key : credentials.keySet()) {
                final var variable = ((List<?>) agent.get("env"))
                        .stream()
                                .map(KubernetesExecutionBackendTest::map)
                                .filter(entry -> key.equals(entry.get("name")))
                                .findFirst()
                                .orElseThrow();
                assertNull(variable.get("value"));
                assertEquals(
                        Map.of("name", "yca-task", "key", key),
                        map(variable.get("valueFrom")).get("secretKeyRef"));
            }
            for (final var secret : credentials.values()) {
                assertFalse(cluster.createdJob.contains(secret));
                assertFalse(cluster.calls.toString().contains(secret));
                assertFalse(Files.readString(attempt.resolve("execution.log")).contains(secret));
                assertFalse(
                        Files.readString(attempt.resolve("configuration.json")).contains(secret));
            }
            for (final var child : List.of("configuration.json", "storage", "workspace", "output")) {
                final var mount = ((List<?>) agent.get("volumeMounts"))
                        .stream()
                                .map(KubernetesExecutionBackendTest::map)
                                .filter(m -> ("/attempt/" + child).equals(m.get("mountPath")))
                                .findFirst()
                                .orElseThrow();
                assertEquals("attempt with spaces/" + child, mount.get("subPath"));
                assertEquals(child.equals("configuration.json"), mount.get("readOnly"));
            }
            assertFalse(cluster.createdJob.contains("hostPath"));
            assertFalse(cluster.createdJob.contains("docker.sock"));
            assertFalse(cluster.createdJob.contains("agent-secret"));
            assertFalse(cluster.createdJob.contains(home.toString()));
            assertTrue(cluster.secret.contains("agent-secret"));
            assertTrue(cluster.calls.indexOf("DELETE /apis/batch/v1/namespaces/sandbox/jobs/yca-task")
                    < cluster.calls.indexOf("DELETE /api/v1/namespaces/sandbox/secrets/yca-task"));
            assertTrue(cluster.secretDeleted);
            assertEquals("agent output\n", Files.readString(attempt.resolve("execution.log")));
            assertTrue(cluster.patch.contains("\"suspend\":true"));
            assertTrue(cluster.delete.contains("Foreground"));
            assertTrue(cluster.authorizations.stream().allMatch("Bearer cluster-secret"::equals));
        }
    }

    @Test
    void recoveryRetainsSecretWhenPodTerminationIsUncertain() throws Exception {
        try (final var cluster = new Cluster();
                final var json = json();
                final var client = cluster.client()) {
            cluster.job = "{}";
            cluster.running = true;
            assertThrows(
                    IllegalStateException.class, () -> backend(json, client).stop("yca-task"));
            assertTrue(cluster.calls.stream().anyMatch(c -> c.startsWith("PATCH ")));
            assertTrue(cluster.calls.stream().noneMatch(c -> c.startsWith("DELETE ")));
        }
    }

    @Test
    void deletionUncertaintyRetainsSecretAndExistingSecretsAreNotAdopted() throws Exception {
        final var attempt = attempt();
        try (final var cluster = new Cluster();
                final var json = json();
                final var client = cluster.client()) {
            cluster.secret = "existing";
            assertThrows(
                    IllegalStateException.class,
                    () -> backend(json, client).execute(attempt, "yca-task", "review", Map.of(), Map.of()));
            assertTrue(cluster.calls.stream().noneMatch(c -> c.startsWith("POST ")));
            cluster.job = "{}";
            cluster.retainJob = true;
            assertThrows(
                    IllegalStateException.class, () -> backend(json, client).stop("yca-task"));
            assertTrue(cluster.calls.stream().anyMatch(c -> c.startsWith("DELETE /apis/")));
            assertTrue(cluster.calls.stream().noneMatch(c -> c.startsWith("DELETE /api/")));
        }
    }

    @Test
    void mutationsAreNeverRetriedAndDoNotExposeResponseSecrets() throws Exception {
        final var attempt = attempt();
        try (final var cluster = new Cluster();
                final var json = json();
                final var client = cluster.client()) {
            cluster.failPost = true;
            final var failure = assertThrows(
                    IllegalStateException.class,
                    () -> backend(json, client).execute(attempt, "yca-task", "review", Map.of(), Map.of()));
            assertFalse(failure.toString().contains("response-secret"));
            assertNull(failure.getCause());
            assertEquals(
                    1, cluster.calls.stream().filter(c -> c.startsWith("POST ")).count());
        }
    }

    @Test
    void malformedOrOversizedResponsesFailClosed() throws Exception {
        for (final var response : List.of("{\"wrong\":[]}", "x".repeat(8 * 1024 * 1024 + 1))) {
            try (final var cluster = new Cluster();
                    final var json = json();
                    final var client = cluster.client()) {
                cluster.podResponse = response;
                assertThrows(
                        IllegalStateException.class, () -> backend(json, client).stop("yca-task"));
                assertTrue(cluster.calls.stream().noneMatch(c -> c.startsWith("DELETE ")));
            }
        }
    }

    @Test
    void boundsResponseBodyWaitEvenAfterHeadersArrive() throws Exception {
        try (final var cluster = new Cluster();
                final var json = json();
                final var client = cluster.client()) {
            cluster.slow = true;
            cluster.job = "{}";
            cluster.secret = "retain-me";
            final var backend = backend(json, client);
            assertTimeoutPreemptively(
                    Duration.ofSeconds(1),
                    () -> assertThrows(IllegalStateException.class, () -> backend.stop("yca-task")));
            assertEquals(0, cluster.bodyStarted.getCount());
            assertEquals(1, cluster.calls.size());
            assertFalse(cluster.secretDeleted);
        }
    }

    @Test
    void slowDripBodyUsesOneDeadlineIncludingHeaders() throws Exception {
        try (final var cluster = new Cluster();
                final var json = json();
                final var client = cluster.client()) {
            cluster.slow = true;
            cluster.drip = true;
            cluster.headerDelayMillis = 700;
            final var backend = backend(json, client, Duration.ofSeconds(1));
            assertTimeoutPreemptively(Duration.ofMillis(1500), () -> {
                final var failure = assertThrows(IllegalStateException.class, () -> backend.stop("yca-task"));
                assertNull(failure.getCause());
            });
            assertEquals(0, cluster.bodyStarted.getCount());
            assertTrue(cluster.calls.stream().noneMatch(c -> c.startsWith("DELETE ")));
        }
    }

    @Test
    void oversizedChunkedBodyIsRejectedWithoutWaitingForEof() throws Exception {
        try (final var cluster = new Cluster();
                final var json = json();
                final var client = cluster.client()) {
            cluster.oversizedChunked = true;
            cluster.job = "{}";
            cluster.secret = "retain-me";
            final var backend = backend(json, client, Duration.ofSeconds(5));
            assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
                final var failure = assertThrows(IllegalStateException.class, () -> backend.stop("yca-task"));
                assertNull(failure.getCause());
                assertFalse(failure.toString().contains("response-secret"));
            });
            assertEquals(1, cluster.calls.size());
            assertFalse(cluster.secretDeleted);
        }
    }

    @Test
    void acceptsCompleteBodyAtExactSizeLimit() throws Exception {
        final var attempt = attempt();
        try (final var cluster = new Cluster();
                final var json = json();
                final var client = cluster.client()) {
            cluster.logOutput = "x".repeat(8 * 1024 * 1024);
            assertEquals(
                    7,
                    backend(json, client, Duration.ofSeconds(5))
                            .execute(attempt, "yca-task", "review", Map.of(), Map.of()));
            assertEquals(cluster.logOutput, Files.readString(attempt.resolve("execution.log")));
            assertTrue(cluster.secretDeleted);
        }
    }

    @Test
    void interruptionCancelsStalledBodyAndPreservesInterruptAndResources() throws Exception {
        try (final var cluster = new Cluster();
                final var json = json();
                final var client = cluster.client()) {
            cluster.slow = true;
            cluster.job = "{}";
            cluster.secret = "retain-me";
            final var backend = backend(json, client, Duration.ofSeconds(10));
            final var failure = new AtomicReference<Throwable>();
            final var interrupted = new AtomicBoolean();
            final var caller = Thread.ofVirtual().start(() -> {
                try {
                    backend.stop("yca-task");
                } catch (final Throwable error) {
                    failure.set(error);
                    interrupted.set(Thread.currentThread().isInterrupted());
                }
            });
            try {
                assertTrue(cluster.bodyStarted.await(3, TimeUnit.SECONDS));
                caller.interrupt();
                caller.join(2000);
                assertFalse(caller.isAlive());
                assertInstanceOf(IllegalStateException.class, failure.get());
                assertEquals(
                        "Kubernetes interrupted; runtime state uncertain",
                        failure.get().getMessage());
                assertNull(failure.get().getCause());
                assertTrue(interrupted.get());
                assertFalse(cluster.secretDeleted);
                assertTrue(cluster.calls.stream().noneMatch(c -> c.startsWith("DELETE ")));
            } finally {
                caller.interrupt();
                cluster.bodyRelease.countDown();
                caller.join(2000);
            }
        }
    }

    @Test
    void responseReadsDoNotDependOnCommonPoolAvailability() throws Exception {
        final var parallelism = ForkJoinPool.getCommonPoolParallelism();
        Assumptions.assumeTrue(parallelism > 1 && parallelism <= 256);
        final var started = new CountDownLatch(parallelism);
        final var release = new CountDownLatch(1);
        final var blockers = new ArrayList<CompletableFuture<Void>>();
        try (final var cluster = new Cluster();
                final var json = json();
                final var client = cluster.client()) {
            final var backend = backend(json, client, Duration.ofSeconds(1));
            // Plain latch waits intentionally do not trigger ForkJoinPool managed-block compensation.
            for (int i = 0; i < parallelism; i++) {
                blockers.add(CompletableFuture.runAsync(() -> {
                    started.countDown();
                    try {
                        release.await();
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }));
            }
            assertTrue(started.await(3, TimeUnit.SECONDS));
            assertTimeoutPreemptively(Duration.ofSeconds(3), () -> backend.stop("yca-task"));
            assertTrue(cluster.secretDeleted);
        } finally {
            release.countDown();
            for (final var blocker : blockers) {
                blocker.get(3, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void rejectsExistingIdentityAndUnsafePathsWithoutMutation() throws Exception {
        final var attempt = attempt();
        try (final var cluster = new Cluster();
                final var json = json();
                final var client = cluster.client()) {
            final var backend = backend(json, client);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> backend.execute(home, "yca-task", "review", Map.of(), Map.of()));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> backend.execute(attempt, "yca-task", "review", Map.of("AWS_SECRET", "secret"), Map.of()));
            assertThrows(IllegalArgumentException.class, () -> backend.stop("--all"));
            cluster.job = "{}";
            assertThrows(
                    IllegalStateException.class,
                    () -> backend.execute(attempt, "yca-task", "review", Map.of(), Map.of()));
            Files.delete(attempt.resolve("configuration.json"));
            Files.createSymbolicLink(attempt.resolve("configuration.json"), home.resolve("elsewhere"));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> backend.execute(attempt, "yca-task", "review", Map.of(), Map.of()));
            assertTrue(cluster.calls.stream().noneMatch(c -> c.startsWith("POST ")));
        }
    }

    private Path attempt() throws Exception {
        final var attempt = Files.createDirectories(home.resolve("shared/attempt with spaces"));
        Files.writeString(attempt.resolve("configuration.json"), "{}");
        for (final var child : List.of("storage", "workspace", "output")) {
            Files.createDirectory(attempt.resolve(child));
        }
        return attempt;
    }

    private KubernetesExecutionBackend backend(final JsonMapperImpl json, final KubernetesClient client)
            throws Exception {
        return backend(json, client, Duration.ofMillis(300));
    }

    private KubernetesExecutionBackend backend(
            final JsonMapperImpl json, final KubernetesClient client, final Duration commandTimeout) throws Exception {
        return backend(json, client, commandTimeout, "shown");
    }

    private KubernetesExecutionBackend backend(
            final JsonMapperImpl json,
            final KubernetesClient client,
            final Duration commandTimeout,
            final String showToolOutput)
            throws Exception {
        Files.createDirectories(home.resolve("shared"));
        return new KubernetesExecutionBackend(
                json,
                client,
                "sandbox",
                "attempts",
                "trusted-image",
                home.resolve("shared"),
                1000,
                1000,
                Duration.ofSeconds(3),
                commandTimeout,
                "yca-agent",
                showToolOutput);
    }

    private JsonMapperImpl json() {
        return new JsonMapperImpl(
                List.of(
                        new KubernetesExecutionBackend$Job$FusionJsonCodec(),
                        new KubernetesExecutionBackend$Secret$FusionJsonCodec(),
                        new KubernetesExecutionBackend$PodList$FusionJsonCodec(),
                        new KubernetesExecutionBackend$JobSpec$FusionJsonCodec(),
                        new KubernetesExecutionBackend$PodTemplate$FusionJsonCodec(),
                        new KubernetesExecutionBackend$PodSpec$FusionJsonCodec(),
                        new KubernetesExecutionBackend$Container$FusionJsonCodec(),
                        new KubernetesExecutionBackend$Environment$FusionJsonCodec(),
                        new KubernetesExecutionBackend$EnvironmentSource$FusionJsonCodec(),
                        new KubernetesExecutionBackend$SecretKey$FusionJsonCodec(),
                        new KubernetesExecutionBackend$VolumeMount$FusionJsonCodec(),
                        new KubernetesExecutionBackend$Volume$FusionJsonCodec(),
                        new KubernetesExecutionBackend$PersistentVolumeClaim$FusionJsonCodec(),
                        new KubernetesExecutionBackend$EmptyDirectory$FusionJsonCodec(),
                        new KubernetesExecutionBackend$SecurityContext$FusionJsonCodec(),
                        new KubernetesExecutionBackend$Capabilities$FusionJsonCodec(),
                        new KubernetesExecutionBackend$SeccompProfile$FusionJsonCodec(),
                        new KubernetesExecutionBackend$Resources$FusionJsonCodec(),
                        new KubernetesExecutionBackend$Metadata$FusionJsonCodec(),
                        new KubernetesExecutionBackend$Pod$FusionJsonCodec(),
                        new KubernetesExecutionBackend$PodStatus$FusionJsonCodec(),
                        new KubernetesExecutionBackend$ContainerStatus$FusionJsonCodec(),
                        new KubernetesExecutionBackend$ContainerState$FusionJsonCodec(),
                        new KubernetesExecutionBackend$Terminated$FusionJsonCodec()),
                key -> Optional.empty());
    }

    private static Map<?, ?> map(final Object value) {
        return (Map<?, ?>) value;
    }

    private static final class Cluster implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        private final List<String> calls = new CopyOnWriteArrayList<>();
        private final List<String> authorizations = new CopyOnWriteArrayList<>();
        private volatile String job;
        private volatile String createdJob;
        private volatile String secret;
        private volatile boolean secretDeleted;
        private volatile String patch;
        private volatile String delete;
        private volatile String podResponse;
        private volatile String logOutput = "agent output\n";
        private volatile boolean running;
        private volatile boolean failPost;
        private volatile boolean deleted;
        private volatile boolean slow;
        private volatile boolean drip;
        private volatile boolean oversizedChunked;
        private volatile long headerDelayMillis;
        private final CountDownLatch bodyStarted = new CountDownLatch(1);
        private final CountDownLatch bodyRelease = new CountDownLatch(1);
        private volatile boolean retainJob;

        private Cluster() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/", exchange -> {
                try (exchange) {
                    final var path = exchange.getRequestURI().getPath();
                    final var method = exchange.getRequestMethod();
                    calls.add(method + ' ' + path);
                    authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
                    final var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    int status = 200;
                    String output = "{}";
                    if (oversizedChunked) {
                        exchange.sendResponseHeaders(200, 0);
                        final var chunk = "response-secret".repeat(1024).getBytes(StandardCharsets.UTF_8);
                        final var total = 8 * 1024 * 1024 + 1;
                        for (int sent = 0; sent < total; sent += chunk.length) {
                            exchange.getResponseBody().write(chunk, 0, Math.min(chunk.length, total - sent));
                        }
                        exchange.getResponseBody().flush();
                        try {
                            bodyRelease.await(10, TimeUnit.SECONDS);
                        } catch (final InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return;
                    }
                    if (slow) {
                        try {
                            Thread.sleep(headerDelayMillis);
                        } catch (final InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        exchange.sendResponseHeaders(200, 100);
                        exchange.getResponseBody().write('x');
                        exchange.getResponseBody().flush();
                        bodyStarted.countDown();
                        try {
                            if (drip) {
                                for (int i = 0; i < 99; i++) {
                                    Thread.sleep(20);
                                    exchange.getResponseBody().write('x');
                                    exchange.getResponseBody().flush();
                                }
                            } else {
                                bodyRelease.await(10, TimeUnit.SECONDS);
                            }
                        } catch (final InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return;
                    }
                    if (method.equals("POST")) {
                        if (failPost) {
                            status = 500;
                            output = "response-secret";
                        } else if (path.endsWith("/secrets")) {
                            secret = body;
                            status = 201;
                        } else {
                            job = body;
                            createdJob = body;
                            status = 201;
                        }
                    } else if (method.equals("PATCH")) {
                        patch = body;
                    } else if (method.equals("DELETE")) {
                        if (path.contains("/jobs/")) {
                            delete = body;
                            deleted = true;
                            if (!retainJob) {
                                job = null;
                            }
                        } else {
                            secretDeleted = true;
                        }
                    } else if (path.endsWith("/pods")) {
                        output = podResponse != null
                                ? podResponse
                                : deleted || job == null
                                        ? "{\"items\":[]}"
                                        : "{\"items\":[{\"metadata\":{\"name\":\"yca-pod\"},\"status\":{\"phase\":\""
                                                + (running ? "Running" : "Failed")
                                                + "\",\"containerStatuses\":[{\"state\":{\"terminated\":{\"exitCode\":7}}}]}}]}";
                    } else if (path.endsWith("/log")) {
                        output = logOutput;
                    } else if (path.contains("/jobs/")) {
                        status = job == null ? 404 : 200;
                    } else if (path.contains("/secrets/")) {
                        status = secret == null || secretDeleted ? 404 : 200;
                    }
                    final var bytes = output.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(status, bytes.length);
                    exchange.getResponseBody().write(bytes);
                }
            });
            server.start();
        }

        private KubernetesClient client() {
            return new KubernetesClient(new KubernetesClientConfiguration()
                    .setMaster("http://127.0.0.1:" + server.getAddress().getPort())
                    .setTokenEvaluator(() -> "cluster-secret"));
        }

        @Override
        public void close() {
            bodyRelease.countDown();
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
