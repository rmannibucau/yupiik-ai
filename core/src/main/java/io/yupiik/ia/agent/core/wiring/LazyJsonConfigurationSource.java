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
import io.yupiik.fusion.framework.api.io.ReaderSupplier;
import io.yupiik.fusion.json.configuration.JsonConfigurationSource;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;

/**
 * Lazily-built {@link ConfigurationSource} that backs the global {@code ~/.yupiik/coding-agent/
 * configuration.json} and the project-local {@code .yca/configuration.json}, honoring the project
 * file's {@code "$inherit": true} merge semantics.
 * Set {@code yca.configuration.project.enabled=false} in trusted CLI/environment/system configuration
 * to ignore the project-local file entirely and load only the global configuration.
 *
 * <p>Everything is deferred to the first {@link #get(String)} call because that is the earliest point
 * at which the captured {@link Configuration} - the live one every bean reads from - is fully settled
 * (CLI args, environment, system properties and every source registered before the container started).
 * The file locations are then resolved through it rather than through raw
 * {@link System#getProperty}/{@link System#getenv} reads, so CLI/env/system-property driven values win
 * instead of being duplicated or ignored.</p>
 *
 * <p>The captured Configuration is the very one whose source list contains this instance: its
 * {@code get(...)} would otherwise re-enter {@link #get(String)} before {@link #inner} is assigned and
 * overflow the stack. A {@code building} flag makes such a nested {@link #get(String)} return
 * {@code null}, so the path-resolution lookups ({@code yca.configuration.json},
 * {@code yca.storage.home}, {@code user.home}, {@code yca.workingDir}) fall through to the base sources
 * that own them - the JSON file never produces those keys (its typed keys are camel-cased, e.g.
 * {@code yca.storageHome}), so falling through is always correct.</p>
 */
public class LazyJsonConfigurationSource implements ConfigurationSource {
    private static final String PREFIX = "yca.";
    private static final String INHERIT = "$inherit";
    private static final String GLOBAL_CONFIG_KEY = PREFIX + "configuration.json";
    private static final String PROJECT_ENABLED_KEY = PREFIX + "configuration.project.enabled";
    private static final String STORAGE_HOME_KEY = PREFIX + "storage.home";
    private static final String WORKING_DIR_KEY = PREFIX + "workingDir";
    private static final String USER_HOME_KEY = "user.home";
    private static final Function<String, String> KEY_NORMALIZER = key -> {
        final var stripped = key.startsWith(PREFIX) ? key.substring(PREFIX.length()) : key;
        return stripped.replace('-', '.');
    };

    private final Configuration configuration;

    /** Resolved and built on first {@link #get(String)}; still {@code null} before that and while building. */
    private volatile ConfigurationSource inner;

    /** Guards the build step so a re-entrant {@link #get(String)} does not recurse into {@link #build()}. */
    private boolean building;

    public LazyJsonConfigurationSource(final Configuration configuration) {
        this.configuration = configuration;
    }

    @Override
    public String get(final String key) {
        final var current = inner;
        if (current == null) {
            final var built = build();
            return built == null ? null : built.get(key);
        }
        return current.get(key);
    }

    private synchronized ConfigurationSource build() {
        var current = inner;
        if (current != null) {
            return current;
        }
        if (building) {
            // re-entrancy guard: a path-resolution lookup on the live Configuration triggered this
            // before inner was assigned - fall through to the base sources owning those keys
            return null;
        }
        building = true;
        try {
            current = createSource(configuration);
            inner = current;
            return current;
        } finally {
            building = false;
        }
    }

    private static ConfigurationSource createSource(final Configuration configuration) {
        final var globalPath = resolveGlobalConfigurationFile(configuration);
        if (!Boolean.parseBoolean(configuration.get(PROJECT_ENABLED_KEY).orElse("true"))) {
            return loadJson(globalPath);
        }
        final var child = loadJson(projectConfigurationFile(configuration));
        if (child != null && "true".equals(child.get(INHERIT))) {
            final var parent = loadJson(globalPath);
            return parent == null ? child : new CombinedConfigurationSource(child, parent);
        }
        return child != null ? child : loadJson(globalPath);
    }

    private static Path projectConfigurationFile(final Configuration configuration) {
        final var workingDir = configuration.get(WORKING_DIR_KEY).orElse(".");
        return Path.of(workingDir).toAbsolutePath().normalize().resolve(".yca").resolve("configuration.json");
    }

    private static Path resolveGlobalConfigurationFile(final Configuration configuration) {
        return Path.of(configuration.get(GLOBAL_CONFIG_KEY).orElseGet(() -> defaultConfigurationFile(configuration)));
    }

    /**
     * Global default location, resolved from the {@link Configuration} (which already abstracts
     * system properties, environment and CLI args) instead of raw {@code System.*} reads.
     */
    private static String defaultConfigurationFile(final Configuration configuration) {
        final var storageHome = configuration.get(STORAGE_HOME_KEY).orElse("").trim();
        if (storageHome.isEmpty()) {
            final var userHome = configuration.get(USER_HOME_KEY).orElse(".");
            return Path.of(userHome, ".yupiik", "coding-agent", "configuration.json")
                    .toString();
        }
        final var expanded = storageHome.startsWith("~/")
                ? configuration.get(USER_HOME_KEY).orElse(".") + storageHome.substring(1)
                : storageHome;
        return Path.of(expanded, "configuration.json").toString();
    }

    private static ConfigurationSource loadJson(final Path path) {
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try (final var in = Files.newInputStream(path)) {
            final var bytes = in.readAllBytes();
            final ReaderSupplier supplier = () -> new InputStreamReader(new ByteArrayInputStream(bytes));
            return new JsonConfigurationSource(supplier, KEY_NORMALIZER);
        } catch (final IOException e) {
            throw new UncheckedIOException("Invalid configuration file: '" + path + "'", e);
        }
    }
}
