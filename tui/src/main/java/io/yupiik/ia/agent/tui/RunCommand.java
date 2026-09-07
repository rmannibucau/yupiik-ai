package io.yupiik.ia.agent.tui;

import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.cli.Command;
import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;
import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.agent.AgentLoop;
import java.util.concurrent.atomic.AtomicBoolean;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.session.SessionStore;

@DefaultScoped
@Command(name = "run", description = "Run a single headless prompt and print the answer on stdout.")
public class RunCommand implements Runnable {
    private final RunConf conf;
    private final AgentLoop agentLoop;
    private final AgentEventBridge bridge;
    private final PermissionService permissionService;
    private final SessionStore sessionStore;

    public RunCommand(final RunConf conf, final AgentLoop agentLoop, final AgentEventBridge bridge,
                      final PermissionService permissionService, final SessionStore sessionStore) {
        this.conf = conf;
        this.agentLoop = agentLoop;
        this.bridge = bridge;
        this.permissionService = permissionService;
        this.sessionStore = sessionStore;
    }

    @RootConfiguration("run")
    public record RunConf(
            @Property(required = true, documentation = "Prompt to send to the agent.") String prompt,
            @Property(defaultValue = "\"\"", documentation = "Session id to append to; a new session is created when empty.") String session,
            @Property(defaultValue = "false", documentation = "Print reasoning deltas on stderr.") boolean showThinking,
            @Property(defaultValue = "false", documentation = "Bypass permission checks and allow all tool calls.") boolean permitAll) {
    }

    @Override
    public void run() {
        final var sessionId = conf.session() == null || conf.session().isBlank()
                ? sessionStore.create() : conf.session();
        final var failed = new AtomicBoolean();
        bridge.sink(event -> {
            print(event, conf.showThinking());
            if (event instanceof AgentEvent.Error) {
                failed.set(true);
            }
        });
        permissionService.setGate((title, detail) -> {
            System.err.println("[denied] " + title + " requires an approval in headless mode (" + detail
                    + "); set yca.permissions.mode=yolo to auto allow.");
            return false;
        });
        permissionService.setPermitAll(conf.permitAll());
        try {
            agentLoop.run(conf.prompt(), sessionId, null, () -> false);
        } finally {
            System.out.flush();
            bridge.sink(null);
            permissionService.setGate(null);
            permissionService.setPermitAll(false);
        }
        if (failed.get()) {
            System.exit(1);
        }
    }

    private void print(final AgentEvent event, final boolean showThinking) {
        if (event instanceof final AgentEvent.TextDelta t) {
            System.out.print(t.delta());
        } else if (event instanceof final AgentEvent.ThinkingDelta t) {
            if (showThinking) {
                System.err.print(t.delta());
            }
        } else if (event instanceof final AgentEvent.ToolStarted t) {
            System.err.println("[tool] " + t.name());
        } else if (event instanceof final AgentEvent.ToolStream t) {
            System.err.println("[" + t.name() + "] " + t.fragment());
        } else if (event instanceof final AgentEvent.ToolFinished t) {
            if (!t.success()) {
                System.err.println("[tool failed] " + t.name());
            }
        } else if (event instanceof final AgentEvent.Compacted c) {
            System.err.println("[context] " + c.kind() + " applied");
        } else if (event instanceof final AgentEvent.Status s) {
            System.err.println("[yca] " + s.message());
        } else if (event instanceof final AgentEvent.Error e) {
            System.err.println("[error] " + e.message());
        }
    }
}
