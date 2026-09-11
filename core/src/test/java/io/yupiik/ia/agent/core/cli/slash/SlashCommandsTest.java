package io.yupiik.ia.agent.core.cli.slash;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlashCommandsTest {

    @Test
    void baseCommonCommands() {
        final var common = new BaseSlashCommands() {
        }.commands();
        assertEquals(19, common.size());
        assertEquals("/help", common.get(0).display());
        assertEquals("/clear", common.get(common.size() - 1).display());
        assertTrue(common.stream().anyMatch(c -> c.display().equals("/thinking show|hide")));
        assertTrue(common.stream().anyMatch(c -> c.display().equals("/tool-output show|hide")));
    }

    @Test
    void replAddsTodosOnTopOfCommon() {
        final var commands = new ReplSlashCommands().commands();
        assertEquals(20, commands.size());
        assertEquals("/todos", commands.get(commands.size() - 1).display());
        assertTrue(commands.stream().anyMatch(c -> c.display().equals("/help")));
        assertFalse(commands.stream().anyMatch(c -> c.display().equals("/theme")));
    }
}