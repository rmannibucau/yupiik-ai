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
package io.yupiik.ia.agent.core.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.core.cli.FormatDatePromptHelper;
import io.yupiik.ia.agent.core.configuration.LabelsConfiguration;
import io.yupiik.ia.agent.core.configuration.TestConfigs;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SpeakerLabelsPartialsTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2031-04-05T06:07:08Z"), ZoneId.systemDefault());
    private static final SpeakerLabels.Ctx CTX =
            new SpeakerLabels.Ctx("model", "provider", "agent", "plan", "s1", null);

    @Test
    void defaultsShareTheLogsPrefix() {
        final var labels = labels(Map.of("yca.labels.logsTemplate", "[shared]"));
        assertEquals("[shared] " + LabelsConfiguration.defaultUserLabel(), labels.userLabel(CTX));
        assertEquals("[shared] provider::model", labels.assistantLabel(CTX));
        assertEquals("[shared]", labels.logsLabel(CTX));
    }

    @Test
    void partialInheritsHelpersAndEachRenderingContext() {
        final var labels = labels(Map.of(
                "yca.labels.logsTemplate", "[{{formatDate \"yyyy-MM-dd HH:mm:ss\"}} {{model.provider}} {{agent}}]",
                "yca.labels.userTemplate", "{{> logsTemplate}} user",
                "yca.labels.assistantTemplate", "{{> logsTemplate}} assistant"));
        final var stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.systemDefault())
                .format(CLOCK.instant());
        assertEquals("[" + stamp + " provider agent] user", labels.userLabel(CTX));
        assertEquals("[" + stamp + " provider agent] assistant", labels.assistantLabel(CTX));
        final var other = new SpeakerLabels.Ctx("other", "second", "reviewer", "build", "s2", null);
        assertEquals("[" + stamp + " second reviewer] assistant", labels.assistantLabel(other));
    }

    @Test
    void blankPartialDoesNotExpandTheStandaloneLogsFallback() {
        for (final var source : List.of("", "  ", "{{#if missing}}hidden{{/if}}")) {
            final var labels = labels(Map.of("yca.labels.logsTemplate", source));
            assertEquals(LabelsConfiguration.defaultUserLabel(), labels.userLabel(CTX));
            assertEquals("provider::model", labels.assistantLabel(CTX));
            assertEquals("Logs", labels.logsLabel(CTX));
        }
    }

    @Test
    void existingInlineDateTemplatesStillWork() {
        final var labels = labels(Map.of(
                "yca.labels.userTemplate", "[{{formatDate \"yyyy\"}}] user",
                "yca.labels.assistantTemplate", "[{{formatDate \"yyyy\"}}] assistant"));
        assertEquals("[2031] user", labels.userLabel(CTX));
        assertEquals("[2031] assistant", labels.assistantLabel(CTX));
    }

    @Test
    void logsTemplateCannotReferenceAnyPartialEvenInsideAConditional() {
        for (final var source : List.of(
                "{{> logsTemplate}}",
                "{{> userTemplate}}",
                "{{> assistantTemplate}}",
                "{{> unknown}}",
                "{{#if missing}}{{> logsTemplate}}{{/if}}")) {
            final var error = assertThrows(
                    IllegalArgumentException.class, () -> labels(Map.of("yca.labels.logsTemplate", source)));
            assertTrue(error.getMessage().contains("yca.labels.logsTemplate"), error.getMessage());
            assertTrue(error.getMessage().contains("No partials"), error.getMessage());
        }
    }

    @Test
    void speakerTemplatesCannotReferenceEachOtherOrThemselves() {
        for (final var property : List.of("userTemplate", "assistantTemplate")) {
            for (final var partial : List.of("userTemplate", "assistantTemplate", "unknown")) {
                final var error = assertThrows(
                        IllegalArgumentException.class,
                        () -> labels(Map.of("yca.labels." + property, "{{> " + partial + "}}")));
                assertTrue(error.getMessage().contains("yca.labels." + property), error.getMessage());
            }
        }
    }

    private SpeakerLabels labels(final Map<String, String> overrides) {
        return new SpeakerLabels(List.of(new FormatDatePromptHelper(CLOCK)), TestConfigs.codingAgent(overrides), null);
    }
}
