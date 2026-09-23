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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.core.cli.slash.SlashCommandSpec;
import io.yupiik.ia.agent.core.cli.slash.SlashGroup;
import io.yupiik.ia.agent.core.shell.slash.ArgCompletion;
import io.yupiik.ia.agent.core.shell.slash.SlashCommand;
import io.yupiik.ia.agent.core.shell.slash.SlashCommandSet;
import io.yupiik.ia.agent.core.shell.slash.SlashRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

class CompletionEngineTest {
    private static SlashRegistry registry() {
        final SlashCommandSet set = () -> List.of(
                spec("/model", ArgCompletion.MODELS, SlashGroup.CONFIG),
                spec("/provider", ArgCompletion.PROVIDERS, SlashGroup.CONFIG),
                spec("/agent", ArgCompletion.AGENTS, SlashGroup.CONFIG),
                spec("/resume", ArgCompletion.SESSIONS, SlashGroup.SESSION),
                spec("/tools", ArgCompletion.TOOLS, SlashGroup.INSPECT),
                spec("/mode", ArgCompletion.MODE, SlashGroup.CONFIG),
                spec("/permissions", ArgCompletion.PERMISSIONS, SlashGroup.CONFIG),
                spec("/thinking", ArgCompletion.SHOW_HIDE, SlashGroup.CONFIG),
                spec("/tool-output", ArgCompletion.SHOW_COLLAPSE_HIDE, SlashGroup.CONFIG),
                spec("/context", ArgCompletion.PATH, SlashGroup.INSPECT),
                spec("/editplan", ArgCompletion.PATH, SlashGroup.MAINTAIN));
        return new SlashRegistry(List.of(set));
    }

    private static SlashCommand spec(final String name, final ArgCompletion completion, final SlashGroup group) {
        return SlashCommand.of(new SlashCommandSpec(name, null, name + " cmd", group), completion, (arg, env) -> null);
    }

    private static CompletionEngine engine() {
        return new CompletionEngine(
                registry(),
                () -> List.of("gpt-5-mini", "gpt-5", "qwen3-coder"),
                () -> List.of("openai", "ollama"),
                () -> List.of("reviewer", "tester"),
                () -> List.of("11111111-aaaa (2026-09-01 10:00)", "22222222-bbbb (2026-09-02 11:00)"),
                () -> List.of("read_file", "write_file", "bash"),
                () -> List.of("coder", "writer"),
                model -> List.<String>of("default", "fast".equals(model) ? "deep" : "fast"));
    }

    @Test
    void emptyTextHasNoSuggestion() {
        assertTrue(engine().suggest("", 0).empty());
    }

    @Test
    void completesSlashCommandPrefix() {
        final var s = engine().suggest("/per", 4);
        assertTrue(s.items().contains("/permissions"));
        assertEquals(0, s.replaceStart());
        assertEquals(4, s.replaceEnd());
    }

    @Test
    void completesModelParam() {
        final var s = engine().suggest("/model gpt", 10);
        assertTrue(s.items().contains("gpt-5-mini"));
        assertTrue(s.items().contains("gpt-5"));
        assertEquals(7, s.replaceStart());
        assertEquals(10, s.replaceEnd());
        assertEquals("model", s.label());
    }

    @Test
    void completesVariantAsSecondModelToken() {
        final var s = engine().suggest("/model gpt-5 f", 13);
        assertTrue(s.items().contains("fast"));
        assertTrue(s.items().contains("default"));
        assertEquals("variant", s.label());
    }

    @Test
    void variantCompletionIsDrivenByTheTypedModel() {
        // "fast" typed model resolves a different variant set than "gpt-5"
        final var forFast = engine().suggest("/model fast f", 12);
        assertTrue(forFast.items().contains("deep"), "variant list must reflect the typed model");
        final var forGpt5 = engine().suggest("/model gpt-5 f", 13);
        assertTrue(forGpt5.items().contains("fast"), "gpt-5 typed model keeps its own variants");
        assertFalse(forGpt5.items().contains("deep"));
    }

    @Test
    void completesModeParam() {
        final var s = engine().suggest("/mode pl", 8);
        assertTrue(s.items().contains("plan"));
    }

    @Test
    void completesPermissionsParam() {
        final var all = engine().suggest("/permissions a", 14);
        assertTrue(all.items().contains("all"));
        final var y = engine().suggest("/permissions y", 14);
        assertTrue(y.items().contains("yolo"));
        final var auto = engine().suggest("/permissions auto", 17);
        assertTrue(auto.items().contains("auto_edit"));
        final var n = engine().suggest("/permissions n", 14);
        assertTrue(n.items().contains("none"));
        final var s = engine().suggest("/permissions sh", 15);
        assertTrue(s.items().contains("show"));
    }

    @Test
    void completesThinkingParam() {
        final var s = engine().suggest("/thinking h", 11);
        assertTrue(s.items().contains("hide"));
    }

    @Test
    void completesToolOutputParam() {
        final var s = engine().suggest("/tool-output ", 13);
        assertTrue(s.items().contains("show"));
        assertTrue(s.items().contains("collapse"));
        assertTrue(s.items().contains("hide"));
    }

    @Test
    void completesAgentNameOverPrefix() {
        final var s = engine().suggest("@cod", 4);
        assertTrue(s.items().contains("coder"));
    }

    @Test
    void fuzzyContainsMatching() {
        final var s = engine().suggest("/model mini", 11);
        assertTrue(s.items().contains("gpt-5-mini"));
    }

    @Test
    void noMatchIsEmpty() {
        assertTrue(engine().suggest("/mode zzz", 9).empty());
    }

    @Test
    void pathCompletionLooksUpFilesystem() {
        final var s = CompletionEngine.pathCandidates("src/m");
        assertEquals(List.of("src/main/"), s);
    }

    @Test
    void pathCompletionWithoutTokenListsDirectory() {
        assertTrue(!CompletionEngine.pathCandidates("").isEmpty());
    }
}
