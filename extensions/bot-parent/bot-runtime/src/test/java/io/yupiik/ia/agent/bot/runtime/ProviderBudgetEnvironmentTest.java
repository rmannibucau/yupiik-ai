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
package io.yupiik.ia.agent.bot.runtime;

import static io.yupiik.ia.agent.bot.runtime.ProviderBudgetEnvironment.*;
import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.fusion.framework.api.configuration.Configuration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProviderBudgetEnvironmentTest {
    @TempDir
    Path root;

    @Test
    void disabledDoesNotChangeLegacyEnvironment() {
        assertEquals(Map.of(), select(Configuration.of(Map.of("OPENAI_API_KEY", "never-copy"))));
        assertEquals(Map.of(), select(Configuration.of(Map.of(ROOT, " "))));
        for (final var orphan : Map.of(ID, "account", SLOTS, "").entrySet()) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> select(Configuration.of(Map.of(orphan.getKey(), orphan.getValue()))));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> select(Configuration.of(Map.of(ROOT, " ", orphan.getKey(), orphan.getValue()))));
        }
    }

    @Test
    void selectsOnlyTrustedBudgetVariablesAndRejectsAgentMountOverlap() throws Exception {
        final var budget = Files.createDirectory(root.resolve("budget"));
        final var environment = Configuration.of(
                Map.of(ROOT, budget.toString(), ID, "provider_account", SLOTS, "2", "SECRET", "hidden"));
        assertEquals(
                Map.of(ROOT, budget.toString(), ID, "provider_account", SLOTS, "2"),
                select(environment, root.resolve("attempts"), root.resolve("state")));
        assertThrows(IllegalArgumentException.class, () -> select(environment, root));
        assertThrows(IllegalArgumentException.class, () -> select(environment, budget.resolve("agent")));
    }

    @Test
    void rejectsAmbiguousOrUnprovisionedFilesystemRoutes() throws Exception {
        final var budget = Files.createDirectory(root.resolve("budget"));
        final var link = Files.createSymbolicLink(root.resolve("alias"), budget);
        for (final var invalid : new String[] {
            "relative",
            root.resolve("missing").toString(),
            link.toString(),
            budget.resolve("..").resolve("budget").toString()
        }) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> select(Configuration.of(Map.of(ROOT, invalid, ID, "account", SLOTS, "1"))));
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> select(Configuration.of(Map.of(ROOT, budget.toString(), ID, "../account", SLOTS, "1"))));
        assertThrows(
                IllegalArgumentException.class,
                () -> select(Configuration.of(Map.of(ROOT, budget.toString(), ID, "account", SLOTS, "0"))));
    }
}
