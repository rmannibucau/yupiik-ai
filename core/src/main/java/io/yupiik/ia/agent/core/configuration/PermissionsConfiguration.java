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
package io.yupiik.ia.agent.core.configuration;

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.json.JsonModel;
import java.util.List;

@JsonModel
public record PermissionsConfiguration(
        @Property(
                defaultValue = "io.yupiik.ia.agent.core.configuration.PermissionMode.ASK",
                documentation =
                        "Permission mode: ask, auto_edit (edits allowed, bash asks) or yolo (everything allowed).")
        PermissionMode mode,

        @Property(
                defaultValue = "java.util.List.of()",
                documentation =
                        "Tool name or bash command prefixes always allowed. Comma separated, eg. `read_file,grep,git *`.")
        List<String> allow,

        @Property(
                defaultValue = "java.util.List.of()",
                documentation =
                        "Tool name or bash command prefixes always denied. Comma separated, eg. `bash:rm -rf *`.")
        List<String> deny,

        @Property(
                defaultValue =
                        "java.util.List.of(\"git status\", \"git diff\", \"git log\", \"git branch\", \"ls\", \"cat\", \"pwd\", \"echo\", \"find\", \"grep\", \"rg\", \"wc\", \"head\", \"tail\", \"sed -n\")",
                documentation =
                        "Bash command prefixes allowed in plan (read-only) mode. Comma separated, eg. `git status,git diff`. "
                                + "The command is split on &&, ||, ;, | and newlines; every segment must start with one of these "
                                + "prefixes (leading VAR=value assignments and cd segments are tolerated). Redirections and "
                                + "command substitution are rejected.")
        List<String> planBash) {

    public boolean isAllowed(final String name) {
        return allow != null && allow.stream().anyMatch(a -> a != null && matches(a, name));
    }

    public boolean isDenied(final String name) {
        return deny != null && deny.stream().anyMatch(d -> d != null && matches(d, name));
    }

    public boolean isPlanBash(final String command) {
        if (command == null || planBash == null) {
            return false;
        }
        for (final var segment : command.strip().split("&&|\\|\\||;|\\||\\n|&")) {
            if (!isPlanSegment(segment)) {
                return false;
            }
        }
        return true;
    }

    private boolean isPlanSegment(final String rawSegment) {
        final var segment = rawSegment.strip();
        if (segment.isEmpty()) {
            return true;
        }
        // tolerate stderr silencing/moving; reject other redirections and command substitution
        final var harmless = segment.replace("2>/dev/null", "")
                .replace(">/dev/null", "")
                .replace("2>&1", "")
                .strip();
        if (harmless.contains(">") || harmless.contains("$(") || harmless.contains("`") || harmless.contains("<(")) {
            return false;
        }
        final var tokens = harmless.split("\\s+");
        var i = 0;
        while (i < tokens.length && isEnvAssignment(tokens[i])) {
            i++;
        }
        if (i >= tokens.length) {
            return true; // pure env assignments
        }
        if (tokens[i].equals("cd")) {
            return true; // directory change has no repository side effect
        }
        final var normalized = String.join(" ", java.util.Arrays.asList(tokens).subList(i, tokens.length));
        if (normalized.startsWith("sed") && tokensAfter(tokens, i, "-i")) {
            return false; // in-place sed rewrites files even behind a `sed -n` prefix
        }
        return planBash.stream().anyMatch(p -> p != null && normalized.startsWith(p.strip()));
    }

    private static boolean tokensAfter(final String[] tokens, final int from, final String needle) {
        for (var j = from; j < tokens.length; j++) {
            if (tokens[j].equals(needle)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isEnvAssignment(final String token) {
        if (token.isEmpty() || !Character.isLetterOrDigit(token.charAt(0)) && token.charAt(0) != '_') {
            return false;
        }
        for (var i = 0; i < token.length(); i++) {
            final var c = token.charAt(i);
            if (c == '=') {
                return i > 0 && (Character.isLetterOrDigit(token.charAt(i - 1)) || token.charAt(i - 1) == '_');
            }
            if (!Character.isLetterOrDigit(c) && c != '_' && c != '.') {
                return false;
            }
        }
        return false;
    }

    private static boolean matches(final String pattern, final String name) {
        final var p = pattern.strip();
        return p.equals(name) || (p.endsWith("*") && name.startsWith(p.substring(0, p.length() - 1)));
    }
}
