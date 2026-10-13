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
package io.yupiik.ia.agent.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.yupiik.ia.agent.core.agent.AgentEvent;
import org.junit.jupiter.api.Test;

/** Mapping of the tool lifecycle events to the flat SSE DTO the browser consumes. */
class WebEventDtoTest {

    @Test
    void toolStreamCarriesNameAndFragment() {
        final var dto = WebEventDto.of(new AgentEvent.ToolStream("bash", "line 1", "s1", null, null));
        assertEquals("tool-stream", dto.type());
        // message = tool name (the frontend binds stream fragments to the running card by name)
        assertEquals("bash", dto.message());
        // the streamed fragment travels in data.fragment
        assertEquals("line 1", dto.data().get("fragment"));
        assertEquals(true, dto.data().get("stream"));
    }

    @Test
    void toolStartedAndFinishedCarryTheSameName() {
        final var started = WebEventDto.of(new AgentEvent.ToolStarted("write_file", "{}", 1, "s1", null, null));
        final var finished = WebEventDto.of(new AgentEvent.ToolFinished("write_file", "ok", true, "s1", null, null));
        assertEquals("tool-started", started.type());
        assertEquals("write_file", started.message());
        assertEquals(1L, started.data().get("ordinal"));
        assertEquals("tool-finished", finished.type());
        assertEquals("write_file", finished.message());
        assertEquals("ok", finished.data().get("output"));
        assertEquals(true, finished.data().get("success"));
    }
}
