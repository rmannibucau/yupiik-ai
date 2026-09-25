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
import io.yupiik.fusion.framework.build.api.json.JsonProperty;
import java.util.Arrays;
import java.util.List;

/**
 * A single {@code yca.permissions.bash.rules} entry: how to recognize a bash command family
 * and which permission mode applies to it in plan and build modes.
 *
 * <p>Example: allow {@code docker run} in build mode while keeping plan mode asking for it:</p>
 * <pre>{@code
 * { "matcher": { "commandStart": { "options": ["docker", "run"] } }, "plan": "ask", "default": "all" }
 * }</pre>
 */
@JsonModel
public record CommandPermissionConfiguration(
        @Property(
                documentation =
                        "How to recognize the command: leading tokens (`commandStart`) and options rejected even when they match (`excludeOptions`).")
        CommandStartMatcherConfiguration matcher,

        @Property(
                documentation =
                        "Permission mode applied in plan mode when the matcher matches (ask/auto_edit/yolo, `all` = yolo)."
                                + " Absent = same as `default`.")
        PermissionMode plan,

        @JsonProperty("default")
        @Property(
                value = "default",
                documentation =
                        "Permission mode applied in build mode when the matcher matches (ask/auto_edit/yolo, `all` = yolo)."
                                + " Absent (null) = inherit `plan`.")
        PermissionMode defaultMode) {

    /**
     * Built-in read-only command families: recognized and allowed without prompting in both plan and build modes.
     * State changing forms are carved out with {@code excludeOptions} (eg. {@code sed -i}, {@code find -delete},
     * {@code git branch -d}, {@code sort -o}).
     */
    public static List<CommandPermissionConfiguration> defaultRules() {
        return List.of(
                command("git status"),
                command("git diff"),
                command("git log"),
                command("git branch", "-d", "-D", "--delete"),
                command("ls"),
                command("cat"),
                command("pwd"),
                command("echo"),
                command(
                        "find",
                        "-delete",
                        "-exec",
                        "-execdir",
                        "-ok",
                        "-okdir",
                        "-fls",
                        "-fprint",
                        "-fprint0",
                        "-fprintf"),
                command("grep"),
                command("rg"),
                command("wc"),
                command("head"),
                command("tail"),
                command("test"),
                command("tr"),
                command("sort", "-o", "--output"),
                command("uniq"),
                command("sed", "-i", "--in-place"),
                command("java -version"),
                command("java -help"));
    }

    private static CommandPermissionConfiguration command(final String commandStart, final String... excludedOptions) {
        final var excludes = Arrays.stream(excludedOptions)
                .map(option -> new CommandOptionsConfiguration(List.of(option)))
                .toList();
        return new CommandPermissionConfiguration(
                new CommandStartMatcherConfiguration(new CommandOptionsConfiguration(List.of(commandStart)), excludes),
                PermissionMode.YOLO,
                null);
    }
}
