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
package io.yupiik.ia.agent.core.cli.slash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class SlashCommandsTest {

    private static Map<String, SlashCommandSpec> byDisplay(final List<SlashCommandSpec> commands) {
        return commands.stream().collect(Collectors.toMap(SlashCommandSpec::display, Function.identity()));
    }

    @Test
    void baseCommonCommands() {
        final var common = byDisplay(new BaseSlashCommands() {}.commands());
        assertEquals(25, common.size());
        assertTrue(common.containsKey("/help"));
        assertTrue(common.containsKey("/clear"));
        assertTrue(common.containsKey("/thinking show|hide"));
        assertTrue(common.containsKey("/tool-output show|hide"));
        assertTrue(common.containsKey("/session-id"));
        assertTrue(common.containsKey("/session-flush"));
        assertTrue(common.containsKey("/session-housekeeping"));
        assertTrue(common.containsKey("/snapshot save|cancel|restore|status"));
        assertTrue(common.containsKey("/mcp status|reset [server]"));
    }

    @Test
    void replAddsTodosOnTopOfCommon() {
        final var commands = byDisplay(new ReplSlashCommands().commands());
        assertEquals(26, commands.size());
        assertTrue(commands.containsKey("/todos"));
        assertTrue(commands.containsKey("/help"));
        assertFalse(commands.containsKey("/theme"));
    }
}
