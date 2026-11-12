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
package io.yupiik.ia.agent.github;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class MentionParserTest {
    @Test
    void commands() {
        assertEquals(
                new MentionParser.Invocation("solve", "fix this"),
                MentionParser.parse("Please @Bot solve fix this", "bot").orElseThrow());
        assertEquals(
                new MentionParser.Invocation("review", ""),
                MentionParser.parse("@bot review", "bot").orElseThrow());
        assertEquals(
                "help", MentionParser.parse("@bot HELP", "bot").orElseThrow().kind());
        assertTrue(MentionParser.parse("@bot deploy now", "bot").isEmpty());
        assertTrue(MentionParser.parse("@bot solver", "bot").isEmpty());
        assertTrue(MentionParser.parse("@bot-else solve", "bot").isEmpty());
        assertTrue(MentionParser.parse("x@bot solve", "bot").isEmpty());
        assertTrue(MentionParser.parse("@bot[bot] solve", "bot").isEmpty());
        assertTrue(MentionParser.parse("@bot unknown\n@bot solve", "bot").isEmpty());
        assertTrue(MentionParser.parse(null, "bot").isEmpty());
    }

    @Test
    void ignoresTaskMarkedOutputRegardlessOfMentionLocationOrMarkerKind() {
        for (final var marker : new String[] {"<!-- yca-task:task-id -->", "<!-- yca-task:task-id:review -->"}) {
            assertTrue(MentionParser.parse(marker + "\nMention @bot review on a pull request", "bot")
                    .isEmpty());
            assertTrue(
                    MentionParser.parse("@bot solve fix this\n" + marker, "bot").isEmpty());
        }
        assertTrue(MentionParser.parse("Mention `@bot review` or `@bot solve <request>`", "bot")
                .isEmpty());
    }

    @Test
    void ignoresQuotedAndCodeMentions() {
        final var text = "> @bot solve quoted\n```java\n@bot solve fenced\n```\n"
                + "~~~\n@bot solve tilde\n~~~\n`@bot solve inline` and ``@bot review code``\n"
                + "@bot review real request";
        assertEquals(
                new MentionParser.Invocation("review", "real request"),
                MentionParser.parse(text, "bot").orElseThrow());
        assertTrue(MentionParser.parse("```\n@bot solve", "bot").isEmpty());
        assertTrue(MentionParser.parse("`@bot solve`", "bot").isEmpty());
        assertEquals(
                "help",
                MentionParser.parse("@yca[bot] help", "yca[bot]").orElseThrow().kind());
    }
}
