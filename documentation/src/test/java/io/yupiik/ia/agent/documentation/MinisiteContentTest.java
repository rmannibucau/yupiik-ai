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
package io.yupiik.ia.agent.documentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class MinisiteContentTest {
    private final Path content = Path.of("src/main/minisite/content");

    @Test
    void commandDirectoriesLinkGeneratedReferences() throws Exception {
        final var cli = Files.readString(content.resolve("cli.adoc"));
        final var bot = Files.readString(content.resolve("github-bot/index.adoc"));
        assertTrue(cli.contains("xref:cli/index.adoc["));
        assertTrue(bot.contains("xref:../cli/index.adoc["));
        for (final var command : List.of("cli", "run", "code-graph-index", "completion-bash", "tui", "web")) {
            assertTrue(cli.contains("xref:cli/" + command + ".adoc["), command);
        }
        for (final var command : List.of(
                "poll", "list", "show", "create", "worker", "retry", "delete", "purge", "cleanup", "cache-maintain")) {
            assertTrue(bot.contains("xref:../cli/github-bot-" + command + ".adoc["), command);
        }
        assertTrue(Files.readString(content.resolve("github-bot/configuration.adoc"))
                .contains("xref:index.adoc#cli-commands["));
    }

    @Test
    void dependencyCacheIsAChildPageLinkedFromTheCanonicalBotGuides() throws Exception {
        final var cache = Files.readString(content.resolve("github-bot/cache.adoc"));
        assertTrue(cache.startsWith("= Dependency cache\n"));
        assertTrue(cache.contains("Home[../index.html]"));
        assertTrue(cache.contains("GitHub bot[index.html]"));
        for (final var page :
                List.of("index", "configuration", "architecture", "security", "installation", "kubernetes")) {
            assertTrue(
                    Files.readString(content.resolve("github-bot/" + page + ".adoc"))
                            .contains("xref:cache.adoc["),
                    page);
        }
    }

    @Test
    void diagramsUseTheRendererSupportedBlockAndOneLocalLoader() throws Exception {
        final var block = Pattern.compile("(?m)^\\[mermaid]\\R");
        final var supported = Pattern.compile("(?m)^\\[mermaid]\\R\\.\\.\\.\\.\\R");
        final var include = "include::content/_partials/mermaid.js.adoc[]";
        for (final var page : List.of(
                "configuration.adoc",
                "features/context.adoc",
                "features/compaction.adoc",
                "features/permissions.adoc",
                "features/sessions.adoc",
                "github-bot/architecture.adoc")) {
            final var source = Files.readString(content.resolve(page));
            final var count = block.matcher(source).results().count();
            assertTrue(count > 0, page);
            assertEquals(count, supported.matcher(source).results().count(), page);
            assertEquals(1, source.split(Pattern.quote(include), -1).length - 1, page);
        }
        final var pom = Files.readString(Path.of("pom.xml"));
        assertFalse(pom.contains("mermaid.min.js"));
        assertFalse(pom.contains("window.mermaid"));
    }

    @Test
    void nestedNavigationDoesNotRepeatTheFeatureDirectory() throws Exception {
        try (final var pages = Files.list(content.resolve("features"))) {
            for (final var page :
                    pages.filter(path -> path.toString().endsWith(".adoc")).toList()) {
                final var source = Files.readString(page);
                assertFalse(source.contains("xref:features/"), page.toString());
                assertTrue(source.contains("Home[../index.html]"), page.toString());
            }
        }
    }
}
