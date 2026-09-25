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
import io.yupiik.ia.agent.core.configuration.BashPermissionsConfiguration;
import io.yupiik.ia.agent.core.configuration.CommandOptionsConfiguration;
import io.yupiik.ia.agent.core.configuration.CommandPermissionConfiguration;
import io.yupiik.ia.agent.core.configuration.CommandStartMatcherConfiguration;
import io.yupiik.ia.agent.core.configuration.PermissionMode;
import java.util.List;
import org.junit.jupiter.api.Test;

class BashRulesTest {

    private static BashRules.Compiled compile(final List<CommandPermissionConfiguration> rules) {
        return BashRules.compile(new BashPermissionsConfiguration(rules));
    }

    private static CommandPermissionConfiguration rule(
            final PermissionMode plan, final PermissionMode defaultMode, final String commandStart) {
        return new CommandPermissionConfiguration(
                new CommandStartMatcherConfiguration(new CommandOptionsConfiguration(List.of(commandStart)), List.of()),
                plan,
                defaultMode);
    }

    @Test
    void defaultRulesAllowReadOnlyFamiliesInBothModes() {
        final var compiled = compile(CommandPermissionConfiguration.defaultRules());
        for (final var agentMode : List.of(AgentMode.PLAN, AgentMode.BUILD)) {
            assertTrue(compiled.evaluate(agentMode, "git status --short").allowed(), agentMode.name());
            assertTrue(compiled.evaluate(agentMode, "git diff HEAD~1").allowed(), agentMode.name());
            assertTrue(compiled.evaluate(agentMode, "ls -la").allowed(), agentMode.name());
            assertTrue(compiled.evaluate(agentMode, "wc -l pom.xml").allowed(), agentMode.name());
            assertTrue(compiled.evaluate(agentMode, "java -version").allowed(), agentMode.name());
        }
    }

    @Test
    void defaultRulesExcludeWriteOptionForms() {
        final var compiled = compile(CommandPermissionConfiguration.defaultRules());
        assertFalse(
                compiled.evaluate(AgentMode.BUILD, "sed -i 's/a/b/' pom.xml").allowed());
        assertFalse(compiled.evaluate(AgentMode.BUILD, "sed --in-place=suf 's/a/b/' pom.xml")
                .allowed());
        assertFalse(compiled.evaluate(AgentMode.BUILD, "find . -delete").allowed());
        assertFalse(compiled.evaluate(AgentMode.BUILD, "find . -exec rm {} ;").allowed());
        assertFalse(
                compiled.evaluate(AgentMode.BUILD, "sort -o out.txt pom.xml").allowed());
        assertFalse(compiled.evaluate(AgentMode.BUILD, "sort --output=out.txt pom.xml")
                .allowed());
        assertFalse(compiled.evaluate(AgentMode.BUILD, "git branch -D feature").allowed());
        assertFalse(compiled.evaluate(AgentMode.BUILD, "git branch -d feature").allowed());
        // the read-only forms stay allowed
        assertTrue(compiled.evaluate(AgentMode.BUILD, "sed -n 1,30p pom.xml").allowed());
        assertTrue(compiled.evaluate(AgentMode.BUILD, "sed 's/a/b/' pom.xml").allowed());
        assertTrue(compiled.evaluate(AgentMode.BUILD, "find . -name '*.java'").allowed());
        assertTrue(compiled.evaluate(AgentMode.BUILD, "sort pom.xml").allowed());
        assertTrue(compiled.evaluate(AgentMode.BUILD, "git branch").allowed());
        assertTrue(compiled.evaluate(AgentMode.BUILD, "git branch --list 'f*'").allowed());
    }

    @Test
    void commandStartIsMatchedTokenWiseNotTextually() {
        final var compiled = compile(List.of(rule(PermissionMode.YOLO, null, "git diff")));
        // `git difftool` is not `git diff`: token equality, unlike the old textual prefix list
        assertFalse(compiled.evaluate(AgentMode.BUILD, "git difftool").allowed());
        assertTrue(compiled.evaluate(AgentMode.BUILD, "git diff HEAD").allowed());
        // options passed as comma separated or space separated entries are equivalent
        final var comma = compile(List.of(new CommandPermissionConfiguration(
                new CommandStartMatcherConfiguration(new CommandOptionsConfiguration(List.of("git,status")), List.of()),
                PermissionMode.YOLO,
                null)));
        assertTrue(comma.evaluate(AgentMode.BUILD, "git status --short").allowed());
    }

    @Test
    void modeResolutionInheritsBetweenPlanAndDefault() {
        // defaultMode null -> inherits plan; plan null -> falls back to default; both null -> never matches
        final var inheritPlan = compile(List.of(rule(PermissionMode.YOLO, null, "git status")));
        assertTrue(inheritPlan.evaluate(AgentMode.BUILD, "git status").allowed());
        assertTrue(inheritPlan.evaluate(AgentMode.PLAN, "git status").allowed());

        final var planOnly = compile(List.of(rule(null, PermissionMode.YOLO, "git status")));
        assertTrue(planOnly.evaluate(AgentMode.PLAN, "git status").allowed());
        assertTrue(planOnly.evaluate(AgentMode.BUILD, "git status").allowed());

        final var divergent = compile(List.of(rule(PermissionMode.YOLO, PermissionMode.ASK, "docker run")));
        assertTrue(divergent.evaluate(AgentMode.PLAN, "docker run x").allowed());
        assertFalse(divergent.evaluate(AgentMode.BUILD, "docker run x").allowed());

        final var none = compile(List.of(rule(null, null, "git status")));
        assertFalse(none.evaluate(AgentMode.PLAN, "git status").allowed());
        assertFalse(none.evaluate(AgentMode.BUILD, "git status").allowed());
    }

    @Test
    void emptyRulesAllowNothingButToleratedSegments() {
        final var compiled = BashRules.compile(null);
        assertFalse(compiled.evaluate(AgentMode.BUILD, "git status").allowed());
        assertFalse(compiled.evaluate(AgentMode.BUILD, "git status && rm -rf /").allowed());
        assertTrue(compiled.evaluate(AgentMode.BUILD, "cd /somewhere").allowed());
        assertTrue(compiled.evaluate(AgentMode.BUILD, "LC_ALL=C").allowed());
    }

    @Test
    void segmentSplittingHandlesOperatorsAndContinuations() {
        final var compiled =
                compile(List.of(rule(PermissionMode.YOLO, null, "ls"), rule(PermissionMode.YOLO, null, "head")));
        // pipelines split into segments and pipelines spanning lines are joined
        assertTrue(compiled.evaluate(AgentMode.BUILD, "ls | head -20").allowed());
        assertTrue(compiled.evaluate(AgentMode.BUILD, "ls |\n head -20").allowed());
        assertTrue(compiled.evaluate(AgentMode.BUILD, "ls \\\n -la").allowed());
        assertTrue(compiled.evaluate(AgentMode.BUILD, "ls &&\n head -20").allowed());
        // a newline after a complete line is a boundary: the second line must match a rule too
        assertFalse(compiled.evaluate(AgentMode.BUILD, "ls -la\n rm -rf /").allowed());
        // one bad segment anywhere rejects the whole compound command
        assertFalse(compiled.evaluate(AgentMode.BUILD, "ls; rm -rf /").allowed());
        assertFalse(compiled.evaluate(AgentMode.BUILD, "ls & rm -rf /").allowed());
        assertFalse(
                compiled.evaluate(AgentMode.BUILD, "ls && head -20 || rm -rf /").allowed());
    }

    @Test
    void redirectionsAndSubstitutionsFallBackToTheGate() {
        final var compiled =
                compile(List.of(rule(PermissionMode.YOLO, null, "grep"), rule(PermissionMode.YOLO, null, "echo")));
        assertFalse(compiled.evaluate(AgentMode.BUILD, "grep foo bar > out.txt").allowed());
        assertFalse(compiled.evaluate(AgentMode.BUILD, "grep foo bar < in.txt").allowed());
        assertFalse(compiled.evaluate(AgentMode.BUILD, "echo $(rm -rf /)").allowed());
        assertFalse(compiled.evaluate(AgentMode.BUILD, "echo `rm -rf /`").allowed());
        assertFalse(compiled.evaluate(AgentMode.BUILD, "echo <(rm -rf /)").allowed());
        // tolerated stderr handling
        assertTrue(
                compiled.evaluate(AgentMode.BUILD, "grep foo bar 2>/dev/null").allowed());
        assertTrue(compiled.evaluate(AgentMode.BUILD, "grep foo bar >/dev/null").allowed());
        assertTrue(compiled.evaluate(AgentMode.BUILD, "grep foo bar 2>&1").allowed());
        // quoted content is literal: no redirection, no substitution
        assertTrue(compiled.evaluate(AgentMode.BUILD, "echo \"a > b\"").allowed());
        assertTrue(compiled.evaluate(AgentMode.BUILD, "echo 'a $(ls) b'").allowed());
        assertFalse(compiled.evaluate(AgentMode.BUILD, "echo \"a $(ls) b\"").allowed());
    }

    @Test
    void envPrefixesAndCdAreTolerated() {
        final var compiled = compile(List.of(rule(PermissionMode.YOLO, null, "grep")));
        assertTrue(compiled.evaluate(AgentMode.BUILD, "LC_ALL=C grep foo bar").allowed());
        assertTrue(compiled.evaluate(AgentMode.BUILD, "cd core && grep -n foo pom.xml")
                .allowed());
        assertFalse(compiled.evaluate(AgentMode.BUILD, "LC_ALL=C rm -rf /").allowed());
    }

    @Test
    void excludeSequencesMustBeConsecutive() {
        final var rule = new CommandPermissionConfiguration(
                new CommandStartMatcherConfiguration(
                        new CommandOptionsConfiguration(List.of("find")),
                        List.of(new CommandOptionsConfiguration(List.of("rm", "-rf")))),
                PermissionMode.YOLO,
                null);
        final var compiled = compile(List.of(rule));
        assertFalse(compiled.evaluate(AgentMode.BUILD, "find . rm -rf x").allowed());
        assertTrue(compiled.evaluate(AgentMode.BUILD, "find . rm x -rf").allowed());
    }

    @Test
    void commandPrefixIsRuleBasedForSingleSegmentsOnly() {
        final var compiled = compile(List.of(rule(PermissionMode.ASK, null, "docker run")));
        assertEquals(
                "docker run",
                compiled.evaluate(AgentMode.BUILD, "docker run nginx").prefix());
        // unmatched commands, tolerated segments and compounds fall back to the first word (handled by the caller)
        assertNull(compiled.evaluate(AgentMode.BUILD, "mvn install").prefix());
        assertNull(compiled.evaluate(AgentMode.BUILD, "cd /tmp").prefix());
        assertNull(compiled.evaluate(AgentMode.BUILD, "docker run nginx && ls").prefix());
        assertNull(compiled.evaluate(AgentMode.BUILD, "LC_ALL=C mvn install").prefix());
        // env prefixes do not hide the matched command start
        assertEquals(
                "docker run",
                compiled.evaluate(AgentMode.BUILD, "LC_ALL=C docker run nginx").prefix());
    }

    @Test
    void segmentsNormalizesQuotesForPatternMatching() {
        final var compiled = compile(List.of(rule(PermissionMode.YOLO, null, "echo")));
        assertEquals(List.of("echo a b", "ls -la"), compiled.segments("echo \"a b\" | ls -la"));
        assertEquals(List.of("git status"), compiled.segments("  git   status  "));
        assertEquals(List.of(), compiled.segments(""));
    }
}
