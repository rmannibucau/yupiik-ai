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
package io.yupiik.ia.agent.core.wiring;

import io.yupiik.fusion.framework.api.configuration.Configuration;
import io.yupiik.fusion.framework.api.configuration.ConfigurationSource;
import io.yupiik.fusion.framework.api.container.configuration.ConfigurationRegistration;
import io.yupiik.fusion.framework.api.io.ReaderSupplier;
import io.yupiik.fusion.framework.api.main.ArgsConfigSource;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.event.OnEvent;
import io.yupiik.fusion.framework.build.api.order.Order;
import io.yupiik.fusion.json.configuration.JsonConfigurationSource;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;

@DefaultScoped
public class YcaConfigurationProducer {
    private static final String PREFIX = "yca.";
    private static final String INHERIT = "$inherit";
    private static final Function<String, String> KEY_NORMALIZER = key -> {
        final var stripped = key.startsWith(PREFIX) ? key.substring(PREFIX.length()) : key;
        return stripped.replace('-', '.');
    };

    private final ArgsConfigSource args;

    public YcaConfigurationProducer(final ArgsConfigSource args) {
        this.args = args;
    }

    public void register(@OnEvent @Order(Integer.MIN_VALUE + 1) final ConfigurationRegistration registration) {
        // first the CLI args (highest priority), wrapped so --provider-model etc. work as --yca-provider-model
        registration.addSource().accept(new YcaPrefixShortcutConfigurationSource(args));

        final var configuration = registration.configuration();
        final var source = createConfigurationSource(configuration);
        if (source != null) {
            registration.addSource().accept(source);
        }
    }

    /**
     * Builds the JSON configuration source for this invocation. When a project-local
     * {@code .yca/configuration.json} exists it is the primary source and, if it declares
     * {@code "$inherit": true}, is layered over the global configuration file. Otherwise the global
     * file alone is returned (legacy behavior).
     *
     * @return the source to register, or {@code null} when no configuration file applies
     */
    private ConfigurationSource createConfigurationSource(final Configuration configuration) {
        final var globalPath = resolveGlobalConfigurationFile(configuration);
        final var child = loadJson(projectConfigurationFile(configuration));
        if (child != null && "true".equals(child.get(INHERIT))) {
            // the project file inherits the global file as a low-priority base when it exists
            final var parent = loadJson(globalPath);
            return parent == null ? child : new CombinedConfigurationSource(child, parent);
        }
        return child != null ? child : loadJson(globalPath);
    }

    /**
     * Resolves the working-directory base for the project-local {@code .yca/configuration.json},
     * mirroring {@link CodingAgentConfiguration#resolveWorkingDir()}: the configured
     * {@code yca.workingDir} wins when set, otherwise the process working directory. Only the CLI
     * source is visible here (the globals are loaded below), so the override is read from there.
     */
    private static Path projectConfigurationFile(final Configuration configuration) {
        final var workingDir = configuration.get(PREFIX + "workingDir").orElse(".");
        return Path.of(workingDir).toAbsolutePath().normalize().resolve(".yca").resolve("configuration.json");
    }

    private static Path resolveGlobalConfigurationFile(final Configuration configuration) {
        return Path.of(configuration
                .get(PREFIX + "configuration.json")
                .orElseGet(YcaConfigurationProducer::defaultConfigurationFile));
    }

    private static String defaultConfigurationFile() {
        var home = System.getProperty(PREFIX + "storage.home", System.getenv("YCA_STORAGE_HOME"));
        if (home == null || home.isBlank()) {
            home = System.getProperty("user.home") + "/.yupiik/coding-agent";
        } else {
            home = CodingAgentConfiguration.expandHome(home);
        }
        return Path.of(home, "configuration.json").toString();
    }

    private ConfigurationSource loadJson(final Path path) {
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try (final var in = Files.newInputStream(path)) {
            final var bytes = in.readAllBytes();
            final ReaderSupplier supplier = () -> new InputStreamReader(new ByteArrayInputStream(bytes));
            return new JsonConfigurationSource(supplier, KEY_NORMALIZER);
        } catch (final IOException e) {
            throw new UncheckedIOException("Invalid configuration file: " + path, e);
        }
    }
}
