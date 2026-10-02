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
package io.yupiik.ia.agent.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.test.TestJsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(TestJsonMapper.class)
class ToolOutputFormatTest {
    private JsonMapper mapper;
    private ToolOutputFormat service;

    @BeforeEach
    void setUp(final JsonMapper mapper) {
        this.mapper = mapper;
        service = new ToolOutputFormat(mapper);
    }

    @Test
    void compactJsonDetectedAndPrettyPrinted() {
        assertSame(ToolOutputFormat.Format.JSON, service.detect("{\"a\":1,\"b\":2}"));
        final var pretty = service.prettyJson("{\"a\":1,\"b\":2}");
        assertTrue(pretty.contains("\n"), pretty);
        assertTrue(pretty.contains("\"a\""), pretty);
    }

    @Test
    void invalidJsonStaysText() {
        assertSame(ToolOutputFormat.Format.TEXT, service.detect("{oops"));
        assertEquals("{oops", service.prettyJson("{oops"));
    }

    @Test
    void xmlDetectedAndPrettyPrinted() {
        assertSame(ToolOutputFormat.Format.XML, service.detect("<root><a>1</a><b>2</b></root>"));
        final var pretty = service.prettyXml("<root><a>1</a><b>2</b></root>");
        assertTrue(pretty.contains("\n"), pretty);
    }

    @Test
    void doctypeXmlRejectedForSecurity() {
        final var raw = "<!DOCTYPE foo [ ]><root>secret</root>";
        assertSame(ToolOutputFormat.Format.TEXT, service.detect(raw));
        assertEquals(raw, service.prettyXml(raw));
    }

    @Test
    void yamlHeuristic() {
        assertSame(ToolOutputFormat.Format.YAML, service.detect("a: 1\nb: 2"));
    }

    @Test
    void diffSampleWins() {
        assertSame(ToolOutputFormat.Format.DIFF, service.detect("--- a/file\n+++ b/file\n-x\n+y"));
    }

    @Test
    void plainLogStaysText() {
        assertSame(ToolOutputFormat.Format.TEXT, service.detect("2026-09-21 10:00:00 INFO boom\ndone"));
    }

    @Test
    void markdownDocumentWithFenceDetected() {
        assertSame(ToolOutputFormat.Format.MARKDOWN, service.detect("# Title\n\n```java\nint a = 1;\n```"));
    }

    @Test
    void markdownHeadingWithCompleteTableDetected() {
        assertSame(
                ToolOutputFormat.Format.MARKDOWN, service.detect("## Report\n\n| a | b |\n| --- | --- |\n| 1 | 2 |\n"));
    }

    @Test
    void markdownOneSignalStaysText() {
        assertSame(ToolOutputFormat.Format.TEXT, service.detect("# Title only"));
    }

    /**
     * The regression: a python {@code tabulate(...)} dump plus one list marker used to be
     * classified as markdown and rendered as a bordered grid, prefixing every line with a
     * vertical bar. Two loose signals are no longer enough.
     */
    @Test
    void pipeTableWithOneListMarkerStaysText() {
        final var tabulated = """
                | name | age |
                |------|-----|
                | bob  | 42  |
                - bob is 42
                """;
        assertSame(ToolOutputFormat.Format.TEXT, service.detect(tabulated));
    }

    /** A delimiter row without a body row is not a table, so a piped dump stays text. */
    @Test
    void incompleteTableStaysText() {
        assertSame(ToolOutputFormat.Format.TEXT, service.detect("# a\n\n| x | y |\n| --- | --- |\n"));
    }

    /** A proven source language wins over the loose yaml key heuristic. */
    @Test
    void detectedLanguageWinsOverYaml() {
        assertSame(ToolOutputFormat.Format.CODE, service.detect("name: bob\nage: 42", "python3 report.py"));
    }

    /** ...but json/xml/diff keep their dedicated rendering even for a code command. */
    @Test
    void jsonStillWinsOverDetectedLanguage() {
        assertSame(ToolOutputFormat.Format.JSON, service.detect("{\"a\":1,\"b\":2}", "python3 x.py"));
    }
}
