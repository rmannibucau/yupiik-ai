package io.yupiik.ia.agent.core.permissions;

import io.yupiik.ia.agent.core.configuration.AgentMode;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.PermissionMode;
import io.yupiik.ia.agent.core.configuration.PermissionsConfiguration;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionServiceTest {
    private PermissionService service(final PermissionMode mode, final List<String> allow, final List<String> deny) {
        return service(mode, allow, deny, List.of());
    }

    private PermissionService service(final PermissionMode mode, final List<String> allow, final List<String> deny,
                                      final List<String> planBash) {
        final var permissions = new PermissionsConfiguration(mode, allow, deny, planBash);
        final var configuration = new CodingAgentConfiguration("/tmp/yca-test", null, null, null, null, null, null, null, null, permissions, null, null, null, null);
        return new PermissionService(configuration);
    }

    @Test
    void readAlwaysAllowed() {
        final var decision = service(PermissionMode.ASK, List.of(), List.of()).check("grep", ToolCategory.READ, Map.of());
        assertTrue(decision.allowed());
    }

    @Test
    void denyBeatsAllow() {
        final var service = service(PermissionMode.YOLO, List.of("bash"), List.of("bash:rm *"));
        assertFalse(service.check("bash:rm -rf /", ToolCategory.BASH, Map.of("command", "rm -rf /")).allowed());
        assertTrue(service.check("bash:ls", ToolCategory.BASH, Map.of("command", "ls")).allowed());
    }

    @Test
    void configAllowBypassesGate() {
        final var service = service(PermissionMode.ASK, List.of("write_file"), List.of());
        assertTrue(service.check("write_file", ToolCategory.EDIT, Map.of()).allowed());
    }

    @Test
    void askDeniesWithoutGate() {
        final var service = service(PermissionMode.ASK, List.of(), List.of());
        final var decision = service.check("write_file", ToolCategory.EDIT, Map.of("path", "a.txt"));
        assertFalse(decision.allowed());
        assertTrue(decision.reason().contains("no interactive gate"));
    }

    @Test
    void gateApprovedAndRefused() {
        final var service = service(PermissionMode.ASK, List.of(), List.of());
        service.setGate((title, detail) -> true);
        assertTrue(service.check("write_file", ToolCategory.EDIT, Map.of()).allowed());

        final var refused = service(PermissionMode.ASK, List.of(), List.of());
        refused.setGate((title, detail) -> false);
        final var decision = refused.check("write_file", ToolCategory.EDIT, Map.of());
        assertFalse(decision.allowed());
        assertEquals("refused by the user", decision.reason());
    }

    @Test
    void sessionGrantAppliesOnceGranted() {
        final var service = service(PermissionMode.ASK, List.of(), List.of());
        assertFalse(service.check("bash", ToolCategory.BASH, Map.of()).allowed());
        service.grantSession("bash");
        assertTrue(service.check("bash", ToolCategory.BASH, Map.of()).allowed());
    }

    @Test
    void yoloAllowsEverything() {
        final var service = service(PermissionMode.YOLO, List.of(), List.of());
        assertTrue(service.check("bash", ToolCategory.BASH, Map.of()).allowed());
        assertTrue(service.check("write_file", ToolCategory.EDIT, Map.of()).allowed());
    }

    @Test
    void autoEditAllowsEditsOnly() {
        final var service = service(PermissionMode.AUTO_EDIT, List.of(), List.of());
        assertTrue(service.check("write_file", ToolCategory.EDIT, Map.of()).allowed());
        assertFalse(service.check("bash", ToolCategory.BASH, Map.of()).allowed());
    }

    @Test
    void planModeBlocksEditAndBash() {
        final var service = service(PermissionMode.ASK, List.of(), List.of());
        service.setMode(AgentMode.PLAN);
        assertFalse(service.check("write_file", ToolCategory.EDIT, Map.of()).allowed());
        assertFalse(service.check("bash", ToolCategory.BASH, Map.of()).allowed());
    }

    @Test
    void planModeAllowsAndBlocksBashByPrefix() {
        final var service = service(PermissionMode.ASK, List.of(), List.of(), List.of("git status", "ls"));
        service.setMode(AgentMode.PLAN);
        assertTrue(service.check("bash", ToolCategory.BASH, Map.of("command", "git status --short")).allowed());
        assertTrue(service.check("bash", ToolCategory.BASH, Map.of("command", "ls -la")).allowed());
        assertFalse(service.check("bash", ToolCategory.BASH, Map.of("command", "rm -rf /")).allowed());
        assertFalse(service.check("bash", ToolCategory.BASH, Map.of("command", "git push")).allowed());
    }

    @Test
    void planModeAllowsReadAndAgent() {
        final var service = service(PermissionMode.ASK, List.of(), List.of());
        service.setMode(AgentMode.PLAN);
        assertTrue(service.check("grep", ToolCategory.READ, Map.of()).allowed());
        assertTrue(service.check("todo_write", ToolCategory.AGENT, Map.of()).allowed());
    }

    @Test
    void planModeAllowsReadOnlyMcpTool() {
        final var service = service(PermissionMode.ASK, List.of(), List.of());
        service.setMode(AgentMode.PLAN);
        assertTrue(service.check("ctx_query", ToolCategory.MCP, Map.of(), true).allowed());
        assertFalse(service.check("ctx_write", ToolCategory.MCP, Map.of(), false).allowed());
    }

    @Test
    void buildModeUsesPermissionModeForMcp() {
        final var service = service(PermissionMode.ASK, List.of(), List.of());
        service.setGate((title, detail) -> false);
        assertFalse(service.check("ctx_write", ToolCategory.MCP, Map.of(), false).allowed());
    }
}
