package io.yupiik.ia.agent.core.cli.slash;

import java.util.List;

/**
 * Commands shared by every shell (CLI and TUI). Concrete shells extend this
 * class and add only their own commands on top of the common list so the
 * common declarations are defined once.
 */
public abstract class BaseSlashCommands implements SlashCommands {

    @Override
    public List<SlashCommandSpec> commands() {
        return List.of(
                new SlashCommandSpec("/help", null, "show this help", SlashGroup.OTHER),
                new SlashCommandSpec("/shortcuts", null, "show the keyboard shortcuts", SlashGroup.OTHER),
                new SlashCommandSpec("/new", null, "start a fresh session", SlashGroup.SESSION),
                new SlashCommandSpec("/resume", "<id>", "resume an existing session", SlashGroup.SESSION),
                new SlashCommandSpec("/quit", "[/exit]", "leave the session (or Ctrl-D)", SlashGroup.SESSION),
                new SlashCommandSpec("/mode", "plan|build", "switch the agent working mode", SlashGroup.CONFIG),
                new SlashCommandSpec("/model", "[name]", "show or switch the model", SlashGroup.CONFIG),
                new SlashCommandSpec("/provider", "[id]", "show or switch the provider", SlashGroup.CONFIG),
                new SlashCommandSpec("/agent", "[name]", "show or set the active custom agent", SlashGroup.CONFIG),
                new SlashCommandSpec("/permissions", "all|none", "allow all tools or ask for approval", SlashGroup.CONFIG),
                new SlashCommandSpec("/context", "[path]", "dump raw context to stdout or a file", SlashGroup.INSPECT),
                new SlashCommandSpec("/memory", null, "show global and project memory", SlashGroup.INSPECT),
                new SlashCommandSpec("/tools", "[filter]", "list tools, optionally filtered", SlashGroup.INSPECT),
                new SlashCommandSpec("/compact", null, "compact the context window", SlashGroup.MAINTAIN),
                new SlashCommandSpec("/editplan", "[path]", "edit the current plan (in plan mode)", SlashGroup.MAINTAIN),
                new SlashCommandSpec("/structural-code-graph", null, "resync the code structure index", SlashGroup.MAINTAIN),
                new SlashCommandSpec("/clear", null, "clear the conversation", SlashGroup.SESSION));
    }
}