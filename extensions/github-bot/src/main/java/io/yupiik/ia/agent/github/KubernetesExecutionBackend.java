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

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.kubernetes.client.KubernetesClient;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Fusion Kubernetes HTTP Job adapter. sharedRoot must be the dispatcher's mount of the named PVC's root,
 * not a host temporary directory. Attempts must already contain sanitized configuration.json,
 * storage, workspace and output, with configuration paths rooted at /attempt.
 * The trusted image's entrypoint must be the agent CLI. Requires a healthy, non-force-deleting
 * Kubernetes control plane/kubelet and exclusive ownership of yca-* names in the namespace.
 * No TTL is set: uncertain termination deliberately leaves resources for operator recovery.
 */
public final class KubernetesExecutionBackend {
    private static final Pattern KUBERNETES_NAME = Pattern.compile("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?");

    private static final int MAX_OUTPUT = 8 * 1024 * 1024;
    private final JsonMapper json;
    private final KubernetesClient client;
    private final String namespace;
    private final String claim;
    private final String image;
    private final Path sharedRoot;
    private final Duration timeout;
    private final Duration commandTimeout;
    private final long uid;
    private final long gid;
    private final String serviceAccount;

    public KubernetesExecutionBackend(
            final JsonMapper json,
            final KubernetesClient client,
            final String namespace,
            final String claim,
            final String image,
            final Path sharedRoot,
            final long uid,
            final long gid,
            final Duration timeout,
            final Duration commandTimeout,
            final String serviceAccount) {
        if (json == null
                || client == null
                || image.isBlank()
                || uid <= 0
                || gid <= 0
                || timeout.toSeconds() < 1
                || commandTimeout.isNegative()
                || commandTimeout.isZero()) {
            throw new IllegalArgumentException("Trusted runtime and positive budgets/uid/gid required");
        }
        validateName(namespace);
        validateName(claim);
        validateName(serviceAccount);
        this.serviceAccount = serviceAccount;
        this.json = json;
        this.client = client;
        this.sharedRoot = checked(sharedRoot, true);
        this.namespace = namespace;
        this.claim = claim;
        this.image = image;
        this.uid = uid;
        this.gid = gid;
        this.timeout = timeout;
        this.commandTimeout = commandTimeout;
    }

    public int execute(
            final Path directory, final String runtimeId, final String prompt, final Map<String, String> credentials) {
        validateRuntimeIdentity(runtimeId);
        if (!Set.of("GH_TOKEN", "GITHUB_BOT_FORK_TOKEN").containsAll(credentials.keySet())) {
            throw new IllegalArgumentException("Unsupported executor credential variable");
        }
        final var attempt = checked(directory, true);
        if (!attempt.startsWith(sharedRoot) || attempt.equals(sharedRoot)) {
            throw new IllegalArgumentException("Attempt must be a subtree of the shared PVC root");
        }
        checked(attempt.resolve("configuration.json"), false);
        for (final var child : List.of("storage", "workspace", "output")) {
            checked(attempt.resolve(child), true);
        }
        final var relative = sharedRoot.relativize(attempt).toString();
        // Names are single-use and controlled exclusively by this dispatcher.
        if (request("GET", jobs() + '/' + runtimeId, null, true).code() != 404
                || request("GET", secrets() + '/' + runtimeId, null, true).code() != 404) {
            throw new IllegalStateException("Runtime identity already exists; recover with stop first");
        }
        final var env = new ArrayList<Map<String, Object>>();
        env.add(Map.of("name", "HOME", "value", "/attempt/storage"));
        env.add(Map.of("name", "GH_CONFIG_DIR", "value", "/attempt/storage/gh"));
        for (final var key : credentials.keySet()) {
            env.add(Map.of("name", key, "valueFrom", Map.of("secretKeyRef", Map.of("name", runtimeId, "key", key))));
        }
        final var mounts = new ArrayList<Map<String, Object>>();
        for (final var child : List.of("configuration.json", "storage", "workspace", "output")) {
            mounts.add(Map.of(
                    "name",
                    "attempt",
                    "mountPath",
                    "/attempt/" + child,
                    "subPath",
                    relative + "/" + child,
                    "readOnly",
                    child.equals("configuration.json")));
        }
        mounts.add(Map.of("name", "tmp", "mountPath", "/tmp"));
        final var container = Map.of(
                "name",
                "agent",
                "image",
                image,
                "workingDir",
                "/attempt/workspace",
                "args",
                List.of(
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
                "env",
                env,
                "volumeMounts",
                mounts,
                "securityContext",
                Map.of(
                        "runAsNonRoot",
                        true,
                        "runAsUser",
                        uid,
                        "runAsGroup",
                        gid,
                        "readOnlyRootFilesystem",
                        true,
                        "allowPrivilegeEscalation",
                        false,
                        "capabilities",
                        Map.of("drop", List.of("ALL")),
                        "seccompProfile",
                        Map.of("type", "RuntimeDefault")),
                "resources",
                Map.of(
                        "requests",
                        Map.of("cpu", "1", "memory", "1Gi", "ephemeral-storage", "1Gi"),
                        "limits",
                        Map.of("cpu", "2", "memory", "4Gi", "ephemeral-storage", "2Gi")));
        final var pod = Map.of(
                "serviceAccountName",
                serviceAccount,
                "automountServiceAccountToken",
                false,
                "enableServiceLinks",
                false,
                "restartPolicy",
                "Never",
                "terminationGracePeriodSeconds",
                30,
                "containers",
                List.of(container),
                "volumes",
                List.of(
                        Map.of("name", "attempt", "persistentVolumeClaim", Map.of("claimName", claim)),
                        Map.of("name", "tmp", "emptyDir", Map.of("sizeLimit", "1Gi"))));
        final var job = new Job(
                "batch/v1",
                "Job",
                metadata(runtimeId),
                Map.of(
                        "backoffLimit",
                        0,
                        "parallelism",
                        1,
                        "completions",
                        1,
                        "activeDeadlineSeconds",
                        timeout.toSeconds(),
                        "template",
                        Map.of("metadata", Map.of("labels", Map.of("yca-runtime", runtimeId)), "spec", pod)));
        // create, never apply: an existing runtime must not be overwritten or adopted.
        // The Secret is sent over authenticated HTTP only; never staged on the shared PVC.
        request(
                "POST",
                secrets(),
                json.toString(new Secret("v1", "Secret", metadata(runtimeId), "Opaque", credentials)),
                false);
        try {
            request("POST", jobs(), json.toString(job), false);
            final long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                final var pods = pods(runtimeId);
                if (pods.size() == 1) {
                    final var status = map(pods.getFirst().get("status"));
                    if (Set.of("Succeeded", "Failed").contains(status.get("phase"))) {
                        final var statuses = list(status.get("containerStatuses"));
                        if (statuses.size() == 1) {
                            final var terminated =
                                    map(map(statuses.getFirst().get("state")).get("terminated"));
                            if (terminated.get("exitCode") instanceof Number code) {
                                final var podName = String.valueOf(
                                        map(pods.getFirst().get("metadata")).get("name"));
                                validateName(podName);
                                final var logs = request(
                                        "GET",
                                        core() + "/pods/" + podName + "/log?container=agent&limitBytes=" + MAX_OUTPUT,
                                        null,
                                        false);
                                Files.write(
                                        attempt.resolve("execution.log"),
                                        logs.output(),
                                        StandardOpenOption.CREATE_NEW,
                                        StandardOpenOption.WRITE);
                                return code.intValue();
                            }
                        }
                    }
                } else if (pods.size() > 1) {
                    throw new IllegalStateException("Ambiguous Job pod identity");
                }
                Thread.sleep(100);
            }
            throw new IllegalStateException("Execution timeout");
        } catch (final IOException e) {
            throw new IllegalStateException("Cannot retain executor log", e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Execution interrupted; stop must be retried", e);
        } finally {
            stop(runtimeId);
        }
    }

    public void stop(final String runtimeId) {
        validateRuntimeIdentity(runtimeId);
        if (request("GET", jobs() + '/' + runtimeId, null, true).code() != 404) {
            request("PATCH", jobs() + '/' + runtimeId, "{\"spec\":{\"suspend\":true}}", false);
        }
        final long deadline = System.nanoTime() + commandTimeout.toNanos();
        while (!pods(runtimeId).stream()
                .allMatch(p -> Set.of("Succeeded", "Failed")
                        .contains(map(p.get("status")).get("phase")))) {
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException("Pod termination uncertain; retaining Job and Secret");
            }
            pause();
        }
        request(
                "DELETE",
                jobs() + '/' + runtimeId,
                "{\"apiVersion\":\"v1\",\"kind\":\"DeleteOptions\",\"propagationPolicy\":\"Foreground\"}",
                true);
        final long deletionDeadline = System.nanoTime() + commandTimeout.toNanos();
        while (request("GET", jobs() + '/' + runtimeId, null, true).code() != 404
                || !pods(runtimeId).isEmpty()) {
            if (System.nanoTime() >= deletionDeadline) {
                throw new IllegalStateException("Job or Pods remain; retaining Secret");
            }
            pause();
        }
        request("DELETE", secrets() + '/' + runtimeId, null, true);
        final long secretDeadline = System.nanoTime() + commandTimeout.toNanos();
        while (request("GET", secrets() + '/' + runtimeId, null, true).code() != 404) {
            if (System.nanoTime() >= secretDeadline) {
                throw new IllegalStateException("Secret deletion uncertain; retry cleanup");
            }
            pause();
        }
    }

    private void pause() {
        try {
            Thread.sleep(100);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Termination interrupted; retaining resources");
        }
    }

    private List<Map<String, Object>> pods(final String id) {
        final var result = request("GET", core() + "/pods?labelSelector=yca-runtime%3D" + id, null, false);
        final var value = json.fromString(PodList.class, new String(result.output(), StandardCharsets.UTF_8));
        if (value == null || value.items() == null) {
            throw new IllegalStateException("Malformed pod response");
        }
        return value.items();
    }

    private Map<String, Object> metadata(final String id) {
        return Map.of("name", id, "namespace", namespace);
    }

    private String core() {
        return "/api/v1/namespaces/" + namespace;
    }

    private String secrets() {
        return core() + "/secrets";
    }

    private String jobs() {
        return "/apis/batch/v1/namespaces/" + namespace + "/jobs";
    }

    private Result request(final String method, final String path, final String input, final boolean allowMissing) {
        final var builder = HttpRequest.newBuilder(URI.create("https://kubernetes.api" + path))
                .timeout(commandTimeout)
                .header("Accept", "application/json")
                .header("Content-Type", "PATCH".equals(method) ? "application/merge-patch+json" : "application/json")
                .method(
                        method,
                        input == null
                                ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofString(input));
        // Bound both the body and the complete exchange, not just receipt of headers.
        final var pending = client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        final long deadline = System.nanoTime() + commandTimeout.toNanos();
        try {
            final var response = pending.get(commandTimeout.toNanos(), TimeUnit.NANOSECONDS);
            try (final var body = response.body()) {
                final var read = CompletableFuture.supplyAsync(() -> {
                    try {
                        return body.readNBytes(MAX_OUTPUT + 1);
                    } catch (final IOException e) {
                        throw new IllegalStateException("Cannot read Kubernetes response");
                    }
                });
                final byte[] bytes;
                try {
                    bytes = read.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                } finally {
                    read.cancel(true);
                }
                if (bytes.length > MAX_OUTPUT
                        || (!(response.statusCode() >= 200 && response.statusCode() < 300)
                                && !(allowMissing && response.statusCode() == 404))) {
                    throw new IllegalStateException("Kubernetes rejected operation; runtime state uncertain");
                }
                return new Result(response.statusCode(), bytes);
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Kubernetes interrupted; runtime state uncertain");
        } catch (final Exception e) {
            // Never expose response bodies, manifests or transport diagnostics containing credentials.
            throw new IllegalStateException("Kubernetes request failed; runtime state uncertain");
        } finally {
            pending.cancel(true);
        }
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
            throw new IllegalArgumentException("Missing runtime path");
        }
        return absolute;
    }

    private static void validateName(final String value) {
        if (value == null
                || value.length() > 63
                || !KUBERNETES_NAME.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid Kubernetes name");
        }
    }

    private static void validateRuntimeIdentity(final String value) {
        validateName(value);
        if (!value.startsWith("yca-")) {
            throw new IllegalArgumentException("Invalid execution identity");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(final Object value) {
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalStateException("Malformed runtime response");
        }
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(final Object value) {
        if (!(value instanceof List<?>)) {
            throw new IllegalStateException("Malformed runtime response");
        }
        return (List<Map<String, Object>>) value;
    }

    private record Result(int code, byte[] output) {}

    @JsonModel
    public record Job(
            @Property(documentation = "Kubernetes API version.")
            String apiVersion,

            @Property(documentation = "Kubernetes resource kind.")
            String kind,

            @Property(documentation = "Runtime resource identity.")
            Map<String, Object> metadata,

            @Property(documentation = "Bounded hardened Job specification.")
            Map<String, Object> spec) {}

    @JsonModel
    public record Secret(
            @Property(documentation = "Kubernetes API version.")
            String apiVersion,

            @Property(documentation = "Kubernetes resource kind.")
            String kind,

            @Property(documentation = "Runtime resource identity.")
            Map<String, Object> metadata,

            @Property(documentation = "Secret encoding type.")
            String type,

            @Property(documentation = "Agent-only credentials, never staged on shared storage.")
            Map<String, String> stringData) {}

    @JsonModel
    public record PodList(
            @Property(documentation = "Pods selected by the unique runtime label.")
            List<Map<String, Object>> items) {}
}
