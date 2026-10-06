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
package io.yupiik.ia.agent.core.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.yupiik.fusion.framework.handlebars.HandlebarsCompiler;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FormatDatePromptHelperTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-24T10:11:12.345Z"), ZoneOffset.UTC);

    private static String render(final String template) {
        final var functions = new LinkedHashMap<
                String,
                java.util.function.Function<io.yupiik.fusion.framework.handlebars.helper.HelperContext, Object>>();
        functions.put("formatDate", new FormatDatePromptHelper(CLOCK)::apply);
        return new HandlebarsCompiler()
                .compile(new HandlebarsCompiler.CompilationContext(
                        new HandlebarsCompiler.Settings().helpers(functions), template))
                .render(Map.of());
    }

    @Test
    void defaultPatternIsTimeOnly() {
        assertEquals("10:11:12", render("{{formatDate}}"));
    }

    @Test
    void positionalPatternIsApplied() {
        assertEquals("2026-09-24 10:11:12.345", render("{{formatDate \"yyyy-MM-dd HH:mm:ss.SSS\"}}"));
    }

    @Test
    void blankPatternFallsBackToTheDefault() {
        assertEquals("10:11:12", render("{{formatDate \"\"}}"));
    }

    @Test
    void tooManyArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> render("{{formatDate \"HH:mm:ss\" \"extra\"}}"));
    }

    @Test
    void inlineCallRendersInThePromptLine() {
        assertEquals("t:10:11:12", render("t:{{formatDate}}"));
    }
}
