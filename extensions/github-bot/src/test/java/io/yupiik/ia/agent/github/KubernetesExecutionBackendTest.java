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

import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.json.internal.JsonMapperImpl;
import io.yupiik.fusion.kubernetes.client.KubernetesClient;
import io.yupiik.fusion.kubernetes.client.KubernetesClientConfiguration;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(15)
class KubernetesExecutionBackendTest {
    @TempDir
    Path home;

    @Test
    void createsHardenedJobAndSecretOverAuthenticatedHttp() throws Exception {
        final var attempt = attempt();
        try (final var cluster = new Cluster();
                final var json = json();
                final var client = cluster.client()) {
            assertEquals(
                    7,
                    backend(json, client)
                            .execute(attempt, "yca-task", "Review '$(never)'", Map.of("GH_TOKEN", "agent-secret")));
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
            final var security = map(agent.get("securityContext"));
            assertEquals(true, security.get("runAsNonRoot"));
            assertEquals(true, security.get("readOnlyRootFilesystem"));
            assertEquals(false, security.get("allowPrivilegeEscalation"));
            assertEquals(List.of("ALL"), map(security.get("capabilities")).get("drop"));
            assertEquals("RuntimeDefault", map(security.get("seccompProfile")).get("type"));
            assertTrue(map(agent.get("resources")).containsKey("limits"));
            assertTrue(((List<?>) agent.get("args")).contains("Review '$(never)'"));
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
                    () -> backend(json, client).execute(attempt, "yca-task", "review", Map.of()));
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
                    () -> backend(json, client).execute(attempt, "yca-task", "review", Map.of()));
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
            assertTimeoutPreemptively(
                    Duration.ofSeconds(3),
                    () -> assertThrows(
                            IllegalStateException.class,
                            () -> backend(json, client).stop("yca-task")));
        }
    }

    @Test
    void rejectsExistingIdentityAndUnsafePathsWithoutMutation() throws Exception {
        final var attempt = attempt();
        try (final var cluster = new Cluster();
                final var json = json();
                final var client = cluster.client()) {
            final var backend = backend(json, client);
            assertThrows(IllegalArgumentException.class, () -> backend.execute(home, "yca-task", "review", Map.of()));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> backend.execute(attempt, "yca-task", "review", Map.of("AWS_SECRET", "secret")));
            assertThrows(IllegalArgumentException.class, () -> backend.stop("--all"));
            cluster.job = "{}";
            assertThrows(IllegalStateException.class, () -> backend.execute(attempt, "yca-task", "review", Map.of()));
            Files.delete(attempt.resolve("configuration.json"));
            Files.createSymbolicLink(attempt.resolve("configuration.json"), home.resolve("elsewhere"));
            assertThrows(
                    IllegalArgumentException.class, () -> backend.execute(attempt, "yca-task", "review", Map.of()));
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
                Duration.ofMillis(300),
                "yca-agent");
    }

    private JsonMapperImpl json() {
        return new JsonMapperImpl(
                List.of(
                        new KubernetesExecutionBackend$Job$FusionJsonCodec(),
                        new KubernetesExecutionBackend$Secret$FusionJsonCodec(),
                        new KubernetesExecutionBackend$PodList$FusionJsonCodec()),
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
        private volatile boolean running;
        private volatile boolean failPost;
        private volatile boolean deleted;
        private volatile boolean slow;
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
                    if (slow) {
                        exchange.sendResponseHeaders(200, 100);
                        exchange.getResponseBody().write('x');
                        exchange.getResponseBody().flush();
                        try {
                            Thread.sleep(5000);
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
                        output = "agent output\n";
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
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
