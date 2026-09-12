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
import java.util.List;

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
                        "java.util.List.of(\"git status\", \"git diff\", \"git log\", \"git branch\", \"ls\", \"cat\", \"pwd\", \"echo\", \"find\", \"grep\", \"rg\", \"wc\", \"head\", \"tail\")",
                documentation =
                        "Bash command prefixes allowed in plan (read-only) mode. Comma separated, eg. `git status,git diff`. A plan-mode bash call is allowed when it starts with one of these prefixes.")
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
        final var c = command.strip();
        return planBash.stream().anyMatch(p -> p != null && c.startsWith(p.strip()));
    }

    private static boolean matches(final String pattern, final String name) {
        final var p = pattern.strip();
        return p.equals(name) || (p.endsWith("*") && name.startsWith(p.substring(0, p.length() - 1)));
    }
}
