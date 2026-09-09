package io.yupiik.ia.agent.tui;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.cli.slash.SlashCommandSpec;
import io.yupiik.ia.agent.core.cli.slash.SlashCommands;
import io.yupiik.ia.agent.core.cli.slash.SlashGroup;

import java.util.List;

@ApplicationScoped
public class TuiSlashCommands implements SlashCommands {

    @Override
    public List<SlashCommandSpec> commands() {
        return List.of(
                new SlashCommandSpec("/help", null, "show this help", SlashGroup.OTHER),
                new SlashCommandSpec("/shortcuts", null, "show the keyboard shortcuts", SlashGroup.OTHER),
                new SlashCommandSpec("/new", null, "start a new session", SlashGroup.SESSION),
                new SlashCommandSpec("/resume", null, "resume the last session", SlashGroup.SESSION),
                new SlashCommandSpec("/compact", null, "compact the session context now", SlashGroup.MAINTAIN),
                new SlashCommandSpec("/model", "[$name]", "list /v1/models, or set model (Tab completes)", SlashGroup.CONFIG),
                new SlashCommandSpec("/agent", "[name]", "list custom agents, or set the active agent ('' clears, Tab completes)", SlashGroup.CONFIG),
                new SlashCommandSpec("/mode", "build|plan", "switch mode: /mode build or /mode plan", SlashGroup.CONFIG),
                new SlashCommandSpec("/editplan", "[path]", "in plan mode, export the plan to a file, edit it in your editor, then press Enter to reload (Esc or click Cancel to abort)", SlashGroup.MAINTAIN),
                new SlashCommandSpec("/memory", null, "show the project memory", SlashGroup.INSPECT),
                new SlashCommandSpec("/context", "[path]", "show the resolved context (system prompt + session), write to path when given", SlashGroup.INSPECT),
                new SlashCommandSpec("/permissions", "(all|none)", "allow all tools without asking (all), or restore interactive approval (none)", SlashGroup.CONFIG),
                new SlashCommandSpec("/theme", "(light|dark)", "switch between light and dark themes (persisted)", SlashGroup.CONFIG),
                new SlashCommandSpec("/tools", "[filters]", "list tools per MCP server; space separated filters must all match the lowercase server/tool name", SlashGroup.INSPECT),
                new SlashCommandSpec("/structural-code-graph", null, "resync the structural code graph (symbols, edges and doc tree) on demand", SlashGroup.MAINTAIN),
                new SlashCommandSpec("/clear", null, "clear the transcript", SlashGroup.SESSION),
                new SlashCommandSpec("/quit", "[/exit]", "exit yca", SlashGroup.SESSION));
    }
}