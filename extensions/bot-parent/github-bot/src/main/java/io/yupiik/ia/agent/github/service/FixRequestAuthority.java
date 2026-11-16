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

import static io.yupiik.ia.agent.github.service.FixPublisher.branch;
import static io.yupiik.ia.agent.github.service.FixPublisher.prefix;
import static io.yupiik.ia.agent.github.service.FixPublisher.repository;
import static io.yupiik.ia.agent.github.service.FixPublisher.sha;
import static io.yupiik.ia.agent.github.service.FixPublisher.target;
import static io.yupiik.ia.agent.github.service.FixPublisher.targetMap;
import static io.yupiik.ia.agent.github.service.FixPublisher.text;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.github.client.GitHubClient;
import io.yupiik.ia.agent.github.configuration.FixConfiguration;
import io.yupiik.ia.agent.github.model.TaskDocument;
import io.yupiik.ia.agent.github.model.TaskKind;
import io.yupiik.ia.agent.github.service.FixPublisher.Target;
import io.yupiik.ia.agent.github.store.TaskStore;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Host-only request authority shared by trigger pinning and publication, never exposed to an agent. */
@ApplicationScoped
public class FixRequestAuthority {
    private final GitHubClient github;
    private final FixConfiguration configuration;

    public FixRequestAuthority(final GitHubClient github, final FixConfiguration configuration) {
        this.github = github;
        this.configuration = configuration;
    }

    /** Resolves an authorized host draft without requiring a stored task ID. No store lock is held. */
    public Map<String, Object> pinRequest(final TaskDocument draft) {
        validateTask(draft);
        if (draft.recovery() != null && draft.recovery().containsKey("fixTarget")) {
            final var pinned = target(TaskStore.map(draft.recovery().get("fixTarget")));
            verify(draft, pinned, true);
            return targetMap(pinned);
        }
        final var pr = pull(draft);
        final var head = TaskStore.map(pr.get("head"));
        final var headRepo = TaskStore.map(head.get("repo"));
        final var baseRepo = TaskStore.map(TaskStore.map(pr.get("base")).get("repo"));
        final var target = new Target(
                text(headRepo, "node_id"),
                repository(text(headRepo, "full_name")),
                branch(text(head, "ref")),
                sha(head.get("sha")),
                text(baseRepo, "node_id"),
                draft.repository(),
                draft.number(),
                text(pr, "node_id"),
                text(pr, "html_url"));
        verify(draft, target, true, pr);
        return targetMap(target);
    }

    void validateTask(final TaskDocument task) {
        if (task == null) {
            throw new IllegalArgumentException("Missing fix task");
        }
        if (task.kind() != TaskKind.fix
                || "discussion".equals(task.sourceType())
                || task.discussionId() != null
                || task.number() == null
                || task.number() <= 0) {
            throw new IllegalArgumentException("Fix requires a pull request task, never a discussion");
        }
        repository(task.repository());
        if (!configuration.enabled()) {
            throw new IllegalStateException("Fix publication is disabled");
        }
    }

    void policy(final String repository) {
        if (!configuration.enabled()
                || configuration.repositories() == null
                || !List.of(configuration.repositories().split(",", -1)).stream()
                        .map(String::strip)
                        .anyMatch(repository::equals)) {
            throw new IllegalStateException("Head repository is not explicitly allowed for fixes");
        }
    }

    private Map<String, Object> pull(final TaskDocument task) {
        final var pr = TaskStore.map(github.get("/repos/" + task.repository() + "/pulls/" + task.number()));
        if (!"open".equals(pr.get("state"))
                || Boolean.TRUE.equals(pr.get("merged"))
                || !(pr.get("number") instanceof Number number)
                || number.longValue() != task.number()) {
            throw new IllegalStateException("Fix requires the existing open pull request");
        }
        return pr;
    }

    String verify(final TaskDocument task, final Target target, final boolean exactHead) {
        policy(target.repository());
        return verify(task, target, exactHead, pull(task));
    }

    private String verify(
            final TaskDocument task, final Target target, final boolean exactHead, final Map<String, Object> pr) {
        if (!target.upstream().equals(task.repository())
                || target.number() != task.number()
                || !target.url().equals("https://github.com/" + task.repository() + "/pull/" + task.number())) {
            throw new IllegalStateException("Pinned upstream identity changed");
        }
        policy(target.repository());
        final var head = TaskStore.map(pr.get("head"));
        final var headRepo = TaskStore.map(head.get("repo"));
        final var baseRepo = TaskStore.map(TaskStore.map(pr.get("base")).get("repo"));
        if (!target.pullRequestId().equals(pr.get("node_id"))
                || !target.url().equals(pr.get("html_url"))
                || !target.repositoryId().equals(headRepo.get("node_id"))
                || !target.repository().equals(headRepo.get("full_name"))
                || !target.upstreamId().equals(baseRepo.get("node_id"))
                || !target.upstream().equals(baseRepo.get("full_name"))
                || !target.branch().equals(head.get("ref"))) {
            throw new IllegalStateException("PR no longer matches pinned fix binding");
        }
        final var repo = TaskStore.map(github.get(prefix(target)));
        if (!target.repositoryId().equals(repo.get("node_id"))
                || !target.repository().equals(repo.get("full_name"))
                || !Boolean.FALSE.equals(repo.get("archived"))
                || !Boolean.FALSE.equals(repo.get("disabled"))
                || !Boolean.TRUE.equals(TaskStore.map(repo.get("permissions")).get("push"))) {
            throw new IllegalStateException("Fix head repository is unavailable or lacks push permission");
        }
        final var upstream = TaskStore.map(github.get("/repos/" + target.upstream()));
        if (!target.upstreamId().equals(upstream.get("node_id"))
                || !target.upstream().equals(upstream.get("full_name"))
                || !Boolean.FALSE.equals(upstream.get("archived"))
                || !Boolean.FALSE.equals(upstream.get("disabled"))) {
            throw new IllegalStateException("Pinned upstream repository is unavailable");
        }
        final var ref = TaskStore.map(github.get(prefix(target) + "/git/ref/heads/" + encode(target.branch())));
        final var headSha = sha(TaskStore.map(ref.get("object")).get("sha"));
        if (!Objects.equals(ref.get("ref"), "refs/heads/" + target.branch())
                || !headSha.equals(sha(head.get("sha")))
                || (exactHead && !target.headSha().equals(headSha))) {
            throw new IllegalStateException("PR head moved; fix will not rebase or overwrite it");
        }
        return headSha;
    }

    private static String encode(final String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
