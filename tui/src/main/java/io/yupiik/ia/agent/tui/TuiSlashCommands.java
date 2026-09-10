package io.yupiik.ia.agent.tui;

import io.yupiik.ia.agent.core.cli.slash.BaseSlashCommands;
import io.yupiik.ia.agent.core.cli.slash.SlashCommandSpec;
import io.yupiik.ia.agent.core.cli.slash.SlashGroup;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.order.Order;

import java.util.List;
import java.util.stream.Stream;

@ApplicationScoped
@Order(2000)
public class TuiSlashCommands extends BaseSlashCommands {
    @Override
    public List<SlashCommandSpec> commands() {
        return Stream.concat(
                        super.commands().stream(),
                        Stream.of(new SlashCommandSpec("/theme", "(light|dark)", "switch between light and dark themes (persisted)", SlashGroup.CONFIG)))
                .toList();
    }
}