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
import io.yupiik.ia.agent.core.configuration.BashPermissionsConfiguration;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfigurations;
import io.yupiik.ia.agent.core.configuration.PermissionMode;
import io.yupiik.ia.agent.core.configuration.PermissionsConfiguration;
import io.yupiik.ia.agent.core.context.UsageTracker;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.question.UserFeedbackService;
import io.yupiik.ia.agent.core.runtime.StatusReporter;
import io.yupiik.ia.agent.core.session.SessionStore;
import io.yupiik.ia.agent.core.shell.RunShellFrontend;
import io.yupiik.ia.agent.core.shell.SessionRuntime;
import io.yupiik.ia.agent.core.shell.SessionRuntimeFactory;
import io.yupiik.ia.agent.core.shell.ShellRegistry;
import io.yupiik.ia.agent.core.todo.InMemoryTodoStore;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/**
 * Runs isolated from all other classes: it swaps the JVM-global {@code System.out}/{@code System.err}
 * and attaches handlers to the shared {@code java.util.logging} hierarchy, which is a JVM-wide contract
 * no {@code @ResourceLock} can guard against classes that merely <em>write</em> to those streams.
 */
@Isolated
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
    /** Concurrent: records are published by whichever thread logs, not always the test one. */
    private final Queue<String> logLines = new ConcurrentLinkedQueue<>();
    /**
     * Strong references to the loggers the handler is attached to. {@code LogManager} only keeps
     * loggers weakly, and a class literal ({@code RunShellFrontend.class}) does <em>not</em> trigger
     * static initialization: without these fields the logger created by {@link #capture()} can be
     * collected before the class under test initializes its own {@code LOGGER} static, which then
     * registers on a fresh logger the handler was never attached to - the records silently vanish,
     * intermittently and only under the GC pressure of a full suite.
     */
    private final List<Logger> capturedLoggers = new ArrayList<>();

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
        logLines.clear();
        capturedLoggers.clear();
        for (final var name : List.of(RunShellFrontend.class.getName(), RunCommand.class.getName())) {
            final var logger = Logger.getLogger(name);
            logger.addHandler(logHandler);
            capturedLoggers.add(logger);
        }
    }

    @AfterEach
    void restore() {
        System.setOut(originalOut);
        System.setErr(originalErr);
        capturedLoggers.forEach(logger -> logger.removeHandler(logHandler));
        capturedLoggers.clear();
    }

    private String logs() {
        return String.join("\n", logLines);
    }

    /**
     * Waits for a log record carrying {@code needle} instead of asserting immediately: a record is
     * published by the logger caller, which is not guaranteed to be this thread, so the assertion has
     * to race nothing.
     *
     * @param needle substring the expected record must carry
     */
    private void awaitLog(final String needle) {
        final var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!logs().contains(needle) && System.nanoTime() < deadline) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        assertTrue(logs().contains(needle), "expected a '" + needle + "' log record, got: [" + logs() + "]");
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
                shellRegistry.onEvent(new AgentEvent.TextDelta("hel", sessionId));
                shellRegistry.onEvent(new AgentEvent.TextDelta("lo", sessionId));
                shellRegistry.onEvent(new AgentEvent.TurnDone("hello", sessionId));
                return "ok";
            }
        };
        final var command = new RunCommand(
                new RunCommand.RunConf("say hi", "", "", false, true, "", "collapsed", "", ""),
                shellRegistry,
                runtimeFactory(agentLoop, new PermissionService(null)),
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
                shellRegistry.onEvent(new AgentEvent.Error("boom", sessionId));
                return "ko";
            }
        };
        final var command = new RunCommand(
                new RunCommand.RunConf("say hi", "s-2", "", false, true, "", "collapsed", "", ""),
                shellRegistry,
                runtimeFactory(agentLoop, new PermissionService(null)),
                sessionStore(),
                null);

        final var error = assertThrows(IllegalStateException.class, command::run);

        assertEquals("s-2", lastSessionIdUsed.get());
        assertTrue(error.getMessage().contains("boom"));
        awaitLog("boom");
    }

    @Test
    void deniedApprovalExplainsHeadlessBuildMode() {
        final var configuration = CodingAgentConfigurations.builder()
                .baseUrl("http://localhost:1/v1/chat/completions")
                .storageHome(temp.toString())
                .permissions(new PermissionsConfiguration(
                        PermissionMode.ASK, List.of(), List.of(), new BashPermissionsConfiguration(List.of())))
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
                new RunCommand.RunConf("say hi", "", "", false, false, "build", "collapsed", "", ""),
                shellRegistry,
                runtimeFactory(agentLoop, permissions),
                sessionStore(),
                new AgentProvider(configuration));

        command.run();

        assertFalse(allowed.get(), "headless gate must deny the tool call");
        awaitLog("denied");
        awaitLog("headless mode");
    }

    @Test
    void planModeHeadlessDeniesStateChangingToolInPermissionService() {
        final var configuration = CodingAgentConfigurations.builder()
                .baseUrl("http://localhost:1/v1/chat/completions")
                .storageHome(temp.toString())
                .permissions(new PermissionsConfiguration(
                        PermissionMode.ASK, List.of(), List.of(), new BashPermissionsConfiguration(List.of())))
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
                new RunCommand.RunConf("say hi", "", "", false, false, "", "collapsed", "", ""),
                shellRegistry,
                runtimeFactory(agentLoop, permissions),
                sessionStore(),
                new AgentProvider(configuration));

        command.run();

        assertFalse(allowed.get(), "state-changing bash must be refused in headless mode via the gate");
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
                new RunCommand.RunConf("say hi", "", "", false, false, "plan", "collapsed", "", ""),
                shellRegistry,
                runtimeFactory(agentLoop, permissions),
                sessionStore(),
                new AgentProvider(configuration));

        command.run();

        assertEquals(AgentMode.PLAN, modeSeen.get(), "the run --mode plan flag must switch to plan mode");
    }

    @Test
    void planModeStateChangingToolReachesGate() {
        final var configuration = CodingAgentConfigurations.builder()
                .baseUrl("http://localhost:1/v1/chat/completions")
                .storageHome(temp.toString())
                .permissions(new PermissionsConfiguration(
                        PermissionMode.ASK, List.of(), List.of(), new BashPermissionsConfiguration(List.of())))
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
                new RunCommand.RunConf("say hi", "", "", false, false, "plan", "collapsed", "", ""),
                shellRegistry,
                runtimeFactory(agentLoop, permissions),
                sessionStore(),
                new AgentProvider(configuration));

        command.run();

        // plan mode no longer hard-denies a state-changing command up front: it is routed
        // to the headless gate, which refuses it with the generic gate denial reason
        assertFalse(reason.get().contains("[denied]"));
        assertTrue(
                reason.get().contains("refused by the user"),
                "plan-mode state-changing tool must be refused by the gate: " + reason.get());
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
                shellRegistry.onEvent(new AgentEvent.ToolStarted("read_file", "{\"path\":\"a.txt\"}", sessionId));
                shellRegistry.onEvent(new AgentEvent.ToolFinished("read_file", "line1\nline2", true, sessionId));
                shellRegistry.onEvent(new AgentEvent.TurnDone("done", sessionId));
                return "ok";
            }
        };
        final var command = new RunCommand(
                new RunCommand.RunConf("say hi", "", "", false, false, "", "shown", "", ""),
                shellRegistry,
                runtimeFactory(agentLoop, new PermissionService(null)),
                sessionStore(),
                null);

        command.run();

        awaitLog("line1");
        awaitLog("line2");
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
                shellRegistry.onEvent(new AgentEvent.ToolStarted("bash", "{\"command\":\"echo hi\"}", sessionId));
                shellRegistry.onEvent(new AgentEvent.ToolStream("bash", "streamed line", sessionId));
                shellRegistry.onEvent(new AgentEvent.ToolFinished("bash", "streamed line", true, sessionId));
                shellRegistry.onEvent(new AgentEvent.TurnDone("done", sessionId));
                return "ok";
            }
        };
        final var command = new RunCommand(
                new RunCommand.RunConf("say hi", "", "", false, false, "", "shown", "", ""),
                shellRegistry,
                runtimeFactory(agentLoop, new PermissionService(null)),
                sessionStore(),
                null);

        command.run();

        // streamed tool output must not be re-printed from ToolFinished.output
        awaitLog("streamed line");
        assertEquals(1, logs().split("streamed line", -1).length - 1);
    }

    @Test
    void unknownAgentIsRejected() {
        final var configuration = CodingAgentConfigurations.builder()
                .baseUrl("http://localhost:1/v1/chat/completions")
                .storageHome(temp.toString())
                .build();
        final var shellRegistry = testRegistry();
        final var command = new RunCommand(
                new RunCommand.RunConf("say hi", "", "nope", false, true, "", "collapsed", "", ""),
                shellRegistry,
                runtimeFactory(stubAgentLoop(), new PermissionService(null)),
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

    private static SessionRuntimeFactory runtimeFactory(final AgentLoop loop, final PermissionService permissions) {
        return new SessionRuntimeFactory() {
            @Override
            public SessionRuntime create(final String sessionId) {
                return new SessionRuntime(
                        loop,
                        permissions,
                        new UserFeedbackService(),
                        new UsageTracker(null, null, null, Clock.systemUTC()),
                        new InMemoryTodoStore(),
                        new StatusReporter(),
                        null,
                        null);
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
