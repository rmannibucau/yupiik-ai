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
package io.yupiik.ia.agent.core.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.yupiik.fusion.framework.api.configuration.ConfigurationSource;
import io.yupiik.fusion.framework.api.configuration.impl.MapConfigSource;
import io.yupiik.fusion.framework.api.container.configuration.ConfigurationImpl;
import io.yupiik.fusion.framework.api.container.configuration.ConfigurationRegistration;
import io.yupiik.fusion.framework.api.main.ArgsConfigSource;
import io.yupiik.ia.agent.core.wiring.CombinedConfigurationSource;
import io.yupiik.ia.agent.core.wiring.YcaConfigurationProducer;
import io.yupiik.ia.agent.core.wiring.YcaPrefixShortcutConfigurationSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises {@link YcaConfigurationProducer} wiring end to end (file resolution + {@code $inherit}
 * merge semantics) by invoking {@code register} directly against a captured set of added sources and
 * binding them into a real {@link CodingAgentConfiguration} with the annotation-processor-generated
 * factory (the same length-bounded binding the runtime uses).
 */
class YcaConfigurationProducerTest {

    @TempDir
    Path temp;

    private CodingAgentConfiguration config(final List<String> args) {
        final var producer = new YcaConfigurationProducer(new ArgsConfigSource(args));
        final List<ConfigurationSource> added = new ArrayList<>();
        final Consumer<ConfigurationSource> sink = added::add;
        final ConfigurationRegistration registration = new ConfigurationRegistration(
                new ConfigurationImpl(List.of(new YcaPrefixShortcutConfigurationSource(new ArgsConfigSource(args)))),
                sink);
        producer.register(registration);

        final var sources = new ArrayList<ConfigurationSource>();
        sources.add(new YcaPrefixShortcutConfigurationSource(new ArgsConfigSource(args)));
        sources.addAll(added);
        final var configuration = new ConfigurationImpl(sources);
        return new CodingAgentConfiguration$FusionConfigurationFactory(configuration).get();
    }

    private List<String> baseArgs() {
        return List.of("--yca.workingDir", temp.toString(), "--yca.configuration.json", globalConfigPath());
    }

    private String globalConfigPath() {
        return temp.resolve("global-configuration.json").toString();
    }

    private void write(final Path path, final String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    @Test
    void noProjectFileFallsBackToGlobalAlone() throws IOException {
        write(Path.of(globalConfigPath()), "{\"yca\":{\"provider\":{\"model\":\"global\"}}}");
        assertEquals("global", config(baseArgs()).provider().model());
    }

    @Test
    void projectFileWithoutInheritIgnoresGlobal() throws IOException {
        write(Path.of(globalConfigPath()), "{\"yca\":{\"provider\":{\"model\":\"global\"}}}");
        write(temp.resolve(".yca").resolve("configuration.json"), "{\"yca\":{\"provider\":{\"model\":\"project\"}}}");
        assertEquals("project", config(baseArgs()).provider().model());
    }

    @Test
    void scalarsOverrideAndOmittedKeysInherit() throws IOException {
        write(
                Path.of(globalConfigPath()),
                "{\"yca\":{\"provider\":{\"model\":\"global-model\",\"baseUrl\":\"http://global\"}}}");
        write(
                temp.resolve(".yca").resolve("configuration.json"),
                "{\"$inherit\":true,\"yca\":{\"provider\":{\"model\":\"project-model\"}}}");
        final var cfg = config(baseArgs());
        assertEquals("project-model", cfg.provider().model(), "explicit scalar overrides the inherited one");
        assertEquals("http://global", cfg.provider().baseUrl(), "an omitted scalar inherits the global value");
    }

    @Test
    void childListTruncatesInheritedList() throws IOException {
        write(
                Path.of(globalConfigPath()),
                "{\"yca\":{\"provider\":{\"providers\":["
                        + "{\"id\":\"g0\",\"model\":\"m0\"},"
                        + "{\"id\":\"g1\",\"model\":\"m1\"},"
                        + "{\"id\":\"g2\",\"model\":\"m2\"}]}}}");
        write(
                temp.resolve(".yca").resolve("configuration.json"),
                "{\"$inherit\":true,\"yca\":{\"provider\":{\"providers\":[{\"id\":\"p0\",\"model\":\"pm0\"}]}}}");
        final var cfg = config(baseArgs()).provider();
        // the length-bounded generated binding truncates at the child's length, hiding the parent's extra elements
        assertEquals(1, cfg.providers().size(), "child length must truncate the inherited 3-provider list");
        assertEquals("p0", cfg.providers().get(0).id());
        assertEquals("pm0", cfg.providers().get(0).model());
    }

    @Test
    void emptyChildListResetsToEmpty() throws IOException {
        write(
                Path.of(globalConfigPath()),
                "{\"yca\":{\"provider\":{\"providers\":[{\"id\":\"g0\",\"model\":\"m0\"}]}}}");
        write(
                temp.resolve(".yca").resolve("configuration.json"),
                "{\"$inherit\":true,\"yca\":{\"provider\":{\"providers\":[]}}}");
        assertEquals(0, config(baseArgs()).provider().providers().size(), "empty array must reset the inherited list");
    }

    @Test
    void omittedListInheritsParentWholesale() throws IOException {
        write(
                Path.of(globalConfigPath()),
                "{\"yca\":{\"provider\":{\"providers\":["
                        + "{\"id\":\"g0\",\"model\":\"m0\"},"
                        + "{\"id\":\"g1\",\"model\":\"m1\"}]}}}");
        write(temp.resolve(".yca").resolve("configuration.json"), "{\"$inherit\":true}");
        final var providers = config(baseArgs()).provider().providers();
        assertEquals(2, providers.size(), "an omitted list must inherit the parent wholesale");
        assertEquals("g1", providers.get(1).id());
    }

    @Test
    void cliArgsWinOverProjectInheritance() throws IOException {
        write(Path.of(globalConfigPath()), "{\"yca\":{\"provider\":{\"model\":\"global\"}}}");
        write(
                temp.resolve(".yca").resolve("configuration.json"),
                "{\"$inherit\":true,\"yca\":{\"provider\":{\"model\":\"project\"}}}");
        final var cfg = config(List.of(
                "--yca.workingDir", temp.toString(),
                "--yca.configuration.json", globalConfigPath(),
                "--yca.provider.model", "cli"));
        assertEquals("cli", cfg.provider().model(), "CLI args must win over the project file inheritance");
    }

    @Test
    void inheritFalseDisablesInheritance() throws IOException {
        write(Path.of(globalConfigPath()), "{\"yca\":{\"provider\":{\"model\":\"global\"}}}");
        write(
                temp.resolve(".yca").resolve("configuration.json"),
                "{\"$inherit\":false,\"yca\":{\"provider\":{\"model\":\"project\"}}}");
        final var cfg = config(baseArgs());
        assertEquals("project", cfg.provider().model(), "$inherit:false must not read the global");
        assertEquals("https://api.openai.com/v1", cfg.provider().baseUrl(), "default baseUrl, not the inherited one");
    }

    @Test
    void inheritTargetMissingGlobalsFallsBackToChildAlone() throws IOException {
        write(
                temp.resolve(".yca").resolve("configuration.json"),
                "{\"$inherit\":true,\"yca\":{\"provider\":{\"model\":\"only\"}}}");
        assertEquals("only", config(baseArgs()).provider().model(), "missing global must not break the project file");
    }

    @Test
    void combinedSourceIsSingleLevel() {
        final var first = new MapConfigSource(java.util.Map.of("a", "1"));
        final var second = new MapConfigSource(java.util.Map.of("b", "2", "a", "shadowed"));
        final var combined = new CombinedConfigurationSource(first, second);
        assertEquals("1", combined.get("a"), "first wins on present key");
        assertEquals("2", combined.get("b"), "second provides absent keys");
        assertEquals(null, combined.get("z"), "absent key resolves to null");
    }

    @Test
    void projectDirUnderExplicitWorkingDir() throws IOException {
        final var customWork = temp.resolve("custom-work");
        write(
                customWork.resolve(".yca").resolve("configuration.json"),
                "{\"yca\":{\"provider\":{\"model\":\"proj\"}}}");
        write(Path.of(globalConfigPath()), "{\"yca\":{\"provider\":{\"model\":\"global\"}}}");
        final var cfg = config(
                List.of("--yca.workingDir", customWork.toString(), "--yca.configuration.json", globalConfigPath()));
        assertEquals("proj", cfg.provider().model(), "project file must be taken from the configured working dir");
    }
}
