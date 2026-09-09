package io.yupiik.ia.agent.core.cli.slash;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;

import java.util.List;

@ApplicationScoped
public class ReplSlashCommands implements SlashCommands {

    @Override
    public List<SlashCommandSpec> commands() {
        return List.of(
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
                new SlashCommandSpec("/todos", null, "show the todo list", SlashGroup.INSPECT),
                new SlashCommandSpec("/tools", "[filter]", "list tools, optionally filtered", SlashGroup.INSPECT),
                new SlashCommandSpec("/compact", null, "compact the context window", SlashGroup.MAINTAIN),
                new SlashCommandSpec("/editplan", "[path]", "edit the current plan (plan mode)", SlashGroup.MAINTAIN),
                new SlashCommandSpec("/structural-code-graph", null, "resync the code structure index", SlashGroup.MAINTAIN),
                new SlashCommandSpec("/help", null, "this help", SlashGroup.OTHER));
    }
}