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
package io.yupiik.ia.agent.slack.context;

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.fusion.json.internal.JsonMapperImpl;
import io.yupiik.ia.agent.bot.runtime.configuration.ExecutorConfiguration;
import io.yupiik.ia.agent.bot.runtime.model.AgentConfigurationDocument;
import io.yupiik.ia.agent.bot.runtime.model.AgentConfigurationDocument$FusionJsonCodec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SlackContextStorageTest {
    @TempDir
    Path home;

    @Test
    void acceptsConfigurablePrivateRootAndTmpfsBindSubdirectory() throws Exception {
        final var root = root("custom attempts");
        final var metadata = "81 12 0:30 /some/subdirectory " + escape(root)
                + " rw,nosuid,nodev,noexec shared:7 - tmpfs tmpfs rw,noswap,size=512m";
        assertEquals(root, storage(List.of(disk(), metadata)).validate(configuration(root)));
    }

    @Test
    void decodesAllKernelMountPointEscapes() throws Exception {
        final var root = root("attempt \\ tab\t newline\n");
        assertEquals(
                root,
                storage(List.of(disk(), mount(root, "tmpfs", "rw,nodev,nosuid,noexec", "rw,noswap")))
                        .validate(configuration(root)));
    }

    @Test
    void refusesUnreadableMountMetadataRatherThanFallingBackToFileStore() throws Exception {
        final var root = root("attempts");
        final var helper = new SlackContextStorage() {
            @Override
            protected List<String> mountInfo() throws IOException {
                throw new IOException("unavailable");
            }
        };
        assertThrows(IOException.class, () -> helper.validate(configuration(root)));
    }

    @Test
    void longestMountWinsEvenWhenEarlierOrLaterAncestorIsTmpfs() throws Exception {
        final var root = root("attempts");
        final var parent = mount(home, "tmpfs", "rw,nodev,nosuid,noexec", "rw,noswap");
        final var child = mount(root, "ext4", "rw,nodev,nosuid,noexec", "rw");
        for (final var lines : List.of(List.of(disk(), parent, child), List.of(child, parent, disk()))) {
            assertThrows(IOException.class, () -> storage(lines).validate(configuration(root)));
        }
        assertEquals(root, storage(List.of(disk(), parent)).validate(configuration(root)));
    }

    @Test
    void rejectsDiskSwapAndMissingPerMountFlags() throws Exception {
        final var root = root("attempts");
        for (final var type : List.of("ext4", "overlay", "ramfs")) {
            assertThrows(
                    IOException.class,
                    () -> storage(List.of(mount(root, type, "rw,nodev,nosuid,noexec", "rw,noswap")))
                            .validate(configuration(root)));
        }
        for (final var flags :
                List.of("rw,nosuid,noexec", "rw,nodev,noexec", "rw,nodev,nosuid", "ro,nodev,nosuid,noexec")) {
            // Superblock flags cannot compensate for flags missing on a bind mount.
            assertThrows(
                    IOException.class,
                    () -> storage(List.of(mount(root, "tmpfs", flags, "rw,noswap,nodev,nosuid,noexec")))
                            .validate(configuration(root)));
        }
        for (final var flags : List.of("rw", "rw,swap", "rw,noswap=false")) {
            assertThrows(
                    IOException.class,
                    () -> storage(List.of(mount(root, "tmpfs", "rw,nodev,nosuid,noexec", flags)))
                            .validate(configuration(root)));
        }
    }

    @Test
    void rejectsMissingMalformedAmbiguousAndNestedMounts() throws Exception {
        final var root = root("attempts");
        final var valid = mount(root, "tmpfs", "rw,nodev,nosuid,noexec", "rw,noswap");
        for (final var lines : List.of(
                List.<String>of(),
                List.of("invalid"),
                List.of(valid, valid),
                List.of(valid, mount(root.resolve("nested"), "ext4", "rw", "rw")),
                List.of(mount(home.resolve("attempts-other"), "tmpfs", "rw,nodev,nosuid,noexec", "rw,noswap")),
                List.of(valid.replace(escape(root), escape(root) + "\\999")))) {
            assertThrows(IOException.class, () -> storage(lines).validate(configuration(root)));
        }
    }

    @Test
    void rejectsMissingRelativeUnnormalizedSymlinkAndNonprivateRoots() throws Exception {
        final var root = root("attempts");
        final var helper = storage(List.of(mount(home, "tmpfs", "rw,nodev,nosuid,noexec", "rw,noswap")));
        for (final var path : List.of(Path.of("relative"), root.resolve(".."))) {
            assertThrows(IllegalArgumentException.class, () -> helper.validate(configuration(path)));
        }
        assertThrows(IllegalArgumentException.class, () -> helper.validate(configuration(Path.of(""))));
        assertThrows(IOException.class, () -> helper.validate(configuration(home.resolve("missing"))));
        final var link = Files.createSymbolicLink(home.resolve("link"), root);
        final var nested = Files.createDirectory(root.resolve("nested"));
        assertThrows(IOException.class, () -> helper.validate(configuration(link)));
        assertThrows(IOException.class, () -> helper.validate(configuration(link.resolve(nested.getFileName()))));
        for (final var permissions : List.of("rwxr-x---", "rwx---rwx", "rw-------")) {
            Files.setPosixFilePermissions(root, PosixFilePermissions.fromString(permissions));
            assertThrows(IOException.class, () -> helper.validate(configuration(root)));
        }
    }

    @Test
    void rejectsUnsupportedPlatformAndBackendsWithoutReadingMounts() throws Exception {
        final var root = root("attempts");
        for (final var backend :
                List.of(ExecutorConfiguration.Backend.NONO, ExecutorConfiguration.Backend.KUBERNETES)) {
            final var config =
                    new ExecutorConfiguration(backend, root.toString(), 60, 5, "hidden", null, null, null, null);
            assertThrows(
                    IllegalArgumentException.class, () -> storage(List.of()).validate(config));
        }
        final var helper = new SlackContextStorage() {
            @Override
            protected String operatingSystem() {
                return "Mac OS X";
            }
        };
        assertThrows(IllegalArgumentException.class, () -> helper.validate(configuration(root)));
    }

    @Test
    void sanitizesWrappedAndPlainTrustedConfigurationWithoutChangingProviderOrPaths() throws Exception {
        try (final var json = new JsonMapperImpl(
                List.of(new AgentConfigurationDocument$FusionJsonCodec()), ignored -> Optional.empty())) {
            for (final var wrapped : List.of(true, false)) {
                final var source = "\"provider\":\"openai\",\"home\":\"/attempt/storage\","
                        + "\"workingDir\":\"/attempt/workspace\",\"model\":\"trusted\","
                        + "\"memory\":{\"enabled\":true,\"secret\":\"canary\"},"
                        + "\"skills\":{\"enabled\":true},\"subagents\":{\"enabled\":true},"
                        + "\"mcp\":{\"servers\":[{\"url\":\"canary\"}]},\"notifications\":{\"enabled\":true},"
                        + "\"hooks\":{\"onStart\":\"canary\"},\"tools\":{\"builtin\":{\"enabled\":true},"
                        + "\"websearch\":{\"providers\":[\"canary\"]}}";
                final var file = Files.writeString(
                        home.resolve("config-" + wrapped + ".json"),
                        wrapped
                                ? "{\"yca\":{" + source + "},\"run\":{\"result-correction-prompt\":\"completion\"}}"
                                : "{" + source + ",\"run\":{\"result-correction-prompt\":\"completion\"}}");
                new SlackContextStorage().sanitize(file, json);
                final var output = Files.readString(file);
                assertFalse(output.contains("canary"));
                final var document = json.fromString(AgentConfigurationDocument.class, output);
                final var settings = document.configuration();
                for (final var key : List.of("memory", "skills", "subagents", "notifications")) {
                    assertEquals(Map.of("enabled", false), settings.get(key));
                }
                assertEquals(Map.of("enabled", false, "servers", List.of()), settings.get("mcp"));
                assertEquals(Map.of(), settings.get("hooks"));
                assertEquals(
                        Map.of("builtin", Map.of("enabled", true), "websearch", Map.of("providers", List.of())),
                        settings.get("tools"));
                assertEquals("openai", settings.get("provider"));
                assertEquals("trusted", settings.get("model"));
                assertEquals("/attempt/storage", settings.get("home"));
                assertEquals("/attempt/workspace", settings.get("workingDir"));
                assertEquals(
                        Map.of("run", Map.of("result-correction-prompt", "completion")), document.completionSettings());
                new SlackContextStorage().sanitize(file, json);
                assertEquals(output, Files.readString(file));
            }
        }
    }

    @Test
    void refusesPersistentLoggingAndAncillaryEgressBeforeInspectingStorage() {
        final var safe = configuration(home);
        final var docker = safe.docker();
        final var services = docker.services();
        final var unsafeServices = new ExecutorConfiguration.Docker.Services(
                services.image(),
                services.controlNetwork(),
                services.keyStore(),
                services.keyStorePasswordEnv(),
                services.serverName(),
                services.providerTarget(),
                null);
        for (final var unsafe : List.of(
                new ExecutorConfiguration(safe.backend(), safe.attemptRoot(), 60, 5, "shown", docker, null, null, null),
                new ExecutorConfiguration(safe.backend(), safe.attemptRoot(), 60, 5, "hidden", null, null, null, null),
                new ExecutorConfiguration(
                        safe.backend(),
                        safe.attemptRoot(),
                        60,
                        5,
                        "hidden",
                        new ExecutorConfiguration.Docker("docker", "agent:test", "", null, services, false),
                        null,
                        null,
                        null),
                new ExecutorConfiguration(
                        safe.backend(),
                        safe.attemptRoot(),
                        60,
                        5,
                        "hidden",
                        new ExecutorConfiguration.Docker("docker", "agent:test", "", null, null, true),
                        null,
                        null,
                        null),
                new ExecutorConfiguration(
                        safe.backend(),
                        safe.attemptRoot(),
                        60,
                        5,
                        "hidden",
                        new ExecutorConfiguration.Docker("docker", "agent:test", "", null, unsafeServices, true),
                        null,
                        null,
                        null))) {
            assertThrows(
                    IllegalArgumentException.class, () -> storage(List.of()).validate(unsafe));
        }
    }

    @Test
    void disablesAbsentDefaultWebSearchAndRejectsConfigurationSymlinks() throws Exception {
        try (final var json = new JsonMapperImpl(
                List.of(new AgentConfigurationDocument$FusionJsonCodec()), ignored -> Optional.empty())) {
            final var file = Files.writeString(home.resolve("config.json"), "{}");
            final var link = Files.createSymbolicLink(home.resolve("config-link.json"), file);
            final var helper = new SlackContextStorage();
            assertThrows(IOException.class, () -> helper.sanitize(link, json));
            assertEquals("{}", Files.readString(file));
            helper.sanitize(file, json);
            final var sanitized = json.fromString(AgentConfigurationDocument.class, Files.readString(file))
                    .configuration();
            assertEquals(Map.of("feedback", Map.of("enabled", false)), sanitized.get("bot"));
            assertEquals(Map.of("debug", false), sanitized.get("httpClient"));
            assertEquals(
                    Map.of("websearch", Map.of("providers", List.of())),
                    json.fromString(AgentConfigurationDocument.class, Files.readString(file))
                            .configuration()
                            .get("tools"));
        }
    }

    private Path root(final String name) throws IOException {
        return Files.createDirectory(
                        home.resolve(name),
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
                .toRealPath();
    }

    private ExecutorConfiguration configuration(final Path root) {
        final var services = new ExecutorConfiguration.Docker.Services(
                "services:test",
                "control",
                "/private/tls.p12",
                "PASSWORD",
                "attempt-services.yca.internal",
                "http://192.168.1.2:8080/v1",
                new ExecutorConfiguration.Docker.Egress("egress:test", List.of(), "127.0.0.1", false));
        return new ExecutorConfiguration(
                ExecutorConfiguration.Backend.DOCKER,
                root.toString(),
                60,
                5,
                "hidden",
                new ExecutorConfiguration.Docker("docker", "agent:test", "", null, services, true),
                null,
                null,
                null);
    }

    private SlackContextStorage storage(final List<String> lines) {
        return new SlackContextStorage() {
            @Override
            protected List<String> mountInfo() {
                return lines;
            }
        };
    }

    private String disk() {
        return mount(Path.of("/"), "ext4", "rw", "rw");
    }

    private String mount(final Path point, final String type, final String options, final String superOptions) {
        return "20 1 0:30 / " + escape(point) + " " + options + " - " + type + " tmpfs " + superOptions;
    }

    private String escape(final Path path) {
        return path.toString()
                .replace("\\", "\\134")
                .replace(" ", "\\040")
                .replace("\t", "\\011")
                .replace("\n", "\\012");
    }
}
