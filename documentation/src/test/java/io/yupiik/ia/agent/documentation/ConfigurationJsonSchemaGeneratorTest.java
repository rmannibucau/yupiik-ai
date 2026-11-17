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
package io.yupiik.ia.agent.documentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.framework.api.RuntimeContainer;
import io.yupiik.fusion.framework.api.configuration.ConfigurationSource;
import io.yupiik.fusion.framework.api.container.FusionBean;
import io.yupiik.fusion.framework.api.container.FusionListener;
import io.yupiik.fusion.framework.api.container.FusionModule;
import io.yupiik.fusion.json.JsonMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.BiPredicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigurationJsonSchemaGeneratorTest {
    @Test
    void tuiSchemaKeepsExistingPropertiesAndNestedTheme(@TempDir final Path temp) throws Exception {
        new ConfigurationJsonSchemaGenerator(
                        temp,
                        Map.of(
                                "output", "schema.json",
                                "partialOutput", "copy.json",
                                "excludedRoots",
                                        "io.yupiik.ia.agent.github.configuration.GithubBotConfiguration,"
                                                + "io.yupiik.ia.agent.github.configuration.GithubExecutorConfiguration,"
                                                + "io.yupiik.ia.agent.github.configuration.AgentContextConfiguration,"
                                                + "io.yupiik.ia.agent.github.configuration.ContextConfiguration,"
                                                + "io.yupiik.ia.agent.github.configuration.FixConfiguration,"
                                                + "io.yupiik.ia.agent.github.configuration.PromptConfiguration,"
                                                + "io.yupiik.ia.agent.github.configuration.ReviewPresentationConfiguration,"
                                                + "io.yupiik.ia.agent.slack.command.TaskOptions,"
                                                + "io.yupiik.ia.agent.slack.configuration.SlackExecutorConfiguration,"
                                                + "io.yupiik.ia.agent.slack.configuration.SlackProviderConfiguration,"
                                                + "io.yupiik.ia.agent.slack.configuration.SlackPrompts"))
                .run();
        try (final var container = ConfiguringContainer.of()
                        .register(new FusionModule() {
                            @Override
                            public BiPredicate<RuntimeContainer, FusionBean<?>> beanFilter() {
                                return (container, bean) -> !(bean.type() instanceof Class<?> type
                                        && ConfigurationSource.class.isAssignableFrom(type));
                            }

                            @Override
                            public BiPredicate<RuntimeContainer, FusionListener<?>> listenerFilter() {
                                return (container, listener) -> false;
                            }
                        })
                        .start();
                final var mapper = container.lookup(JsonMapper.class)) {
            final var schema = mapper.instance().fromString(Map.class, Files.readString(temp.resolve("schema.json")));
            final var defs = map(schema.get("$defs"));
            final var slack = resolve(defs, map(map(schema.get("properties")).get("slack")));
            final var channelMembers = map(map(slack.get("properties")).get("allowChannelMembers"));
            assertEquals("boolean", channelMembers.get("type"));
            assertEquals(false, channelMembers.get("default"));
            assertFalse(map(schema.get("properties")).containsKey("github"));
            assertFalse(map(schema.get("properties")).containsKey("githubContext"));
            assertFalse(defs.keySet().stream().anyMatch(key -> key.toString().contains("GithubBot")));
            assertFalse(defs.keySet().stream()
                    .anyMatch(key -> key.toString().contains("AgentContextConfiguration")
                            || key.toString().contains("FixConfiguration")
                            || key.toString().contains("github.configuration.PromptConfiguration")
                            || key.toString().contains("ReviewPresentationConfiguration")));
            final var yca = map(map(map(schema.get("properties")).get("yca")).get("properties"));
            final var ycaBot = map(map(yca.get("bot")).get("properties"));
            assertFalse(ycaBot.containsKey("admission"), "Host admission is not a yca.* configuration root");
            final var feedback = resolve(defs, map(ycaBot.get("feedback")));
            final var feedbackProperties = map(feedback.get("properties"));
            assertEquals("boolean", map(feedbackProperties.get("enabled")).get("type"));
            assertEquals(
                    "integer", map(feedbackProperties.get("timeoutSeconds")).get("type"));
            assertEquals("string", map(feedbackProperties.get("directory")).get("type"));
            final var bot = map(map(map(schema.get("properties")).get("bot")).get("properties"));
            final var admission = resolve(defs, map(bot.get("admission")));
            final var admissionProperties = map(admission.get("properties"));
            assertEquals("string", map(admissionProperties.get("root")).get("type"));
            assertEquals("integer", map(admissionProperties.get("slots")).get("type"));
            assertEquals("string", map(admissionProperties.get("trustedGroup")).get("type"));
            final var nativeProperties = map(map(yca.get("native")).get("properties"));
            final var nativeProvider = resolve(defs, map(nativeProperties.get("provider")));
            assertEquals(
                    "string",
                    map(map(nativeProvider.get("properties")).get("socket")).get("type"));
            final var provider = resolve(defs, map(yca.get("provider")));
            final var budget = resolve(defs, map(map(provider.get("properties")).get("budget")));
            final var budgetProperties = map(budget.get("properties"));
            for (final var name : new String[] {"root", "id", "slots"}) {
                assertEquals("string", map(budgetProperties.get(name)).get("type"), name);
            }
            assertTrue(map(budgetProperties.get("slots"))
                    .get("description")
                    .toString()
                    .contains("fail closed"));
            assertTrue(
                    map(provider.get("properties")).containsKey("contextWindow"),
                    "Adding yca.provider.budget must preserve the existing provider schema properties");
            assertTrue(map(map(provider.get("properties")).get("contextWindow"))
                    .get("description")
                    .toString()
                    .contains("falls back to 32000"));
            final var providers = map(map(provider.get("properties")).get("providers"));
            final var entry = resolve(defs, map(providers.get("items")));
            assertTrue(map(map(entry.get("properties")).get("contextWindow"))
                    .get("description")
                    .toString()
                    .contains("falls back to 32000"));
            final var tui = resolve(defs, map(yca.get("tui")));
            final var properties = map(tui.get("properties"));
            assertTrue(properties.containsKey("accent"));
            assertTrue(properties.containsKey("history"));
            final var theme = resolve(defs, map(properties.get("theme")));
            assertEquals("string", map(map(theme.get("properties")).get("name")).get("type"));
        }
    }

    private Map<?, ?> resolve(final Map<?, ?> defs, final Map<?, ?> reference) {
        final var ref = (String) reference.get("$ref");
        if (ref == null) {
            assertTrue(
                    reference.containsKey("properties"), "Expected an inline object schema or a definition reference");
            return reference;
        }
        assertTrue(ref.startsWith("#/$defs/"));
        return map(defs.get(ref.substring("#/$defs/".length())));
    }

    private Map<?, ?> map(final Object value) {
        return (Map<?, ?>) value;
    }
}
