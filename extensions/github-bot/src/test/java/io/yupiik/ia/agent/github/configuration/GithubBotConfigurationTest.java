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
package io.yupiik.ia.agent.github.configuration;

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.ia.agent.github.command.PollCommand$Options$FusionConfigurationFactory;
import io.yupiik.ia.agent.github.command.PurgeCommand$Options$FusionConfigurationFactory;
import io.yupiik.ia.agent.github.command.WorkerCommand$Options$FusionConfigurationFactory;
import io.yupiik.ia.agent.github.service.RepositorySelector;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class GithubBotConfigurationTest {
    @Test
    void defaultsAreHierarchicalAndOperationSettingsAreSeparate() {
        final var shared = new GithubBotConfiguration$FusionConfigurationFactory(key -> Optional.empty()).get();
        final var executor = new ExecutorConfiguration$FusionConfigurationFactory(key -> Optional.empty()).get();
        final var options = new PollCommand$Options$FusionConfigurationFactory(key -> Optional.empty()).get();
        final var worker = new WorkerCommand$Options$FusionConfigurationFactory(key -> Optional.empty()).get();
        final var purge = new PurgeCommand$Options$FusionConfigurationFactory(key -> Optional.empty()).get();
        assertEquals(GithubBotConfiguration.AuthType.pat, shared.auth().type());
        assertEquals("GH_TOKEN", shared.auth().tokenEnv());
        assertEquals("GH_AGENT_TOKEN", shared.auth().agentTokenEnv());
        assertEquals("", shared.authorization().userIds());
        assertEquals(
                List.of(RepositorySelector.Visibility.values()),
                shared.repositories().visibility());
        assertEquals(ExecutorConfiguration.Backend.DOCKER, executor.backend());
        assertEquals("docker", executor.docker().executable());
        assertNull(executor.docker().egress(), "Absent nested configuration must not enable the gateway");
        assertEquals("nono", executor.nono().executable());
        assertEquals("yca-attempts", executor.kubernetes().claim());
        assertEquals("", executor.relay().bind());
        assertEquals("now", options.poll().since());
        assertEquals(20, options.poll().pageBudget());
        assertFalse(options.poll().reviews().enabled());
        assertEquals(2, worker.worker().maxRetries());
        assertEquals(1, worker.worker().parallelism());
        assertEquals(7, purge.retentionDays());
    }

    @Test
    void bindsSharedExecutorAndCommandSubgraphs() {
        final var values = Map.ofEntries(
                Map.entry("github.auth.type", "app"),
                Map.entry("github.auth.appId", "123"),
                Map.entry("github.auth.installationId", "456"),
                Map.entry("github.auth.privateKeyFile", "/private/key.pem"),
                Map.entry("github.authorization.userIds", "42"),
                Map.entry("github.authorization.teams", "acme/dev"),
                Map.entry("github.repositories.explicit", "acme/repo"),
                Map.entry("github.repositories.discovery", "org:acme"),
                Map.entry("github.repositories.includes", "acme/*"),
                Map.entry("github.repositories.excludeForks", "true"),
                Map.entry("github.executor.backend", "KUBERNETES"),
                Map.entry("github.executor.docker.executable", "/bin/docker"),
                Map.entry("github.executor.nono.runtimeRoot", "/runtime"),
                Map.entry("github.executor.kubernetes.master", "https://cluster.example"),
                Map.entry("github.executor.kubernetes.token", "/private/token"),
                Map.entry("github.executor.kubernetes.certificates", "/private/ca.pem"),
                Map.entry("github.executor.kubernetes.privateKey", "key-pem"),
                Map.entry("github.executor.kubernetes.privateKeyCertificate", "cert-pem"),
                Map.entry("github.executor.kubernetes.kubeconfig", "/private/kube.json"),
                Map.entry("github.executor.kubernetes.uid", "1234"),
                Map.entry("github.executor.kubernetes.gid", "5678"),
                Map.entry("github.executor.kubernetes.skipTls", "true"),
                Map.entry("github.executor.relay.bind", "127.0.0.1"),
                Map.entry("poll.since", "2026-01-01T00:00:00Z"),
                Map.entry("poll.pageBudget", "3"),
                Map.entry("poll.reviews.enabled", "true"),
                Map.entry("worker.parallelism", "4"),
                Map.entry("worker.maxRetries", "5"),
                Map.entry("worker.cacheRoot", "/cache"),
                Map.entry("retentionDays", "10"));
        final var shared = new GithubBotConfiguration$FusionConfigurationFactory(
                        key -> Optional.ofNullable(values.get(key)))
                .get();
        final var executor =
                new ExecutorConfiguration$FusionConfigurationFactory(key -> Optional.ofNullable(values.get(key))).get();
        final var options =
                new PollCommand$Options$FusionConfigurationFactory(key -> Optional.ofNullable(values.get(key))).get();
        final var worker =
                new WorkerCommand$Options$FusionConfigurationFactory(key -> Optional.ofNullable(values.get(key))).get();
        final var purge =
                new PurgeCommand$Options$FusionConfigurationFactory(key -> Optional.ofNullable(values.get(key))).get();
        assertEquals(GithubBotConfiguration.AuthType.app, shared.auth().type());
        assertEquals("123", shared.auth().appId());
        assertEquals(456, shared.auth().installationId());
        assertEquals("/private/key.pem", shared.auth().privateKeyFile());
        assertEquals("42", shared.authorization().userIds());
        assertEquals("acme/dev", shared.authorization().teams());
        assertEquals("acme/repo", shared.repositories().explicit());
        assertEquals("org:acme", shared.repositories().discovery());
        assertEquals("acme/*", shared.repositories().includes());
        assertTrue(shared.repositories().excludeForks());
        assertEquals(ExecutorConfiguration.Backend.KUBERNETES, executor.backend());
        assertEquals("/bin/docker", executor.docker().executable());
        assertEquals("/runtime", executor.nono().runtimeRoot());
        assertEquals("https://cluster.example", executor.kubernetes().master());
        assertEquals("/private/token", executor.kubernetes().token());
        assertEquals("/private/ca.pem", executor.kubernetes().certificates());
        assertEquals("key-pem", executor.kubernetes().privateKey());
        assertEquals("cert-pem", executor.kubernetes().privateKeyCertificate());
        assertEquals("/private/kube.json", executor.kubernetes().kubeconfig());
        assertEquals(1234, executor.kubernetes().uid());
        assertEquals(5678, executor.kubernetes().gid());
        assertTrue(executor.kubernetes().skipTls());
        assertEquals("127.0.0.1", executor.relay().bind());
        assertEquals("2026-01-01T00:00:00Z", options.poll().since());
        assertEquals(3, options.poll().pageBudget());
        assertTrue(options.poll().reviews().enabled());
        assertEquals(4, worker.worker().parallelism());
        assertEquals(5, worker.worker().maxRetries());
        assertEquals("/cache", worker.worker().cacheRoot());
        assertEquals(10, purge.retentionDays());
    }

    @Test
    void legacyFlatSettingsAreNotAliases() {
        final var values = Map.of(
                "github.parallelism", "9",
                "github.pollReviews", "true",
                "github.executor.namespace", "legacy",
                "bot.worker.parallelism", "8",
                "bot.poll.pollReviews", "true",
                "bot.purge.retentionDays", "99");
        final var options =
                new PollCommand$Options$FusionConfigurationFactory(key -> Optional.ofNullable(values.get(key))).get();
        final var executor =
                new ExecutorConfiguration$FusionConfigurationFactory(key -> Optional.ofNullable(values.get(key))).get();
        final var worker =
                new WorkerCommand$Options$FusionConfigurationFactory(key -> Optional.ofNullable(values.get(key))).get();
        final var purge =
                new PurgeCommand$Options$FusionConfigurationFactory(key -> Optional.ofNullable(values.get(key))).get();
        assertEquals(1, worker.worker().parallelism());
        assertEquals(7, purge.retentionDays());
        assertFalse(options.poll().reviews().enabled());
        assertEquals("yca-github", executor.kubernetes().namespace());
    }
}
