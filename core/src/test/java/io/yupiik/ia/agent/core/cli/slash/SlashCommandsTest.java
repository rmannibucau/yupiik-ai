package io.yupiik.ia.agent.core.cli.slash;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlashCommandsTest {

    private static Map<String, SlashCommandSpec> byDisplay(final List<SlashCommandSpec> commands) {
        return commands.stream().collect(Collectors.toMap(SlashCommandSpec::display, Function.identity()));
    }

    @Test
    void baseCommonCommands() {
        final var common = byDisplay(new BaseSlashCommands() {
        }.commands());
        assertEquals(23, common.size());
        assertTrue(common.containsKey("/help"));
        assertTrue(common.containsKey("/clear"));
        assertTrue(common.containsKey("/thinking show|hide"));
        assertTrue(common.containsKey("/tool-output show|hide"));
        assertTrue(common.containsKey("/session-id"));
        assertTrue(common.containsKey("/session-flush"));
        assertTrue(common.containsKey("/session-housekeeping"));
        assertTrue(common.containsKey("/snapshot save|cancel|restore|status"));
    }

    @Test
    void replAddsTodosOnTopOfCommon() {
        final var commands = byDisplay(new ReplSlashCommands().commands());
        assertEquals(24, commands.size());
        assertTrue(commands.containsKey("/todos"));
        assertTrue(commands.containsKey("/help"));
        assertFalse(commands.containsKey("/theme"));
    }
}