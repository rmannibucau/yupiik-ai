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
package io.yupiik.ia.agent.slack.service;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.bot.feedback.FeedbackMailbox;
import io.yupiik.ia.agent.bot.runtime.AttemptPreparation;
import io.yupiik.ia.agent.bot.runtime.ResultReader;
import io.yupiik.ia.agent.bot.runtime.admission.HostAdmission;
import io.yupiik.ia.agent.github.configuration.ExecutorConfiguration;
import io.yupiik.ia.agent.slack.configuration.SlackConfiguration;
import io.yupiik.ia.agent.slack.model.SlackTask;
import io.yupiik.ia.agent.slack.store.SlackStore;
import io.yupiik.ia.agent.slack.transport.SlackWebApi;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.UnaryOperator;

/** Domain worker. Every runtime intent precedes launch; termination confirmation precedes results or cleanup. */
public final class SlackWorker {
    private final SlackConfiguration configuration;
    private final ExecutorConfiguration executor;
    private final JsonMapper json;
    private final SlackStore store;
    private final SlackPromptRenderer prompts;
    private final Sandbox sandbox;
    private final SlackFeedback feedback;
    private final SlackWebApi api;
    private final String fingerprint;
    private final HostAdmission admission;

    public SlackWorker(
            final SlackConfiguration configuration,
            final ExecutorConfiguration executor,
            final JsonMapper json,
            final SlackStore store,
            final SlackPromptRenderer prompts,
            final Sandbox sandbox,
            final SlackFeedback feedback,
            final SlackWebApi api,
            final HostAdmission admission) {
        this.configuration = configuration;
        this.executor = executor;
        this.json = json;
        this.store = store;
        this.prompts = prompts;
        this.sandbox = sandbox;
        this.feedback = feedback;
        this.api = api;
        this.admission = admission;
        fingerprint = SlackStore.identity("slack-runtime-v1:" + executor
                + (sandbox.recoveryIdentity().isBlank() ? "" : ':' + sandbox.recoveryIdentity()));
    }

    public void recover() {
        for (final var task : store.list()) {
            if (task.terminal() || "queued".equals(task.status())) {
                continue;
            }
            if (task.runtimeId() == null) {
                update(task.id(), current -> current.transition("recovery_blocked", System.currentTimeMillis()));
                continue;
            }
            if (!validRuntime(task)) {
                update(task.id(), current -> current.transition("recovery_blocked", System.currentTimeMillis()));
                continue;
            }
            try {
                sandbox.stop(task.runtimeId());
                // Unknown publication outcomes and stale live question controls are intentionally not resumed.
                update(task.id(), current -> current.feedback(null, "recovery_blocked", System.currentTimeMillis()));
            } catch (final RuntimeException unknown) {
                update(task.id(), current -> current.transition("recovery_blocked", System.currentTimeMillis()));
            }
        }
    }

    public boolean runOne() {
        try {
            final var admitted = admission.tryAcquire();
            if (admitted.isEmpty()) {
                return false;
            }
            try (final var lease = admitted.orElseThrow()) {
                lease.confirmTermination(); // No launch is possible until execute marks this lease uncertain.
                return runAdmitted(lease);
            }
        } catch (final IOException failure) {
            throw new IllegalStateException("Shared host admission requires reconciliation", failure);
        }
    }

    private boolean runAdmitted(final HostAdmission.Lease lease) {
        final var claimed = store.transaction(tasks -> {
            if (tasks.stream().anyMatch(task -> "recovery_blocked".equals(task.status()))) {
                return null; // Unknown runtimes/publication state require operator reconciliation before new work.
            }
            for (int i = 0; i < tasks.size(); i++) {
                final var task = tasks.get(i);
                if ("queued".equals(task.status())
                        && tasks.stream()
                                .noneMatch(other -> !other.id().equals(task.id())
                                        && other.conversation().equals(task.conversation())
                                        && !other.terminal()
                                        && !"queued".equals(other.status()))) {
                    final var next = task.transition("preparing", System.currentTimeMillis());
                    tasks.set(i, next);
                    return next;
                }
            }
            return null;
        });
        if (claimed == null) {
            return false;
        }
        execute(claimed, lease);
        return true;
    }

    private void execute(final SlackTask claimed, final HostAdmission.Lease lease) {
        Path directory = null;
        String runtime = null;
        boolean terminated = false;
        boolean restoreInterrupt = Thread.interrupted();
        Thread executionThread = null;
        final var completion = new CompletableFuture<Integer>();
        try {
            final var root = Path.of(executor.attemptRoot()).toAbsolutePath().normalize();
            directory = new AttemptPreparation(json)
                    .prepare(Path.of(configuration.agentConfiguration()), root, "yca-slack-");
            Files.createDirectory(
                    directory.resolve("feedback"),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            Files.writeString(
                    directory.resolve(".feedback-timeout"),
                    Integer.toString(configuration.questionTimeoutSeconds()),
                    StandardOpenOption.CREATE_NEW);
            final var prompt = prompts.render(claimed, store.list());
            runtime = "yca-slack-" + claimed.id().substring(0, 32) + "-" + (claimed.attempt() + 1);
            final var durable = claimed.running(directory.toString(), runtime, fingerprint, System.currentTimeMillis());
            update(claimed.id(), current -> {
                if (!"preparing".equals(current.status())) {
                    throw new IllegalStateException("Task was cancelled during preparation");
                }
                return durable;
            });
            Files.delete(directory.resolve(".prepared-only"));
            final var attempt = directory;
            final var identity = runtime;
            // A borrowed worker slot is retained while the agent synchronously waits for ask_user.
            lease.markUncertain();
            executionThread = Thread.ofVirtual().name(identity).start(() -> {
                try {
                    completion.complete(sandbox.execute(attempt, identity, prompt));
                } catch (final Throwable failure) {
                    completion.completeExceptionally(failure);
                }
            });
            try (final var mailbox = new FeedbackMailbox(directory.resolve("feedback"), json)) {
                while (!completion.isDone()) {
                    final var current = find(claimed.id());
                    if ("cancel_requested".equals(current.status()) || "recovery_blocked".equals(current.status())) {
                        quiesce(runtime, executionThread, completion);
                        restoreInterrupt |= Thread.interrupted();
                        terminated = true;
                        if (!"recovery_blocked".equals(current.status())) {
                            update(claimed.id(), task -> task.feedback(null, "cancelled", System.currentTimeMillis()));
                        }
                        return;
                    }
                    feedback.poll(claimed.id(), mailbox);
                    try {
                        completion.get(25, TimeUnit.MILLISECONDS);
                    } catch (final TimeoutException waiting) {
                        // Poll bounded private feedback only; Slack transport never waits for the model.
                    }
                }
                if (completion.get() != 0) {
                    throw new IllegalStateException("Sandbox execution did not succeed");
                }
            }
            sandbox.stop(runtime);
            terminated = true;
            final var current = find(claimed.id());
            if ("cancel_requested".equals(current.status())) {
                update(claimed.id(), task -> task.feedback(null, "cancelled", System.currentTimeMillis()));
                return;
            }
            final var parsed = json.fromString(
                    Object.class,
                    ResultReader.read(
                            directory.resolve("output/result.json"), configuration.maxAnswerChars() * 6 + 1024));
            if (!(parsed instanceof Map<?, ?> result)
                    || !result.keySet().equals(Set.of("taskId", "summary"))
                    || !claimed.id().equals(result.get("taskId"))
                    || !(result.get("summary") instanceof String answer)
                    || answer.isBlank()
                    || answer.length() > configuration.maxAnswerChars()) {
                throw new IllegalStateException("Invalid bounded Slack result contract");
            }
            final var publish = store.transaction(tasks -> {
                for (int index = 0; index < tasks.size(); index++) {
                    final var task = tasks.get(index);
                    if (task.id().equals(claimed.id())) {
                        if ("cancel_requested".equals(task.status())) {
                            tasks.set(index, task.feedback(null, "cancelled", System.currentTimeMillis()));
                            return false;
                        }
                        if (!Set.of("running", "waiting_for_user").contains(task.status())) {
                            return false;
                        }
                        tasks.set(
                                index,
                                task.feedback(null, "running", System.currentTimeMillis())
                                        .publication(answer, null, "publishing", System.currentTimeMillis()));
                        return true;
                    }
                }
                throw new IllegalStateException("Unknown Slack task");
            });
            if (!publish) {
                return;
            }
            try {
                final var response = api.postMessage(Map.of(
                                "channel",
                                claimed.channel(),
                                "thread_ts",
                                claimed.thread(),
                                "text",
                                "Assistant response",
                                "unfurl_links",
                                false,
                                "unfurl_media",
                                false,
                                "blocks",
                                answerBlocks(answer)))
                        .toCompletableFuture()
                        .join();
                if (!(response.get("ts") instanceof String ts)
                        || !claimed.channel().equals(response.get("channel"))) {
                    throw new IllegalStateException("Invalid Slack publication receipt");
                }
                update(claimed.id(), task -> task.publication(answer, ts, "done", System.currentTimeMillis()));
            } catch (final RuntimeException ambiguous) {
                update(claimed.id(), task -> task.transition("recovery_blocked", System.currentTimeMillis()));
            }
        } catch (final Exception failure) {
            if (failure instanceof InterruptedException) {
                restoreInterrupt = true;
            }
            restoreInterrupt |= Thread.interrupted();
            if (runtime == null) {
                update(claimed.id(), task -> task.transition("failed", System.currentTimeMillis()));
            } else {
                try {
                    quiesce(runtime, executionThread, completion);
                    restoreInterrupt |= Thread.interrupted();
                    terminated = true;
                    update(
                            claimed.id(),
                            task -> task.question() != null
                                            && Set.of("publishing", "opening")
                                                    .contains(task.question().state())
                                    ? task.transition("recovery_blocked", System.currentTimeMillis())
                                    : task.feedback(null, "failed", System.currentTimeMillis()));
                } catch (final RuntimeException unknown) {
                    restoreInterrupt |= Thread.interrupted();
                    update(claimed.id(), task -> task.transition("recovery_blocked", System.currentTimeMillis()));
                }
            }
        } finally {
            restoreInterrupt |= Thread.interrupted();
            if (runtime == null || terminated) {
                lease.confirmTermination();
            }
            if (directory != null && (runtime == null || terminated)) {
                // Preserve blocked publication state/results for manual reconciliation.
                final var finalState = find(claimed.id());
                if (!"recovery_blocked".equals(finalState.status())) {
                    try {
                        AttemptPreparation.delete(directory);
                    } catch (final IOException ignored) {
                        // Retention cleanup can retry, but never touch an unconfirmed runtime.
                    }
                }
            }
            if (restoreInterrupt) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void quiesce(final String runtime, final Thread thread, final CompletableFuture<Integer> completion) {
        if (thread == null) {
            sandbox.stop(runtime);
            return;
        }
        boolean interrupted = Thread.interrupted();
        try {
            thread.interrupt();
            try {
                sandbox.stop(runtime);
            } catch (final RuntimeException notYetCreatedOrUnknown) {
                // A launcher may still be starting. Only the final stop after it exits is authoritative.
            }
            final var settled = completion.handle((result, failure) -> null);
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(executor.commandTimeoutSeconds() + 5L);
            while (!settled.isDone()) {
                try {
                    settled.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                } catch (final InterruptedException stopping) {
                    interrupted = true;
                } catch (final ExecutionException | TimeoutException unknown) {
                    throw new IllegalStateException("Sandbox launcher termination is unconfirmed");
                }
            }
            sandbox.stop(runtime);
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private List<Map<String, Object>> answerBlocks(final String answer) {
        final var blocks = new ArrayList<Map<String, Object>>();
        for (int index = 0; index < answer.length(); ) {
            int end = Math.min(index + 2900, answer.length());
            if (end < answer.length() && Character.isHighSurrogate(answer.charAt(end - 1))) {
                end--;
            }
            blocks.add(Map.of(
                    "type", "section", "text", Map.of("type", "plain_text", "text", answer.substring(index, end))));
            index = end;
        }
        return blocks;
    }

    private boolean validRuntime(final SlackTask task) {
        if (!fingerprint.equals(task.fingerprint())
                || task.attempt() < 1
                || task.attemptPath() == null
                || !task.runtimeId().equals("yca-slack-" + task.id().substring(0, 32) + "-" + task.attempt())) {
            return false;
        }
        try {
            final var path = Path.of(task.attemptPath()).toAbsolutePath().normalize();
            return path.getParent() != null
                    && path.getParent()
                            .equals(Path.of(executor.attemptRoot())
                                    .toAbsolutePath()
                                    .normalize())
                    && path.getFileName().toString().startsWith("yca-slack-");
        } catch (final InvalidPathException malformed) {
            return false;
        }
    }

    private SlackTask find(final String id) {
        return store.list().stream()
                .filter(task -> task.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private void update(final String id, final UnaryOperator<SlackTask> operation) {
        store.transaction(tasks -> {
            for (int i = 0; i < tasks.size(); i++) {
                if (tasks.get(i).id().equals(id)) {
                    tasks.set(i, operation.apply(tasks.get(i)));
                    return null;
                }
            }
            throw new IllegalStateException("Unknown Slack task");
        });
    }
}
