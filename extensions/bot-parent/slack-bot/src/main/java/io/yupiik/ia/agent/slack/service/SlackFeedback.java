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

import io.yupiik.ia.agent.bot.feedback.FeedbackMailbox;
import io.yupiik.ia.agent.slack.configuration.SlackConfiguration;
import io.yupiik.ia.agent.slack.configuration.SlackPrompts;
import io.yupiik.ia.agent.slack.model.SlackQuestion;
import io.yupiik.ia.agent.slack.model.SlackQuestionRequest;
import io.yupiik.ia.agent.slack.model.SlackTask;
import io.yupiik.ia.agent.slack.store.SlackStore;
import io.yupiik.ia.agent.slack.transport.SlackWebApi;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

/** Controller-owned interaction authority. No Slack token or publication authority reaches the mailbox. */
public final class SlackFeedback {
    private final SlackConfiguration configuration;
    private final SlackPrompts presentation;
    private final SlackStore store;
    private final SlackWebApi api;

    public SlackFeedback(
            final SlackConfiguration configuration,
            final SlackPrompts presentation,
            final SlackStore store,
            final SlackWebApi api) {
        this.configuration = configuration;
        this.presentation = presentation;
        this.store = store;
        this.api = api;
    }

    /** Called only by the admitted worker while retaining its slot and pinned mailbox. */
    public void poll(final String taskId, final FeedbackMailbox mailbox) throws IOException {
        final var request = mailbox.pending();
        if (request == null) {
            return;
        }
        var task = find(taskId);
        if (task.question() != null && task.question().request().nonce().equals(request.nonce())) {
            final var question = task.question();
            if ("answered".equals(question.state())) {
                mailbox.answer(question.request().mailbox(), question.answers(), false);
            } else if ("cancelled".equals(question.state())
                    || System.currentTimeMillis() >= question.request().expires()) {
                mailbox.answer(question.request().mailbox(), Map.of(), true);
            }
            return;
        }
        if (request.expires() <= System.currentTimeMillis()
                || request.expires()
                        > System.currentTimeMillis() + configuration.questionTimeoutSeconds() * 1000L + 1000) {
            mailbox.answer(request, Map.of(), true);
            return;
        }
        final var pending = new SlackQuestion(SlackQuestionRequest.from(request), "publishing", null, null, null);
        final var publish = store.transaction(tasks -> {
            for (int index = 0; index < tasks.size(); index++) {
                final var current = tasks.get(index);
                if (current.id().equals(taskId)) {
                    if (!"running".equals(current.status()) || current.attempt() != task.attempt()) {
                        return false;
                    }
                    tasks.set(index, current.feedback(pending, "waiting_for_user", System.currentTimeMillis()));
                    return true;
                }
            }
            return false;
        });
        if (!publish) {
            return;
        }
        // Persist intent before sending; an ambiguous remote failure is never retried automatically.
        final var response = api.postMessage(Map.of(
                        "channel",
                        task.channel(),
                        "thread_ts",
                        task.thread(),
                        "text",
                        presentation.questionTitle(),
                        "blocks",
                        List.of(
                                Map.of("type", "section", "text", plain(presentation.questionTitle())),
                                Map.of(
                                        "type",
                                        "actions",
                                        "elements",
                                        List.of(
                                                button("answer", request.nonce(), presentation.answerLabel()),
                                                button("cancel", request.nonce(), presentation.cancelLabel()))))))
                .toCompletableFuture()
                .join();
        if (!(response.get("ts") instanceof String ts) || !task.channel().equals(response.get("channel"))) {
            throw new IllegalStateException("Invalid Slack question publication result");
        }
        update(taskId, current -> {
            if (current.question() == null
                    || !current.question().request().nonce().equals(request.nonce())) {
                return current;
            }
            final var cancelled = "cancel_requested".equals(current.status());
            if (!cancelled && !"waiting_for_user".equals(current.status())) {
                return current;
            }
            return current.feedback(
                    new SlackQuestion(
                            SlackQuestionRequest.from(request), cancelled ? "cancelled" : "waiting", ts, null, null),
                    current.status(),
                    System.currentTimeMillis());
        });
    }

    /** Executes only short durable authorization/submission work on the Socket Mode delivery path. */
    public Optional<Map<String, Object>> interaction(final Map<String, Object> payload) {
        final var team = object(payload.get("team"));
        final var actor = object(payload.get("user")).get("id");
        if (!configuration.workspace().equals(team.get("id"))
                || !(actor instanceof String user)
                || !configuration.users().contains(user)) {
            return Optional.empty();
        }
        if ("block_actions".equals(payload.get("type"))) {
            final var actions = payload.get("actions");
            if (!(actions instanceof List<?> list) || list.size() != 1) {
                return Optional.empty();
            }
            final var action = object(list.getFirst());
            final var nonce = action.get("value");
            final var task = active(nonce, user);
            if (task == null
                    || task.question().messageTs() == null
                    || !task.channel().equals(object(payload.get("channel")).get("id"))
                    || !task.question()
                            .messageTs()
                            .equals(object(payload.get("message")).get("ts"))) {
                return Optional.empty();
            }
            if ("cancel".equals(action.get("action_id"))
                    && List.of("waiting", "opening", "open")
                            .contains(task.question().state())) {
                update(
                        task.id(),
                        current -> current.question() != null
                                        && current.question()
                                                .request()
                                                .nonce()
                                                .equals(task.question()
                                                        .request()
                                                        .nonce())
                                        && List.of("waiting", "opening", "open")
                                                .contains(current.question().state())
                                ? current.feedback(
                                        new SlackQuestion(
                                                current.question().request(),
                                                "cancelled",
                                                current.question().messageTs(),
                                                current.question().viewId(),
                                                Map.of()),
                                        "cancel_requested",
                                        System.currentTimeMillis())
                                : current);
            } else if ("answer".equals(action.get("action_id"))
                    && payload.get("trigger_id") instanceof String trigger
                    && trigger.length() < 256) {
                // Discovery/publication scheduling is independent of this priority call. No synchronous network wait
                // before ACK.
                final var claimed = store.transaction(tasks -> {
                    for (int i = 0; i < tasks.size(); i++) {
                        final var current = tasks.get(i);
                        if (current.id().equals(task.id())
                                && "waiting".equals(current.question().state())) {
                            tasks.set(
                                    i,
                                    current.feedback(
                                            new SlackQuestion(
                                                    current.question().request(),
                                                    "opening",
                                                    current.question().messageTs(),
                                                    null,
                                                    null),
                                            "waiting_for_user",
                                            System.currentTimeMillis()));
                            return true;
                        }
                    }
                    return false;
                });
                if (claimed) {
                    api.openView(Map.of("trigger_id", trigger, "view", modal(task)))
                            .whenComplete((result, failure) -> {
                                if (failure == null
                                        && object(result.get("view")).get("id") instanceof String viewId) {
                                    update(
                                            task.id(),
                                            current -> "waiting_for_user".equals(current.status())
                                                            && "opening"
                                                                    .equals(current.question()
                                                                            .state())
                                                    ? current.feedback(
                                                            new SlackQuestion(
                                                                    current.question()
                                                                            .request(),
                                                                    "open",
                                                                    current.question()
                                                                            .messageTs(),
                                                                    viewId,
                                                                    null),
                                                            "waiting_for_user",
                                                            System.currentTimeMillis())
                                                    : current);
                                } else {
                                    // Unknown open outcome cannot safely reuse a trigger or bind an unknown modal.
                                    update(
                                            task.id(),
                                            current -> current.question() != null
                                                            && current.question()
                                                                    .request()
                                                                    .nonce()
                                                                    .equals(task.question()
                                                                            .request()
                                                                            .nonce())
                                                            && "waiting_for_user".equals(current.status())
                                                            && "opening"
                                                                    .equals(current.question()
                                                                            .state())
                                                    ? current.transition("recovery_blocked", System.currentTimeMillis())
                                                    : current);
                                }
                            });
                }
            }
        } else if ("view_submission".equals(payload.get("type")) || "view_closed".equals(payload.get("type"))) {
            final var view = object(payload.get("view"));
            if (!"yca_feedback".equals(view.get("callback_id"))) {
                return Optional.empty();
            }
            final var task = active(view.get("private_metadata"), user);
            if (task == null
                    || task.question().viewId() == null
                    || !task.question().viewId().equals(view.get("id"))) {
                return Optional.of(Map.of(
                        "response_action", "errors", "errors", Map.of("q0", "This question is no longer active.")));
            }
            if ("view_closed".equals(payload.get("type"))) {
                update(
                        task.id(),
                        current -> current.question() != null
                                        && current.question()
                                                .request()
                                                .nonce()
                                                .equals(task.question()
                                                        .request()
                                                        .nonce())
                                        && List.of("waiting", "opening", "open")
                                                .contains(current.question().state())
                                ? current.feedback(
                                        new SlackQuestion(
                                                current.question().request(),
                                                "cancelled",
                                                current.question().messageTs(),
                                                current.question().viewId(),
                                                Map.of()),
                                        "cancel_requested",
                                        System.currentTimeMillis())
                                : current);
                return Optional.empty();
            }
            try {
                final var answers = answers(
                        task.question().request().mailbox(),
                        object(object(view.get("state")).get("values")));
                FeedbackMailbox.validateAnswers(task.question().request().mailbox(), answers);
                update(task.id(), current -> {
                    if ("answered".equals(current.question().state())) {
                        return current;
                    }
                    if (!"open".equals(current.question().state())) {
                        throw new IllegalStateException("Question is no longer active");
                    }
                    return current.feedback(
                            new SlackQuestion(
                                    current.question().request(),
                                    "answered",
                                    current.question().messageTs(),
                                    current.question().viewId(),
                                    answers),
                            "running",
                            System.currentTimeMillis());
                });
            } catch (final IllegalArgumentException | IllegalStateException invalid) {
                return Optional.of(Map.of(
                        "response_action",
                        "errors",
                        "errors",
                        Map.of("q0", "Choose valid answers for every question.")));
            }
        }
        return Optional.empty();
    }

    private Map<String, Object> modal(final SlackTask task) {
        final var blocks = new ArrayList<Map<String, Object>>();
        final var questions = task.question().request().questions();
        for (int index = 0; index < questions.size(); index++) {
            final var question = questions.get(index);
            final Map<String, Object> element;
            if (question.options().isEmpty()) {
                element =
                        Map.of("type", "plain_text_input", "action_id", "value", "multiline", true, "max_length", 3000);
            } else {
                final var options = new ArrayList<Map<String, Object>>();
                for (int option = 0; option < question.options().size(); option++) {
                    options.add(Map.of("text", plain(question.options().get(option)), "value", "o" + option));
                }
                element = Map.of(
                        "type",
                        question.multiple() ? "multi_static_select" : "static_select",
                        "action_id",
                        "value",
                        "options",
                        options);
            }
            blocks.add(Map.of(
                    "type",
                    "input",
                    "block_id",
                    "q" + index,
                    "label",
                    plain(question.prompt()
                            .substring(0, Math.min(200, question.prompt().length()))),
                    "element",
                    element));
        }
        return Map.of(
                "type",
                "modal",
                "notify_on_close",
                true,
                "callback_id",
                "yca_feedback",
                "private_metadata",
                task.question().request().nonce(),
                "title",
                plain(presentation.questionTitle()),
                "submit",
                plain(presentation.answerLabel()),
                "close",
                plain(presentation.cancelLabel()),
                "blocks",
                blocks);
    }

    private Map<String, Object> answers(final FeedbackMailbox.Request request, final Map<?, ?> values) {
        if (values.size() != request.questions().size()) {
            throw new IllegalArgumentException("Unexpected question blocks");
        }
        final var result = new LinkedHashMap<String, Object>();
        for (int index = 0; index < request.questions().size(); index++) {
            final var question = request.questions().get(index);
            final var value = object(object(values.get("q" + index)).get("value"));
            if (question.options().isEmpty()) {
                result.put(question.id(), value.get("value"));
            } else if (question.multiple()) {
                if (!(value.get("selected_options") instanceof List<?> selected)) {
                    throw new IllegalArgumentException("Missing choices");
                }
                result.put(
                        question.id(),
                        selected.stream()
                                .map(option -> option(question, object(option).get("value")))
                                .toList());
            } else {
                result.put(
                        question.id(),
                        option(question, object(value.get("selected_option")).get("value")));
            }
        }
        return result;
    }

    private String option(final FeedbackMailbox.Item question, final Object value) {
        if (!(value instanceof String id) || !id.matches("o[0-9]{1,2}")) {
            throw new IllegalArgumentException("Invalid opaque choice");
        }
        final var index = Integer.parseInt(id.substring(1));
        if (index >= question.options().size()) {
            throw new IllegalArgumentException("Invalid choice index");
        }
        return question.options().get(index);
    }

    private SlackTask active(final Object nonce, final String actor) {
        return store.list().stream()
                .filter(task -> !task.terminal()
                        && !"recovery_blocked".equals(task.status())
                        && task.requester().equals(actor)
                        && task.question() != null
                        && List.of("waiting", "opening", "open", "answered")
                                .contains(task.question().state())
                        && task.question().request().nonce().equals(nonce)
                        && task.question().request().expires() > System.currentTimeMillis())
                .findFirst()
                .orElse(null);
    }

    private SlackTask find(final String id) {
        return store.list().stream()
                .filter(task -> task.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private void update(final String id, final UnaryOperator<SlackTask> change) {
        store.transaction(tasks -> {
            for (int i = 0; i < tasks.size(); i++) {
                if (tasks.get(i).id().equals(id)) {
                    tasks.set(i, change.apply(tasks.get(i)));
                    return null;
                }
            }
            throw new IllegalStateException("Unknown Slack task");
        });
    }

    private Map<?, ?> object(final Object value) {
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private Map<String, Object> plain(final String text) {
        return Map.of("type", "plain_text", "text", text);
    }

    private Map<String, Object> button(final String action, final String nonce, final String text) {
        return Map.of("type", "button", "action_id", action, "value", nonce, "text", plain(text));
    }
}
