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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.agent.AgentEventBridge;
import io.yupiik.ia.agent.core.agent.AgentLoop;
import io.yupiik.ia.agent.core.agent.AgentProvider;
import io.yupiik.ia.agent.core.configuration.PermissionMode;
import io.yupiik.ia.agent.core.configuration.PermissionsConfiguration;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.question.UserFeedbackService;
import io.yupiik.ia.agent.core.session.SessionStore;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunCommandTest {
    @TempDir
    Path temp;

    private PrintStream originalOut;
    private PrintStream originalErr;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @BeforeEach
    void capture() {
        originalOut = System.out;
        originalErr = System.err;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restore() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    @Test
    void happyPathPrintsDeltas() {
        final var bridge = new AgentEventBridge();
        final var calls = new ArrayList<String[]>();
        final var agentLoop = new AgentLoop() {
            @Override
            public String run(
                    final String prompt,
                    final String agentName,
                    final String sessionId,
                    final List<Message> history,
                    final BooleanSupplier cancelled) {
                calls.add(new String[] {prompt, agentName, sessionId});
                assertNull(history);
                bridge.onEvent(new AgentEvent.TextDelta("hel"));
                bridge.onEvent(new AgentEvent.TextDelta("lo"));
                bridge.onEvent(new AgentEvent.TurnDone("hello"));
                return "ok";
            }
        };
        final var command = new RunCommand(
                new RunCommand.RunConf("say hi", "", "", false, true),
                agentLoop,
                bridge,
                new PermissionService(null),
                new UserFeedbackService(),
                sessionStore(),
                null);

        command.run();

        assertEquals(1, calls.size());
        assertEquals("say hi", calls.get(0)[0]);
        assertNull(calls.get(0)[1]);
        assertEquals("s-run", calls.get(0)[2]);
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("hello"));
    }

    @Test
    void errorEventFails() {
        final var bridge = new AgentEventBridge();
        final var agentLoop = new AgentLoop() {
            @Override
            public String run(
                    final String prompt,
                    final String agentName,
                    final String sessionId,
                    final List<Message> history,
                    final BooleanSupplier cancelled) {
                lastSessionIdUsed.set(sessionId);
                bridge.onEvent(new AgentEvent.Error("boom"));
                return "ko";
            }
        };
        final var command = new RunCommand(
                new RunCommand.RunConf("say hi", "s-2", "", false, true),
                agentLoop,
                bridge,
                new PermissionService(null),
                new UserFeedbackService(),
                sessionStore(),
                null);

        final var error = assertThrows(IllegalStateException.class, command::run);

        assertEquals("s-2", lastSessionIdUsed.get());
        assertTrue(error.getMessage().contains("boom"));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("[error] boom"));
    }

    @Test
    void deniedApprovalExplainsHeadlessMode() {
        final var configuration = MockLlmServer.configuration(
                "http://localhost:1/v1",
                temp.toString(),
                new PermissionsConfiguration(PermissionMode.ASK, List.of(), List.of(), List.of()));
        final var permissions = new PermissionService(configuration);
        final var allowed = new AtomicBoolean(true);
        final var bridge = new AgentEventBridge();
        final var agentLoop = new AgentLoop() {
            @Override
            public String run(
                    final String prompt,
                    final String agentName,
                    final String sessionId,
                    final List<Message> history,
                    final BooleanSupplier cancelled) {
                final var decision = permissions.check("bash", ToolCategory.BASH, Map.of("command", "rm -rf /"));
                allowed.set(decision.allowed());
                return "ok";
            }
        };
        final var command = new RunCommand(
                new RunCommand.RunConf("say hi", "", "", false, false),
                agentLoop,
                bridge,
                permissions,
                new UserFeedbackService(),
                sessionStore(),
                new AgentProvider(configuration));

        command.run();

        assertFalse(allowed.get(), "headless gate must deny the tool call");
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("[denied]"));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("headless mode"));
    }

    @Test
    void unknownAgentIsRejected() {
        final var configuration = MockLlmServer.configuration("http://localhost:1/v1", temp.toString());
        final var bridge = new AgentEventBridge();
        final var command = new RunCommand(
                new RunCommand.RunConf("say hi", "", "nope", false, true),
                stubAgentLoop(bridge),
                bridge,
                new PermissionService(null),
                new UserFeedbackService(),
                sessionStore(),
                new AgentProvider(configuration));

        final var error = assertThrows(IllegalArgumentException.class, command::run);
        assertTrue(error.getMessage().contains("Unknown agent"));
    }

    private final AtomicReference<String> lastSessionIdUsed = new AtomicReference<>();

    private AgentLoop stubAgentLoop(final AgentEventBridge bridge) {
        return new AgentLoop() {
            @Override
            public String run(
                    final String prompt,
                    final String agentName,
                    final String sessionId,
                    final List<Message> history,
                    final BooleanSupplier cancelled) {
                lastSessionIdUsed.set(sessionId);
                return "ok";
            }
        };
    }

    private SessionStore sessionStore() {
        return new SessionStore() {
            @Override
            public String create() {
                return "s-run";
            }

            @Override
            public List<Message> load(final String id) {
                return List.of();
            }
        };
    }
}
