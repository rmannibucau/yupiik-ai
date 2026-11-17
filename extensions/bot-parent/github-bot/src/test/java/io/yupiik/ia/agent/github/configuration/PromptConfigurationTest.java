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

import io.yupiik.ia.agent.bot.runtime.configuration.ExecutorConfiguration;
import io.yupiik.ia.agent.github.configuration.WorkerOptions.SourceMode;
import io.yupiik.ia.agent.github.execution.AttemptPreparation;
import io.yupiik.ia.agent.github.model.TaskDocument;
import io.yupiik.ia.agent.github.support.BotJsonMapper;
import io.yupiik.ia.agent.github.support.TaskFixtures;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PromptConfigurationTest {
    @TempDir
    Path home;

    @Test
    void generatedDefaultsSelectSourceTaskAndPublicationForEveryExecutionMode() {
        try (final var json = BotJsonMapper.create(List.of(), key -> Optional.empty())) {
            final var preparation = new AttemptPreparation(json, prompts(Map.of()), executor(Map.of()));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> preparation.prompt(task("solve", "request"), "fork", false, null));
            for (final var kind : List.of("review", "solve", "fix")) {
                for (final var mode : SourceMode.values()) {
                    for (final var commenting : List.of(false, true)) {
                        final var task = task(kind, "request");
                        if (!valid(kind, mode, commenting)) {
                            assertThrows(
                                    IllegalArgumentException.class,
                                    () -> preparation.prompt(task, "fork", commenting, mode));
                            continue;
                        }
                        final var output = preparation.prompt(task, "fork", commenting, mode);
                        assertTrue(output.contains("untrusted task data"), output);
                        assertTrue(output.contains("Summary, Findings and Tests"), output);
                        assertTrue(output.contains("/attempt/output/result.json"), output);
                        assertTrue(output.contains("USER REQUEST (data):"), output);
                        assertEquals(mode == SourceMode.git, output.contains("AGENT-MANAGED GIT SOURCE:"));
                        assertEquals(mode == SourceMode.broker, output.contains("BROKERED CONTEXT:"));
                        assertEquals(mode == SourceMode.export, output.contains("github-source.json"));
                        assertEquals(commenting, output.contains("You MAY publish a single native COMMENT review"));
                        assertEquals(!commenting, output.contains("STRICT MODE:"));
                        assertEquals(kind.equals("review"), output.contains("For review: obtain current head SHA"));
                        assertEquals(kind.equals("solve"), output.contains("Implement and test the requested change"));
                        assertEquals(kind.equals("fix"), output.contains("FIX ONLY:"));
                    }
                }
            }
        }
    }

    @Test
    void eachGeneratedPropertyReplacesOnlyItsSelectedComponentAndRemovesOldParagraph() {
        final var paragraphs = Map.ofEntries(
                Map.entry("main", "You are a GitHub development assistant"),
                Map.entry("commonPolicy", "You are a GitHub development assistant"),
                Map.entry("headings", "Presentation: use concise Summary"),
                Map.entry("exportSource", "github-source.json"),
                Map.entry("gitSource", "AGENT-MANAGED GIT SOURCE:"),
                Map.entry("brokerSource", "BROKERED CONTEXT:"),
                Map.entry("strictPublication", "STRICT MODE:"),
                Map.entry("agentPublication", "Fetch context using gh"),
                Map.entry("review", "For review: obtain current head SHA"),
                Map.entry("solve", "Implement and test the requested change"),
                Map.entry("fix", "FIX ONLY:"),
                Map.entry("toolchain", "Toolchain: select the project's actual JDK"),
                Map.entry("resultContract", "Write a single JSON object"),
                Map.entry("taskEnvelope", "Task identity and metadata (JSON data):"));
        assertEquals(prompts(Map.of()).sources().keySet(), paragraphs.keySet());
        try (final var json = BotJsonMapper.create(List.of(), key -> Optional.empty())) {
            final var defaults = new AttemptPreparation(json, prompts(Map.of()), executor(Map.of()));
            for (final var entry : paragraphs.entrySet()) {
                final var marker = "REPLACEMENT-" + entry.getKey();
                final var configured = new AttemptPreparation(
                        json, prompts(Map.of("github.prompts." + entry.getKey(), marker)), executor(Map.of()));
                for (final var kind : List.of("review", "solve", "fix")) {
                    for (final var mode : SourceMode.values()) {
                        for (final var commenting : List.of(false, true)) {
                            final var task = task(kind, "request");
                            if (!valid(kind, mode, commenting)) {
                                assertThrows(
                                        IllegalArgumentException.class,
                                        () -> configured.prompt(task, "fork", commenting, mode));
                                continue;
                            }
                            final var before = defaults.prompt(task, "fork", commenting, mode);
                            final var after = configured.prompt(task, "fork", commenting, mode);
                            if (!selected(entry.getKey(), kind, mode, commenting)) {
                                assertEquals(before, after, entry.getKey());
                                continue;
                            }
                            assertNotEquals(before, after, entry.getKey());
                            assertTrue(before.contains(entry.getValue()), entry.getKey());
                            assertTrue(after.contains(marker), entry.getKey());
                            assertFalse(after.contains(entry.getValue()), entry.getKey());
                            if (entry.getKey().equals("main")) {
                                assertEquals(marker, after);
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    void customMainEachAndIfExposeOnlyEffectiveDownloadHosts() {
        final var main = "{{#if dependencyDownloadsEnabled}}allowed:{{#each allowedDownloadHosts}}[{{{this}}}]{{/each}}"
                + "{{else}}disabled{{/if}}|{{{allowedDownloadHostsJson}}}";
        try (final var json = BotJsonMapper.create(List.of(), key -> Optional.empty())) {
            final var configuration = prompts(Map.of("github.prompts.main", main));
            final var task = task("solve", "request");
            for (final var prefix :
                    List.of("github.executor.docker.egress.", "github.executor.docker.services.dependencyEgress.")) {
                final var values = prefix.contains("services") ? services() : new LinkedHashMap<String, String>();
                values.put(prefix + "image", "proxy");
                values.put(prefix + "allowedHosts", "custom.example,*.example.org");
                final var output =
                        new AttemptPreparation(json, configuration, executor(values)).prompt(task, "fork", false);
                assertEquals(
                        "allowed:[custom.example]\n[*.example.org]|[\"custom.example\",\"*.example.org\"]", output);
                assertFalse(output.contains("cdn.azul.com"));
                assertFalse(output.contains("repo.maven.apache.org"));
                values.put(prefix + "enabled", "false");
                assertEquals(
                        "disabled|[]",
                        new AttemptPreparation(json, configuration, executor(values)).prompt(task, "fork", false));
                values.put(prefix + "enabled", "true");
                values.put(prefix + "allowedHosts", "");
                assertEquals(
                        "disabled|[]",
                        new AttemptPreparation(json, configuration, executor(values)).prompt(task, "fork", false));
            }
            final var services = executor(services());
            final var hosts = services.docker().services().dependencyEgress().allowedHosts();
            assertTrue(hosts.contains("cdn.azul.com"));
            final var expected = new StringBuilder("allowed:");
            expected.append(String.join(
                    "\n", hosts.stream().map(host -> "[" + host + "]").toList()));
            expected.append('|').append(json.toString(hosts));
            assertEquals(
                    expected.toString(),
                    new AttemptPreparation(json, configuration, services).prompt(task, "fork", false));
            assertEquals(
                    "disabled|[]",
                    new AttemptPreparation(json, configuration, executor(Map.of())).prompt(task, "fork", false));
        }
    }

    @Test
    void invocationRemainsLiteralJsonAcrossDefaultAndCustomMainAndConfigurationIsNotExposed() {
        final var request = "{{> missing}} {{env SECRET}} {{{commonPolicy}}} \"quotes\" <tag>&\n";
        try (final var json = BotJsonMapper.create(List.of(), key -> Optional.empty())) {
            final var task = task("solve", request);
            final var encoded = json.toString(new AttemptPreparation.UserRequest(request));
            final var defaults =
                    new AttemptPreparation(json, prompts(Map.of()), executor(Map.of())).prompt(task, "fork", false);
            assertTrue(defaults.endsWith("USER REQUEST (data): " + encoded), defaults);
            final var values = new LinkedHashMap<>(Map.of(
                    "github.prompts.main",
                            "{{{taskEnvelope}}}|{{{requestJson}}}|{{configuration}}|{{globalconfiguration}}|{{env}}|{{secrets}}",
                    "github.prompts.taskEnvelope", "{{{requestJson}}}",
                    "configuration", "HOST-CONFIG-SECRET",
                    "globalconfiguration", "HOST-CONFIG-SECRET",
                    "env", "HOST-CONFIG-SECRET",
                    "secrets", "HOST-CONFIG-SECRET",
                    "github.token", "HOST-CONFIG-SECRET"));
            final var output =
                    new AttemptPreparation(json, prompts(values), executor(values)).prompt(task, "fork", false);
            assertEquals(encoded + "|" + encoded + "||||", output);
            assertFalse(output.contains("HOST-CONFIG-SECRET"));
            assertFalse(output.contains("&quot;"));
            assertFalse(output.contains("&lt;"));
            assertEquals(
                    request,
                    json.fromString(AttemptPreparation.UserRequest.class, encoded)
                            .request());
        }
    }

    @Test
    void invalidConfiguredTemplatesFailInConstructorBeforeCreatingAnyAttempt() throws Exception {
        final var trusted = Files.writeString(home.resolve("trusted.json"), "{}");
        final var attempts = home.resolve("attempts");
        try (final var json = BotJsonMapper.create(List.of(), key -> Optional.empty())) {
            for (final var name : prompts(Map.of()).sources().keySet()) {
                for (final var invalid : List.of("PRIVATE {{broken", "{{#if solve}}PRIVATE", "{{> missing}}")) {
                    final var error = assertThrows(
                            IllegalArgumentException.class,
                            () -> new AttemptPreparation(
                                            json,
                                            prompts(Map.of("github.prompts." + name, invalid)),
                                            executor(Map.of()))
                                    .prepare(trusted, attempts));
                    assertTrue(error.getMessage().contains("github.prompts"));
                    assertTrue(error.getMessage().contains(name));
                    assertFalse(error.getMessage().contains("PRIVATE"));
                    assertNull(error.getCause());
                }
            }
            assertFalse(Files.exists(attempts));
            assertEquals("{}", Files.readString(trusted));
        }
    }

    private boolean valid(final String kind, final SourceMode mode, final boolean commenting) {
        return !(commenting && (mode != SourceMode.export || kind.equals("fix")))
                && !(mode == SourceMode.git && !kind.equals("solve"));
    }

    private boolean selected(
            final String component, final String kind, final SourceMode mode, final boolean commenting) {
        return switch (component) {
            case "exportSource" -> mode == SourceMode.export;
            case "gitSource" -> mode == SourceMode.git;
            case "brokerSource" -> mode == SourceMode.broker;
            case "strictPublication" -> !commenting;
            case "agentPublication" -> commenting;
            case "review", "solve", "fix" -> component.equals(kind);
            default -> true;
        };
    }

    private TaskDocument task(final String kind, final String request) {
        return TaskFixtures.task(
                Map.of("id", "task-1", "repository", "owner/repo", "number", 7, "kind", kind, "request", request));
    }

    private PromptConfiguration prompts(final Map<String, String> values) {
        return new PromptConfiguration$FusionConfigurationFactory(key -> Optional.ofNullable(values.get(key))).get();
    }

    private ExecutorConfiguration executor(final Map<String, String> values) {
        return new GithubExecutorConfiguration$FusionConfigurationFactory(key -> Optional.ofNullable(values.get(key)))
                .get()
                .shared();
    }

    private LinkedHashMap<String, String> services() {
        return new LinkedHashMap<>(Map.of(
                "github.executor.docker.services.image", "trusted-services",
                "github.executor.docker.services.controlNetwork", "control",
                "github.executor.docker.services.keyStore", "/trusted/services.p12",
                "github.executor.docker.services.providerTarget", "http://172.20.0.1:8080/v1"));
    }
}
