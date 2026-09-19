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

import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.agent.AgentLoop;
import io.yupiik.ia.agent.core.agent.AgentProvider;
import io.yupiik.ia.agent.core.configuration.AgentMode;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfigurations;
import io.yupiik.ia.agent.core.configuration.PermissionMode;
import io.yupiik.ia.agent.core.configuration.PermissionsConfiguration;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.question.UserFeedbackService;
import io.yupiik.ia.agent.core.session.SessionStore;
import io.yupiik.ia.agent.core.shell.RunShellFrontend;
import io.yupiik.ia.agent.core.shell.ShellRegistry;
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
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

@ResourceLock(Resources.SYSTEM_OUT)
@ResourceLock(Resources.SYSTEM_ERR)
@ResourceLock("java.util.logging")
class RunCommandTest {
    @TempDir
    Path temp;

    private PrintStream originalOut;
    private PrintStream originalErr;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private final List<String> logLines = new ArrayList<>();
    private Handler logHandler;

    @BeforeEach
    void capture() {
        originalOut = System.out;
        originalErr = System.err;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        logHandler = new Handler() {
            @Override
            public void publish(final LogRecord record) {
                logLines.add(record.getMessage());
            }

            @Override
            public void flush() {
                // no-op
            }

            @Override
            public void close() {
                // no-op
            }
        };
        Logger.getLogger(RunShellFrontend.class.getName()).addHandler(logHandler);
        Logger.getLogger(RunCommand.class.getName()).addHandler(logHandler);
    }

    @AfterEach
    void restore() {
        System.setOut(originalOut);
        System.setErr(originalErr);
        Logger.getLogger(RunShellFrontend.class.getName()).removeHandler(logHandler);
        Logger.getLogger(RunCommand.class.getName()).removeHandler(logHandler);
    }

    private String logs() {
        return String.join("\n", logLines);
    }

    @Test
    void happyPathPrintsDeltas() {
        final var shellRegistry = testRegistry();
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
                shellRegistry.onEvent(new AgentEvent.TextDelta("hel"));
                shellRegistry.onEvent(new AgentEvent.TextDelta("lo"));
                shellRegistry.onEvent(new AgentEvent.TurnDone("hello"));
                return "ok";
            }
        };
        final var command = new RunCommand(
                new RunCommand.RunConf("say hi", "", "", false, true, "", false, "", ""),
                agentLoop,
                shellRegistry,
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
        final var shellRegistry = testRegistry();
        final var agentLoop = new AgentLoop() {
            @Override
            public String run(
                    final String prompt,
                    final String agentName,
                    final String sessionId,
                    final List<Message> history,
                    final BooleanSupplier cancelled) {
                lastSessionIdUsed.set(sessionId);
                shellRegistry.onEvent(new AgentEvent.Error("boom"));
                return "ko";
            }
        };
        final var command = new RunCommand(
                new RunCommand.RunConf("say hi", "s-2", "", false, true, "", false, "", ""),
                agentLoop,
                shellRegistry,
                new PermissionService(null),
                new UserFeedbackService(),
                sessionStore(),
                null);

        final var error = assertThrows(IllegalStateException.class, command::run);

        assertEquals("s-2", lastSessionIdUsed.get());
        assertTrue(error.getMessage().contains("boom"));
        assertTrue(logs().contains("boom"));
    }

    @Test
    void deniedApprovalExplainsHeadlessBuildMode() {
        final var configuration = CodingAgentConfigurations.builder()
                .baseUrl("http://localhost:1/v1/chat/completions")
                .storageHome(temp.toString())
                .permissions(new PermissionsConfiguration(PermissionMode.ASK, List.of(), List.of(), List.of()))
                .build();
        final var permissions = new PermissionService(configuration);
        permissions.setMode(AgentMode.BUILD);
        final var allowed = new AtomicBoolean(true);
        final var shellRegistry = testRegistry();
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
                new RunCommand.RunConf("say hi", "", "", false, false, "build", false, "", ""),
                agentLoop,
                shellRegistry,
                permissions,
                new UserFeedbackService(),
                sessionStore(),
                new AgentProvider(configuration));

        command.run();

        assertFalse(allowed.get(), "headless gate must deny the tool call");
        assertTrue(logs().contains("denied"));
        assertTrue(logs().contains("headless mode"));
    }

    @Test
    void planModeHeadlessDeniesStateChangingToolInPermissionService() {
        final var configuration = CodingAgentConfigurations.builder()
                .baseUrl("http://localhost:1/v1/chat/completions")
                .storageHome(temp.toString())
                .permissions(new PermissionsConfiguration(PermissionMode.ASK, List.of(), List.of(), List.of()))
                .build();
        final var permissions = new PermissionService(configuration);
        // default mode, falls back to PLAN
        final var allowed = new AtomicBoolean(true);
        final var shellRegistry = testRegistry();
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
                new RunCommand.RunConf("say hi", "", "", false, false, "", false, "", ""),
                agentLoop,
                shellRegistry,
                permissions,
                new UserFeedbackService(),
                sessionStore(),
                new AgentProvider(configuration));

        command.run();

        assertFalse(allowed.get(), "plan mode must hard-deny the tool call without consulting the gate");
    }

    @Test
    void planModeFlagPutsRunInPlanMode() {
        final var configuration = CodingAgentConfigurations.builder()
                .baseUrl("http://localhost:1/v1/chat/completions")
                .storageHome(temp.toString())
                .build();
        final var permissions = new PermissionService(configuration);
        final var modeSeen = new AtomicReference<AgentMode>();
        final var shellRegistry = testRegistry();
        final var agentLoop = new AgentLoop() {
            @Override
            public String run(
                    final String prompt,
                    final String agentName,
                    final String sessionId,
                    final List<Message> history,
                    final BooleanSupplier cancelled) {
                modeSeen.set(permissions.mode());
                return "ok";
            }
        };
        final var command = new RunCommand(
                new RunCommand.RunConf("say hi", "", "", false, false, "plan", false, "", ""),
                agentLoop,
                shellRegistry,
                permissions,
                new UserFeedbackService(),
                sessionStore(),
                new AgentProvider(configuration));

        command.run();

        assertEquals(AgentMode.PLAN, modeSeen.get(), "the run --mode plan flag must switch to plan mode");
    }

    @Test
    void planModeDenialSuggestsBuildMode() {
        final var configuration = CodingAgentConfigurations.builder()
                .baseUrl("http://localhost:1/v1/chat/completions")
                .storageHome(temp.toString())
                .permissions(new PermissionsConfiguration(PermissionMode.ASK, List.of(), List.of(), List.of()))
                .build();
        final var permissions = new PermissionService(configuration);
        final var reason = new AtomicReference<String>();
        final var shellRegistry = testRegistry();
        final var agentLoop = new AgentLoop() {
            @Override
            public String run(
                    final String prompt,
                    final String agentName,
                    final String sessionId,
                    final List<Message> history,
                    final BooleanSupplier cancelled) {
                final var decision = permissions.check("bash", ToolCategory.BASH, Map.of("command", "rm -rf /"));
                reason.set(decision.reason());
                return "ok";
            }
        };
        final var command = new RunCommand(
                new RunCommand.RunConf("say hi", "", "", false, false, "plan", false, "", ""),
                agentLoop,
                shellRegistry,
                permissions,
                new UserFeedbackService(),
                sessionStore(),
                new AgentProvider(configuration));

        command.run();

        assertFalse(reason.get().contains("[denied]"));
        assertTrue(
                reason.get().contains("--yca-run-mode build"),
                "plan-mode denial must suggest build mode: " + reason.get());
    }

    @Test
    void showToolOutputPrintsNonStreamedSuccess() {
        final var shellRegistry = testRegistry();
        final var agentLoop = new AgentLoop() {
            @Override
            public String run(
                    final String prompt,
                    final String agentName,
                    final String sessionId,
                    final List<Message> history,
                    final BooleanSupplier cancelled) {
                shellRegistry.onEvent(new AgentEvent.ToolStarted("read_file", "{\"path\":\"a.txt\"}"));
                shellRegistry.onEvent(new AgentEvent.ToolFinished("read_file", "line1\nline2", true));
                shellRegistry.onEvent(new AgentEvent.TurnDone("done"));
                return "ok";
            }
        };
        final var command = new RunCommand(
                new RunCommand.RunConf("say hi", "", "", false, false, "", true, "", ""),
                agentLoop,
                shellRegistry,
                new PermissionService(null),
                new UserFeedbackService(),
                sessionStore(),
                null);

        command.run();

        assertTrue(logs().contains("line1"));
        assertTrue(logs().contains("line2"));
    }

    @Test
    void showToolOutputSkipsStreamedTools() {
        final var shellRegistry = testRegistry();
        final var agentLoop = new AgentLoop() {
            @Override
            public String run(
                    final String prompt,
                    final String agentName,
                    final String sessionId,
                    final List<Message> history,
                    final BooleanSupplier cancelled) {
                shellRegistry.onEvent(new AgentEvent.ToolStarted("bash", "{\"command\":\"echo hi\"}"));
                shellRegistry.onEvent(new AgentEvent.ToolStream("bash", "streamed line"));
                shellRegistry.onEvent(new AgentEvent.ToolFinished("bash", "streamed line", true));
                shellRegistry.onEvent(new AgentEvent.TurnDone("done"));
                return "ok";
            }
        };
        final var command = new RunCommand(
                new RunCommand.RunConf("say hi", "", "", false, false, "", true, "", ""),
                agentLoop,
                shellRegistry,
                new PermissionService(null),
                new UserFeedbackService(),
                sessionStore(),
                null);

        command.run();

        // streamed tool output must not be re-printed from ToolFinished.output
        final var logged = logs();
        assertTrue(logged.contains("streamed line"));
        assertEquals(1, logged.split("streamed line", -1).length - 1);
    }

    @Test
    void unknownAgentIsRejected() {
        final var configuration = CodingAgentConfigurations.builder()
                .baseUrl("http://localhost:1/v1/chat/completions")
                .storageHome(temp.toString())
                .build();
        final var shellRegistry = testRegistry();
        final var command = new RunCommand(
                new RunCommand.RunConf("say hi", "", "nope", false, true, "", false, "", ""),
                stubAgentLoop(),
                shellRegistry,
                new PermissionService(null),
                new UserFeedbackService(),
                sessionStore(),
                new AgentProvider(configuration));

        final var error = assertThrows(IllegalArgumentException.class, command::run);
        assertTrue(error.getMessage().contains("Unknown agent"));
    }

    private final AtomicReference<String> lastSessionIdUsed = new AtomicReference<>();

    private AgentLoop stubAgentLoop() {
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

    private static ShellRegistry testRegistry() {
        return new ShellRegistry(new Emitter() {
            @Override
            public <T> void emit(final T event) {
                // events are forwarded synchronously to the registered frontend
            }
        });
    }
}
