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

import io.yupiik.fusion.json.internal.JsonMapperImpl;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ToolOutputFormatTest {
    private JsonMapperImpl mapper;
    private ToolOutputFormat service;

    @BeforeEach
    void setUp() {
        mapper = new JsonMapperImpl(List.of(), key -> Optional.empty());
        service = new ToolOutputFormat(mapper);
    }

    @AfterEach
    void tearDown() {
        if (mapper != null) {
            mapper.close();
            mapper = null;
        }
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
    void markdownTwoSignalsDetected() {
        assertSame(ToolOutputFormat.Format.MARKDOWN, service.detect("# Title\n- item"));
    }

    @Test
    void markdownOneSignalStaysText() {
        assertSame(ToolOutputFormat.Format.TEXT, service.detect("# Title only"));
    }
}
