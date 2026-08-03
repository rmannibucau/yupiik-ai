/*
 * Copyright (c) 2026 - present - Yupiik SAS - https://www.yupiik.com
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
package io.yupiik.fusion.a2a.documentation.build;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Rewrites the configuration table of the {@code README.adoc} from the one the minisite generates.
 * <p>
 * The reference lives in the {@code @Property} metadata of {@code A2AConfiguration}, which
 * {@code ConfigurationDocumentationGenerator} turns into {@code content/_partials/generated/documentation.a2a.adoc}. The
 * README used to repeat it by hand, so it drifted; this reads that generated table back and writes it into the block the
 * README marks as generated, which is why it must run <b>after</b> the generator in the {@code preActions} list.
 * <p>
 * The site table has four columns - name, environment variable, description, default - and the README has three, so the
 * environment variable is dropped: the README says once, above the table, how a key maps to one.
 * <p>
 * It is a build helper, not something the module ships: the {@code build} package is excluded from the jar.
 */
public class ReadmeConfigurationSynchronizer implements Runnable {
    /**
     * Opens the block this rewrites. Everything up to {@link #END} is replaced, so a row which is not generated - a key
     * of another module, a note - has to live outside of it.
     */
    private static final String START = "// <generated>";

    private static final String END = "// </generated>";

    private final Path readme;
    private final Path generated;

    /**
     * @param configuration {@code readme} and {@code generated}, the two files to synchronize.
     */
    public ReadmeConfigurationSynchronizer(final Map<String, String> configuration) {
        this.readme = Path.of(require(configuration, "readme"));
        this.generated = Path.of(require(configuration, "generated"));
    }

    @Override
    public void run() {
        try {
            final var rows = rows(Files.readAllLines(generated));
            if (rows.isEmpty()) {
                throw new IllegalStateException("No configuration row found in '" + generated + "'");
            }
            Files.write(readme, merge(Files.readAllLines(readme), rows));
        } catch (final IOException ioe) {
            throw new UncheckedIOException("Can't synchronize '" + readme + "'", ioe);
        }
    }

    /**
     * Reads the generated table, whose records are four consecutive cells - name, environment variable, description,
     * default - separated by blank lines.
     *
     * @param lines the generated partial.
     * @return one README row per documented key, in the order the generator sorted them.
     */
    private List<String> rows(final List<String> lines) {
        final var rows = new ArrayList<String>();
        final var cells = new ArrayList<String>(4);
        for (final var line : lines) {
            final var stripped = line.strip();
            if (stripped.startsWith("|===") || stripped.startsWith("[options=")) {
                continue;
            }
            if (!stripped.startsWith("|")) { // a blank line ends the record
                cells.clear();
                continue;
            }
            if (stripped.startsWith("|Name ") || stripped.startsWith("|Name|")) { // the header
                continue;
            }
            cells.add(stripped.substring(1).strip());
            if (cells.size() == 4) {
                rows.add("| " + cells.get(0) + " | " + cells.get(3) + " | " + cells.get(2));
                cells.clear();
            }
        }
        return rows;
    }

    /**
     * @param lines the README.
     * @param rows  what the generated block must contain.
     * @return the README with that block replaced.
     */
    private List<String> merge(final List<String> lines, final List<String> rows) {
        final var start = indexOf(lines, START);
        final var end = indexOf(lines, END);
        if (end < start) {
            throw new IllegalStateException("'" + END + "' comes before '" + START + "' in '" + readme + "'");
        }
        final var merged = new ArrayList<String>(lines.size());
        merged.addAll(lines.subList(0, start + 1));
        merged.addAll(rows);
        merged.addAll(lines.subList(end, lines.size()));
        return merged;
    }

    private int indexOf(final List<String> lines, final String marker) {
        for (int i = 0; i < lines.size(); i++) {
            if (marker.equals(lines.get(i).strip())) {
                return i;
            }
        }
        throw new IllegalStateException("No '" + marker + "' marker in '" + readme + "'");
    }

    private static String require(final Map<String, String> configuration, final String key) {
        final var value = configuration == null ? null : configuration.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing '" + key + "' in the pre-action configuration");
        }
        return value;
    }
}
