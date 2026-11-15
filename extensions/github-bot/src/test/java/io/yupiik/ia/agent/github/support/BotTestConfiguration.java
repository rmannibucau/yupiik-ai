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
package io.yupiik.ia.agent.github.support;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.notifications.NotificationService;
import io.yupiik.ia.agent.github.client.DiscussionClient;
import io.yupiik.ia.agent.github.client.GitHubClient;
import io.yupiik.ia.agent.github.command.PollCommand;
import io.yupiik.ia.agent.github.command.WorkerCommand;
import io.yupiik.ia.agent.github.configuration.ExecutorConfiguration;
import io.yupiik.ia.agent.github.configuration.GithubBotConfiguration;
import io.yupiik.ia.agent.github.context.GithubContextSnapshot;
import io.yupiik.ia.agent.github.context.GithubPrivateContext;
import io.yupiik.ia.agent.github.execution.AttemptPreparation;
import io.yupiik.ia.agent.github.service.BotAudit;
import io.yupiik.ia.agent.github.service.DiscussionPoller;
import io.yupiik.ia.agent.github.service.DiscussionPublisher;
import io.yupiik.ia.agent.github.service.ForkProvisioner;
import io.yupiik.ia.agent.github.service.GithubServices;
import io.yupiik.ia.agent.github.service.Poller;
import io.yupiik.ia.agent.github.service.RepositoryDiscovery;
import io.yupiik.ia.agent.github.service.ReviewPublisher;
import io.yupiik.ia.agent.github.service.ReviewSummaryPoller;
import io.yupiik.ia.agent.github.service.StrictSolvePublisher;
import io.yupiik.ia.agent.github.service.TeamAuthorization;
import io.yupiik.ia.agent.github.service.Worker;
import io.yupiik.ia.agent.github.store.TaskStore;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

public final class BotTestConfiguration {
    public static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-13T12:00:00Z"), ZoneOffset.UTC);

    private BotTestConfiguration() {}

    public static GithubBotConfiguration home(final Path home) {
        return configuration(home.toString(), "bot", "");
    }

    public static GithubBotConfiguration configuration(final String home, final String login, final String teams) {
        return new GithubBotConfiguration(
                home, null, login, 0, null, new GithubBotConfiguration.Authorization("42", teams, false, 7200), null);
    }

    public static TaskStore store(final GithubBotConfiguration conf, final JsonMapper json) {
        return new TaskStore(conf, json);
    }

    public static BotAudit audit(final GithubBotConfiguration conf, final JsonMapper json) {
        return new BotAudit(conf, json, CLOCK);
    }

    public static TeamAuthorization authorization(final GitHubClient github, final String teams) {
        return new TeamAuthorization(github, configuration("", "bot", teams), CLOCK);
    }

    public static ReviewPublisher reviewPublisher(
            final GitHubClient github, final TaskStore store, final String login) {
        return new ReviewPublisher(github, store, configuration("", login, ""));
    }

    public static DiscussionPublisher discussionPublisher(
            final GitHubClient github, final TaskStore store, final String login) {
        return new DiscussionPublisher(new DiscussionClient(github), store, configuration("", login, ""));
    }

    public static Worker worker(
            final GithubBotConfiguration conf,
            final TaskStore store,
            final GitHubClient github,
            final JsonMapper json,
            final ExecutorConfiguration execution) {
        return new Worker(
                conf,
                store,
                github,
                json,
                execution,
                audit(conf, json),
                new TeamAuthorization(github, conf, CLOCK),
                new AttemptPreparation(json),
                new GithubPrivateContext(github, json),
                new GithubContextSnapshot(github, json),
                new ForkProvisioner(github, store),
                new ReviewPublisher(github, store, conf),
                new StrictSolvePublisher(github, store, json),
                new DiscussionPublisher(new DiscussionClient(github), store, conf),
                CLOCK);
    }

    public static ReviewSummaryPoller reviewSummaryPoller(
            final GithubBotConfiguration conf,
            final TaskStore store,
            final GitHubClient github,
            final Clock clock,
            final BotAudit audit) {
        return new ReviewSummaryPoller(conf, store, github, clock, audit, new TeamAuthorization(github, conf, clock));
    }

    public static DiscussionPoller discussionPoller(
            final GithubBotConfiguration conf,
            final TaskStore store,
            final GitHubClient github,
            final Clock clock,
            final BotAudit audit) {
        return new DiscussionPoller(
                conf,
                store,
                github,
                clock,
                audit,
                new TeamAuthorization(github, conf, clock),
                new DiscussionClient(github));
    }

    public static Poller poller(
            final GithubBotConfiguration conf,
            final TaskStore store,
            final GitHubClient github,
            final Clock clock,
            final BotAudit audit) {
        return new Poller(
                conf,
                store,
                github,
                clock,
                audit,
                new TeamAuthorization(github, conf, clock),
                reviewSummaryPoller(conf, store, github, clock, audit),
                discussionPoller(conf, store, github, clock, audit),
                new RepositoryDiscovery(conf, github, clock));
    }

    public static PollCommand pollCommand(
            final PollCommand.Options options,
            final GithubBotConfiguration conf,
            final JsonMapper json,
            final TaskStore store,
            final BotAudit audit) {
        return new PollCommand(options, null) {
            @Override
            public void run() {
                try (final var runtime = new GithubServices().client(conf, json, CLOCK)) {
                    new PollCommand(options, poller(conf, store, runtime, CLOCK, audit)).run();
                }
            }
        };
    }

    public static WorkerCommand workerCommand(
            final WorkerCommand.Options options,
            final GithubBotConfiguration conf,
            final JsonMapper json,
            final TaskStore store,
            final BotAudit audit,
            final ExecutorConfiguration execution,
            final NotificationService notifications) {
        return new WorkerCommand(options, store, null, notifications) {
            @Override
            public void run() {
                try (final var runtime = new GithubServices().client(conf, json, CLOCK)) {
                    new WorkerCommand(options, store, worker(conf, store, runtime, json, execution), notifications)
                            .run();
                }
            }
        };
    }
}
