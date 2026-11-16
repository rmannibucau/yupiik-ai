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
package io.yupiik.ia.agent.bot.runtime;

import io.yupiik.fusion.framework.api.configuration.Configuration;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/** Trusted forwarding configuration. These variables must never be passed to an agent. */
public final class ProviderBudgetEnvironment {
    public static final String ROOT = "YCA_PROVIDER_BUDGET_ROOT";
    public static final String ID = "YCA_PROVIDER_BUDGET_ID";
    public static final String SLOTS = "YCA_PROVIDER_BUDGET_SLOTS";
    public static final Set<String> KEYS = Set.of(ROOT, ID, SLOTS);

    private ProviderBudgetEnvironment() {}

    public static Map<String, String> select(final Configuration configuration, final Path... excludedRoots) {
        final var root = configuration.get(ROOT).orElse("");
        if (root.isBlank()) {
            if (configuration.get(ID).isPresent() || configuration.get(SLOTS).isPresent()) {
                throw new IllegalArgumentException("Incomplete provider request budget configuration");
            }
            return Map.of();
        }
        final var id = configuration.get(ID).orElse("");
        final var count = configuration.get(SLOTS).orElse("");
        if (!id.matches("[A-Za-z0-9_-]{1,64}") || !count.matches("[1-9][0-9]{0,3}")) {
            throw new IllegalArgumentException("Explicit provider budget identity and positive slot count required");
        }
        final var path = Path.of(root);
        if (!path.isAbsolute() || !path.normalize().equals(path) || root.contains(",") || root.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Provider budget requires a canonical absolute local directory");
        }
        var current = path.getRoot();
        for (final var part : path) {
            current = current.resolve(part);
            if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("Provider budget directory must be provisioned without symlinks");
            }
        }
        for (final var excluded : excludedRoots) {
            if (excluded != null) {
                final var normalized = excluded.toAbsolutePath().normalize();
                if (path.startsWith(normalized) || normalized.startsWith(path)) {
                    throw new IllegalArgumentException(
                            "Provider budget must be disjoint from agent and controller data");
                }
            }
        }
        return Map.of(ROOT, root, ID, id, SLOTS, count);
    }
}
