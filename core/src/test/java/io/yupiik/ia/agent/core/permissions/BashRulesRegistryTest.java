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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.core.configuration.AgentMode;
import io.yupiik.ia.agent.core.configuration.BashPermissionsConfiguration;
import io.yupiik.ia.agent.core.configuration.CommandOptionsConfiguration;
import io.yupiik.ia.agent.core.configuration.CommandPermissionConfiguration;
import io.yupiik.ia.agent.core.configuration.CommandStartMatcherConfiguration;
import io.yupiik.ia.agent.core.configuration.PermissionMode;
import io.yupiik.ia.agent.core.configuration.TestConfigs;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BashRulesRegistryTest {

    private static CommandPermissionConfiguration rule(final String commandStart) {
        return new CommandPermissionConfiguration(
                new CommandStartMatcherConfiguration(new CommandOptionsConfiguration(List.of(commandStart)), List.of()),
                PermissionMode.YOLO,
                null);
    }

    private static Map<String, String> bashProps(final String... commandStarts) {
        final var props = new HashMap<String, String>();
        props.put("yca.permissions.bash.rules.length", String.valueOf(commandStarts.length));
        for (var i = 0; i < commandStarts.length; i++) {
            props.put("yca.permissions.bash.rules." + i + ".matcher.commandStart.options", commandStarts[i]);
            props.put(
                    "yca.permissions.bash.rules." + i + ".plan",
                    PermissionMode.YOLO.name().toLowerCase(Locale.ROOT));
        }
        return props;
    }

    private static BashRulesRegistry registry(final Map<String, String> props) {
        return new BashRulesRegistry(TestConfigs.codingAgent(props));
    }

    @Test
    void emptyOverridesReturnTheSameBaseInstance() {
        final var registry = registry(bashProps("ls", "echo"));
        assertSame(registry.base(), registry.compiled(List.of()));
        assertSame(registry.base(), registry.compiled(null));
    }

    @Test
    void baseIsRecompiledWhenTheConfigIdentityChanges() {
        final var first = new BashPermissionsConfiguration(List.of(rule("ls")), null, 1024);
        final var second = new BashPermissionsConfiguration(List.of(rule("echo")), null, 1024);
        final var registry = registry(Map.of());

        final var base1 = registry.base();
        registry.setConfig(first);
        assertSame(registry.base(), registry.base());
        registry.setConfig(second);
        assertNotSame(base1, registry.base());
        // the new base reflects the new config: `echo` is a rule, `ls` is not any more
        assertTrue(registry.base().evaluate(AgentMode.BUILD, "echo x").allowed());
        assertFalse(registry.base().evaluate(AgentMode.BUILD, "ls").allowed());
    }

    @Test
    void identicalOverrideSnapshotsShareOneCombinedEntryAcrossSessions() {
        final var registry = registry(bashProps("echo"));
        final var one = registry.compiled(List.of(rule("ls")));
        final var two = registry.compiled(List.of(rule("ls")));
        assertSame(one, two);
        // and the combined evaluator merges configured + override rules
        assertTrue(one.evaluate(AgentMode.BUILD, "echo x").allowed());
        assertTrue(one.evaluate(AgentMode.BUILD, "ls").allowed());
    }

    @Test
    void setConfigInvalidatesTheCombinedCache() {
        final var registry = registry(bashProps("echo"));
        final var before = registry.compiled(List.of(rule("ls")));
        registry.setConfig(new BashPermissionsConfiguration(List.of(rule("echo")), null, 1024));
        // the combination recompiles against the new config: `ls` still comes from the override, so
        // the new instance differs from the pre-invalidation one
        assertNotSame(before, registry.compiled(List.of(rule("ls"))));
    }

    @Test
    void dynamicOverrideSupportsNestedSubstitutionEvaluation() {
        // a custom family allowed dynamically must also be honored inside $( ... ), nested included
        final var registry = registry(bashProps("echo"));
        final var dynamic = registry.compiled(List.of(rule("npm test")));
        assertTrue(dynamic.evaluate(AgentMode.BUILD, "npm test -- --run").allowed());
        assertTrue(dynamic.evaluate(AgentMode.BUILD, "echo \"a $(npm test) b\"").allowed());
        // a mutating command stays gated even with an override present
        assertFalse(
                dynamic.evaluate(AgentMode.BUILD, "echo \"a $(rm -rf /) b\"").allowed());
    }

    @Test
    void combinedCacheIsBoundedByTheConfiguredMaxCombined() {
        final var props = bashProps("echo");
        props.put("yca.permissions.bash.maxCombined", "1");
        final var registry = registry(props);

        final var first = registry.compiled(List.of(rule("a")));
        final var second = registry.compiled(List.of(rule("b")));
        // bound 1: the second distinct snapshot clears the cache, so `second` compiles on a fresh map
        assertNotSame(first, second);
        // the base (configured rules) is not affected by the combined bound
        assertSame(registry.base(), registry.compiled(List.of()));
    }

    @Test
    void noExplicitBashConfigFallsBackToTheDefaultReadOnlyFamilies() {
        final var registry = new BashRulesRegistry(TestConfigs.codingAgent(Map.of("yca.storageHome", "target/tmp")));
        // no yca.permissions.bash.* configured: the base is the default read-only families (ls included)
        assertTrue(registry.base().evaluate(AgentMode.BUILD, "ls").allowed());
        assertFalse(registry.base().evaluate(AgentMode.BUILD, "mvn install").allowed());
        // an override still merges with the default base
        final var overridden = registry.compiled(List.of(rule("mvn")));
        assertTrue(overridden.evaluate(AgentMode.BUILD, "mvn install").allowed());
        assertTrue(overridden.evaluate(AgentMode.BUILD, "ls").allowed());
    }
}
