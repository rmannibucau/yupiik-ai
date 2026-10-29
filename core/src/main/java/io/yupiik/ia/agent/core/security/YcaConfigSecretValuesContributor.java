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
package io.yupiik.ia.agent.core.security;

import io.yupiik.fusion.framework.api.configuration.Configuration;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Harvests secret values from the YCA {@code configuration.json} files: the project-local
 * {@code <workingDir>/.yca/configuration.json} and the global
 * {@code ~/.yupiik/coding-agent/configuration.json} (or the {@code yca.configuration.json} /
 * storage-home override). Both files are read directly from disk - even when a project-local file
 * without {@code $inherit} would otherwise shadow the global file in the resolved configuration - so
 * no secret is missed. Every {@code *apiKey*} leaf value and every header value whose name matches
 * {@code yca.secrets.http.headerNames} (token-after-first-space rule) is harvested.
 */
@ApplicationScoped
public class YcaConfigSecretValuesContributor implements SecretValuesContributor {

    private static final Logger LOGGER = Logger.getLogger(YcaConfigSecretValuesContributor.class.getName());

    private static final String GLOBAL_CONFIG_KEY = "yca.configuration.json";
    private static final String STORAGE_HOME_KEY = "yca.storage.home";
    private static final String WORKING_DIR_KEY = "yca.workingDir";
    private static final String USER_HOME_KEY = "user.home";

    private final CodingAgentConfiguration configuration;
    private final Configuration rawConfiguration;
    private final JsonMapper mapper;
    private final Set<String> headerNames;

    public YcaConfigSecretValuesContributor(
            final CodingAgentConfiguration configuration,
            final Configuration rawConfiguration,
            final JsonMapper mapper) {
        this.configuration = configuration;
        this.rawConfiguration = rawConfiguration;
        this.mapper = mapper;
        this.headerNames = httpHeaderNames(configuration);
    }

    /** Default known secret HTTP header names, when nothing is configured. */
    private static final List<String> DEFAULT_HTTP_HEADER_NAMES =
            List.of("Authorization", "Proxy-Authorization", "Api-Key", "X-Api-Key", "Cookie");

    @Override
    public Collection<String> values() {
        if (mapper == null || rawConfiguration == null) {
            return List.of();
        }
        final var out = new ArrayList<String>();
        final var visitor = new Visitor(out, headerNames, mapper);
        for (final var file : files()) {
            visitor.visit(file);
        }
        return out;
    }

    private List<Path> files() {
        final List<Path> out = new ArrayList<>();
        if (configuration != null && configuration.secrets() != null) {
            final var ycaConfig = configuration.secrets().ycaConfig();
            if (ycaConfig != null
                    && ycaConfig.locations() != null
                    && !ycaConfig.locations().isEmpty()) {
                for (final var location : ycaConfig.locations()) {
                    final var path = SecretPaths.resolve(configuration, location);
                    if (path != null) {
                        out.add(path);
                    }
                }
                return out;
            }
        }
        // no explicit locations: auto-compute local + global
        final var local = Path.of(rawConfiguration.get(WORKING_DIR_KEY).orElse("."))
                .toAbsolutePath()
                .normalize()
                .resolve(".yca")
                .resolve("configuration.json");
        out.add(local);
        out.add(globalConfigurationFile(rawConfiguration));
        return out;
    }

    private static Path globalConfigurationFile(final Configuration configuration) {
        final var override = configuration.get(GLOBAL_CONFIG_KEY).orElse(null);
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        final var storageHome = configuration.get(STORAGE_HOME_KEY).orElse("").trim();
        if (storageHome.isEmpty()) {
            return Path.of(
                    configuration.get(USER_HOME_KEY).orElse("."), ".yupiik", "coding-agent", "configuration.json");
        }
        final var expanded = storageHome.startsWith("~/")
                ? configuration.get(USER_HOME_KEY).orElse(".") + storageHome.substring(1)
                : storageHome;
        return Path.of(expanded, "configuration.json");
    }

    /** Walks one JSON file, harvesting apiKey leaves and secret-header values. */
    private static final class Visitor {
        private final Collection<String> out;
        private final Set<String> headerNames;
        private final JsonMapper mapper;

        private Visitor(final Collection<String> out, final Set<String> headerNames, final JsonMapper mapper) {
            this.out = out;
            this.headerNames = headerNames;
            this.mapper = mapper;
        }

        private void visit(final Path path) {
            if (path == null || !Files.isRegularFile(path)) {
                return;
            }
            try {
                final var json = Files.readString(path, StandardCharsets.UTF_8);
                final var root = mapper.read(Map.class, new StringReader(json));
                visitValue("", root);
            } catch (final IOException | RuntimeException e) {
                LOGGER.log(Level.FINE, () -> "Cannot parse YCA config " + path + ": " + e.getMessage());
            }
        }

        private void visitValue(final String key, final Object value) {
            if (value instanceof Map) {
                visitMap((Map<?, ?>) value);
            } else if (value instanceof Iterable) {
                for (final var item : (Iterable<?>) value) {
                    visitValue(key, item);
                }
            } else if (value instanceof CharSequence s) {
                harvest(key, s.toString());
            }
        }

        private void visitMap(final Map<?, ?> map) {
            for (final var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String k)) {
                    continue;
                }
                visitValue(k, entry.getValue());
            }
        }

        private void harvest(final String key, final String value) {
            if (value == null || value.isBlank()) {
                return;
            }
            final var compact = key.toLowerCase(Locale.ROOT)
                    .replace("_", "")
                    .replace("-", "")
                    .replace(".", "");
            if (headerNames.contains(key.toLowerCase(Locale.ROOT).trim())) {
                addToken(value); // a known secret header: keep only the credential part
            } else if (compact.contains("apikey")) {
                out.add(value); // any apikey-named leaf is a secret
            }
        }

        private void addToken(final String value) {
            final var space = value.indexOf(' ');
            final var token = space < 0 ? value : value.substring(space + 1).trim();
            if (!token.isBlank()) {
                out.add(token);
            }
        }
    }

    /** Reads the shared {@code yca.secrets.http.headerNames} allowlist with a fallback default. */
    static Set<String> httpHeaderNames(final CodingAgentConfiguration configuration) {
        if (configuration == null || configuration.secrets() == null) {
            return lower(DEFAULT_HTTP_HEADER_NAMES);
        }
        final var http = configuration.secrets().http();
        if (http == null || http.headerNames() == null || http.headerNames().isEmpty()) {
            return lower(DEFAULT_HTTP_HEADER_NAMES);
        }
        return lower(http.headerNames());
    }

    private static Set<String> lower(final List<String> names) {
        final var out = new java.util.HashSet<String>();
        for (final var n : names) {
            if (n != null) {
                out.add(n.trim().toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }
}
