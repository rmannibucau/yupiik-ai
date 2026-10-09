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

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.LogNoiseConfiguration;
import io.yupiik.ia.agent.core.configuration.LogNoisePatternConfiguration;
import io.yupiik.ia.agent.core.configuration.OptimizeConfiguration;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Shared framework/test-runner log-noise filter (yupiik-logging-jul, Spring Boot, Tomcat, Jetty
 * formats). Injected into the maven/gradle output processors (and reusable for spring-cli) so the
 * massive runtime INFO/debug logs of a test run do not ride every subsequent LLM turn.
 *
 * <p>The drop rules (their defaults live in {@link LogNoiseConfiguration#DEFAULT_EXCLUDES} /
 * {@link LogNoiseConfiguration#DEFAULT_INCLUDES}, i.e. the config model, not here) are matched with
 * {@link FastRegex}, a bounded matcher that short-circuits on inexpensive literal scans instead of
 * running a heavyweight regex on every emitted line.</p>
 *
 * <p>Matching is: a line is <em>kept</em> when it matches an include (even if an exclude also
 * matches); otherwise dropped when it matches an exclude. By default dropped levels are
 * {@code INFO/FINE/FINER/FINEST/CONFIG/DEBUG}; {@code WARNING/SEVERE/ERROR} are never dropped.</p>
 */
@ApplicationScoped
public class LogNoiseOutputProcessor implements OutputProcessor {

    private final List<FastRegex> excludes;
    private final List<FastRegex> includes;

    public LogNoiseOutputProcessor(final CodingAgentConfiguration configuration) {
        this(configuration.optimize());
    }

    protected LogNoiseOutputProcessor(final OptimizeConfiguration configuration) {
        final var logNoise = configuration == null ? null : configuration.logNoise();
        this.excludes = toFastRegex(logNoise == null ? null : logNoise.excludes());
        this.includes = toFastRegex(logNoise == null ? null : logNoise.includes());
    }

    public LogNoiseOutputProcessor() {
        this(OptimizeConfiguration.defaults());
    }

    private static List<FastRegex> toFastRegex(final List<LogNoisePatternConfiguration> list) {
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        return list.stream().map(p -> new FastRegex(p.pattern())).toList();
    }

    /** True when the line must be dropped from the tool output. */
    public boolean drop(final String line) {
        return !matches(includes, line) && matches(excludes, line);
    }

    private static boolean matches(final List<FastRegex> patterns, final String line) {
        for (final var p : patterns) {
            if (p.find(line)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean supports(final String command) {
        return true; // a generic filter: works for any command whose log-noise lines appear
    }

    @Override
    public Stream<String> process(final String command, final Stream<String> lines) {
        return lines.filter(l -> !drop(l));
    }

    @Override
    public Set<String> names() {
        return Set.of("logNoise");
    }

    @Override
    public int order() {
        return 2; // before the maven(10)/gradle(11) specific processors
    }
}
