/*
 * Copyright (c) 2026 - present - Yupiik SAS - https://www.yupiik.com
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
package io.yupiik.fusion.a2a.protocol;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.logging.Level.FINE;
import static java.util.logging.Level.SEVERE;

import io.yupiik.fusion.a2a.configuration.A2AConfiguration;
import io.yupiik.fusion.a2a.model.CancelTaskRequest;
import io.yupiik.fusion.a2a.model.HttpErrorResponse;
import io.yupiik.fusion.a2a.model.SendMessageRequest;
import io.yupiik.fusion.a2a.model.TaskPushNotificationConfig;
import io.yupiik.fusion.a2a.model.TaskState;
import io.yupiik.fusion.a2a.service.A2AService;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.http.HttpMatcher;
import io.yupiik.fusion.http.server.api.Request;
import io.yupiik.fusion.http.server.api.Response;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.jsonrpc.JsonRpcException;
import java.net.URLDecoder;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.logging.Logger;

/**
 * The HTTP+JSON binding of A2A - section 11 of the specification - served under {@code /a2a/v1}:
 * <table>
 *     <caption>Routes</caption>
 *     <tr><td>{@code POST /message:send}</td><td>{@code SendMessage}</td></tr>
 *     <tr><td>{@code POST /message:stream}</td><td>{@code SendStreamingMessage}</td></tr>
 *     <tr><td>{@code GET /tasks}</td><td>{@code ListTasks}</td></tr>
 *     <tr><td>{@code GET /tasks/{id}}</td><td>{@code GetTask}</td></tr>
 *     <tr><td>{@code POST /tasks/{id}:cancel}</td><td>{@code CancelTask}</td></tr>
 *     <tr><td>{@code POST /tasks/{id}:subscribe}</td><td>{@code SubscribeToTask}</td></tr>
 *     <tr><td>{@code POST /tasks/{id}/pushNotificationConfigs}</td><td>{@code CreateTaskPushNotificationConfig}</td></tr>
 *     <tr><td>{@code GET /tasks/{id}/pushNotificationConfigs}</td><td>{@code ListTaskPushNotificationConfigs}</td></tr>
 *     <tr><td>{@code GET /tasks/{id}/pushNotificationConfigs/{configId}}</td><td>{@code GetTaskPushNotificationConfig}</td></tr>
 *     <tr><td>{@code DELETE /tasks/{id}/pushNotificationConfigs/{configId}}</td><td>{@code DeleteTaskPushNotificationConfig}</td></tr>
 *     <tr><td>{@code GET /extendedAgentCard}</td><td>{@code GetExtendedAgentCard}</td></tr>
 * </table>
 * It is a thin adapter on {@link A2AService} - the very same object the JSON-RPC binding calls - so both bindings are
 * functionally equivalent by construction, which is what section 5.1 requires. Only the shapes differ: the parameters
 * come from the path and the query string, the errors are {@code google.rpc.Status} payloads and the streams carry bare
 * {@code StreamResponse} events with no JSON-RPC envelope.
 * <p>
 * Set {@code fusion.a2a.restBinding=false} to stop serving it, the routes then answer {@code 404}.
 *
 * @see A2AJSONRPCProtocol for the JSON-RPC binding.
 */
@ApplicationScoped
public class A2ARestProtocol {
    private final Logger logger = Logger.getLogger(A2ARestProtocol.class.getName());

    private final A2AService service;
    private final JsonMapper jsons;
    private final boolean enabled;

    protected A2ARestProtocol() {
        this(null, null, null);
    }

    public A2ARestProtocol(final A2AService service, final JsonMapper jsons, final A2AConfiguration configuration) {
        this.service = service;
        this.jsons = jsons;
        this.enabled = configuration == null || configuration.restBinding();
    }

    @HttpMatcher(path = A2AProtocol.REST_PATH, pathMatching = HttpMatcher.PathMatching.STARTS_WITH)
    public CompletionStage<Response> handle(final Request request) {
        if (!enabled) {
            return completedFuture(notFound());
        }
        final var version = A2AProtocol.version(request);
        if (!A2AProtocol.isSupported(version)) {
            return completedFuture(error(A2AErrors.versionNotSupported(A2AProtocol.resolve(version))));
        }
        request.setAttribute(A2AProtocol.EXTENSIONS_ATTRIBUTE, A2AEndpoint.extensions(request));

        try {
            return route(request, path(request));
        } catch (final JsonRpcException jre) {
            logger.log(FINE, jre, jre::getMessage);
            return completedFuture(error(jre));
        } catch (final RuntimeException re) {
            logger.log(SEVERE, re, re::getMessage);
            return completedFuture(error(
                    A2AErrors.error(A2AErrors.INTERNAL_ERROR, String.valueOf(re.getMessage()), "INTERNAL", Map.of())));
        }
    }

    private CompletionStage<Response> route(final Request request, final String path) {
        final var method = request.method().toUpperCase(Locale.ROOT);
        if ("POST".equals(method) && "/message:send".equals(path)) {
            return body(request, SendMessageRequest.class)
                    .thenCompose(payload -> service.sendMessage(payload, request))
                    .thenApply(this::json)
                    .exceptionally(this::onAsyncError);
        }
        if ("POST".equals(method) && "/message:stream".equals(path)) {
            return body(request, SendMessageRequest.class)
                    .thenApply(payload -> A2AEndpoint.sse(
                            request,
                            service.sendStreamingMessage(payload, request, sink -> A2AStream.plain(sink, jsons))))
                    .exceptionally(this::onAsyncError);
        }
        if ("GET".equals(method) && "/tasks".equals(path)) {
            return completedFuture(json(service.listTasks(
                    tenant(request),
                    request.parameter("contextId"),
                    taskState(request.parameter("status")),
                    integer(request, "pageSize"),
                    request.parameter("pageToken"),
                    integer(request, "historyLength"),
                    request.parameter("statusTimestampAfter"),
                    bool(request, "includeArtifacts"))));
        }
        if ("GET".equals(method) && "/extendedAgentCard".equals(path)) {
            return completedFuture(json(service.extendedAgentCard(tenant(request), request)));
        }

        final var task = taskRoute(path);
        if (task != null) {
            return routeTask(request, method, task);
        }
        return completedFuture(notFound());
    }

    private CompletionStage<Response> routeTask(final Request request, final String method, final TaskRoute route) {
        if (route.action() != null) {
            return switch (route.action()) {
                case "cancel" ->
                    "POST".equals(method)
                            ? body(request, CancelTaskRequest.class, new CancelTaskRequest(null, null, null))
                                    .thenApply(payload -> json(service.cancelTask(
                                            tenantOr(request, payload.tenant()), route.taskId(), payload.metadata())))
                                    .exceptionally(this::onAsyncError)
                            : completedFuture(notFound());
                case "subscribe" ->
                    "POST".equals(method)
                            ? completedFuture(A2AEndpoint.sse(
                                    request,
                                    service.subscribeToTask(
                                            tenant(request), route.taskId(), sink -> A2AStream.plain(sink, jsons))))
                            : completedFuture(notFound());
                default -> completedFuture(notFound());
            };
        }

        if (!route.pushConfigs()) { // GET /tasks/{id}
            return "GET".equals(method)
                    ? completedFuture(
                            json(service.getTask(tenant(request), route.taskId(), integer(request, "historyLength"))))
                    : completedFuture(notFound());
        }

        if (route.configId() == null) {
            return switch (method) {
                case "POST" ->
                    body(request, TaskPushNotificationConfig.class)
                            .thenApply(payload -> json(service.createPushConfig(payload, route.taskId())))
                            .exceptionally(this::onAsyncError);
                case "GET" ->
                    completedFuture(json(service.listPushConfigs(
                            tenant(request),
                            route.taskId(),
                            integer(request, "pageSize"),
                            request.parameter("pageToken"))));
                default -> completedFuture(notFound());
            };
        }
        return switch (method) {
            case "GET" ->
                completedFuture(json(service.getPushConfig(tenant(request), route.taskId(), route.configId())));
            case "DELETE" -> {
                service.deletePushConfig(tenant(request), route.taskId(), route.configId());
                yield completedFuture(json(Map.of()));
            }
            default -> completedFuture(notFound());
        };
    }

    /**
     * Parses {@code /tasks/{id}}, {@code /tasks/{id}:action} and the {@code pushNotificationConfigs} sub resources.
     *
     * @return the parsed route or {@code null} when {@code path} is none of them.
     */
    private static TaskRoute taskRoute(final String path) {
        if (!path.startsWith("/tasks/")) {
            return null;
        }
        final var segments = path.substring("/tasks/".length()).split("/", -1);
        if (segments[0].isEmpty()) { // "/tasks/" designates no task
            return null;
        }

        var id = decode(segments[0]);
        String action = null;
        final var colon = id.lastIndexOf(':');
        if (colon > 0) {
            action = id.substring(colon + 1);
            id = id.substring(0, colon);
        }
        if (action != null) {
            return segments.length == 1 ? new TaskRoute(id, action, false, null) : null;
        }
        return switch (segments.length) {
            case 1 -> new TaskRoute(id, null, false, null);
            case 2 -> "pushNotificationConfigs".equals(segments[1]) ? new TaskRoute(id, null, true, null) : null;
            case 3 ->
                "pushNotificationConfigs".equals(segments[1]) && !segments[2].isEmpty()
                        ? new TaskRoute(id, null, true, decode(segments[2]))
                        : null;
            default -> null;
        };
    }

    private <T> CompletionStage<T> body(final Request request, final Class<T> type) {
        if (!A2AProtocol.hasBody(request)) {
            throw A2AErrors.invalidParams("body", "A JSON body is required");
        }
        return request.fullBody().string().thenApply(payload -> {
            if (payload == null || payload.isBlank()) {
                throw A2AErrors.invalidParams("body", "A JSON body is required");
            }
            return read(type, payload);
        });
    }

    private <T> CompletionStage<T> body(final Request request, final Class<T> type, final T fallback) {
        if (!A2AProtocol.hasBody(request)) {
            return completedFuture(fallback);
        }
        return request.fullBody()
                .string()
                .thenApply(payload -> payload == null || payload.isBlank() ? fallback : read(type, payload));
    }

    private <T> T read(final Class<T> type, final String payload) {
        try {
            return jsons.fromString(type, payload);
        } catch (final RuntimeException re) {
            throw A2AErrors.error(A2AErrors.PARSE_ERROR, "Invalid JSON payload", "INVALID_ARGUMENT", Map.of());
        }
    }

    private Response onAsyncError(final Throwable error) {
        final var cause = A2AProtocol.unwrap(error);
        if (cause instanceof JsonRpcException jre) {
            logger.log(FINE, jre, jre::getMessage);
            return error(jre);
        }
        logger.log(SEVERE, cause, cause::getMessage);
        return error(
                A2AErrors.error(A2AErrors.INTERNAL_ERROR, String.valueOf(cause.getMessage()), "INTERNAL", Map.of()));
    }

    private Response json(final Object payload) {
        return Response.of()
                .status(200)
                .header("content-type", A2AProtocol.MEDIA_TYPE + ";charset=utf-8")
                .body(jsons.toString(payload))
                .build();
    }

    @SuppressWarnings("unchecked")
    private Response error(final JsonRpcException error) {
        final var status = A2AErrors.httpStatus(error.code());
        return Response.of()
                .status(status)
                .header("content-type", A2AProtocol.MEDIA_TYPE + ";charset=utf-8")
                .body(jsons.toString(HttpErrorResponse.of(
                        status,
                        A2AErrors.status(error.code()),
                        error.getMessage(),
                        error.data() instanceof List<?> details ? (List<Object>) details : List.of())))
                .build();
    }

    private Response notFound() {
        return Response.of()
                .status(404)
                .header("content-type", A2AProtocol.MEDIA_TYPE + ";charset=utf-8")
                .body(jsons.toString(HttpErrorResponse.of(404, "NOT_FOUND", "Unknown A2A route", List.of())))
                .build();
    }

    private static String path(final Request request) {
        final var path = request.path();
        final var index = path.indexOf(A2AProtocol.REST_PATH);
        final var relative = index < 0 ? path : path.substring(index + A2AProtocol.REST_PATH.length());
        return relative.isEmpty() ? "/" : relative;
    }

    private static String tenant(final Request request) {
        return request.parameter("tenant");
    }

    private static String tenantOr(final Request request, final String fromBody) {
        return fromBody != null && !fromBody.isBlank() ? fromBody : tenant(request);
    }

    private static TaskState taskState(final String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return TaskState.valueOf(status.strip());
        } catch (final IllegalArgumentException iae) {
            throw A2AErrors.invalidParams(
                    "status", "Unknown task state '" + status + "', it must be a TASK_STATE_* name");
        }
    }

    private static Integer integer(final Request request, final String name) {
        final var value = request.parameter(name);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value.strip());
        } catch (final NumberFormatException nfe) {
            throw A2AErrors.invalidParams(name, "It must be a number, got '" + value + '\'');
        }
    }

    private static Boolean bool(final Request request, final String name) {
        final var value = request.parameter(name);
        return value == null || value.isBlank() ? null : Boolean.valueOf(value.strip());
    }

    private static String decode(final String segment) {
        return URLDecoder.decode(segment, UTF_8);
    }

    /**
     * A parsed {@code /tasks/...} route.
     *
     * @param taskId      the task the route targets.
     * @param action      {@code cancel} or {@code subscribe} for a {@code /tasks/{id}:action} route, else {@code null}.
     * @param pushConfigs whether the route targets the push notification configurations of the task.
     * @param configId    the configuration the route targets, {@code null} for the collection itself.
     */
    private record TaskRoute(String taskId, String action, boolean pushConfigs, String configId) {}
}
