package io.yupiik.ia.agent.tui;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.cli.slash.SlashCommandSpec;
import io.yupiik.ia.agent.core.cli.slash.SlashCommands;
import io.yupiik.ia.agent.core.cli.slash.SlashGroup;

import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class TuiSlashCommands implements SlashCommands {
    @Override
    public List<SlashCommandSpec> commands() {
        final var out = new ArrayList<>(SlashCommands.commonCommands());
        out.add(new SlashCommandSpec("/theme", "(light|dark)", "switch between light and dark themes (persisted)", SlashGroup.CONFIG));
        return List.copyOf(out);
    }
}