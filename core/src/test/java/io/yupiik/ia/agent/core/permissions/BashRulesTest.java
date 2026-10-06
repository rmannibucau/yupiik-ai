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
        return BashRules.compile(new BashPermissionsConfiguration(rules, null));
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
            assertTrue(
                    compiled.evaluate(agentMode, "git log --oneline master -").allowed(), agentMode.name());
            assertTrue(compiled.evaluate(agentMode, "ls -la").allowed(), agentMode.name());
            assertTrue(compiled.evaluate(agentMode, "wc -l pom.xml").allowed(), agentMode.name());
            assertTrue(compiled.evaluate(agentMode, "java -version").allowed(), agentMode.name());
            // jq is a pure read-only JSON filter (no in-place/output option)
            assertTrue(compiled.evaluate(agentMode, "jq '.version' pom.json").allowed(), agentMode.name());
            assertTrue(compiled.evaluate(agentMode, "jq -r . \"$F\"").allowed(), agentMode.name());
            // git read-only inspection families
            assertTrue(compiled.evaluate(agentMode, "git show --stat 7483271").allowed(), agentMode.name());
            assertTrue(compiled.evaluate(agentMode, "git blame pom.xml").allowed(), agentMode.name());
            assertTrue(compiled.evaluate(agentMode, "git rev-parse HEAD").allowed(), agentMode.name());
            assertTrue(
                    compiled.evaluate(agentMode, "git merge-base HEAD master").allowed(), agentMode.name());
            assertTrue(compiled.evaluate(agentMode, "git ls-files pom.xml").allowed(), agentMode.name());
            assertTrue(
                    compiled.evaluate(agentMode, "git cat-file -p HEAD^{tree}").allowed(), agentMode.name());
            assertTrue(compiled.evaluate(agentMode, "git ls-tree HEAD").allowed(), agentMode.name());
            assertTrue(compiled.evaluate(agentMode, "git for-each-ref").allowed(), agentMode.name());
            assertTrue(compiled.evaluate(agentMode, "git name-rev HEAD").allowed(), agentMode.name());
            assertTrue(compiled.evaluate(agentMode, "git describe --always").allowed(), agentMode.name());
            assertTrue(compiled.evaluate(agentMode, "git stash list").allowed(), agentMode.name());
            assertTrue(compiled.evaluate(agentMode, "git submodule status").allowed(), agentMode.name());
            assertTrue(compiled.evaluate(agentMode, "git remote -v").allowed(), agentMode.name());
            assertTrue(
                    compiled.evaluate(agentMode, "xargs git log --oneline -1").allowed(), agentMode.name());
            // the write forms of these families are carved out
            assertFalse(
                    compiled.evaluate(agentMode, "git remote add origin https://x")
                            .allowed(),
                    agentMode.name());
            assertFalse(compiled.evaluate(agentMode, "git remote remove origin").allowed(), agentMode.name());
            assertFalse(
                    compiled.evaluate(agentMode, "git remote set-url origin https://x")
                            .allowed(),
                    agentMode.name());
            assertFalse(compiled.evaluate(agentMode, "git remote prune origin").allowed(), agentMode.name());
            assertFalse(compiled.evaluate(agentMode, "git stash drop").allowed(), agentMode.name());
            assertFalse(
                    compiled.evaluate(agentMode, "git submodule update --init").allowed(), agentMode.name());
            // read-only text processing and inspection families
            assertTrue(compiled.evaluate(agentMode, "awk 'NR>=16' pom.xml").allowed(), agentMode.name());
            assertTrue(
                    compiled.evaluate(agentMode, "cat pom.xml | awk 'NR>=16'").allowed(), agentMode.name());
            assertTrue(compiled.evaluate(agentMode, "cut -d: -f1 pom.xml").allowed(), agentMode.name());
            assertTrue(
                    compiled.evaluate(agentMode, "diff -u pom.xml pom.xml.orig").allowed(), agentMode.name());
            assertTrue(compiled.evaluate(agentMode, "stat -c %Y pom.xml").allowed(), agentMode.name());
            assertTrue(compiled.evaluate(agentMode, "date +%s").allowed(), agentMode.name());
            assertTrue(compiled.evaluate(agentMode, "sha256sum pom.xml").allowed(), agentMode.name());
            assertTrue(compiled.evaluate(agentMode, "basename pom.xml").allowed(), agentMode.name());
            assertTrue(compiled.evaluate(agentMode, "dirname pom.xml").allowed(), agentMode.name());
            assertTrue(compiled.evaluate(agentMode, "du -sh target").allowed(), agentMode.name());
            assertTrue(compiled.evaluate(agentMode, "df -h .").allowed(), agentMode.name());
            assertTrue(compiled.evaluate(agentMode, "id -u").allowed(), agentMode.name());
            assertTrue(compiled.evaluate(agentMode, "uname -a").allowed(), agentMode.name());
            // the write forms of these families are carved out
            assertFalse(compiled.evaluate(agentMode, "diff -o out.txt a b").allowed(), agentMode.name());
            assertFalse(
                    compiled.evaluate(agentMode, "diff --output=out.txt a b").allowed(), agentMode.name());
            assertFalse(compiled.evaluate(agentMode, "date -s '2026-01-01'").allowed(), agentMode.name());
            assertFalse(compiled.evaluate(agentMode, "date --set=2026-01-01").allowed(), agentMode.name());
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
        // jq has no write option but shell redirection is still gated
        assertFalse(
                compiled.evaluate(AgentMode.BUILD, "jq . pom.json > out.json").allowed());
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
    void deniedEvaluationReportsTheFailingSegmentAndCause() {
        final var compiled = compile(CommandPermissionConfiguration.defaultRules());
        // git add is not a read-only family: the compound reports it as the failing segment
        final var compound = compiled.evaluate(AgentMode.PLAN, "git status && git add -A && git diff --cached");
        assertFalse(compound.allowed());
        assertEquals("git add -A", compound.deniedSegment());
        assertEquals(BashRules.Cause.NO_MATCH, compound.deniedCause());

        // a rule matching but resolving to interactive approval is a different cause
        final var ask = compile(List.of(rule(PermissionMode.ASK, null, "docker run")));
        final var matched = ask.evaluate(AgentMode.BUILD, "docker run nginx");
        assertFalse(matched.allowed());
        assertEquals("docker run nginx", matched.deniedSegment());
        assertEquals(BashRules.Cause.NOT_YOLO, matched.deniedCause());
        assertEquals("docker run", matched.prefix());

        // a redirect makes the segment fall back to the gate with its dedicated cause
        final var redirect = compile(List.of(rule(PermissionMode.YOLO, null, "grep")));
        final var redirected = redirect.evaluate(AgentMode.BUILD, "grep foo bar > out.txt");
        assertFalse(redirected.allowed());
        assertEquals(BashRules.Cause.REDIRECT_OR_SUBSTITUTION, redirected.deniedCause());

        // fully allowed commands carry no denial info
        final var allowed = compiled.evaluate(AgentMode.PLAN, "git status --short");
        assertTrue(allowed.allowed());
        assertNull(allowed.deniedSegment());
        assertNull(allowed.deniedCause());
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

    @Test
    void coverageKeysComeFromConfiguredRulesNotHardcodedCommands() {
        final var compiled =
                compile(List.of(rule(PermissionMode.YOLO, null, "git show"), rule(PermissionMode.YOLO, null, "grep")));
        // matched rule prefix + target: rev:path collapses to the path
        assertEquals(
                List.of("git show:src/main/java/Foo.java"),
                compiled.coverageKeys("git show abc123:src/main/java/Foo.java"));
        // multi-token target: last non-flag token
        assertEquals(
                List.of("grep:fusion-jwt/src/main/java"),
                compiled.coverageKeys("grep -rn \"get(\\\"\" fusion-jwt/src/main/java"));
        // a command with no matching rule yields no key (never a plateau signal)
        assertEquals(List.of(), compiled.coverageKeys("mvn install"));
        // env prefix, cd and shell wrappers yield no key
        assertEquals(List.of(), compiled.coverageKeys("LC_ALL=C git status"));
        assertEquals(List.of(), compiled.coverageKeys("cd /tmp"));
        assertEquals(List.of(), compiled.coverageKeys("sh -c 'ls -la'"));
        // compounds produce one key per segment
        assertEquals(
                List.of("git show:Foo.java", "grep:bar.txt"),
                compiled.coverageKeys("git show abc123:Foo.java | grep foo bar.txt"));
        // redirections drop the redirect target, not the read target
        assertEquals(List.of("grep:bar"), compiled.coverageKeys("grep foo bar > out.txt"));
    }

    @Test
    void coverageKeysFollowConfigAddedReadOnlyRules() {
        // a rule the user adds to yca.permissions.bash.rules (jq) is picked up automatically
        final var compiled = compile(List.of(rule(PermissionMode.YOLO, null, "jq")));
        assertEquals(List.of("jq:pom.json"), compiled.coverageKeys("jq '.version' pom.json"));
    }

    @Test
    void readOnlyCommandSubstitutionsAreRecursivelyVerified() {
        // $( ... ) used to be blanket-denied; a substitution is now verified with the same engine
        final var compiled = compile(CommandPermissionConfiguration.defaultRules());
        for (final var agentMode : List.of(AgentMode.PLAN, AgentMode.BUILD)) {
            assertTrue(
                    compiled.evaluate(agentMode, "echo $(find . -name '*.java')")
                            .allowed(),
                    agentMode.name());
            assertTrue(
                    compiled.evaluate(agentMode, "echo \"$(find . -name '*.java')\"")
                            .allowed(),
                    agentMode.name());
            assertTrue(
                    compiled.evaluate(agentMode, "path=$(find . -name '*.java' 2>/dev/null)")
                            .allowed(),
                    agentMode.name());
            assertTrue(
                    compiled.evaluate(agentMode, "grep -l foo $(find src -name '*.java' | head -3)")
                            .allowed(),
                    agentMode.name());
            assertTrue(
                    compiled.evaluate(agentMode, "LC_ALL=C grep foo $(find .)").allowed(), agentMode.name());
            // nested substitutions are verified recursively
            assertTrue(
                    compiled.evaluate(agentMode, "echo $(head -1 $(find . -name '*.txt'))")
                            .allowed(),
                    agentMode.name());
        }
    }

    @Test
    void mutatingSubstitutionsStayGated() {
        // the whole point of the recursive verification: side effects inside $( ... ) keep gating
        final var compiled = compile(CommandPermissionConfiguration.defaultRules());
        final var denied = List.of(
                "echo $(rm -rf /)",
                "path=$(rm -rf /)",
                "echo \"$(rm -rf /)\"",
                "echo `rm -rf /`",
                "echo <(rm -rf /)",
                "echo \"a $(ls) b\"",
                "for f in $(rm -rf /); do echo $f; done",
                "echo $(git push)",
                "echo $(find . -delete)",
                "echo $(find .");
        for (final var command : denied) {
            assertFalse(compiled.evaluate(AgentMode.PLAN, command).allowed(), command);
            assertFalse(compiled.evaluate(AgentMode.BUILD, command).allowed(), command);
        }
    }

    @Test
    void shellControlKeywordsAreSyntacticNotCommandFamilies() {
        // `for ... done`, `if ... fi`, `while ... done` never execute the keywords themselves; the
        // real commands of the bodies, conditions and branches are still evaluated segment by segment
        final var compiled = compile(CommandPermissionConfiguration.defaultRules());
        for (final var agentMode : List.of(AgentMode.PLAN, AgentMode.BUILD)) {
            assertTrue(
                    compiled.evaluate(agentMode, "for f in $(find . -name '*.java'); do echo $f; done")
                            .allowed(),
                    agentMode.name());
            assertTrue(
                    compiled.evaluate(agentMode, "for f in src/main pom.xml; do cat $f; done")
                            .allowed(),
                    agentMode.name());
            assertTrue(
                    compiled.evaluate(agentMode, "if git diff --exit-code; then echo ok; else echo ko; fi")
                            .allowed(),
                    agentMode.name());
            assertTrue(
                    compiled.evaluate(agentMode, "if [ -f pom.xml ]; then echo yes; fi")
                            .allowed(),
                    agentMode.name());
            assertTrue(
                    compiled.evaluate(agentMode, "while git status; do echo ready; done")
                            .allowed(),
                    agentMode.name());
            // bodies are not skipped: a mutating command inside a loop gates the whole compound
            assertFalse(
                    compiled.evaluate(agentMode, "for f in a b; do git push; done")
                            .allowed(),
                    agentMode.name());
            assertFalse(
                    compiled.evaluate(agentMode, "while read -r line; do echo $line; done")
                            .allowed(),
                    agentMode.name());
            assertFalse(
                    compiled.evaluate(agentMode, "if git push; then echo no; fi")
                            .allowed(),
                    agentMode.name());
        }
    }

    @Test
    void theReportedExplorationCommandsAreAutoAllowed() {
        // the exact two commands that motivated the change: read-only find + grep/wc/head pipelines
        // and a for loop titled from a verified find|xargs|grep|head substitution
        final var compiled = compile(CommandPermissionConfiguration.defaultRules());
        for (final var agentMode : List.of(AgentMode.PLAN, AgentMode.BUILD)) {
            final var command1 = "grep -l '\\.hpl' $(find src/main/repositories -name '*.hwf') | wc -l"
                    + "; echo '---'"
                    + "; grep -h '\\.hpl' $(find src/main/repositories -name '*.hwf') | head -20";
            assertTrue(compiled.evaluate(agentMode, command1).allowed(), agentMode.name());

            final var command2 = "cd src/main/repositories && for f in $(find . -name '*.hpl' -print0"
                    + " | xargs -0 grep -l '\\.hpl' 2>/dev/null | head -8); do echo \"== $f\""
                    + "; grep -o 'filename>[^<]*\\.hpl' \"$f\" | head -3; done";
            assertTrue(compiled.evaluate(agentMode, command2).allowed(), agentMode.name());
        }
    }

    @Test
    void segmentsExpandSubstitutionsBackForPatternMatching() {
        final var compiled = compile(List.of(rule(PermissionMode.YOLO, null, "echo")));
        assertEquals(List.of("echo $(ls -la)", "grep foo bar"), compiled.segments("echo $(ls -la) | grep foo bar"));
    }

    @Test
    void doubleBracketTestsAndNestedSubstitutions() {
        final var compiled = compile(CommandPermissionConfiguration.defaultRules());
        assertTrue(compiled.evaluate(AgentMode.PLAN, "if [[ -n \"$(find . -name '*.java')\" ]]; then echo x; fi")
                .allowed());
        assertFalse(compiled.evaluate(AgentMode.PLAN, "[[ $(rm -rf /) ]]").allowed());
    }
}
