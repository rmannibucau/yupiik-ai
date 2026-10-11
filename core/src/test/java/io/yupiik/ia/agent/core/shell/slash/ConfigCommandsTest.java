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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.agent.AgentLoop;
import io.yupiik.ia.agent.core.configuration.MetricsConfiguration;
import io.yupiik.ia.agent.core.configuration.MetricsDisplayMode;
import io.yupiik.ia.agent.core.configuration.ModelVariantConfiguration;
import io.yupiik.ia.agent.core.context.UsageTracker;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.llm.Usage;
import io.yupiik.ia.agent.core.shell.ConversationTranscript;
import io.yupiik.ia.agent.core.shell.SessionRuntime;
import io.yupiik.ia.agent.core.shell.ShellEnv;
import io.yupiik.ia.agent.core.shell.ShellOutcome;
import io.yupiik.ia.agent.core.shell.ShellSession;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConfigCommandsTest {
    private final ConfigCommands commands = new ConfigCommands(null, null, null, null);

    private ShellOutcome run(final String arg, final ShellSession session, final ConversationTranscript transcript) {
        final var cmd = commands.commands().stream()
                .filter(c -> c.spec().name().equals("/metrics"))
                .findFirst()
                .orElseThrow();
        return cmd.action()
                .apply(arg, new ShellEnv(null, null, session, transcript))
                .toCompletableFuture()
                .join();
    }

    private static ShellSession session() {
        return new ShellSession();
    }

    private static ShellSession sessionWithUsage(final UsageTracker usage) {
        return new ShellSession().runtime(new SessionRuntime(null, null, null, usage, null, null, null, null));
    }

    /** A session whose per-tab selection is backed by a minimal agent loop (geometry-free). */
    private static ShellSession sessionWithLoop() {
        return new ShellSession()
                .runtime(new SessionRuntime(new AgentLoop() {}, null, null, null, null, null, null, null));
    }

    /** A ChatClient stub exposing a fixed model and variant set for the /variant command. */
    private static ChatClient variantClient() {
        return new ChatClient() {
            @Override
            public String model(final ChatSettings settings) {
                return "test-model";
            }

            @Override
            public List<ModelVariantConfiguration> variants(final ChatSettings settings, final String model) {
                return List.of(
                        new ModelVariantConfiguration("fast", null, null, null, null, null, null),
                        new ModelVariantConfiguration("balanced", null, null, null, null, null, null));
            }

            @Override
            public List<ModelVariantConfiguration> variants(final ChatSettings settings) {
                return variants(settings, model(settings));
            }
        };
    }

    private ShellOutcome runVariant(final String arg, final ShellSession session) {
        final var cmd = new ConfigCommands(null, variantClient(), null, null)
                .commands().stream()
                        .filter(c -> c.spec().name().equals("/variant"))
                        .findFirst()
                        .orElseThrow();
        return cmd.action()
                .apply(arg, new ShellEnv(null, null, session, new ConversationTranscript()))
                .toCompletableFuture()
                .join();
    }

    @Test
    void defaultPrintsConfigAndNoTurnYet() {
        final var outcome = run("", session(), new ConversationTranscript());
        assertEquals(
                "metrics: showPerTurn=true showSessionTotals=true displayMode=COMPACT\n(no turn yet)",
                ((ShellOutcome.Status) outcome).message());
    }

    @Test
    void defaultPrintsLastTurnValues() {
        final var transcript = new ConversationTranscript();
        transcript.onEvent(new AgentEvent.TurnMetrics(3, 120, 300, 500, 5, 1500, "session-1"));
        final var outcome = run("", session(), transcript);
        assertEquals(
                "metrics: showPerTurn=true showSessionTotals=true displayMode=COMPACT\n"
                        + "3 LLM (120ms think) · 5 tools (300ms/500ms) · 1.5s",
                ((ShellOutcome.Status) outcome).message());
    }

    @Test
    void defaultAppendsSessionTotalsWhenRequestsRan() {
        final var usage = new UsageTracker(null, null, null, null);
        usage.record(new Usage(100, 50, 150));
        final var outcome = run("", sessionWithUsage(usage), new ConversationTranscript());
        assertEquals(
                "metrics: showPerTurn=true showSessionTotals=true displayMode=COMPACT\n"
                        + "(no turn yet) · session: 1 LLM calls",
                ((ShellOutcome.Status) outcome).message());
    }

    @Test
    void showAndHideToggleTheWholeDisplay() {
        final var session = session();
        run("hide", session, new ConversationTranscript());
        assertFalse(session.metrics().showPerTurn());
        assertFalse(session.metrics().showSessionTotals());
        run("show", session, new ConversationTranscript());
        assertTrue(session.metrics().showPerTurn());
        assertTrue(session.metrics().showSessionTotals());
    }

    @Test
    void compactAndFullSwitchTheModeOnly() {
        final var session = session();
        session.metrics(new MetricsConfiguration(false, false, MetricsDisplayMode.COMPACT));
        run("full", session, new ConversationTranscript());
        assertEquals(MetricsDisplayMode.FULL, session.metrics().displayMode());
        assertFalse(session.metrics().showPerTurn(), "the display toggles must be preserved");
        assertFalse(session.metrics().showSessionTotals(), "the display toggles must be preserved");
        run("compact", session, new ConversationTranscript());
        assertEquals(MetricsDisplayMode.COMPACT, session.metrics().displayMode());
    }

    @Test
    void sessionTokenRequiresOnOrOff() {
        final var session = session();
        assertEquals(
                "usage: /metrics session on|off",
                ((ShellOutcome.Status) run("session", session, new ConversationTranscript())).message());
        assertEquals(
                "usage: /metrics session on|off",
                ((ShellOutcome.Status) run("session foo", session, new ConversationTranscript())).message());
        run("session on", session, new ConversationTranscript());
        assertTrue(session.metrics().showSessionTotals());
        run("session off", session, new ConversationTranscript());
        assertFalse(session.metrics().showSessionTotals());
    }

    @Test
    void unknownTokenReportsUsage() {
        final var outcome = run("bogus", session(), new ConversationTranscript());
        assertEquals(
                "usage: /metrics [show|hide|compact|full|session on|off]", ((ShellOutcome.Status) outcome).message());
    }

    @Test
    void variantArgSetsTheVariantOfTheCurrentModel() {
        final var session = sessionWithLoop();
        final var outcome = runVariant("fast", session);
        assertEquals("fast", session.chatSettings().variant(), "the named variant is selected");
        assertTrue(((ShellOutcome.Status) outcome).message().contains("[variant fast]"));
    }

    @Test
    void variantDefaultClearsTheSelectionAndSampling() {
        final var session = sessionWithLoop();
        session.runtime().updateChatSettings(session.chatSettings().withVariant("fast"));
        final var outcome = runVariant("default", session);
        assertEquals(null, session.chatSettings().variant(), "default clears the forced variant");
        assertEquals(null, session.chatSettings().sampling());
        assertTrue(((ShellOutcome.Status) outcome).message().contains("[variant default]"));
    }

    @Test
    void variantStatusReportsModelAndActiveVariant() {
        final var session = sessionWithLoop();
        final var outcome = runVariant("balanced", session);
        final var message = ((ShellOutcome.Status) outcome).message();
        assertTrue(message.contains("model: test-model"), message);
        assertTrue(message.contains("[variant balanced]"), message);
    }
}
