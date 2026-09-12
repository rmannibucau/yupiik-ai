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
package io.yupiik.ia.agent.core.optimize;

import static io.yupiik.ia.agent.core.optimize.Optimizers.matchesHead;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import java.util.ArrayList;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@ApplicationScoped
public class MavenOutputProcessor implements OutputProcessor {
    private static final Pattern RUNNING = Pattern.compile("\\[INFO] Running ([\\w.,\\s]+)$");
    private static final Pattern TESTS_RUN =
            Pattern.compile("Tests run: (\\d+), Failures: (\\d+), Errors: (\\d+), Skipped: (\\d+)");
    private static final Pattern NOISE =
            Pattern.compile("^\\[INFO] ?(Scanning for projects|Detecting the operating system|Using the .* encoding|"
                    + "Downloading from|Downloaded from|Progress \\(|--- [\\w-]+:[\\w.-]+:[\\w.-]+|"
                    + "<<<|>>>|Caching disabled|Remote snapshots).*|^\\[INFO] ?$");

    @Override
    public boolean supports(final String command) {
        return matchesHead(command, "mvn", "mvnd");
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public Set<String> names() {
        return Set.of("mvn", "mvnd", "mvnw");
    }

    @Override
    public Stream<String> process(final String command, final Stream<String> lines) {
        final var errors = new ArrayList<String>();
        final var warns = new ArrayList<String>();
        final var failingTests = new ArrayList<String>();
        final var passingTests = new ArrayList<String>();
        final var reactor = new ArrayList<String>();
        String buildLine = null;
        String currentTest = null;
        var inReactor = false;

        for (final var raw : lines.toArray(String[]::new)) {
            final var line = Optimizers.stripAnsi(raw);
            if (NOISE.matcher(line).matches()) {
                continue;
            }
            final var m = RUNNING.matcher(line);
            if (m.matches()) {
                currentTest = m.group(1).strip();
                continue;
            }
            final var tm = TESTS_RUN.matcher(line);
            if (tm.find()) {
                final var failures = Integer.parseInt(tm.group(2)) + Integer.parseInt(tm.group(3));
                if (failures > 0) {
                    failingTests.add(
                            currentTest != null ? "FAIL " + currentTest + "  (" + line.strip() + ")" : line.strip());
                } else if (currentTest != null) {
                    passingTests.add(currentTest);
                }
                currentTest = null;
                continue;
            }
            if (line.startsWith("[ERROR]")) {
                errors.add(line);
                continue;
            }
            if (line.startsWith("[WARNING]")) {
                if (warns.size() < 20) {
                    warns.add(line);
                } else if (warns.size() == 20) {
                    warns.add("[WARNING] ... more warnings omitted");
                }
                continue;
            }
            if (line.contains("Reactor Summary")) {
                inReactor = true;
                reactor.add(line.strip());
                continue;
            }
            if (inReactor) {
                if (line.startsWith("[INFO] BUILD") || line.startsWith("[INFO] ---")) {
                    inReactor = false;
                } else if (!line.isBlank() && line.startsWith("[INFO]")) {
                    reactor.add(line.strip());
                    continue;
                }
            }
            if (line.startsWith("[INFO] BUILD")) {
                buildLine = line.strip();
            }
        }

        final var out = new ArrayList<String>();
        if (buildLine != null) {
            out.add(buildLine);
        }
        if (!errors.isEmpty()) {
            out.add("Errors (" + errors.size() + "):");
            final var cap = Math.min(errors.size(), 20);
            for (var i = 0; i < cap; i++) {
                out.add(errors.get(i));
            }
            if (errors.size() > cap) {
                out.add("... +" + (errors.size() - cap) + " more errors");
            }
        }
        if (!failingTests.isEmpty()) {
            out.add("Failing tests:");
            failingTests.forEach(t -> out.add("  " + t));
        }
        if (reactor.size() > 2) {
            out.addAll(reactor);
        }
        if (!warns.isEmpty()) {
            out.add("Warnings (" + warns.size() + "):");
            out.addAll(warns);
        }
        if (!passingTests.isEmpty()) {
            out.add("Passing tests (" + passingTests.size() + "): " + String.join(", ", passingTests));
        }
        return out.stream();
    }
}
