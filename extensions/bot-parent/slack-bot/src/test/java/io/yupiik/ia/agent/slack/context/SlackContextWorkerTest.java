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
package io.yupiik.ia.agent.slack.context;

import static io.yupiik.ia.agent.slack.support.SlackDomainFixture.task;
import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.ia.agent.bot.runtime.admission.HostAdmission;
import io.yupiik.ia.agent.bot.runtime.admission.HostAdmissionConfiguration;
import io.yupiik.ia.agent.bot.runtime.configuration.ExecutorConfiguration;
import io.yupiik.ia.agent.bot.runtime.model.AgentConfigurationDocument;
import io.yupiik.ia.agent.slack.configuration.AgentSlackContextConfiguration;
import io.yupiik.ia.agent.slack.configuration.SlackContextConfiguration;
import io.yupiik.ia.agent.slack.context.agent.SlackContextClient;
import io.yupiik.ia.agent.slack.context.agent.SlackContextTool;
import io.yupiik.ia.agent.slack.model.TaskStatus;
import io.yupiik.ia.agent.slack.service.Sandbox;
import io.yupiik.ia.agent.slack.service.SlackFeedback;
import io.yupiik.ia.agent.slack.service.SlackPromptRenderer;
import io.yupiik.ia.agent.slack.service.SlackWorker;
import io.yupiik.ia.agent.slack.support.SlackDomainFixture;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SlackContextWorkerTest {
    private static final String ACTION_TOKEN = "action-context-test-secret";
    private static final String EVIDENCE = "The release train leaves on Friday";

    @TempDir
    Path root;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void modelResolvesAndSearchesAdvertisedContextBeforePublishingEvidence(final boolean ambiguous) throws Exception {
        final var config = configuration();
        try (final var fixture = new SlackDomainFixture(root, 4, 2);
                final var actions = new SlackActionRegistry(config, Clock.systemUTC());
                final var dispatcher = new SlackContextAttempt.Dispatcher(config);
                final var sandbox = new ContextModel(fixture, false, true)) {
            final var queued = task("context-answer", "U1", TaskStatus.queued, System.currentTimeMillis());
            fixture.add(queued);
            actions.admitted(queued, ACTION_TOKEN, System.currentTimeMillis());
            final var timestamp = (Instant.now().getEpochSecond() - 10) + ".000001";
            final var permalink = "https://example.slack.com/archives/C1/p" + timestamp.replace(".", "");
            fixture.response = exchange -> switch (exchange.request().uri().getPath()) {
                case "/api/users.info" -> {
                    assertEquals(Map.of("user", "U1"), fixture.json.fromString(Object.class, exchange.body()));
                    yield memberResponse();
                }
                case "/api/assistant.search.context" -> {
                    final var body = (Map<?, ?>) fixture.json.fromString(Object.class, exchange.body());
                    assertEquals(ACTION_TOKEN, body.get("action_token"));
                    assertEquals("in:<#C1> from:<@U1> \"release train\"", body.get("query"));
                    assertEquals("C1", body.get("context_channel_id"));
                    assertEquals(List.of("public_channel"), body.get("channel_types"));
                    yield fixture.json.toString(Map.of(
                            "ok",
                            true,
                            "results",
                            Map.of(
                                    "messages",
                                    List.of(Map.of(
                                            "author_user_id",
                                            "U1",
                                            "team_id",
                                            "T1",
                                            "channel_id",
                                            "C1",
                                            "message_ts",
                                            timestamp,
                                            "content",
                                            EVIDENCE,
                                            "permalink",
                                            permalink)))));
                }
                case "/api/chat.postMessage" -> {
                    assertTrue(sandbox.stopped.get(), "Stop must be confirmed before publishing");
                    assertTrue(Files.isDirectory(sandbox.directory));
                    assertTransientState(fixture, TaskStatus.publishing, permalink, true);
                    final var body = (Map<?, ?>) fixture.json.fromString(Object.class, exchange.body());
                    assertEquals("C1", body.get("channel"));
                    assertEquals("1.1", body.get("thread_ts"));
                    final var section = (Map<?, ?>) ((List<?>) body.get("blocks")).getFirst();
                    assertEquals(
                            Map.of("type", "plain_text", "text", "Ada: " + EVIDENCE + " " + permalink),
                            section.get("text"));
                    assertNoSecrets(exchange.body());
                    if (ambiguous) {
                        yield "{\"ok\":true,\"channel\":\"C1\"}";
                    }
                    yield "{\"ok\":true,\"channel\":\"C1\",\"ts\":\"2.1\"}";
                }
                default ->
                    throw new AssertionError(
                            "Unexpected Slack method: " + exchange.request().uri());
            };

            assertTrue(worker(fixture, sandbox, config, actions, dispatcher).runOne());
            // Sandbox assertion failures are caught by the worker: rethrow them on the test thread.
            sandbox.completed.get(2, TimeUnit.SECONDS);
            final var saved = fixture.store.list().getFirst();
            assertTransientState(fixture, ambiguous ? TaskStatus.recovery_blocked : TaskStatus.done, permalink, true);
            assertEquals(ambiguous ? null : "2.1", saved.messageTs());
            assertEquals(
                    List.of("/api/users.info", "/api/assistant.search.context", "/api/chat.postMessage"),
                    fixture.exchanges.stream()
                            .map(e -> e.request().uri().getPath())
                            .toList());
            assertFalse(Files.exists(sandbox.directory), "Successful attempts must be removed");
            assertNoSecrets(Files.readString(Path.of(fixture.configuration.agentConfiguration())));
        }
    }

    @Test
    void cancellationStillPollsWhileUpstreamHasNotReturnedItsFuture() throws Exception {
        final var config = configuration();
        final var upstreamEntered = new CountDownLatch(1);
        final var upstreamInterrupted = new CountDownLatch(1);
        final var releaseUpstream = new CountDownLatch(1);
        try (final var fixture = new SlackDomainFixture(root, 4, 2);
                final var actions = new SlackActionRegistry(config, Clock.systemUTC());
                final var dispatcher = new SlackContextAttempt.Dispatcher(config);
                final var sandbox = new ContextModel(fixture, true, true)) {
            final var queued = task("blocked-context", "U1", TaskStatus.queued, System.currentTimeMillis());
            fixture.add(queued);
            actions.admitted(queued, null, System.currentTimeMillis());
            fixture.response = exchange -> {
                assertEquals("/api/users.info", exchange.request().uri().getPath());
                upstreamEntered.countDown();
                try {
                    if (!releaseUpstream.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Test upstream safety deadline");
                    }
                } catch (final InterruptedException cancelled) {
                    upstreamInterrupted.countDown();
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Test upstream interrupted");
                }
                return memberResponse();
            };
            final var worker = worker(fixture, sandbox, config, actions, dispatcher);
            final var finished = new CompletableFuture<Boolean>();
            final var thread = Thread.ofVirtual().start(() -> {
                try {
                    finished.complete(worker.runOne());
                } catch (final Throwable failure) {
                    finished.completeExceptionally(failure);
                }
            });
            try {
                assertTrue(upstreamEntered.await(5, TimeUnit.SECONDS), "Model must reach the upstream call");
                fixture.store.transaction(tasks -> {
                    tasks.set(0, tasks.getFirst().transition(TaskStatus.cancel_requested, System.currentTimeMillis()));
                    return null;
                });
                assertTrue(
                        upstreamInterrupted.await(2, TimeUnit.SECONDS),
                        "Cancellation must interrupt dispatch even before HTTP returns its future");
                assertTrue(finished.get(2, TimeUnit.SECONDS));
                sandbox.completed.get(2, TimeUnit.SECONDS);
                assertEquals(
                        TaskStatus.cancelled, fixture.store.list().getFirst().status());
                assertTrue(sandbox.stopped.get());
                assertFalse(Files.exists(sandbox.directory));
                assertEquals(1, fixture.exchanges.size(), "Cancelled context must never publish an answer");
            } finally {
                // A regression must fail without retaining a blocked dispatcher/controller thread.
                releaseUpstream.countDown();
                thread.interrupt();
                thread.join(5000);
            }
        }
    }

    @Test
    void unknownStopRetainsPrivateAttemptWithoutPublishingOrPersistingAnswer() throws Exception {
        final var config = configuration();
        final var timestamp = (Instant.now().getEpochSecond() - 10) + ".000001";
        final var permalink = "https://example.slack.com/archives/C1/p" + timestamp.replace(".", "");
        try (final var fixture = new SlackDomainFixture(root, 4, 2);
                final var actions = new SlackActionRegistry(config, Clock.systemUTC());
                final var dispatcher = new SlackContextAttempt.Dispatcher(config);
                final var sandbox = new ContextModel(fixture, false, false)) {
            final var queued = task("unknown-stop-context", "U1", TaskStatus.queued, System.currentTimeMillis());
            fixture.add(queued);
            actions.admitted(queued, ACTION_TOKEN, System.currentTimeMillis());
            fixture.response = exchange -> switch (exchange.request().uri().getPath()) {
                case "/api/users.info" -> memberResponse();
                case "/api/assistant.search.context" ->
                    fixture.json.toString(Map.of(
                            "ok",
                            true,
                            "results",
                            Map.of(
                                    "messages",
                                    List.of(Map.of(
                                            "author_user_id",
                                            "U1",
                                            "team_id",
                                            "T1",
                                            "channel_id",
                                            "C1",
                                            "message_ts",
                                            timestamp,
                                            "content",
                                            EVIDENCE,
                                            "permalink",
                                            permalink)))));
                default -> throw new AssertionError("Unconfirmed runtime must not publish");
            };
            assertTrue(worker(fixture, sandbox, config, actions, dispatcher).runOne());
            sandbox.completed.get(2, TimeUnit.SECONDS);
            assertTransientState(fixture, TaskStatus.recovery_blocked, permalink, false);
            final var saved = fixture.store.list().getFirst();
            assertEquals(sandbox.directory.toString(), saved.attemptPath());
            assertNotNull(saved.runtimeId());
            assertTrue(Files.isDirectory(sandbox.directory), "Unconfirmed runtime retains its private RAM path");
            assertFalse(Files.exists(sandbox.directory.resolve("feedback")));
            assertFalse(sandbox.stopped.get());
            assertEquals(2, fixture.exchanges.size());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void queuedWithoutLiveGrantDoesNotAdvertiseOrInjectContext(final boolean restarted) throws Exception {
        final var config = configuration();
        try (final var fixture = new SlackDomainFixture(root, 4, 2);
                final var actions = new SlackActionRegistry(config, Clock.systemUTC());
                final var dispatcher = new SlackContextAttempt.Dispatcher(config)) {
            final var queued = task("no-live-grant", "U1", TaskStatus.queued, System.currentTimeMillis());
            fixture.add(queued);
            if (restarted) {
                try (final var previousProcess = new SlackActionRegistry(config, Clock.systemUTC())) {
                    previousProcess.admitted(queued, ACTION_TOKEN, System.currentTimeMillis());
                }
            }
            final var executed = new CompletableFuture<Void>();
            final var sandbox = new Sandbox() {
                @Override
                public int execute(
                        final Path attempt, final String runtime, final String prompt, final Path temporaryDirectory) {
                    try {
                        assertFalse(prompt.contains("slack_context"));
                        final var document = fixture.json.fromString(
                                AgentConfigurationDocument.class,
                                Files.readString(attempt.resolve("configuration.json")));
                        assertNull(document.configuration().get("slackContext"));
                        assertFalse(Files.exists(attempt.resolve("storage/slack-context")));
                        assertFalse(fixture.store.list().getFirst().contextBearing());
                        assertNoSecrets(prompt);
                        Files.writeString(
                                attempt.resolve("output/result.json"),
                                fixture.json.toString(
                                        Map.of("taskId", queued.id(), "summary", "Ordinary answer without context")));
                        executed.complete(null);
                        return 0;
                    } catch (final Throwable failure) {
                        executed.completeExceptionally(failure);
                        throw new IllegalStateException(failure);
                    }
                }

                @Override
                public void stop(final String runtime) {}

                @Override
                public void close() {}
            };
            fixture.response = exchange -> {
                assertEquals("/api/chat.postMessage", exchange.request().uri().getPath());
                return "{\"ok\":true,\"channel\":\"C1\",\"ts\":\"2.1\"}";
            };
            assertTrue(worker(fixture, sandbox, config, actions, dispatcher).runOne());
            executed.get(2, TimeUnit.SECONDS);
            final var saved = fixture.store.list().getFirst();
            assertEquals(TaskStatus.done, saved.status());
            assertFalse(saved.contextBearing());
            assertNull(saved.publicationId());
            assertEquals("Ordinary answer without context", saved.answer());
            assertEquals(1, fixture.exchanges.size());
        }
    }

    private SlackWorker worker(
            final SlackDomainFixture fixture,
            final Sandbox sandbox,
            final SlackContextConfiguration config,
            final SlackActionRegistry actions,
            final SlackContextAttempt.Dispatcher dispatcher)
            throws IOException {
        final var defaults = fixture.executor(root);
        final var attemptRoot = Files.createDirectory(
                        Path.of(defaults.attemptRoot()),
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
                .toRealPath();
        final var services = new ExecutorConfiguration.Docker.Services(
                "test-services:local",
                "test-control",
                root.resolve("syntactic-only.p12").toString(),
                "YCA_SERVICES_KEYSTORE_PASSWORD",
                "attempt-services.yca.internal",
                "http://192.168.1.2:8080/v1",
                new ExecutorConfiguration.Docker.Egress("test-services:local", List.of(), "127.0.0.1", false));
        final var executor = new ExecutorConfiguration(
                defaults.backend(),
                attemptRoot.toString(),
                defaults.timeoutSeconds(),
                defaults.commandTimeoutSeconds(),
                defaults.showToolOutput(),
                new ExecutorConfiguration.Docker("docker", "test-agent:local", "", null, services, true),
                defaults.nono(),
                defaults.kubernetes(),
                defaults.relay());
        final var storage = new SlackContextStorage() {
            @Override
            protected List<String> mountInfo() {
                return List.of("41 1 0:41 / " + attemptRoot + " rw,nosuid,nodev,noexec - tmpfs tmpfs rw,noswap");
            }

            @Override
            protected String operatingSystem() {
                return "Linux";
            }
        };
        return new SlackWorker(
                fixture.configuration,
                executor,
                fixture.json,
                fixture.store,
                new SlackPromptRenderer(SlackDomainFixture.prompts(), fixture.json),
                sandbox,
                new SlackFeedback(fixture.configuration, SlackDomainFixture.prompts(), fixture.store, fixture.api),
                fixture.api,
                new HostAdmission(new HostAdmissionConfiguration("", 1, "")),
                config,
                actions,
                dispatcher,
                storage);
    }

    private static void assertTransientState(
            final SlackDomainFixture fixture,
            final TaskStatus status,
            final String permalink,
            final boolean published) {
        final var saved = fixture.store.list().getFirst();
        assertEquals(status, saved.status());
        assertTrue(saved.contextBearing());
        assertNull(saved.answer());
        assertNull(saved.question());
        if (published) {
            assertEquals(saved.runtimeId() + "-answer", saved.publicationId());
        } else {
            assertNull(saved.publicationId());
        }
        final var state = assertDoesNotThrow(
                () -> Files.readString(fixture.configuration.stateRoot().resolve("state.json")));
        assertNoSecrets(state);
        for (final var text : List.of(EVIDENCE, "Ada", "Ada Lovelace", permalink)) {
            assertFalse(state.contains(text), "Context text must not reach durable queue state: " + text);
        }
    }

    private static SlackContextConfiguration configuration() {
        return new SlackContextConfiguration(true, true, true, List.of("C1"), 30, 32, 12, 20, 131072, 20, 2, 600);
    }

    private static String memberResponse() {
        return """
                {"ok":true,"user":{"id":"U1","team_id":"T1","deleted":false,"is_bot":false,
                "name":"ada","profile":{"display_name":"Ada","real_name":"Ada Lovelace"}}}
                """;
    }

    private static void assertNoSecrets(final String value) {
        for (final var secret : List.of(ACTION_TOKEN, "xoxb-test", "xapp-test")) {
            assertFalse(value.contains(secret), "Controller credentials must not reach the agent or answer");
        }
    }

    /** Controlled model: chooses tool steps from the advertised capability, not host-side prelookup. */
    private static final class ContextModel implements Sandbox {
        private final SlackDomainFixture fixture;
        private final boolean cancellation;
        private final boolean stopConfirmed;
        private final AtomicBoolean stopped = new AtomicBoolean();
        private final CompletableFuture<Void> completed = new CompletableFuture<>();
        private volatile Path directory;

        private ContextModel(
                final SlackDomainFixture fixture, final boolean cancellation, final boolean stopConfirmed) {
            this.fixture = fixture;
            this.cancellation = cancellation;
            this.stopConfirmed = stopConfirmed;
        }

        @Override
        public int execute(
                final Path attempt, final String runtime, final String prompt, final Path temporaryDirectory) {
            directory = attempt;
            try {
                final var durable = fixture.store.list().getFirst();
                assertEquals(TaskStatus.running, durable.status());
                assertEquals(attempt.toString(), durable.attemptPath());
                assertEquals(runtime, durable.runtimeId());
                assertTrue(durable.contextBearing());
                assertFalse(Files.exists(attempt.resolve("feedback")));
                assertFalse(Files.exists(attempt.resolve(".prepared-only")));
                assertTrue(prompt.contains("slack_context"));
                assertEquals(attempt.resolve("tmp"), temporaryDirectory);
                assertTrue(Files.isDirectory(temporaryDirectory));
                assertTrue(prompt.contains("untrusted"));
                assertTrue(prompt.contains("Cite returned Slack links"));
                assertNoSecrets(prompt);
                final var configuration = Files.readString(attempt.resolve("configuration.json"));
                assertNoSecrets(configuration);
                final var document = fixture.json.fromString(AgentConfigurationDocument.class, configuration);
                final var context = (Map<?, ?>) document.configuration().get("slackContext");
                assertEquals(true, context.get("enabled"));
                assertEquals("/attempt/storage/slack-context", context.get("directory"));
                final var mailboxPath = attempt.resolve("storage/slack-context");
                assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(mailboxPath));
                {
                    // Translate only the sandbox mount path; keep the real agent tool/client/mailbox protocol.
                    final var tool = new SlackContextTool(new SlackContextClient(
                            new AgentSlackContextConfiguration(
                                    true, mailboxPath.toString(), (String) context.get("channel"), 15),
                            fixture.json));
                    assertTrue(tool.usable());
                    final var resolved = tool.execute(Map.of("operation", "resolve_user", "userId", "U1"));
                    if (cancellation) {
                        assertTrue(resolved.error());
                        completed.complete(null);
                        return 1;
                    }
                    assertFalse(resolved.error(), resolved.output());
                    final var name = (Map<?, ?>) fixture.json.fromString(Object.class, resolved.output());
                    assertFalse(cancellation, "Cancelled tool call must not complete normally");
                    assertEquals("ok", name.get("status"));
                    assertEquals("Ada", name.get("displayName"));
                    assertEquals(true, name.get("untrusted"));
                    final var searched = tool.execute(
                            Map.of("operation", "search", "authorUserId", "U1", "terms", List.of("release train")));
                    assertFalse(searched.error(), searched.output());
                    final var search = (Map<?, ?>) fixture.json.fromString(Object.class, searched.output());
                    assertEquals("ok", search.get("status"));
                    final var evidence = (Map<?, ?>) ((List<?>) search.get("messages")).getFirst();
                    assertEquals(EVIDENCE, evidence.get("content"));
                    assertEquals("U1", evidence.get("authorUserId"));
                    assertEquals(true, evidence.get("untrusted"));
                    assertNoSecrets(fixture.json.toString(name));
                    assertNoSecrets(fixture.json.toString(search));
                    assertFalse(Files.exists(mailboxPath.resolve("request.json")));
                    assertFalse(Files.exists(mailboxPath.resolve("response.json")));
                    final var answer =
                            name.get("displayName") + ": " + evidence.get("content") + " " + evidence.get("permalink");
                    Files.writeString(
                            attempt.resolve("output/result.json"),
                            fixture.json.toString(Map.of("taskId", durable.id(), "summary", answer)));
                }
                completed.complete(null);
                return 0;
            } catch (final Throwable failure) {
                if (cancellation
                        && failure instanceof IOException
                        && Thread.currentThread().isInterrupted()) {
                    completed.complete(null);
                    return 1;
                }
                completed.completeExceptionally(failure);
                throw new IllegalStateException("Controlled context model failed", failure);
            }
        }

        @Override
        public void stop(final String runtime) {
            if (!stopConfirmed) {
                throw new IllegalStateException("Test cannot confirm runtime termination");
            }
            stopped.set(true);
        }

        @Override
        public void close() {}
    }
}
