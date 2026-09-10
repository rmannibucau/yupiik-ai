package io.yupiik.ia.agent.core.cli.slash;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.cli.slash.SlashCommandSpec;
import io.yupiik.ia.agent.core.cli.slash.SlashGroup;

import java.util.List;
import java.util.stream.Stream;

@ApplicationScoped
public class ReplSlashCommands extends BaseSlashCommands {
    @Override
    public List<SlashCommandSpec> commands() {
        return Stream.concat(
                        super.commands().stream(),
                        Stream.of(new SlashCommandSpec("/todos", null, "show the todo list", SlashGroup.INSPECT)))
                .toList();
    }
}