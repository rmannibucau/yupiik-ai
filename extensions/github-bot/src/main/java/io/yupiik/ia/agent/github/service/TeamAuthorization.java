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
import io.yupiik.ia.agent.github.store.TaskStore;
import java.util.Arrays;
import java.util.Map;
import java.util.regex.Pattern;

/** Additional team restriction, never a substitute for the immutable-ID allowlist. */
@ApplicationScoped
public class TeamAuthorization {
    private static final Pattern TEAM_SLUG = Pattern.compile("[A-Za-z0-9-]+/[A-Za-z0-9_-]+");
    private static final Pattern ACTOR_ID = Pattern.compile("[0-9]+");
    private static final Pattern LOGIN = Pattern.compile("[A-Za-z0-9-]+");

    private final GitHubClient github;
    private final GithubBotConfiguration configuration;

    public TeamAuthorization(final GitHubClient github, final GithubBotConfiguration configuration) {
        this.github = github;
        this.configuration = configuration;
    }

    public boolean allows(final Object actorId, final String suppliedLogin) {
        final var teams = Arrays.stream(configuration.authorization().teams().split(","))
                .map(String::trim)
                .filter(it -> !it.isEmpty())
                .toList();
        for (final var team : teams) {
            if (!TEAM_SLUG.matcher(team).matches()) {
                throw new IllegalArgumentException("Expected org/team authorization restriction");
            }
        }
        if (teams.isEmpty()) {
            return true;
        }
        final var id = String.valueOf(actorId);
        if (!ACTOR_ID.matcher(id).matches()) {
            return false;
        }
        // Resolve current login from immutable identity: renamed/reused logins never authorize another user.
        final var user = TaskStore.map(github.get("/user/" + id));
        if (!id.equals(String.valueOf(user.get("id")))) {
            return false;
        }
        final var login = String.valueOf(user.get("login"));
        if (!LOGIN.matcher(login).matches() || (suppliedLogin != null && !login.equalsIgnoreCase(suppliedLogin))) {
            return false;
        }
        for (final var team : teams) {
            final var parts = team.split("/");
            final Map<String, Object> membership;
            try {
                membership =
                        TaskStore.map(github.get("/orgs/" + parts[0] + "/teams/" + parts[1] + "/memberships/" + login));
            } catch (final GitHubClient.HttpException error) {
                if (error.status() == 404) {
                    continue;
                }
                throw error;
            }
            if ("active".equals(membership.get("state"))) {
                return true;
            }
        }
        return false;
    }
}
