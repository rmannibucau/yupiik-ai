package io.yupiik.ia.agent.core.cli.slash;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.cli.slash.SlashCommandSpec;
import io.yupiik.ia.agent.core.cli.slash.SlashGroup;

import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class ReplSlashCommands implements SlashCommands {
    @Override
    public List<SlashCommandSpec> commands() {
        final var out = new ArrayList<>(SlashCommands.commonCommands());
        out.add(new SlashCommandSpec("/todos", null, "show the todo list", SlashGroup.INSPECT));
        return List.copyOf(out);
    }
}