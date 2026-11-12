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

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.framework.api.container.bean.ProvidedInstanceBean;
import io.yupiik.fusion.framework.api.main.ArgsConfigSource;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.github.client.DiscussionClient;
import io.yupiik.ia.agent.github.configuration.GithubBotConfiguration;
import io.yupiik.ia.agent.github.context.GithubContextSnapshot;
import io.yupiik.ia.agent.github.context.GithubPrivateContext;
import io.yupiik.ia.agent.github.execution.AttemptPreparation;
import io.yupiik.ia.agent.github.model.TaskDocument;
import io.yupiik.ia.agent.github.model.TaskKind;
import io.yupiik.ia.agent.github.model.TaskState;
import io.yupiik.ia.agent.github.store.TaskStore;
import io.yupiik.ia.agent.github.support.TaskFixtures;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LazyServicesTest {
    @TempDir
    Path home;

    @Test
    void resolvingServicesDoesNotInitializeAuthenticationOrCreateState() {
        final var previous = System.getProperty("github.home");
        final var state = home.resolve("uncreated");
        System.setProperty("github.home", state.toString());
        try (final var container = ConfiguringContainer.of()
                .register(new ProvidedInstanceBean<>(
                        DefaultScoped.class, ArgsConfigSource.class, () -> new ArgsConfigSource(List.of())))
                .start()) {
            for (final var type : new Class<?>[] {
                TaskStore.class,
                BotAudit.class,
                BotMaintenance.class,
                GithubRuntime.class,
                Poller.class,
                RepositoryDiscovery.class,
                Worker.class,
                TeamAuthorization.class,
                DiscussionClient.class,
                ForkProvisioner.class,
                AttemptPreparation.class,
                DiscussionPublisher.class,
                ReviewPublisher.class,
                StrictSolvePublisher.class,
                GithubContextSnapshot.class,
                GithubPrivateContext.class
            }) {
                try (final var instance = container.lookup(type)) {
                    assertNotEquals(type, instance.instance().getClass(), type.getName());
                }
            }
            assertFalse(Files.exists(state));
        } finally {
            if (previous == null) {
                System.clearProperty("github.home");
            } else {
                System.setProperty("github.home", previous);
            }
        }
        assertFalse(Files.exists(state));
    }

    @Test
    void injectedMapperDiscoversModelsAndLocalStoreWorksWithoutAuthentication() {
        final var previous = System.getProperty("github.home");
        System.setProperty("github.home", home.toString());
        try (final var container = ConfiguringContainer.of()
                        .register(new ProvidedInstanceBean<>(
                                DefaultScoped.class, ArgsConfigSource.class, () -> new ArgsConfigSource(List.of())))
                        .start();
                final var mapper = container.lookup(JsonMapper.class);
                final var store = container.lookup(TaskStore.class)) {
            final var json = mapper.instance();
            assertEquals("\"app\"", json.toString(GithubBotConfiguration.AuthType.app));
            assertEquals(
                    GithubBotConfiguration.AuthType.pat,
                    json.fromString(GithubBotConfiguration.AuthType.class, "\"pat\""));
            final var id = store.instance()
                    .transaction(state -> state.enqueue(
                            "container-test", TaskFixtures.task(Map.of("kind", "help", "repository", "acme/repo"))));
            final var task =
                    store.instance().transaction(state -> state.task(id).document());
            assertEquals(TaskKind.help, task.kind());
            assertEquals(TaskState.pending, task.state());
            assertEquals(task, json.fromString(TaskDocument.class, json.toString(task)));
        } finally {
            if (previous == null) {
                System.clearProperty("github.home");
            } else {
                System.setProperty("github.home", previous);
            }
        }
    }
}
