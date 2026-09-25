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
package io.yupiik.ia.agent.core.shell.slash;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.cli.slash.SlashCommandSpec;
import io.yupiik.ia.agent.core.cli.slash.SlashGroup;
import io.yupiik.ia.agent.core.session.AgentSessionService;
import io.yupiik.ia.agent.core.session.SessionStore;
import io.yupiik.ia.agent.core.shell.ShellEnv;
import io.yupiik.ia.agent.core.shell.ShellOutcome;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Session lifecycle commands shared by every shell: new/resume/quit plus the
 * context dump and session persistence helpers.
 */
@ApplicationScoped
public class SessionCommands implements SlashCommandSet {
    @Override
    public String title() {
        return "Session commands";
    }

    private static final int MAX_RESUME_LIST = 25;
    private static final int RESUME_SUMMARY_WIDTH = 80;

    private final SessionStore sessionStore;
    private final AgentSessionService agentSessionService;

    public SessionCommands(final SessionStore sessionStore, final AgentSessionService agentSessionService) {
        this.sessionStore = sessionStore;
        this.agentSessionService = agentSessionService;
    }

    @Override
    public List<SlashCommand> commands() {
        return List.of(
                SlashCommand.of(
                        new SlashCommandSpec("/new", null, "start a fresh session", SlashGroup.SESSION),
                        SlashAction.sync((arg, env) -> {
                            env.session().sessionId(sessionStore.create());
                            env.session().activeAgent("");
                            env.transcript().reset();
                            return ShellOutcome.status(
                                    "new session " + env.session().sessionId());
                        })),
                SlashCommand.of(
                        new SlashCommandSpec("/resume", "<id>", "resume an existing session", SlashGroup.SESSION),
                        ArgCompletion.SESSIONS,
                        this::resume),
                SlashCommand.of(
                        new SlashCommandSpec("/quit", "[/exit]", "leave the session (or Ctrl-D)", SlashGroup.SESSION),
                        SlashAction.sync((arg, env) -> ShellOutcome.quit())),
                SlashCommand.of(
                        new SlashCommandSpec("/exit", null, "leave the session", SlashGroup.SESSION),
                        SlashAction.sync((arg, env) -> ShellOutcome.quit())),
                SlashCommand.of(
                        new SlashCommandSpec(
                                "/context", "[path]", "dump raw context to stdout or a file", SlashGroup.SESSION),
                        ArgCompletion.PATH,
                        SlashAction.sync(this::context)),
                SlashCommand.of(
                        new SlashCommandSpec(
                                "/session-id",
                                null,
                                "show the current session identifier and its path",
                                SlashGroup.SESSION),
                        SlashAction.sync((arg, env) -> {
                            final var info = agentSessionService.sessionInfo(
                                    env.session().sessionId());
                            return ShellOutcome.status("session " + info.id() + " -> " + info.path());
                        })),
                SlashCommand.of(
                        new SlashCommandSpec(
                                "/session-flush", null, "rewrite the current session to disk", SlashGroup.SESSION),
                        SlashAction.sync((arg, env) -> ShellOutcome.status(
                                agentSessionService.flush(env.session().sessionId())))),
                SlashCommand.of(
                        new SlashCommandSpec("/clear", null, "clear the conversation", SlashGroup.SESSION),
                        SlashAction.sync((arg, env) -> {
                            env.transcript().clearConversation();
                            env.frontend().clear();
                            return ShellOutcome.none();
                        })));
    }

    private CompletionStage<ShellOutcome> resume(final String arg, final ShellEnv env) {
        if (!arg.isBlank()) {
            return CompletableFuture.completedStage(doResume(arg.strip(), env));
        }
        final var sessions = sessionStore.list().stream().limit(MAX_RESUME_LIST).toList();
        if (sessions.isEmpty()) {
            return CompletableFuture.completedStage(ShellOutcome.status("no session to resume"));
        }
        final var labels = sessions.stream()
                .map(s -> s.id() + "  (" + s.sizeBytes() + " bytes)"
                        + (sessionStore.summary(s.id()).isBlank()
                                ? ""
                                : " -- " + SlashRegistry.firstLine(sessionStore.summary(s.id()), RESUME_SUMMARY_WIDTH)))
                .toList();
        return env.io()
                .pick("resume session", labels)
                .thenApply(selected -> selected.isEmpty()
                        ? ShellOutcome.status("resume cancelled")
                        : doResume(selected.get().split("\\s+")[0], env));
    }

    private ShellOutcome doResume(final String id, final ShellEnv env) {
        env.session().sessionId(id);
        env.transcript().reset();
        final var markdown = env.transcript().replay(sessionStore.load(id));
        env.frontend().onReplay(markdown);
        return ShellOutcome.status("resumed session " + id);
    }

    private ShellOutcome context(final String arg, final ShellEnv env) {
        final var dump = agentSessionService.context(
                env.session().sessionId(), env.session().runtime());
        final var sb = new StringBuilder();
        sb.append("**System prompt**\n\n```text\n").append(dump.systemPrompt()).append("\n```\n");
        sb.append("\n**Session ")
                .append(dump.sessionId())
                .append(" (")
                .append(dump.messages().size())
                .append(" messages)**\n\n");
        for (final var message : dump.messages()) {
            if ("assistant".equals(message.role())
                    && message.content() != null
                    && message.content().isBlank()) {
                continue;
            }
            final var toolName = message.toolName();
            sb.append("- **")
                    .append(message.role())
                    .append(toolName == null ? "" : " (" + toolName + ")")
                    .append("**: ")
                    .append(SlashRegistry.firstLine(String.valueOf(message.content()), 120))
                    .append('\n');
        }
        final var markdown = sb.toString();
        if (arg == null || arg.isBlank()) {
            return ShellOutcome.transcript(markdown);
        }
        try {
            final var target = Path.of(arg).toAbsolutePath();
            Files.createDirectories(target.getParent() == null ? Path.of(".") : target.getParent());
            Files.writeString(target, markdown);
            return ShellOutcome.status("context written to " + target);
        } catch (final IOException e) {
            return ShellOutcome.error("context failed: " + e.getMessage());
        }
    }
}
