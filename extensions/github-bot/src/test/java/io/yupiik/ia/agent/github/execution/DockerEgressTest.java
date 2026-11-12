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
package io.yupiik.ia.agent.github.execution;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.yupiik.ia.agent.github.configuration.ExecutorConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DockerEgressTest {
    @TempDir
    Path home;

    @Test
    void ipv6DnsSinkIsPassedAsAnArgumentAndExternalDnsIsRejected() {
        final var configuration = new ExecutorConfiguration.Docker.Egress("gateway", List.of("github.com"), "::1");
        final var gateway = new DockerEgress("docker", configuration, Duration.ofSeconds(30));
        final var args = gateway.agentArguments("yca-ipv6");
        assertEquals("::1", args.get(args.indexOf("--dns") + 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DockerEgress(
                        "docker",
                        new ExecutorConfiguration.Docker.Egress("gateway", List.of("github.com"), "8.8.8.8"),
                        Duration.ofSeconds(30)));
    }

    @Test
    void constructsIsolatedDualHomedCredentialFreeGatewayAndCleansInOrder() throws Exception {
        final var executable = script("""
                printf '%s\\n' "$*" >> "$0.calls"
                if [ "$1" = version ]; then printf '28.1.0\\n'; fi
                if [ "$1" = inspect ]; then printf 'healthy\\n'; fi
                if [ "$1" = container ]; then printf 'Error: No such container: %s\\n' "$5"; exit 1; fi
                if [ "$1" = create ]; then env > "$0.gateway-env"; fi
                if [ "$1" = run ]; then env > "$0.agent-env"; fi
                """);
        final var attempt = attempt();
        final var backend = backend(executable);
        assertEquals(0, backend.execute(attempt, "yca-egress-test", "review", Map.of("GH_TOKEN", "agent-only")));
        final var calls = Files.readAllLines(Path.of(executable + ".calls"));
        assertTrue(calls.get(1).contains("--internal --opt com.docker.network.bridge.gateway_mode_ipv4=isolated"));
        assertTrue(calls.get(2).endsWith("yca-egress-test-external"));
        final var create =
                calls.stream().filter(c -> c.startsWith("create ")).findFirst().orElseThrow();
        assertTrue(create.contains("--network yca-egress-test-external"));
        assertTrue(create.contains("net.ipv4.ip_forward=0"));
        assertFalse(create.contains("--mount"));
        assertFalse(create.contains("GH_TOKEN"));
        assertTrue(calls.contains("network connect --alias egress yca-egress-test-isolated yca-egress-test-egress"));
        final var run =
                calls.stream().filter(c -> c.startsWith("run ")).findFirst().orElseThrow();
        assertTrue(run.contains("--network yca-egress-test-isolated --dns 127.0.0.1"));
        assertTrue(run.contains("HTTPS_PROXY=http://egress:8080"));
        assertTrue(run.contains("-Dhttps.proxyHost=egress"));
        assertFalse(Files.readString(Path.of(executable + ".gateway-env")).contains("GH_TOKEN="));
        assertTrue(Files.readString(Path.of(executable + ".agent-env")).contains("GH_TOKEN=agent-only"));
        assertEquals(
                List.of(
                        "rm --force yca-egress-test-egress",
                        "network rm yca-egress-test-isolated",
                        "network rm yca-egress-test-external"),
                calls.subList(calls.size() - 3, calls.size()));
    }

    @Test
    void failsClosedAndCleansPartialResourcesForEverySetupStage() throws Exception {
        for (final var stage : List.of("version", "internal", "external", "create", "connect", "start", "inspect")) {
            final var executable = script("""
                    printf '%s\\n' "$*" >> "$0.calls"
                    if [ "$1" = version ]; then printf '28.0.0\\n'; fi
                    if [ "$1" = inspect ]; then printf 'healthy\\n'; fi
                    if [ "$1" = container ]; then printf 'Error: No such container: %s\\n' "$5"; exit 1; fi
                    """
                    + switch (stage) {
                        case "internal" ->
                            "if [ \"$1 $2\" = \"network create\" ] && [ \"$5\" = --internal ]; then exit 1; fi\n";
                        case "external" ->
                            "if [ \"$1 $2\" = \"network create\" ] && [ \"$5\" != --internal ]; then exit 1; fi\n";
                        case "connect" -> "if [ \"$1 $2\" = \"network connect\" ]; then exit 1; fi\n";
                        default -> "if [ \"$1\" = " + stage + " ]; then exit 1; fi\n";
                    });
            final var attempt = attempt();
            assertThrows(
                    IllegalStateException.class,
                    () -> backend(executable).execute(attempt, "yca-setup-test", "review", Map.of()));
            final var calls = Files.readAllLines(Path.of(executable + ".calls"));
            assertFalse(calls.stream().anyMatch(c -> c.startsWith("run ")), stage);
            assertTrue(calls.contains("rm --force yca-setup-test-egress"));
            assertTrue(calls.contains("network rm yca-setup-test-isolated"));
        }
    }

    @Test
    void rejectsOldDaemonAndInvalidConfiguration() throws Exception {
        final var executable = script("printf '27.5.0\\n'\n");
        final var egress = new DockerEgress(executable.toString(), configuration(), Duration.ofSeconds(5));
        assertThrows(IllegalStateException.class, () -> egress.start("yca-old-engine"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DockerEgress(
                        "docker",
                        new ExecutorConfiguration.Docker.Egress("", List.of("github.com"), "127.0.0.1"),
                        Duration.ofSeconds(5)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DockerExecutionBackend(
                        "docker", "image", Duration.ofSeconds(1), "custom-network", configuration()));
        assertThrows(IllegalArgumentException.class, () -> egress.start("x".repeat(1_000_000)));
        assertThrows(IllegalArgumentException.class, () -> egress.remove("bad;name"));
    }

    @Test
    void timeoutStopsAgentAndRemovesGatewayAndNetworks() throws Exception {
        final var executable = script("""
                printf '%s\\n' "$*" >> "$0.calls"
                if [ "$1" = version ]; then printf '28.0.0\\n'; fi
                if [ "$1" = inspect ]; then printf 'healthy\\n'; fi
                if [ "$1" = container ]; then printf 'true\\n'; fi
                if [ "$1" = run ]; then exec sleep 20; fi
                """);
        final var attempt = attempt();
        final var backend =
                new DockerExecutionBackend(executable.toString(), "agent", Duration.ofMillis(100), "", configuration());
        assertTimeoutPreemptively(
                Duration.ofSeconds(5),
                () -> assertThrows(
                        IllegalStateException.class,
                        () -> backend.execute(attempt, "yca-egress-timeout", "review", Map.of())));
        final var calls = Files.readAllLines(Path.of(executable + ".calls"));
        assertTrue(calls.contains("kill yca-egress-timeout"));
        assertTrue(calls.indexOf("kill yca-egress-timeout") < calls.indexOf("rm --force yca-egress-timeout-egress"));
        assertTrue(calls.contains("network rm yca-egress-timeout-isolated"));
        assertTrue(calls.contains("network rm yca-egress-timeout-external"));
    }

    @Test
    void cleanupDoesNotAcceptDaemonFailureAsMissingAndAttemptsAllResources() throws Exception {
        final var executable =
                script("printf '%s\\n' \"$*\" >> \"$0.calls\"\nprintf 'daemon unavailable\\n'\nexit 1\n");
        assertThrows(
                IllegalStateException.class,
                () -> new DockerEgress(executable.toString(), configuration(), Duration.ofSeconds(5))
                        .remove("yca-cleanup"));
        assertEquals(3, Files.readAllLines(Path.of(executable + ".calls")).size());
    }

    private ExecutorConfiguration.Docker.Egress configuration() {
        return new ExecutorConfiguration.Docker.Egress(
                "trusted-gateway@sha256:example", List.of("api.github.com"), "127.0.0.1");
    }

    private DockerExecutionBackend backend(final Path executable) {
        return new DockerExecutionBackend(executable.toString(), "agent", Duration.ofSeconds(5), "", configuration());
    }

    private Path attempt() throws Exception {
        final var directory = Files.createTempDirectory(home, "attempt-");
        assumeTrue(((Number) Files.getAttribute(directory, "unix:uid")).intValue() != 0);
        Files.writeString(directory.resolve("configuration.json"), "{}");
        for (final var name : List.of("storage", "workspace", "output")) {
            Files.createDirectory(directory.resolve(name));
        }
        return directory;
    }

    private Path script(final String body) throws Exception {
        final var executable = Files.createTempFile(home, "fake-docker-", ".sh");
        Files.writeString(executable, "#!/bin/sh\n" + body);
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"));
        return executable;
    }
}
