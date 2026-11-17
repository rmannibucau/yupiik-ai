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

import static java.util.stream.Collectors.joining;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.documentation.DocumentationGenerator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

class ScopedConfigurationDocumentationTest {
    private static final Pattern ENTRY = Pattern.compile("(?m)^`([^`\\r\\n]+)` \\(env: `([^`\\r\\n]+)`\\)::$");
    private static final List<String> FULL_MODULES = List.of("core", "tui", "web", "github-bot");
    private final Path base = Path.of("").toAbsolutePath().normalize();
    private final Path source = base.resolve("src/main/minisite");
    private final Path generated = source.resolve("content/_partials/generated");

    @Test
    void scopedReferencesContainEveryMatchingEntry(@TempDir final Path temp) throws Exception {
        final var references = new HashMap<String, List<String>>();
        final var inputs = new HashMap<String, String>();
        final var scoped =
                actions().stream().filter(it -> it.containsKey("output")).toList();
        assertFalse(scoped.isEmpty(), "No explicit configuration partials configured");
        for (final var action : scoped) {
            final var output = Path.of(action.get("output")).toAbsolutePath().normalize();
            final var label = output.getFileName().toString();
            assertTrue(output.startsWith(generated), label);
            assertEquals("definitionlist", action.get("formatter"), label);
            assertEquals("true", action.get("includeEnvironmentNames"), label);
            final var urls = action.getOrDefault("urls", "").split(",", -1);
            assertEquals(1, urls.length, "Exactly one metadata input: " + label);
            assertFalse(urls[0].isBlank(), "Missing metadata input: " + label);
            assertTrue(Files.isRegularFile(Path.of(urls[0].strip())), "Compile reactor metadata first: " + urls[0]);
            final var module = action.getOrDefault("module", "");
            assertFalse(module.isBlank(), label);
            final var previous = inputs.putIfAbsent(module, urls[0]);
            assertTrue(previous == null || previous.equals(urls[0]), "Conflicting metadata for " + module);
            final var prefixes = Arrays.stream(
                            action.getOrDefault("namePrefix", "").split(","))
                    .filter(it -> !it.isBlank())
                    .toList();
            assertFalse(prefixes.isEmpty(), "Missing scope: " + label);
            if (!references.containsKey(module)) {
                final var complete = new HashMap<>(action);
                complete.remove("namePrefix");
                references.put(module, entries(generate(temp, complete, temp.resolve("oracle/" + module + ".adoc"))));
            }
            final var expected = references.get(module).stream()
                    .filter(entry -> matches(key(entry), prefixes))
                    .collect(joining());
            assertFalse(expected.isBlank(), "Scope matches no metadata keys: " + label);
            final var actual = generate(temp, action, temp.resolve(base.relativize(output)));
            assertEquals(expected, actual, "All matching keys, descriptions and defaults: " + label);
            final var entries = entries(actual);
            assertFalse(entries.isEmpty(), label);
            assertTrue(actual.contains(" Default: "), "Missing default text: " + label);
            for (final var entry : entries) {
                final var key = key(entry);
                assertFalse(key.isBlank(), label);
                assertTrue(matches(key, prefixes), "Out-of-scope key: " + key);
                final var env = key.replace("$index", "INDEX")
                        .replaceAll("[^A-Za-z0-9]", "_")
                        .toUpperCase(Locale.ROOT);
                assertTrue(
                        entry.startsWith('`' + key + "` (env: `" + env + "`)::\n"), "Missing environment name: " + key);
            }
        }
    }

    @Test
    void outputsAreUniqueAndIncludesHaveExactlyTheConfiguredProducers() throws Exception {
        final var actions = actions();
        final var occupied = new HashSet<Path>();
        // Classpath discovery writes these implicit full references; named actions can add more.
        final var modules = new HashSet<>(FULL_MODULES);
        actions.stream().map(it -> it.get("module")).filter(it -> it != null).forEach(modules::add);
        modules.forEach(module -> occupied.add(generated.resolve("documentation." + module + ".adoc")));
        final var outputs = new HashSet<Path>();
        for (final var action : actions) {
            if (action.containsKey("output")) {
                final var output =
                        Path.of(action.get("output")).toAbsolutePath().normalize();
                assertTrue(occupied.add(output), "Output collision (including implicit references): " + output);
                assertEquals(generated, output.getParent(), output.toString());
                assertTrue(output.getFileName().toString().matches("configuration-.+\\.adoc"), output.toString());
                outputs.add(output);
            }
        }
        assertFalse(outputs.isEmpty());
        final var includes = new HashSet<Path>();
        final var include =
                Pattern.compile("(?m)^include::(content/_partials/generated/configuration-[^\\[\\r\\n]+\\.adoc)\\[");
        try (final var pages = Files.walk(source.resolve("content"))) {
            for (final var page : pages.filter(it -> it.toString().endsWith(".adoc"))
                    .filter(it -> !it.startsWith(generated))
                    .toList()) {
                include.matcher(Files.readString(page))
                        .results()
                        .map(it -> source.resolve(it.group(1)).normalize())
                        .forEach(includes::add);
            }
        }
        assertEquals(outputs, includes, "Every scoped include needs a producer and every output needs a consumer");
        final var configuration = Files.readString(source.resolve("content/configuration.adoc"));
        final var github = Files.readString(source.resolve("content/github-bot/configuration.adoc"));
        for (final var module : FULL_MODULES) {
            assertTrue(
                    (module.equals("github-bot") ? github : configuration)
                            .contains("include::content/_partials/generated/documentation." + module + ".adoc[]"),
                    module);
        }
    }

    private List<Map<String, String>> actions() throws Exception {
        final var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        final var pom =
                factory.newDocumentBuilder().parse(base.resolve("pom.xml").toFile());
        final var nodes = (NodeList) XPathFactory.newInstance()
                .newXPath()
                .evaluate(
                        "/project/build/plugins/plugin[artifactId='yupiik-tools-maven-plugin']/configuration/preActions/"
                                + "preAction[type='io.yupiik.fusion.documentation.DocumentationGenerator']/configuration",
                        pom,
                        XPathConstants.NODESET);
        final var actions = new ArrayList<Map<String, String>>();
        for (var i = 0; i < nodes.getLength(); i++) {
            final var configuration = new HashMap<String, String>();
            final var children = nodes.item(i).getChildNodes();
            for (var j = 0; j < children.getLength(); j++) {
                final var child = children.item(j);
                if (child.getNodeType() == Node.ELEMENT_NODE) {
                    configuration.put(
                            child.getNodeName(),
                            child.getTextContent().strip().replace("${project.basedir}", base.toString()));
                }
            }
            actions.add(configuration);
        }
        return actions;
    }

    private String generate(final Path temp, final Map<String, String> action, final Path output) throws Exception {
        final var configuration = new HashMap<>(action);
        configuration.put("output", output.toString());
        new DocumentationGenerator(temp, configuration).run();
        return Files.readString(output);
    }

    private List<String> entries(final String document) {
        final var headings = ENTRY.matcher(document).results().toList();
        final var entries = new ArrayList<String>();
        for (var i = 0; i < headings.size(); i++) {
            entries.add(document.substring(
                    headings.get(i).start(),
                    i + 1 < headings.size() ? headings.get(i + 1).start() : document.length()));
        }
        assertEquals(document, String.join("", entries), "Unparsed reference text");
        return entries;
    }

    private String key(final String entry) {
        return entry.substring(1, entry.indexOf('`', 1));
    }

    private boolean matches(final String key, final List<String> prefixes) {
        return prefixes.stream()
                .allMatch(prefix ->
                        prefix.startsWith("!") ? !key.startsWith(prefix.substring(1)) : key.startsWith(prefix));
    }
}
