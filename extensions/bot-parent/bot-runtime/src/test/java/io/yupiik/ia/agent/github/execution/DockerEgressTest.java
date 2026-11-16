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

import io.yupiik.ia.agent.bot.runtime.TestJava;
import io.yupiik.ia.agent.github.configuration.ExecutorConfiguration;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DockerEgressTest {
    @TempDir
    Path home;

    @Test
    void dependencyOwnershipLabelsSeparateSlackFromLegacyGithubInventory() throws Exception {
        for (final var runtime : List.of("yca-slack-labels", "yca-labels", "yca-slackish-labels")) {
            final var executable = script("printf '%s\\n' \"$*\" >> \"$0.calls\"\n");
            new DockerEgress(executable.toString(), configuration(), Duration.ofMillis(500))
                    .start(runtime, runtime + "-services-net");
            final var app = runtime.startsWith("yca-slack-") ? "io.yupiik.yca.slack-bot" : "io.yupiik.yca.github-bot";
            final var calls = Files.readAllLines(Path.of(executable + ".calls"));
            final var labelled = calls.stream()
                    .filter(call -> call.startsWith("network create ") || call.startsWith("create "))
                    .toList();
            assertEquals(2, labelled.size());
            for (final var call : labelled) {
                assertTrue(call.contains("--label app=" + app + " --label"));
                assertTrue(call.contains("--label io.yupiik.yca.github-bot.runtime=" + runtime + " --label"));
                assertTrue(call.contains("--label io.yupiik.yca.github-bot.component=dependency-egress"));
                if (runtime.startsWith("yca-slack-")) {
                    assertFalse(call.contains("app=io.yupiik.yca.github-bot"));
                }
            }
            assertTrue(calls.contains(
                    "network connect --alias egress " + runtime + "-services-net " + runtime + "-egress"));
        }
    }

    @Test
    void jvmProxySelectionBypassesOnlyLoopbackAndExactServicesHost() throws Exception {
        final var gateway = new DockerEgress(
                "docker",
                new ExecutorConfiguration.Docker.Egress("gateway", List.of("repo.maven.apache.org"), "127.0.0.1"),
                Duration.ofSeconds(30));
        final var arguments = gateway.proxyArguments("attempt-services.yca.internal");
        final var options = arguments.stream()
                .filter(value -> value.startsWith("JAVA_TOOL_OPTIONS="))
                .findFirst()
                .orElseThrow()
                .substring("JAVA_TOOL_OPTIONS=".length());
        final var command = TestJava.javaCommand();
        command.addAll(List.of(options.split(" ")));
        command.addAll(List.of(
                "-cp",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                ProxySelectionProbe.class.getName()));
        final var builder = new ProcessBuilder(command).redirectErrorStream(true);
        for (final var key : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")) {
            builder.environment().remove(key);
        }
        final var process = builder.start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS));
            assertEquals(
                    0,
                    process.exitValue(),
                    new String(process.getInputStream().readNBytes(8192), StandardCharsets.UTF_8));
        } finally {
            process.destroyForcibly();
        }
    }

    public static final class ProxySelectionProbe {
        public static void main(final String[] args) {
            for (final var host : List.of(
                    "localhost",
                    "127.0.0.1",
                    "127.42.0.2",
                    "[::1]",
                    "[0:0:0:0:0:0:0:1]",
                    "attempt-services.yca.internal")) {
                for (final var scheme : List.of("http", "https")) {
                    final var selection = ProxySelector.getDefault().select(URI.create(scheme + "://" + host + "/"));
                    if (!selection.equals(List.of(Proxy.NO_PROXY))) {
                        throw new AssertionError("Loopback or exact services host must bypass proxy: " + host);
                    }
                }
            }
            for (final var host :
                    List.of("repo.maven.apache.org", "attempt-services.yca.internal.example.com", "192.168.1.1")) {
                if (ProxySelector.getDefault()
                                .select(URI.create("https://" + host + "/"))
                                .getFirst()
                                .type()
                        != Proxy.Type.HTTP) {
                    throw new AssertionError("Other destinations must use the policy proxy");
                }
            }
        }
    }

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
                env > "$0.$1.env"
                if [ "$1" = version ]; then printf '28.1.0\\n'; fi
                if [ "$1" = exec ]; then printf '%s\\n' "$@" > "$0.readiness-args"; fi
                if [ "$1" = container ]; then printf 'Error: No such container: %s\\n' "$5"; exit 1; fi
                if [ "$1" = create ]; then env > "$0.gateway-env"; fi
                if [ "$1" = run ]; then env > "$0.agent-env"; fi
                """);
        final var attempt = attempt();
        final var backend = backend(executable);
        assertEquals(
                0,
                backend.execute(
                        attempt,
                        "yca-egress-test",
                        "review",
                        Map.of(
                                "GH_TOKEN",
                                "agent-only",
                                "YCA_CONTEXT_TOKEN",
                                "context-only",
                                "YCA_CONTEXT_GIT_TOKEN",
                                "context-git-only"),
                        Map.of()));
        final var calls = Files.readAllLines(Path.of(executable + ".calls"));
        assertTrue(calls.get(1).contains("--internal --opt com.docker.network.bridge.gateway_mode_ipv4=isolated"));
        assertTrue(calls.get(2).endsWith("yca-egress-test-external"));
        final var create =
                calls.stream().filter(c -> c.startsWith("create ")).findFirst().orElseThrow();
        assertTrue(create.contains("--network yca-egress-test-external"));
        assertTrue(create.contains("net.ipv4.ip_forward=0"));
        assertFalse(create.contains("--mount"));
        assertFalse(create.contains("GH_TOKEN"));
        assertFalse(create.contains("--health-"));
        assertFalse(create.contains("/opt/egress/bot.jar"));
        assertEquals(
                List.of(
                        "exec",
                        "--user",
                        "65532:65532",
                        "yca-egress-test-egress",
                        "java",
                        "-Xmx32m",
                        "-cp",
                        "@/app/jib-classpath-file",
                        "io.yupiik.ia.agent.github.execution.EgressGateway",
                        "--check"),
                Files.readAllLines(Path.of(executable + ".readiness-args")));
        assertTrue(calls.indexOf("start yca-egress-test-egress")
                < calls.indexOf(String.join(" ", Files.readAllLines(Path.of(executable + ".readiness-args")))));
        assertTrue(calls.contains("network connect --alias egress yca-egress-test-isolated yca-egress-test-egress"));
        final var run =
                calls.stream().filter(c -> c.startsWith("run ")).findFirst().orElseThrow();
        assertTrue(run.contains("--network yca-egress-test-isolated --dns 127.0.0.1"));
        assertTrue(run.contains("HTTPS_PROXY=http://egress:8080"));
        assertTrue(run.contains("-Dhttps.proxyHost=egress"));
        assertFalse(Files.readString(Path.of(executable + ".gateway-env")).contains("GH_TOKEN="));
        assertTrue(Files.readString(Path.of(executable + ".agent-env")).contains("GH_TOKEN=agent-only"));
        for (final var key : List.of("YCA_CONTEXT_TOKEN", "YCA_CONTEXT_GIT_TOKEN")) {
            assertFalse(Files.readString(Path.of(executable + ".gateway-env")).contains(key + "="));
        }
        assertTrue(Files.readString(Path.of(executable + ".agent-env")).contains("YCA_CONTEXT_TOKEN=context-only"));
        assertTrue(Files.readString(Path.of(executable + ".agent-env"))
                .contains("YCA_CONTEXT_GIT_TOKEN=context-git-only"));
        for (final var value : List.of("agent-only", "context-only", "context-git-only")) {
            assertFalse(calls.toString().contains(value));
            assertFalse(Files.readString(attempt.resolve("execution.log")).contains(value));
        }
        assertEquals(
                List.of(
                        "rm --force yca-egress-test-egress",
                        "network rm yca-egress-test-isolated",
                        "network rm yca-egress-test-external"),
                calls.subList(calls.size() - 3, calls.size()));
        final var expected =
                DockerControlEnvironment.sanitize(new ProcessBuilder("unused")).environment();
        for (final var phase : List.of("version", "network", "create", "start", "exec", "container", "rm")) {
            final var actual = new HashMap<String, String>();
            for (final var entry : Files.readAllLines(Path.of(executable + "." + phase + ".env"))) {
                final int equals = entry.indexOf('=');
                if (!entry.startsWith("PWD=")) {
                    actual.put(entry.substring(0, equals), entry.substring(equals + 1));
                }
            }
            assertEquals(expected, actual, phase);
        }
    }

    @Test
    void readinessRetriesNonzeroExitBeforeAcceptingSuccess() throws Exception {
        final var executable = script("""
                printf '%s\\n' "$1" >> "$0.calls"
                if [ "$1" = version ]; then printf '28.0.0\\n'; fi
                if [ "$1" = exec ] && [ ! -f "$0.checked" ]; then
                  : > "$0.checked"
                  printf 'not ready yet\\n' >&2
                  exit 1
                fi
                """);
        new DockerEgress(executable.toString(), configuration(), Duration.ofSeconds(5)).start("yca-retry");
        final var calls = Files.readAllLines(Path.of(executable + ".calls"));
        assertEquals(2, calls.stream().filter("exec"::equals).count());
        assertEquals("exec", calls.getLast());
        assertTrue(calls.indexOf("start") < calls.indexOf("exec"));
        assertFalse(calls.contains("inspect"));
    }

    @Test
    void readinessDeadlineBoundsHungProbesAndCleansWithoutLaunchingAgent() throws Exception {
        // Use the actual shorter executor budget, not a simulated clock or an interrupted wait.
        final var executable = script("""
                printf '%s\\n' "$*" >> "$0.calls"
                if [ "$1" = version ]; then printf '28.0.0\\n'; fi
                if [ "$1" = exec ] && [ ! -f "$0.checked" ]; then
                  : > "$0.checked"
                  exit 1
                fi
                if [ "$1" = exec ]; then exec /bin/sleep 60; fi
                if [ "$1" = container ]; then printf 'false\\n'; fi
                """);
        final var attempt = attempt();
        final var backend = new DockerExecutionBackend(
                executable.toString(), "agent", Duration.ofSeconds(2), "", configuration(), "shown");
        final long start = System.nanoTime();
        final var failure = assertTimeoutPreemptively(
                Duration.ofSeconds(8),
                () -> assertThrows(
                        IllegalStateException.class,
                        () -> backend.execute(attempt, "yca-readiness-deadline", "review", Map.of(), Map.of())));
        assertEquals("Egress gateway readiness timeout", failure.getMessage());
        assertTrue(System.nanoTime() - start >= Duration.ofSeconds(2).toNanos());
        final var calls = Files.readAllLines(Path.of(executable + ".calls"));
        assertTrue(calls.stream().filter(call -> call.startsWith("exec ")).count() >= 2);
        assertFalse(calls.stream().anyMatch(call -> call.startsWith("run ")));
        assertTrue(calls.indexOf("container inspect --format {{.State.Running}} yca-readiness-deadline")
                < calls.indexOf("rm --force yca-readiness-deadline-egress"));
        assertTrue(calls.contains("network rm yca-readiness-deadline-isolated"));
        assertTrue(calls.contains("network rm yca-readiness-deadline-external"));
    }

    @Test
    void failsClosedAndCleansPartialResourcesForEverySetupStage() throws Exception {
        for (final var stage : List.of("version", "internal", "external", "create", "connect", "start")) {
            final var executable = script("""
                    printf '%s\\n' "$*" >> "$0.calls"
                    if [ "$1" = version ]; then printf '28.0.0\\n'; fi
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
                    () -> backend(executable).execute(attempt, "yca-setup-test", "review", Map.of(), Map.of()));
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
                        "docker", "image", Duration.ofSeconds(1), "custom-network", configuration(), "shown"));
        assertThrows(IllegalArgumentException.class, () -> egress.start("x".repeat(1_000_000)));
        assertThrows(IllegalArgumentException.class, () -> egress.remove("bad;name"));
    }

    @Test
    void timeoutStopsAgentAndRemovesGatewayAndNetworks() throws Exception {
        final var executable = script("""
                printf '%s\\n' "$*" >> "$0.calls"
                if [ "$1" = version ]; then printf '28.0.0\\n'; fi
                if [ "$1" = container ]; then printf 'true\\n'; fi
                if [ "$1" = run ]; then exec sleep 20; fi
                """);
        final var attempt = attempt();
        final var backend = new DockerExecutionBackend(
                executable.toString(), "agent", Duration.ofMillis(100), "", configuration(), "shown");
        assertTimeoutPreemptively(
                Duration.ofSeconds(5),
                () -> assertThrows(
                        IllegalStateException.class,
                        () -> backend.execute(attempt, "yca-egress-timeout", "review", Map.of(), Map.of())));
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
        return new DockerExecutionBackend(
                executable.toString(), "agent", Duration.ofSeconds(5), "", configuration(), "shown");
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
        // These fixtures create only egress resources, never private services resources.
        Files.writeString(executable, "#!/bin/sh\n" + """
                if [ "$#" = 5 ] && [ "$2 $3" = 'inspect --format' ]; then
                  case "$4" in
                    *io.yupiik.yca.github-bot.runtime*io.yupiik.yca.github-bot.component*)
                      case "$1:$5" in
                        container:yca-*-services) printf 'Error: No such container: %s\\n' "$5"; exit 1 ;;
                        network:yca-*-services-net) printf 'Error: No such network: %s\\n' "$5"; exit 1 ;;
                      esac
                      ;;
                  esac
                fi
                """ + body);
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"));
        return executable;
    }
}
