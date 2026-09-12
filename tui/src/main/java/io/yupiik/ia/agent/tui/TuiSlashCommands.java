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
package io.yupiik.ia.agent.tui;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.order.Order;
import io.yupiik.ia.agent.core.cli.slash.BaseSlashCommands;
import io.yupiik.ia.agent.core.cli.slash.SlashCommandSpec;
import io.yupiik.ia.agent.core.cli.slash.SlashGroup;
import java.util.List;
import java.util.stream.Stream;

@ApplicationScoped
@Order(2000)
public class TuiSlashCommands extends BaseSlashCommands {
    @Override
    public List<SlashCommandSpec> commands() {
        return Stream.concat(
                        super.commands().stream(),
                        Stream.of(new SlashCommandSpec(
                                "/theme",
                                "(light|dark)",
                                "switch between light and dark themes (persisted)",
                                SlashGroup.CONFIG)))
                .toList();
    }
}
