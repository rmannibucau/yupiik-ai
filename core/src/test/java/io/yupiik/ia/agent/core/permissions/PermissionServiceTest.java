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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.core.configuration.AgentMode;
import io.yupiik.ia.agent.core.configuration.PermissionMode;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import java.nio.file.Path;
import java.util.HashMap;
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
        return service(mode, allow, deny, Map.of());
    }

    private PermissionService service(
            final PermissionMode mode,
            final List<String> allow,
            final List<String> deny,
            final Map<String, String> extra) {
        final var props = new HashMap<String, String>();
        props.put("yca.storageHome", storageHome.toString());
        props.put("yca.permissions.mode", mode.name().toLowerCase(Locale.ROOT));
        props.put("yca.permissions.allow", String.join(",", allow));
        props.put("yca.permissions.deny", String.join(",", deny));
        props.putAll(extra);
        final var configuration = io.yupiik.ia.agent.core.configuration.TestConfigs.codingAgent(props);
        final var service = new PermissionService(configuration);
        // these build-mode behaviour tests rely on a BUILD baseline; plan mode is
        // exercised explicitly via setMode(AgentMode.PLAN) in dedicated tests
        service.setMode(AgentMode.BUILD);
        return service;
    }

    /** Encodes `yca.permissions.bash.rules` entries with the same mode for plan and default (default inherits plan). */
    private static Map<String, String> rules(final PermissionMode plan, final String... commandStarts) {
        final var props = new HashMap<String, String>();
        props.put("yca.permissions.bash.rules.length", String.valueOf(commandStarts.length));
        for (var i = 0; i < commandStarts.length; i++) {
            final var prefix = "yca.permissions.bash.rules." + i;
            props.put(prefix + ".matcher.commandStart.options", commandStarts[i]);
            if (plan != null) {
                props.put(prefix + ".plan", plan.name().toLowerCase(Locale.ROOT));
            }
        }
        return props;
    }

    /** A single rule with dedicated exclude options, eg. `sed` rejecting `-i`. */
    private static Map<String, String> ruleWithExcludes(
            final PermissionMode plan, final String commandStart, final String... excludedOptions) {
        final var props = new HashMap<String, String>();
        props.put("yca.permissions.bash.rules.length", "1");
        props.put("yca.permissions.bash.rules.0.matcher.commandStart.options", commandStart);
        props.put("yca.permissions.bash.rules.0.matcher.excludeOptions.length", String.valueOf(excludedOptions.length));
        for (var i = 0; i < excludedOptions.length; i++) {
            props.put("yca.permissions.bash.rules.0.matcher.excludeOptions." + i + ".options", excludedOptions[i]);
        }
        if (plan != null) {
            props.put("yca.permissions.bash.rules.0.plan", plan.name().toLowerCase(Locale.ROOT));
        }
        return props;
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
    void runtimePermissionModeOverridesStaticConfig() {
        final var service = service(PermissionMode.ASK, List.of(), List.of());
        // statically ASK so without a gate the edit tool would fall through to the gate
        assertFalse(service.check("write_file", ToolCategory.EDIT, Map.of()).allowed());
        service.setPermissionMode(PermissionMode.YOLO);
        assertTrue(service.check("bash", ToolCategory.BASH, Map.of("command", "rm -rf /"))
                .allowed());
        assertTrue(service.check("write_file", ToolCategory.EDIT, Map.of()).allowed());
    }

    @Test
    void runtimePermissionModeAsyncGivesAutoEdit() {
        final var service = service(PermissionMode.ASK, List.of(), List.of());
        service.setPermissionMode(PermissionMode.AUTO_EDIT);
        assertTrue(service.check("write_file", ToolCategory.EDIT, Map.of()).allowed());
        assertFalse(service.check("bash", ToolCategory.BASH, Map.of("command", "mvn install"))
                .allowed());
    }

    @Test
    void clearPermissionModeRestoresConfiguredDefault() {
        final var service = service(PermissionMode.ASK, List.of(), List.of());
        service.setPermissionMode(PermissionMode.YOLO);
        assertTrue(service.permissionMode() == PermissionMode.YOLO);
        service.setPermissionMode(null);
        assertEquals(PermissionMode.ASK, service.permissionMode());
    }

    @Test
    void permissionModeValuesRecogniseAcceptedForms() {
        assertTrue(PermissionMode.isValue("ask"));
        assertTrue(PermissionMode.isValue("auto_edit"));
        assertTrue(PermissionMode.isValue("autoedit"));
        assertTrue(PermissionMode.isValue("yolo"));
        // `all` is accepted as a yolo alias in configuration (rule modes and yca.permissions.mode)
        assertTrue(PermissionMode.isValue("all"));
        assertEquals(PermissionMode.YOLO, PermissionMode.fromConfigurationString("all"));
        assertFalse(PermissionMode.isValue(""));
        assertFalse(PermissionMode.isValue(null));
        assertFalse(PermissionMode.isValue("bogus"));
        // the completion/usage hints are derived from the enum, so they must be self consistent
        assertTrue(PermissionMode.VALUES.contains("auto_edit"));
        assertTrue(PermissionMode.VALUES.contains("yolo"));
        assertTrue(PermissionMode.VALUES.contains("ask"));
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
        // no gate installed: state-changing tools fall through to the approval flow
        // which refuses without an interactive gate
        assertFalse(service.check("write_file", ToolCategory.EDIT, Map.of()).allowed());
        assertFalse(service.check("bash", ToolCategory.BASH, Map.of()).allowed());
    }

    @Test
    void planModeConsultsGateForStateChangingBash() {
        final var service = service(PermissionMode.ASK, List.of(), List.of());
        final AtomicReference<String> asked = new AtomicReference<>();
        service.setMode(AgentMode.PLAN);
        service.setGate((title, detail, tool, args) -> {
            asked.set(title);
            return ApprovalGate.Result.ALLOW_ONCE;
        });
        // non-readonly bash in plan mode is not hard-denied: it is routed to the
        // approval gate so the user can approve it
        final var decision = service.check("bash", ToolCategory.BASH, Map.of("command", "cd /somewhere && doThing"));
        assertTrue(decision.allowed());
        assertTrue(asked.get() != null, "the approval gate must be consulted in plan mode for state-changing tools");
    }

    @Test
    void planModeConsultsGateForEdit() {
        final var service = service(PermissionMode.ASK, List.of(), List.of());
        final AtomicReference<String> asked = new AtomicReference<>();
        service.setMode(AgentMode.PLAN);
        service.setGate((title, detail, tool, args) -> {
            asked.set(title);
            return ApprovalGate.Result.ALLOW_ONCE;
        });
        final var decision = service.check("edit_file", ToolCategory.EDIT, Map.of("path", "a.txt"));
        assertTrue(decision.allowed());
        assertTrue(asked.get() != null, "the approval gate must be consulted for edit tools in plan mode");
    }

    @Test
    void planModeAllowsAndBlocksBashByPrefix() {
        final var service =
                service(PermissionMode.ASK, List.of(), List.of(), rules(PermissionMode.YOLO, "git status", "ls"));
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
    void planModeBashSegmentsCompoundCommands() {
        final var service = service(
                PermissionMode.ASK,
                List.of(),
                List.of(),
                rules(PermissionMode.YOLO, "git status", "git diff", "ls", "grep", "cat"));
        service.setMode(AgentMode.PLAN);
        // cd prefix is tolerated, every other segment must be allowlisted
        assertTrue(service.check("bash", ToolCategory.BASH, Map.of("command", "cd core && grep -n foo pom.xml"))
                .allowed());
        assertTrue(service.check("bash", ToolCategory.BASH, Map.of("command", "git status && git diff HEAD"))
                .allowed());
        // a harmful segment anywhere denies the whole command
        assertFalse(service.check("bash", ToolCategory.BASH, Map.of("command", "git diff HEAD && rm -rf /"))
                .allowed());
        assertFalse(service.check("bash", ToolCategory.BASH, Map.of("command", "ls; git push"))
                .allowed());
        assertFalse(service.check("bash", ToolCategory.BASH, Map.of("command", "cat pom.xml | sh"))
                .allowed());
    }

    @Test
    void planModeBashRejectsRedirectionAndSubstitution() {
        final var service = service(
                PermissionMode.ASK, List.of(), List.of(), rules(PermissionMode.YOLO, "grep", "echo", "git diff"));
        service.setMode(AgentMode.PLAN);
        assertFalse(service.check("bash", ToolCategory.BASH, Map.of("command", "grep foo bar > out.txt"))
                .allowed());
        assertFalse(service.check("bash", ToolCategory.BASH, Map.of("command", "echo $(rm -rf /)"))
                .allowed());
        assertFalse(service.check("bash", ToolCategory.BASH, Map.of("command", "echo `rm -rf /`"))
                .allowed());
        assertFalse(service.check("bash", ToolCategory.BASH, Map.of("command", "cd $(git rev-parse) && ls"))
                .allowed());
        // harmless stderr silencing stays allowed
        assertTrue(service.check("bash", ToolCategory.BASH, Map.of("command", "grep foo bar 2>/dev/null"))
                .allowed());
    }

    @Test
    void planModeAllowsReadOnlyGitInspectionsWithoutGate() {
        // no gate, no explicit rules: plan mode + the built-in default rules must
        // let read-only git inspection pipelines through (regression for `git show ... | head -60`
        // denied headless because git show/merge-base/xargs were missing from the defaults)
        final var service = service(PermissionMode.ASK, List.of(), List.of());
        service.setMode(AgentMode.PLAN);
        assertTrue(service.check("bash", ToolCategory.BASH, Map.of("command", "git show --stat 7483271 | head -60"))
                .allowed());
        assertTrue(service.check(
                        "bash",
                        ToolCategory.BASH,
                        Map.of("command", "git merge-base HEAD master | xargs git log --oneline -1"))
                .allowed());
        assertTrue(service.check("bash", ToolCategory.BASH, Map.of("command", "grep -m1 '<version>' pom.xml"))
                .allowed());
        assertFalse(service.check("bash", ToolCategory.BASH, Map.of("command", "git remote add origin https://x"))
                .allowed());
    }

    @Test
    void planModeAllowsReadOnlyTextProcessingWithoutGate() {
        // no gate, no explicit rules: plan mode + built-in default rules must let
        // read-only text processing pipelines through (regression for `cat X | awk ...; echo ...; grep -n ...`
        // denied headless because awk was missing from the defaults)
        final var service = service(PermissionMode.ASK, List.of(), List.of());
        service.setMode(AgentMode.PLAN);
        final var inspection =
                "cat fusion-handlebars/src/main/java/io/yupiik/fusion/framework/handlebars/helper/Truthiness.java"
                        + " | awk 'NR>=16'; echo ====NEW-IF;"
                        + " grep -n newIf fusion-handlebars/src/main/java/io/yupiik/fusion/framework/handlebars/helper/Truthiness.java";
        assertTrue(service.check("bash", ToolCategory.BASH, Map.of("command", inspection))
                .allowed());
        // harmless read-only forms stay allowed
        assertTrue(service.check("bash", ToolCategory.BASH, Map.of("command", "diff -u pom.xml pom.xml.orig"))
                .allowed());
        assertTrue(service.check("bash", ToolCategory.BASH, Map.of("command", "date +%s"))
                .allowed());
        assertTrue(service.check("bash", ToolCategory.BASH, Map.of("command", "sha256sum pom.xml"))
                .allowed());
        // write/impure forms are carved out (redirection, diff -o, date -s)
        assertFalse(service.check("bash", ToolCategory.BASH, Map.of("command", "awk 'NR>=16' pom.xml > out.txt"))
                .allowed());
        assertFalse(service.check("bash", ToolCategory.BASH, Map.of("command", "diff -o out.txt a b"))
                .allowed());
        assertFalse(service.check("bash", ToolCategory.BASH, Map.of("command", "date -s '2026-01-01'"))
                .allowed());
    }

    @Test
    void planModeBashToleratesEnvPrefixes() {
        final var service = service(PermissionMode.ASK, List.of(), List.of(), rules(PermissionMode.YOLO, "grep"));
        service.setMode(AgentMode.PLAN);
        assertTrue(service.check("bash", ToolCategory.BASH, Map.of("command", "LC_ALL=C grep foo bar"))
                .allowed());
        // env prefix does not whitelist a non allowlisted command
        assertFalse(service.check("bash", ToolCategory.BASH, Map.of("command", "LC_ALL=C rm -rf /"))
                .allowed());
    }

    @Test
    void planModeBashAllowsReadOnlySedButNotInPlace() {
        final var service =
                service(PermissionMode.ASK, List.of(), List.of(), ruleWithExcludes(PermissionMode.YOLO, "sed", "-i"));
        service.setMode(AgentMode.PLAN);
        assertTrue(service.check("bash", ToolCategory.BASH, Map.of("command", "sed -n 1,30p pom.xml"))
                .allowed());
        // -i rewrites files even behind a `sed -n` prefix
        assertFalse(service.check("bash", ToolCategory.BASH, Map.of("command", "sed -n -i 's/a/b/p' pom.xml"))
                .allowed());
        assertFalse(service.check("bash", ToolCategory.BASH, Map.of("command", "sed -i 's/a/b/' pom.xml"))
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

    // ------------------------------------------------------------------
    // bash rules (yca.permissions.bash.rules)
    // ------------------------------------------------------------------

    @Test
    void buildModeAutoAllowsReadOnlyBashByDefaultRules() {
        // no gate: every assertion below would fail if the command was routed to the approval flow
        final var service = service(PermissionMode.ASK, List.of(), List.of());
        assertTrue(service.check("bash", ToolCategory.BASH, Map.of("command", "git status --short --branch"))
                .allowed());
        assertTrue(service.check("bash", ToolCategory.BASH, Map.of("command", "ls -la"))
                .allowed());
        assertTrue(service.check("bash", ToolCategory.BASH, Map.of("command", "cat pom.xml"))
                .allowed());
        assertTrue(service.check("bash", ToolCategory.BASH, Map.of("command", "grep -n foo pom.xml"))
                .allowed());
        assertTrue(service.check("bash", ToolCategory.BASH, Map.of("command", "sed -n 1,30p pom.xml"))
                .allowed());
        assertTrue(service.check("bash", ToolCategory.BASH, Map.of("command", "git log 2>&1 | head -5"))
                .allowed());
        assertFalse(service.check("bash", ToolCategory.BASH, Map.of("command", "rm -rf /"))
                .allowed());
        assertFalse(service.check("bash", ToolCategory.BASH, Map.of("command", "git push"))
                .allowed());
        assertFalse(service.check("bash", ToolCategory.BASH, Map.of("command", "mvn install"))
                .allowed());
    }

    @Test
    void readOnlyCompoundCommandsAreSilentlyAllowed() {
        // the exact validation example from the design: 7 segments, all read-only
        final var command = "test -f ~/.m2/settings.xml && echo \"settings.xml exists\" || echo \"no settings.xml\""
                + "; ls ~/.m2 2>/dev/null | head -20; java -version 2>&1 | head -2";
        final var build = service(PermissionMode.ASK, List.of(), List.of());
        assertTrue(build.check("bash", ToolCategory.BASH, Map.of("command", command))
                .allowed());
        final var plan = service(PermissionMode.ASK, List.of(), List.of());
        plan.setMode(AgentMode.PLAN);
        assertTrue(plan.check("bash", ToolCategory.BASH, Map.of("command", command))
                .allowed());
    }

    @Test
    void bareJavaIsNotAutoAllowed() {
        final var service = service(PermissionMode.ASK, List.of(), List.of());
        assertTrue(service.check("bash", ToolCategory.BASH, Map.of("command", "java -version"))
                .allowed());
        assertTrue(service.check("bash", ToolCategory.BASH, Map.of("command", "java -help"))
                .allowed());
        // bare java can execute arbitrary code (-jar, -cp, MainClass): it must ask
        assertFalse(service.check("bash", ToolCategory.BASH, Map.of("command", "java -jar app.jar"))
                .allowed());
        assertFalse(service.check("bash", ToolCategory.BASH, Map.of("command", "java MainClass"))
                .allowed());
    }

    @Test
    void ruleDefaultOverridesPlan() {
        // docker run: plan asks (no gate -> denied), build mode default=all allows silently
        final var props = new HashMap<String, String>();
        props.put("yca.permissions.bash.rules.length", "1");
        props.put("yca.permissions.bash.rules.0.matcher.commandStart.options", "docker run");
        props.put("yca.permissions.bash.rules.0.plan", "ask");
        props.put("yca.permissions.bash.rules.0.default", "all");
        final var service = service(PermissionMode.ASK, List.of(), List.of(), props);
        assertTrue(service.check("bash", ToolCategory.BASH, Map.of("command", "docker run nginx"))
                .allowed());
        service.setMode(AgentMode.PLAN);
        assertFalse(service.check("bash", ToolCategory.BASH, Map.of("command", "docker run nginx"))
                .allowed());
    }

    @Test
    void ruleDefaultInheritsPlan() {
        // no `default` key: build mode inherits the plan value (all)
        final var service = service(PermissionMode.ASK, List.of(), List.of(), rules(PermissionMode.YOLO, "git status"));
        assertTrue(service.check("bash", ToolCategory.BASH, Map.of("command", "git status --short"))
                .allowed());
    }

    @Test
    void rulePlanFallsBackToDefaultWhenPlanAbsent() {
        // no `plan` key: plan mode resolves through `default`
        final var props = new HashMap<String, String>();
        props.put("yca.permissions.bash.rules.length", "1");
        props.put("yca.permissions.bash.rules.0.matcher.commandStart.options", "docker run");
        props.put("yca.permissions.bash.rules.0.default", "all");
        final var service = service(PermissionMode.ASK, List.of(), List.of(), props);
        service.setMode(AgentMode.PLAN);
        assertTrue(service.check("bash", ToolCategory.BASH, Map.of("command", "docker run nginx"))
                .allowed());
    }

    @Test
    void ruleAskConsultsGateInBothModes() {
        final var props = new HashMap<String, String>();
        props.put("yca.permissions.bash.rules.length", "1");
        props.put("yca.permissions.bash.rules.0.matcher.commandStart.options", "docker run");
        props.put("yca.permissions.bash.rules.0.plan", "ask");
        for (final var agentMode : List.of(AgentMode.BUILD, AgentMode.PLAN)) {
            final var service = service(PermissionMode.ASK, List.of(), List.of(), props);
            service.setMode(agentMode);
            final AtomicReference<String> asked = new AtomicReference<>();
            service.setGate((title, detail, tool, args) -> {
                asked.set(title);
                return ApprovalGate.Result.ALLOW_ONCE;
            });
            assertTrue(service.check("bash", ToolCategory.BASH, Map.of("command", "docker run nginx"))
                    .allowed());
            assertTrue(
                    asked.get() != null && asked.get().contains("docker run"),
                    "an ask rule must route " + agentMode + " mode to the gate");
        }
    }

    @Test
    void denyListBeatsBashRules() {
        // rm is denied by configuration even inside an otherwise allowed compound command
        final var service = service(PermissionMode.ASK, List.of(), List.of("rm *"));
        final var decision = service.check("bash", ToolCategory.BASH, Map.of("command", "git status && rm -rf /"));
        assertFalse(decision.allowed());
        assertTrue(decision.reason().contains("denied by configuration"));
        assertFalse(service.check("bash", ToolCategory.BASH, Map.of("command", "rm -rf /"))
                .allowed());
        // the rules still allow the harmless part
        assertTrue(service.check("bash", ToolCategory.BASH, Map.of("command", "git status"))
                .allowed());
    }

    @Test
    void denyListSupportsBashDecoratedPatterns() {
        final var service = service(PermissionMode.ASK, List.of(), List.of("bash:ls *"));
        assertFalse(service.check("bash", ToolCategory.BASH, Map.of("command", "ls -la"))
                .allowed());
        assertTrue(service.check("bash", ToolCategory.BASH, Map.of("command", "git status"))
                .allowed());
    }

    @Test
    void allowListCommandPatternsApplyPerSegment() {
        final var service = service(PermissionMode.ASK, List.of("mvn *"), List.of());
        assertTrue(service.check("bash", ToolCategory.BASH, Map.of("command", "mvn clean install"))
                .allowed());
        assertTrue(service.check("bash", ToolCategory.BASH, Map.of("command", "mvn clean && mvn install"))
                .allowed());
        // a segment not matching the allow patterns makes the whole command ask again
        assertFalse(service.check("bash", ToolCategory.BASH, Map.of("command", "mvn clean && rm -rf /"))
                .allowed());
    }

    @Test
    void sessionGrantUsesMatchedRuleCommandStart() {
        final var props = new HashMap<String, String>();
        props.put("yca.permissions.bash.rules.length", "1");
        props.put("yca.permissions.bash.rules.0.matcher.commandStart.options", "docker run");
        props.put("yca.permissions.bash.rules.0.plan", "ask");
        final var service = service(PermissionMode.ASK, List.of(), List.of(), props);
        service.setGate((title, detail, tool, args) -> ApprovalGate.Result.ALLOW_SESSION);
        assertTrue(service.check("bash", ToolCategory.BASH, Map.of("command", "docker run nginx"))
                .allowed());
        assertTrue(
                service.sessionGrants().stream().anyMatch(g -> g.endsWith("|bash:docker run")),
                "the grant must be keyed by the rule commandStart, not the first word");

        // the fine grained grant unlocks docker run commands without consulting the gate...
        service.setGate((title, detail, tool, args) -> ApprovalGate.Result.DENY);
        assertTrue(service.check("bash", ToolCategory.BASH, Map.of("command", "docker run -p 80:80 nginx"))
                .allowed());
        // ...but not other docker commands nor compound commands
        assertFalse(service.check("bash", ToolCategory.BASH, Map.of("command", "docker ps"))
                .allowed());
        assertFalse(service.check("bash", ToolCategory.BASH, Map.of("command", "docker run nginx && ls"))
                .allowed());
    }
}
