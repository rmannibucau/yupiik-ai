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
package io.yupiik.ia.agent.core.permissions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.core.configuration.AgentMode;
import io.yupiik.ia.agent.core.configuration.PermissionMode;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PermissionServiceTest {

    @TempDir
    Path storageHome;

    private PermissionService service(final PermissionMode mode, final List<String> allow, final List<String> deny) {
        return service(mode, allow, deny, List.of());
    }

    private PermissionService service(
            final PermissionMode mode, final List<String> allow, final List<String> deny, final List<String> planBash) {
        final var configuration = io.yupiik.ia.agent.core.configuration.TestConfigs.codingAgent(Map.of(
                "yca.storageHome", storageHome.toString(),
                "yca.permissions.mode", mode.name().toLowerCase(Locale.ROOT),
                "yca.permissions.allow", String.join(",", allow),
                "yca.permissions.deny", String.join(",", deny),
                "yca.permissions.planBash", String.join(",", planBash)));
        final var service = new PermissionService(configuration);
        // these build-mode behaviour tests rely on a BUILD baseline; plan mode is
        // exercised explicitly via setMode(AgentMode.PLAN) in dedicated tests
        service.setMode(AgentMode.BUILD);
        return service;
    }

    @Test
    void readAlwaysAllowed() {
        final var decision =
                service(PermissionMode.ASK, List.of(), List.of()).check("grep", ToolCategory.READ, Map.of());
        assertTrue(decision.allowed());
    }

    @Test
    void defaultModeIsPlanWhenConfigUnset() {
        final var configuration = io.yupiik.ia.agent.core.configuration.TestConfigs.codingAgent(
                Map.of("yca.storageHome", storageHome.toString()));
        final var service = new PermissionService(configuration);
        assertEquals(AgentMode.PLAN, service.mode());
    }

    @Test
    void denyBeatsAllow() {
        final var service = service(PermissionMode.YOLO, List.of("bash"), List.of("bash:rm *"));
        assertFalse(service.check("bash:rm -rf /", ToolCategory.BASH, Map.of("command", "rm -rf /"))
                .allowed());
        assertTrue(service.check("bash:ls", ToolCategory.BASH, Map.of("command", "ls"))
                .allowed());
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
        service.setGate((title, detail, tool, args) -> ApprovalGate.Result.ALLOW_ONCE);
        assertTrue(service.check("write_file", ToolCategory.EDIT, Map.of()).allowed());

        final var refused = service(PermissionMode.ASK, List.of(), List.of());
        refused.setGate((title, detail, tool, args) -> ApprovalGate.Result.DENY);
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
    void planModeBlocksEditAndBashWithoutGate() {
        final var service = service(PermissionMode.ASK, List.of(), List.of());
        service.setMode(AgentMode.PLAN);
        assertFalse(service.check("write_file", ToolCategory.EDIT, Map.of()).allowed());
        assertFalse(service.check("bash", ToolCategory.BASH, Map.of()).allowed());
    }

    @Test
    void planModeHardDeniesStateChangingToolsRegardlessOfGate() {
        final var service = service(PermissionMode.ASK, List.of(), List.of());
        final AtomicReference<String> asked = new AtomicReference<>();
        service.setMode(AgentMode.PLAN);
        service.setGate((title, detail, tool, args) -> {
            asked.set(title);
            return ApprovalGate.Result.ALLOW_ONCE;
        });
        // non-readonly bash is hard-denied in plan mode: the gate is never consulted
        final var decision = service.check("bash", ToolCategory.BASH, Map.of("command", "cd /somewhere && doThing"));
        assertFalse(decision.allowed());
        assertTrue(decision.reason().contains("plan mode"));
        assertNull(asked.get(), "the approval gate must not be invoked in plan mode");
    }

    @Test
    void planModeHardDeniesEditEvenWithGate() {
        final var service = service(PermissionMode.ASK, List.of(), List.of());
        final AtomicReference<String> asked = new AtomicReference<>();
        service.setMode(AgentMode.PLAN);
        service.setGate((title, detail, tool, args) -> {
            asked.set(title);
            return ApprovalGate.Result.ALLOW_ONCE;
        });
        final var decision = service.check("edit_file", ToolCategory.EDIT, Map.of("path", "a.txt"));
        assertFalse(decision.allowed());
        assertTrue(decision.reason().contains("plan mode"));
        assertNull(asked.get(), "the approval gate must not be invoked for edit tools in plan mode");
    }

    @Test
    void planModeAllowsAndBlocksBashByPrefix() {
        final var service = service(PermissionMode.ASK, List.of(), List.of(), List.of("git status", "ls"));
        service.setMode(AgentMode.PLAN);
        assertTrue(service.check("bash", ToolCategory.BASH, Map.of("command", "git status --short"))
                .allowed());
        assertTrue(service.check("bash", ToolCategory.BASH, Map.of("command", "ls -la"))
                .allowed());
        assertFalse(service.check("bash", ToolCategory.BASH, Map.of("command", "rm -rf /"))
                .allowed());
        assertFalse(service.check("bash", ToolCategory.BASH, Map.of("command", "git push"))
                .allowed());
    }

    @Test
    void planModeAllowsReadAndAgent() {
        final var service = service(PermissionMode.ASK, List.of(), List.of());
        service.setMode(AgentMode.PLAN);
        assertTrue(service.check("grep", ToolCategory.READ, Map.of()).allowed());
        assertTrue(service.check("write_todo", ToolCategory.AGENT, Map.of()).allowed());
    }

    @Test
    void planModeAllowsReadOnlyMcpTool() {
        final var service = service(PermissionMode.ASK, List.of(), List.of());
        service.setMode(AgentMode.PLAN);
        assertTrue(service.check("ctx_query", ToolCategory.MCP, Map.of(), true).allowed());
        assertFalse(
                service.check("ctx_write", ToolCategory.MCP, Map.of(), false).allowed());
    }

    @Test
    void buildModeUsesPermissionModeForMcp() {
        final var service = service(PermissionMode.ASK, List.of(), List.of());
        service.setGate((title, detail, tool, args) -> ApprovalGate.Result.DENY);
        assertFalse(
                service.check("ctx_write", ToolCategory.MCP, Map.of(), false).allowed());
    }

    @Test
    void sessionGrantAppliesToCommandFamilyInProject() {
        final var service = service(PermissionMode.ASK, List.of(), List.of());
        assertFalse(service.check("bash", ToolCategory.BASH, Map.of("command", "mvn install"))
                .allowed());
        service.grantSession("bash", Map.of("command", "mvn install"));
        assertTrue(service.check("bash", ToolCategory.BASH, Map.of("command", "mvn install"))
                .allowed());
        assertTrue(service.check("bash", ToolCategory.BASH, Map.of("command", "mvn test"))
                .allowed());
        assertFalse(service.check("bash", ToolCategory.BASH, Map.of("command", "gradle build"))
                .allowed());
    }

    @Test
    void sessionGrantDoesNotCrossProjectDir() {
        final var service = service(PermissionMode.ASK, List.of(), List.of());
        service.grantSession("bash", Map.of("command", "mvn install"));
        assertFalse(service.check("bash", ToolCategory.BASH, Map.of("command", "rm -rf /"))
                .allowed());
        assertTrue(service.sessionGrants().stream().anyMatch(g -> g.contains("|bash:mvn")));
    }

    @Test
    void sessionGrantViaGate() {
        final var service = service(PermissionMode.ASK, List.of(), List.of());
        service.setGate((title, detail, tool, args) -> ApprovalGate.Result.ALLOW_SESSION);
        assertTrue(service.check("bash", ToolCategory.BASH, Map.of("command", "mvn install"))
                .allowed());
        assertTrue(service.check("bash", ToolCategory.BASH, Map.of("command", "mvn test"))
                .allowed());
    }
}
