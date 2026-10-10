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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Minisite preaction that escapes literal Handlebars in the generated documentation partials so the minisite's own
 * template resolver does not treat them as live template keys.
 *
 * <p>The generated config pages (from the fusion {@code DocumentationGenerator}) embed yca's {@code @Property}
 * documentation and default values verbatim, and several of those (the CLI prompt template, the transcript labels)
 * are themselves Handlebars templates whose {@code {{mode}}}-style placeholders collide with the minisite resolver
 * ("Unknown template key"). This pass rewrites every {@code _partials/generated/*.adoc} file, turning each bare
 * {@code {{}} into the minisite's documented {@code {$yupiik.minisite.openbracket$}{}} escape (which it renders back
 * to a literal {@code {}), leaving the actual {@code @Property} defaults untouched at runtime.</p>
 */
public class EscapeGeneratedHandlebarsGenerator implements Runnable {

    /** The minisite/commons escape that renders a literal {@code {} (see TemplateSubstitutor.placeholders). */
    private static final String OPEN = "{$yupiik.minisite.openbracket$}";

    private final Path sourceBase;

    public EscapeGeneratedHandlebarsGenerator(final Path sourceBase) {
        this.sourceBase = sourceBase;
    }

    @Override
    public void run() {
        // escape every adoc under content/ (hand-written feature pages AND generated partials) so the minisite's own
        // template resolver never sees a bare {{...}} that is really a documented Handlebars template example.
        final var content = sourceBase.resolve("content").normalize();
        if (!Files.isDirectory(content)) {
            return;
        }
        try (final var walk = Files.walk(content)) {
            walk.filter(p -> {
                        if (!Files.isRegularFile(p)) {
                            return false;
                        }
                        final var name = p.getFileName().toString();
                        if (name.endsWith(".adoc")) {
                            return true;
                        }
                        // only the generated JSON schema partial is inline-rendered (include'd into
                        // configuration.adoc);
                        // the assets/jsonschema copy is a linked static asset consumed by JS and must not be escaped
                        return name.endsWith(".json")
                                && p.getParent() != null
                                && p.getParent().getFileName().toString().equals("generated");
                    })
                    .forEach(this::escape);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void escape(final Path file) {
        try {
            final var content = Files.readString(file);
            // normalize any escape left by a previous run, then escape again so the pass is idempotent
            final var escaped = escape(content.replace(OPEN, "{"));
            if (!escaped.equals(content)) {
                Files.writeString(file, escaped);
            }
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot escape " + file, e);
        }
    }

    /** Escapes every {@code {{} not already escaped (and not a fusion raw block {@code {{{{/...}}}) to {@code OPEN}}. */
    private String escape(final String content) {
        final var out = new StringBuilder(content.length());
        int i = 0;
        while (i < content.length()) {
            if (content.startsWith("{{", i)) {
                // raw block {{{{name}}}}...{{{{/name}}}} already shields its body: keep it like that
                if (content.startsWith("{{{{", i)) {
                    final var close = content.indexOf("}}}}", i + 4);
                    if (close >= 0) {
                        out.append(content, i, close + 4);
                        i = close + 4;
                        continue;
                    }
                }
                out.append(OPEN).append('{');
                i += 2;
            } else {
                out.append(content.charAt(i));
                i++;
            }
        }
        return out.toString();
    }
}
