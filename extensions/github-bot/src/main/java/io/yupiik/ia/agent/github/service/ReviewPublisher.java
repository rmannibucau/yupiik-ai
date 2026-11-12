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
import io.yupiik.ia.agent.github.client.GitHubClient;
import io.yupiik.ia.agent.github.configuration.GithubBotConfiguration;
import io.yupiik.ia.agent.github.model.AgentResult;
import io.yupiik.ia.agent.github.model.TaskDocument;
import io.yupiik.ia.agent.github.store.TaskStore;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Native reviews are pinned to a head SHA and every proposed inline location is checked. */
@ApplicationScoped
public class ReviewPublisher {
    private static final Pattern HUNK = Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*");
    private final GitHubClient github;
    private final TaskStore store;
    private final GithubBotConfiguration configuration;

    public ReviewPublisher(
            final GitHubClient github, final TaskStore store, final GithubBotConfiguration configuration) {
        this.github = github;
        this.store = store;
        this.configuration = configuration;
    }

    public String publish(final TaskDocument taskDocument, final AgentResult agentResult) {
        final var task = taskDocument;
        final var id = String.valueOf(task.id());
        final var prefix = "/repos/" + task.repository() + "/pulls/" + task.number();
        final var marker = "<!-- yca-task:" + id + ":review -->";
        final var pr = TaskStore.map(github.get(prefix));
        final var sha = String.valueOf(TaskStore.map(pr.get("head")).get("sha"));
        final var intendedSha = store.transaction(state -> state.task(id).reviewIntentSha());
        final var reconciliationSha = intendedSha instanceof String persisted ? persisted : sha;
        String next = prefix + "/reviews?per_page=100";
        while (next != null) {
            final var page = github.page(next);
            for (final var item : (List<?>) page.body()) {
                final var review = TaskStore.map(item);
                if (String.valueOf(review.get("body")).contains(marker)
                        && reconciliationSha.equals(review.get("commit_id"))
                        && "COMMENTED".equals(review.get("state"))
                        && review.get("user") instanceof Map<?, ?>
                        && configuration
                                .login()
                                .equalsIgnoreCase(String.valueOf(
                                        TaskStore.map(review.get("user")).get("login")))) {
                    return String.valueOf(review.get("html_url"));
                }
            }
            next = page.next();
        }
        if (agentResult.review() == null) {
            throw new IllegalStateException("No current task-correlated native review was found");
        }
        if (store.<Boolean>transaction(state -> (state.task(id).reviewIntent() != null))) {
            throw new IllegalStateException("Ambiguous prior review publication; reconcile before another write");
        }
        final var review = agentResult.review();
        if (!sha.equals(review.headSha())) {
            throw new IllegalArgumentException("Review head changed; re-analysis is required");
        }
        final var valid = new HashSet<String>();
        next = prefix + "/files?per_page=100";
        int files = 0;
        while (next != null) {
            final var page = github.page(next);
            for (final var item : (List<?>) page.body()) {
                if (++files > 3000) {
                    throw new IllegalArgumentException("PR file listing exceeded supported limit");
                }
                final var file = TaskStore.map(item);
                if (file.get("patch") instanceof String patch) {
                    valid.addAll(locations(String.valueOf(file.get("filename")), patch));
                }
            }
            next = page.next();
        }
        final var comments = new ArrayList<GitHubClient.ReviewFinding>();
        final var proposed = review.comments() == null ? List.<AgentResult.Finding>of() : review.comments();
        if (proposed.size() > 30) {
            throw new IllegalArgumentException("At most 30 inline findings are supported per review");
        }
        for (final var item : proposed) {
            final var finding = item;
            final var path = finding.path();
            final var side = finding.side();
            final var line = finding.line();
            final var body = finding.body();
            if (line == null
                    || line.compareTo(BigDecimal.valueOf(line.intValue())) != 0
                    || !valid.contains(path + ":" + side + ":" + line.intValue())
                    || body == null
                    || body.isBlank()
                    || body.length() > 10000) {
                throw new IllegalArgumentException("Invalid inline finding location or body; nothing published");
            }
            // Inline findings are independently polled, so they need the same loop-prevention marker.
            comments.add(new GitHubClient.ReviewFinding(path, side, line.intValue(), marker + "\n" + body));
        }
        final var body = review.body() != null
                ? review.body()
                : agentResult.summary() != null ? agentResult.summary() : "Review completed.";
        if (body.length() > 30000) {
            throw new IllegalArgumentException("Review summary too large");
        }
        final var current = TaskStore.map(github.get(prefix));
        if (!sha.equals(TaskStore.map(current.get("head")).get("sha"))) {
            throw new IllegalArgumentException("Review head changed during validation");
        }
        store.transaction(state -> {
            state.task(id).reviewIntent(marker);
            state.task(id).reviewIntentSha(sha);
            return null;
        });
        final var posted = TaskStore.map(github.post(
                prefix + "/reviews", new GitHubClient.ReviewRequest(sha, "COMMENT", marker + "\n" + body, comments)));
        return String.valueOf(posted.get("html_url"));
    }

    public static Set<String> locations(final String path, final String patch) {
        final var result = new HashSet<String>();
        int left = -1;
        int right = -1;
        for (final var line : patch.split("\n", -1)) {
            final var hunk = HUNK.matcher(line);
            if (hunk.matches()) {
                left = Integer.parseInt(hunk.group(1));
                right = Integer.parseInt(hunk.group(3));
            } else if (left >= 0 && !line.isEmpty()) {
                switch (line.charAt(0)) {
                    case '-' -> result.add(path + ":LEFT:" + left++);
                    case '+' -> result.add(path + ":RIGHT:" + right++);
                    case ' ' -> {
                        result.add(path + ":LEFT:" + left++);
                        result.add(path + ":RIGHT:" + right++);
                    }
                    default -> {
                        /* no-newline marker or unsupported metadata */
                    }
                }
            }
        }
        return result;
    }
}
