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
import io.yupiik.ia.agent.github.command.WorkerCommand$Options$FusionConfigurationFactory;
import io.yupiik.ia.agent.github.configuration.ExecutorConfiguration;
import io.yupiik.ia.agent.github.configuration.GithubBotConfiguration;
import io.yupiik.ia.agent.github.configuration.PollOptions;
import io.yupiik.ia.agent.github.configuration.WorkerOptions;
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
import java.util.Map;
import java.util.Optional;

public final class BotTestConfiguration {
    public static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-13T12:00:00Z"), ZoneOffset.UTC);

    private BotTestConfiguration() {}

    public static GithubBotConfiguration home(final Path home) {
        return configuration(home.toString(), "bot", "");
    }

    public static GithubBotConfiguration configuration(final String home, final String login, final String teams) {
        return new GithubBotConfiguration(
                home, null, login, 0, null, new GithubBotConfiguration.Authorization("42", teams), null);
    }

    public static TaskStore store(final GithubBotConfiguration conf, final JsonMapper json) {
        return new TaskStore(conf, json);
    }

    public static BotAudit audit(final GithubBotConfiguration conf, final JsonMapper json) {
        return new BotAudit(conf, json, CLOCK);
    }

    public static TeamAuthorization authorization(final GitHubClient github, final String teams) {
        return new TeamAuthorization(github, configuration("", "bot", teams));
    }

    public static ReviewPublisher reviewPublisher(
            final GitHubClient github, final TaskStore store, final String login) {
        return new ReviewPublisher(github, store, configuration("", login, ""));
    }

    public static DiscussionPublisher discussionPublisher(
            final GitHubClient github, final TaskStore store, final String login) {
        return new DiscussionPublisher(new DiscussionClient(github), store, configuration("", login, ""));
    }

    public static WorkerCommand.Options workerOptions(final String owner) {
        final var values = Map.of("worker.forkOwner", owner);
        return new WorkerCommand$Options$FusionConfigurationFactory(key -> Optional.ofNullable(values.get(key))).get();
    }

    public static StrictSolvePublisher strictSolvePublisher(
            final GitHubClient github, final TaskStore store, final JsonMapper json, final String owner) {
        return new StrictSolvePublisher(github, store, json, workerOptions(owner));
    }

    public static Worker worker(
            final GithubBotConfiguration conf,
            final WorkerOptions options,
            final TaskStore store,
            final GitHubClient github,
            final JsonMapper json,
            final ExecutorConfiguration execution) {
        final var commandOptions = new WorkerCommand.Options("", options);
        return new Worker(
                conf,
                commandOptions,
                store,
                github,
                json,
                execution,
                audit(conf, json),
                new TeamAuthorization(github, conf),
                new AttemptPreparation(json),
                new GithubPrivateContext(github, json),
                new GithubContextSnapshot(github, json),
                new ForkProvisioner(github, store),
                new ReviewPublisher(github, store, conf),
                new StrictSolvePublisher(github, store, json, commandOptions),
                new DiscussionPublisher(new DiscussionClient(github), store, conf),
                CLOCK);
    }

    public static ReviewSummaryPoller reviewSummaryPoller(
            final GithubBotConfiguration conf,
            final PollOptions options,
            final TaskStore store,
            final GitHubClient github,
            final Clock clock,
            final BotAudit audit) {
        return new ReviewSummaryPoller(
                conf,
                new PollCommand.Options(options),
                store,
                github,
                clock,
                audit,
                new TeamAuthorization(github, conf));
    }

    public static DiscussionPoller discussionPoller(
            final GithubBotConfiguration conf,
            final PollOptions options,
            final TaskStore store,
            final GitHubClient github,
            final Clock clock,
            final BotAudit audit) {
        return new DiscussionPoller(
                conf,
                new PollCommand.Options(options),
                store,
                github,
                clock,
                audit,
                new TeamAuthorization(github, conf),
                new DiscussionClient(github));
    }

    public static Poller poller(
            final GithubBotConfiguration conf,
            final PollOptions options,
            final TaskStore store,
            final GitHubClient github,
            final Clock clock,
            final BotAudit audit) {
        return new Poller(
                conf,
                new PollCommand.Options(options),
                store,
                github,
                clock,
                audit,
                new TeamAuthorization(github, conf),
                reviewSummaryPoller(conf, options, store, github, clock, audit),
                discussionPoller(conf, options, store, github, clock, audit),
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
                    new PollCommand(options, poller(conf, options.poll(), store, runtime, CLOCK, audit)).run();
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
                    new WorkerCommand(
                                    options,
                                    store,
                                    worker(conf, options.worker(), store, runtime, json, execution),
                                    notifications)
                            .run();
                }
            }
        };
    }
}
