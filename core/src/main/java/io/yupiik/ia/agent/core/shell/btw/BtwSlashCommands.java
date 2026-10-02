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
package io.yupiik.ia.agent.core.shell.btw;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.cli.slash.SlashCommandSpec;
import io.yupiik.ia.agent.core.cli.slash.SlashGroup;
import io.yupiik.ia.agent.core.shell.ShellEnv;
import io.yupiik.ia.agent.core.shell.ShellOutcome;
import io.yupiik.ia.agent.core.shell.slash.SlashCommand;
import io.yupiik.ia.agent.core.shell.slash.SlashCommandSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * The /btw slash command, shared by every shell. The TUI intercepts it before this
 * action to show the answer in a dedicated overlay instead of the transcript.
 */
@ApplicationScoped
public class BtwSlashCommands implements SlashCommandSet {
    private final BtwAnswerer answerer;

    public BtwSlashCommands(final BtwAnswerer answerer) {
        this.answerer = answerer;
    }

    @Override
    public String title() {
        return "Side questions";
    }

    @Override
    public List<SlashCommand> commands() {
        return List.of(SlashCommand.of(
                new SlashCommandSpec(
                        "/btw",
                        "<question>",
                        "ask a side question answered out of band: it does not interrupt the current turn and is not added to the conversation",
                        SlashGroup.OTHER),
                this::btw));
    }

    private CompletionStage<ShellOutcome> btw(final String arg, final ShellEnv env) {
        if (arg == null || arg.isBlank()) {
            return CompletableFuture.completedFuture(ShellOutcome.status("usage: /btw <question>"));
        }
        final var question = arg.strip();
        final var runtime = env.session().runtime();
        final var gate = runtime == null ? null : runtime.permissions().gate();
        return answerer.ask(
                        question,
                        env.session().sessionId(),
                        runtime == null ? null : runtime.usage(),
                        () -> false,
                        gate)
                .thenApply(answer -> ShellOutcome.transcript(rendered(question, answer)))
                .exceptionally(e -> ShellOutcome.error("btw failed: " + message(e)));
    }

    private String rendered(final String question, final String answer) {
        return "\n\n---\n\n**btw**\n\n*Q: " + question + "*\n\n" + answer + "\n";
    }

    private static String message(final Throwable error) {
        final var cause = error.getCause() == null ? error : error.getCause();
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
