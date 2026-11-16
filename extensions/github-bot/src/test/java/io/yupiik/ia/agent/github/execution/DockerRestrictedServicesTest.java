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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DockerRestrictedServicesTest {
    @TempDir
    Path home;

    @Test
    void dependencyProxySharesOnlyTheServicesNetworkAndBypassesOnlyTheServicesName() throws Exception {
        nonRoot();
        final var executable = fake("");
        final var config = defaultConfiguration();
        assertEquals(
                0,
                new DockerExecutionBackend(executable.toString(), "agent", Duration.ofSeconds(3), "", null, config, "shown")
                        .execute(attempt(), "yca-dependencies", "review", Map.of(), Map.of(), settings(), secrets()));
        final var calls = Files.readAllLines(Path.of(executable + ".calls"));
        assertEquals(
                2, calls.stream().filter(c -> c.startsWith("network create ")).count());
        assertTrue(
                calls.contains("network connect --alias egress yca-dependencies-services-net yca-dependencies-egress"));
        final var proxy = Files.readAllLines(Path.of(executable + ".proxy.args"));
        assertEquals("yca-dependencies-external", proxy.get(proxy.indexOf("--network") + 1));
        assertTrue(proxy.containsAll(List.of(
                "--read-only",
                "--cap-drop=ALL",
                "net.ipv4.ip_forward=0",
                "net.ipv6.conf.all.forwarding=0",
                "*.docker.io",
                "github.com")));
        assertFalse(proxy.contains("--mount"));
        assertFalse(Files.readString(Path.of(executable + ".proxy.env")).contains("YCA_SERVICES_"));
        final var agent = Files.readAllLines(Path.of(executable + ".run.args"));
        assertEquals(1, agent.stream().filter("--network"::equals).count());
        assertTrue(agent.contains("HTTPS_PROXY=http://egress:8080"));
        assertTrue(agent.contains("NO_PROXY=localhost,127.0.0.1,127.0.0.0/8,::1,[::1],attempt-services.yca.internal"));
        assertTrue(agent.contains("no_proxy=localhost,127.0.0.1,127.0.0.0/8,::1,[::1],attempt-services.yca.internal"));
        assertTrue(
                agent.stream()
                        .anyMatch(
                                a -> a.startsWith("JAVA_TOOL_OPTIONS=-Dhttps.proxyHost=egress")
                                        && a.endsWith(
                                                "-Dhttp.nonProxyHosts=localhost|127.*|[::1]|::1|[0:0:0:0:0:0:0:1]|0:0:0:0:0:0:0:1|attempt-services.yca.internal")));
        assertTrue(agent.contains("MAVEN_USER_HOME=/attempt/storage/.m2"));
        assertTrue(agent.contains("MAVEN_OPTS=-Duser.home=/attempt/storage"));
        assertEquals(
                List.of(
                        "rm --force yca-dependencies-egress",
                        "network rm yca-dependencies-external",
                        "rm --force yca-dependencies-services",
                        "network rm yca-dependencies-services-net"),
                calls.subList(calls.size() - 4, calls.size()));
    }

    @Test
    void emptyHostsAndExplicitDisableKeepServicesConfinementWithoutAProxy() {
        for (final var policy : List.of(
                new ExecutorConfiguration.Docker.Egress(null, List.of(), null, true),
                new ExecutorConfiguration.Docker.Egress(null, List.of("github.com"), null, false))) {
            final var config = new ExecutorConfiguration.Docker.Services(
                    "gateway",
                    "trusted-control",
                    home.resolve("tls.p12").toString(),
                    null,
                    null,
                    "http://172.20.0.1:8080/v1",
                    policy);
            final var args = new DockerRestrictedServices("docker", config, Duration.ofSeconds(1))
                    .agentArguments("yca-disabled");
            assertEquals("127.0.0.1", args.get(args.indexOf("--dns") + 1));
            assertEquals(1, args.stream().filter("--network"::equals).count());
            assertTrue(args.contains("HTTPS_PROXY="));
            assertFalse(args.contains("HTTPS_PROXY=http://egress:8080"));
        }
    }

    @Test
    void customServicesNameIsTheOnlyProxyBypassAndReservedAliasIsRejected() {
        final var config = new ExecutorConfiguration.Docker.Services(
                "gateway",
                "trusted-control",
                home.resolve("tls.p12").toString(),
                null,
                "custom-services.yca.internal",
                "http://172.20.0.1:8080/v1");
        final var args =
                new DockerRestrictedServices("docker", config, Duration.ofSeconds(1)).agentArguments("yca-custom");
        assertTrue(args.contains("NO_PROXY=localhost,127.0.0.1,127.0.0.0/8,::1,[::1],custom-services.yca.internal"));
        assertFalse(args.contains("NO_PROXY="));
        assertEquals(
                1, args.stream().filter(a -> a.startsWith("JAVA_TOOL_OPTIONS=")).count());
        assertThrows(
                IllegalArgumentException.class,
                () -> new DockerRestrictedServices(
                        "docker",
                        new ExecutorConfiguration.Docker.Services(
                                "gateway",
                                "trusted-control",
                                home.resolve("tls.p12").toString(),
                                null,
                                "egress",
                                "http://172.20.0.1:8080/v1"),
                        Duration.ofSeconds(1)));
    }

    @Test
    void foreignDependencyResourcesPreventAnyCleanupMutation() throws Exception {
        for (final var name : List.of("yca-owner-egress", "yca-owner-external")) {
            final var executable = fake("for last; do :; done\n"
                    + "if [ \"$2\" = inspect ] && [ \"$last\" = " + name
                    + " ]; then printf 'yca-other|dependency-egress\\n'; exit 0; fi\n");
            Files.createFile(Path.of(executable + ".container"));
            Files.createFile(Path.of(executable + ".network"));
            assertThrows(
                    IllegalStateException.class,
                    () -> DockerRestrictedServices.stopOwned(
                            executable.toString(), Duration.ofSeconds(1), "yca-owner"));
            assertFalse(Files.readAllLines(Path.of(executable + ".calls")).stream()
                    .anyMatch(c -> c.startsWith("rm ") || c.startsWith("network rm ")));
        }
    }

    @Test
    void dependencyPartialStartupAndRestartCleanupAreOwnedAndOrdered() throws Exception {
        nonRoot();
        final var settings = settings();
        for (final var failure : List.of(
                "if [ \"$1\" = create ] && [ \"$3\" = yca-partial-egress ]; then exit 1; fi\n",
                "if [ \"$1 $2 $4\" = 'network connect egress' ]; then exit 1; fi\n",
                "if [ \"$1 $2\" = 'start yca-partial-egress' ]; then exit 1; fi\n")) {
            final var executable = fake(failure);
            assertThrows(
                    IllegalStateException.class,
                    () -> new DockerExecutionBackend(
                                    executable.toString(),
                                    "agent",
                                    Duration.ofSeconds(2),
                                    "",
                                    null,
                                    defaultConfiguration(), "shown")
                            .execute(attempt(), "yca-partial", "review", Map.of(), Map.of(), settings, secrets()));
            final var calls = Files.readAllLines(Path.of(executable + ".calls"));
            assertFalse(calls.stream().anyMatch(c -> c.startsWith("run ")));
            assertTrue(calls.indexOf("network rm yca-partial-external")
                    < calls.indexOf("network rm yca-partial-services-net"));
        }
        final var executable = fake("");
        for (final var suffix : List.of(".container", ".network", ".yca-restart-egress", ".yca-restart-external")) {
            Files.createFile(Path.of(executable + suffix));
        }
        new DockerExecutionBackend(executable.toString(), "agent", Duration.ofSeconds(2), "", null, "shown").stop("yca-restart");
        final var calls = Files.readAllLines(Path.of(executable + ".calls"));
        assertTrue(calls.indexOf("rm --force yca-restart-egress") < calls.indexOf("network rm yca-restart-external"));
        assertTrue(calls.indexOf("network rm yca-restart-external")
                < calls.indexOf("network rm yca-restart-services-net"));
    }

    private ExecutorConfiguration.Docker.Services defaultConfiguration() {
        return new ExecutorConfiguration.Docker.Services(
                "trusted-gateway",
                "trusted-control",
                home.resolve("tls.p12").toString(),
                null,
                null,
                "http://172.20.0.1:8080/v1");
    }

    @Test
    void normalizesOnlyUnconfiguredRecordsAndRejectsPartialOrConflictingConfiguration() {
        assertNull(new ExecutorConfiguration.Docker(
                        "docker",
                        "agent",
                        "",
                        null,
                        new ExecutorConfiguration.Docker.Services(null, null, null, null, null, null))
                .services());
        final var config = configuration("http://172.20.0.1:8080/v1");
        assertEquals("YCA_SERVICES_KEYSTORE_PASSWORD", config.keyStorePasswordEnv());
        assertEquals("attempt-services.yca.internal", config.serverName());
        assertDoesNotThrow(() -> new ExecutorConfiguration.Docker("docker", "agent", "", null, config));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExecutorConfiguration.Docker("docker", "agent", "trusted", null, config));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DockerExecutionBackend("docker", "agent", Duration.ofSeconds(1), "trusted", null, config, "shown"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DockerExecutionBackend(
                        "docker",
                        "agent",
                        Duration.ofSeconds(1),
                        "",
                        new ExecutorConfiguration.Docker.Egress("egress", List.of("github.com"), "127.0.0.1"),
                        config, "shown"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExecutorConfiguration.Docker(
                        "docker",
                        "agent",
                        "",
                        null,
                        new ExecutorConfiguration.Docker.Services("gateway", null, null, null, null, null)));
        for (final var variable : List.of(
                "HOME",
                "DOCKER_HOST",
                "JAVA_TOOL_OPTIONS",
                "LD_PRELOAD",
                "BASH_ENV",
                "bad=name",
                "YCA_CONTEXT_TOKEN")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new DockerRestrictedServices(
                            "docker",
                            new ExecutorConfiguration.Docker.Services(
                                    "gateway",
                                    "trusted",
                                    home.resolve("tls.p12").toString(),
                                    variable,
                                    null,
                                    "http://172.20.0.1/v1"),
                            Duration.ofSeconds(1)));
        }
    }

    @Test
    void acceptsOnlyFixedNumericHttpProviderOriginsWithPathPrefix() {
        for (final var target : List.of(
                "http://172.20.0.1:8080/v1",
                "http://127.0.0.1:8080",
                "http://10.0.0.1:80/v1/chat/completions",
                "http://192.168.1.1:65535/api/v1")) {
            assertDoesNotThrow(() -> configuration(target).validate(), target);
        }
        for (final var target : List.of(
                "http://127.0.0.1:80/v1/",
                "http://[fd00::1]:8080/v1",
                "http://[::1]:80",
                "http://8.8.8.8:80/v1",
                "http://127.0.0.1/v1",
                "https://172.20.0.1/v1",
                "http://provider/v1",
                "http://localhost/v1",
                "http://127.1/v1",
                "http://127.000.0.1/v1",
                "http://256.0.0.1/v1",
                "http://user@172.20.0.1/v1",
                "http://172.20.0.1/v1?x=1",
                "http://172.20.0.1/v1#fragment",
                "http://172.20.0.1/v1/../other",
                "http://172.20.0.1/%2fother",
                "http://172.20.0.1:0/v1",
                "http://[fe80::1%25eth0]/v1")) {
            assertThrows(
                    IllegalArgumentException.class, () -> configuration(target).validate(), target);
        }
    }

    @Test
    void constructsExactIsolationAndKeepsGatewaySecretsOffArgvAndAgentEnvironment() throws Exception {
        nonRoot();
        final var executable = fake("");
        final var settings = settings();
        final var attempt = attempt();
        final var backend = backend(executable, Duration.ofSeconds(5));
        assertEquals(
                0,
                backend.execute(
                        attempt,
                        "yca-services-test",
                        "review",
                        Map.of("YCA_CONTEXT_TOKEN", "ephemeral-agent-token"),
                        Map.of(),
                        settings,
                        secrets()));
        final var calls = Files.readAllLines(Path.of(executable + ".calls"));
        final var network = calls.stream()
                .filter(c -> c.startsWith("network create "))
                .findFirst()
                .orElseThrow();
        assertEquals(
                "network create --driver bridge --internal --ipv6 --label app=io.yupiik.yca.github-bot --label io.yupiik.yca.github-bot.runtime=yca-services-test --label io.yupiik.yca.github-bot.component=services --opt com.docker.network.bridge.gateway_mode_ipv4=isolated --opt com.docker.network.bridge.gateway_mode_ipv6=isolated yca-services-test-services-net",
                network);
        final var create = Files.readAllLines(Path.of(executable + ".create.args"));
        assertEquals("trusted-control", create.get(create.indexOf("--network") + 1));
        final var uid = Files.getAttribute(settings, "unix:uid");
        final var gid = Files.getAttribute(settings, "unix:gid");
        assertEquals(uid + ":" + gid, create.get(create.indexOf("--user") + 1));
        assertTrue(create.containsAll(List.of(
                "--read-only",
                "--cap-drop=ALL",
                "--security-opt=no-new-privileges",
                "--pids-limit=128",
                "--memory=256m",
                "--cpus=1",
                "/tmp:rw,nosuid,nodev,size=32m")));
        assertTrue(create.contains("type=bind,src=" + home.resolve("tls.p12") + ",dst=/services/tls.p12,readonly"));
        assertTrue(create.contains("type=bind,src=" + settings + ",dst=/services/config.json,readonly"));
        assertEquals(
                List.of(
                        "--entrypoint",
                        "java",
                        "trusted-gateway",
                        "-Xmx128m",
                        "-cp",
                        "@/app/jib-classpath-file",
                        "io.yupiik.ia.agent.github.execution.RestrictedServicesGateway",
                        "--config",
                        "/services/config.json"),
                create.subList(create.indexOf("--entrypoint"), create.size()));
        assertTrue(calls.indexOf(
                        "network connect --alias attempt-services.yca.internal yca-services-test-services-net yca-services-test-services")
                < calls.indexOf("start yca-services-test-services"));
        final var check = "exec --user " + uid + ":" + gid
                + " yca-services-test-services java -Xmx32m -cp @/app/jib-classpath-file io.yupiik.ia.agent.github.execution.RestrictedServicesGateway --check /services/config.json";
        assertEquals(
                List.of(check),
                calls.stream().filter(c -> c.startsWith("exec ")).toList());
        assertTrue(calls.indexOf(check) > calls.indexOf("start yca-services-test-services"));
        assertTrue(create.contains("app=io.yupiik.yca.github-bot"));
        final var run = Files.readAllLines(Path.of(executable + ".run.args"));
        assertEquals("yca-services-test-services-net", run.get(run.indexOf("--network") + 1));
        assertEquals(1, run.stream().filter("--network"::equals).count());
        assertEquals("127.0.0.1", run.get(run.indexOf("--dns") + 1));
        assertTrue(run.contains("io.yupiik.yca.github-bot.component=agent"));
        assertTrue(run.contains("app=io.yupiik.yca.github-bot"));
        for (final var proxy : List.of(
                "HTTP_PROXY",
                "HTTPS_PROXY",
                "ALL_PROXY",
                "NO_PROXY",
                "http_proxy",
                "https_proxy",
                "all_proxy",
                "no_proxy")) {
            assertTrue(run.contains(proxy + "="));
        }
        assertFalse(run.contains("-p"));
        for (final var forbidden : List.of(
                "--publish",
                "--add-host",
                "docker.sock",
                "/services/config.json",
                "/services/tls.p12",
                "trusted-control",
                "YCA_SERVICES_PROVIDER_TOKEN",
                "YCA_SERVICES_KEYSTORE_PASSWORD")) {
            assertFalse(run.toString().contains(forbidden), forbidden);
        }
        final var env = Files.readString(Path.of(executable + ".create.env"));
        secrets().forEach((key, value) -> assertTrue(env.contains(key + "=" + value)));
        final var agentEnv = Files.readString(Path.of(executable + ".run.env"));
        for (final var value : secrets().values()) {
            assertFalse(calls.toString().contains(value));
            assertFalse(create.toString().contains(value));
            assertFalse(agentEnv.contains(value));
            assertFalse(Files.readString(attempt.resolve("execution.log")).contains(value));
        }
        assertTrue(agentEnv.contains("YCA_CONTEXT_TOKEN=ephemeral-agent-token"));
        assertTrue(calls.indexOf("rm --force yca-services-test-services")
                < calls.indexOf("network rm yca-services-test-services-net"));
    }

    @Test
    void rejectsBundlesInUnknownModeAndRequiresExplicitBundleInServicesMode() throws Exception {
        final var executable = fake("");
        final var legacy = new DockerExecutionBackend(executable.toString(), "agent", Duration.ofSeconds(1), "", null, "shown");
        assertThrows(
                IllegalArgumentException.class,
                () -> legacy.execute(
                        home, "yca-mode", "review", Map.of(), Map.of(), home.resolve("settings"), secrets()));
        assertThrows(
                IllegalArgumentException.class,
                () -> legacy.execute(home, "yca-mode", "review", Map.of(), Map.of(), null, secrets()));
        assertThrows(
                IllegalArgumentException.class,
                () -> backend(executable, Duration.ofSeconds(1))
                        .execute(home, "yca-mode", "review", Map.of(), Map.of()));
        assertFalse(Files.exists(Path.of(executable + ".calls")));
    }

    @Test
    void cleanupWorksAfterRestartWithoutServicesConfigurationAndNeverDeletesUnknownResources() throws Exception {
        final var executable = fake("");
        Files.createFile(Path.of(executable + ".container"));
        Files.createFile(Path.of(executable + ".network"));
        new DockerExecutionBackend(executable.toString(), "agent", Duration.ofSeconds(2), "", null, "shown")
                .stop("yca-recovery");
        final var calls = Files.readAllLines(Path.of(executable + ".calls"));
        assertTrue(calls.contains("rm --force yca-recovery-services"));
        assertTrue(calls.contains("network rm yca-recovery-services-net"));
        for (final var answer : List.of(
                "printf 'yca-other|services\\n'",
                "printf 'yca-recovery|agent\\n'",
                "printf 'daemon unavailable\\n'; exit 1",
                "printf 'Error: No such container: yca-other\\n'; exit 1")) {
            final var collision = fake(
                    "if [ \"$1\" = container ] && [ \"$3\" = --format ] && [ \"$4\" != '{{.State.Running}}' ]; then "
                            + answer + "; exit 0; fi\n");
            assertThrows(
                    IllegalStateException.class,
                    () -> DockerRestrictedServices.stopOwned(
                            collision.toString(), Duration.ofSeconds(1), "yca-recovery"));
            assertFalse(Files.readString(Path.of(collision + ".calls")).contains("rm "));
        }
    }

    @Test
    void ambiguousAgentTerminationRetainsServicesResources() throws Exception {
        final var executable = fake("if [ \"$4\" = '{{.State.Running}}' ]; then printf 'unknown\\n'; exit 0; fi\n");
        assertThrows(
                IllegalStateException.class,
                () -> new DockerExecutionBackend(executable.toString(), "agent", Duration.ofSeconds(1), "", null, "shown")
                        .stop("yca-uncertain"));
        final var calls = Files.readAllLines(Path.of(executable + ".calls"));
        assertEquals(1, calls.size());
        assertFalse(calls.toString().contains("rm "));
    }

    @Test
    void networkOwnershipIsCheckedBeforeRemovingEvenAnOwnedGateway() throws Exception {
        final var executable =
                fake("if [ \"$1 $2\" = 'network inspect' ]; then printf 'yca-other|services\\n'; exit 0; fi\n");
        Files.createFile(Path.of(executable + ".container"));
        assertThrows(
                IllegalStateException.class,
                () -> DockerRestrictedServices.stopOwned(executable.toString(), Duration.ofSeconds(1), "yca-owner"));
        assertFalse(Files.readString(Path.of(executable + ".calls")).contains("rm "));
    }

    @Test
    void timeoutCleansOnlyAfterSuccessfulAgentStopAndRetainsOnKillFailure() throws Exception {
        nonRoot();
        final var settings = settings();
        for (final boolean killFails : List.of(false, true)) {
            final var executable = fake("""
                    if [ "$1" = run ]; then exec sleep 60; fi
                    if [ "$4" = '{{.State.Running}}' ]; then printf 'true\\n'; exit 0; fi
                    """ + (killFails ? "if [ \"$1\" = kill ]; then exit 1; fi\n" : ""));
            final var attempt = attempt();
            assertTimeoutPreemptively(
                    Duration.ofSeconds(5),
                    () -> assertThrows(
                            IllegalStateException.class,
                            () -> backend(executable, Duration.ofMillis(300))
                                    .execute(
                                            attempt,
                                            "yca-timeout",
                                            "review",
                                            Map.of(),
                                            Map.of(),
                                            settings,
                                            secrets())));
            final var calls = Files.readAllLines(Path.of(executable + ".calls"));
            assertTrue(calls.contains("kill yca-timeout"));
            if (killFails) {
                assertFalse(calls.stream().anyMatch(c -> c.startsWith("rm ") || c.startsWith("network rm ")));
            } else {
                assertTrue(calls.indexOf("kill yca-timeout") < calls.indexOf("rm --force yca-timeout-services"));
            }
        }
    }

    @Test
    void rejectsOldDaemonBadControlNetworkAndUnapprovedSecretsBeforeAgentLaunch() throws Exception {
        nonRoot();
        final var settings = settings();
        for (final var override : List.of(
                "if [ \"$1\" = version ]; then printf '27.5.0\\n'; exit 0; fi\n",
                "if [ \"$1\" = version ]; then printf '28.0.0\\nextra'; exit 0; fi\n",
                "if [ \"$1 $2\" = 'network inspect' ] && [ \"$5\" = trusted-control ]; then printf 'host|false\\n'; exit 0; fi\n",
                "if [ \"$1 $2\" = 'network inspect' ] && [ \"$5\" = trusted-control ]; then printf 'bridge|true\\n'; exit 0; fi\n")) {
            final var executable = fake(override);
            final var gateway = new DockerRestrictedServices(
                    executable.toString(), configuration("http://172.20.0.1:8080/v1"), Duration.ofSeconds(2));
            assertThrows(IllegalStateException.class, () -> gateway.start("yca-old", settings, secrets()));
            final var calls = Files.readString(Path.of(executable + ".calls"));
            assertFalse(calls.contains("network create"));
            assertFalse(calls.contains("create --name"));
        }
        final var executable = fake("");
        assertThrows(
                IllegalArgumentException.class,
                () -> new DockerRestrictedServices(
                                executable.toString(),
                                configuration("http://172.20.0.1:8080/v1"),
                                Duration.ofSeconds(1))
                        .start("yca-secrets", settings, Map.of("GH_TOKEN", "forbidden")));
        assertFalse(Files.exists(Path.of(executable + ".calls")));
    }

    @Test
    void partialStartFailureCleansOnlyOwnedResourcesWithoutLaunchingAgent() throws Exception {
        nonRoot();
        final var settings = settings();
        for (final var stage : List.of("create", "connect", "start")) {
            final var executable = fake(
                    stage.equals("connect")
                            ? "if [ \"$1 $2\" = 'network connect' ]; then exit 1; fi\n"
                            : "if [ \"$1\" = '" + stage + "' ]; then exit 1; fi\n");
            final var attempt = attempt();
            assertThrows(
                    IllegalStateException.class,
                    () -> backend(executable, Duration.ofSeconds(2))
                            .execute(attempt, "yca-partial", "review", Map.of(), Map.of(), settings, secrets()));
            final var calls = Files.readString(Path.of(executable + ".calls"));
            assertFalse(calls.contains("run --rm"));
            assertTrue(calls.contains("network rm yca-partial-services-net"));
            if (!stage.equals("create")) {
                assertTrue(calls.contains("rm --force yca-partial-services"));
            }
        }
    }

    @Test
    void controlDeadlineKillsClientAndDescendantWithoutLeakingDiagnostics() throws Exception {
        final var executable = fake(
                "if [ \"$1\" = container ]; then printf '%s' \"$$\" > \"$0.pid\"; sleep 60 & printf '%s' \"$!\" > \"$0.child\"; wait; fi\n");
        assertTimeoutPreemptively(
                Duration.ofSeconds(4),
                () -> assertThrows(
                        IllegalStateException.class,
                        () -> DockerRestrictedServices.stopOwned(
                                executable.toString(), Duration.ofMillis(200), "yca-deadline")));
        for (final var suffix : List.of(".pid", ".child")) {
            final long pid = Long.parseLong(Files.readString(Path.of(executable + suffix)));
            assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
        }
    }

    @Test
    void tlsReadinessRetriesAndDeadlineFailureCleansWithoutAgentLaunch() throws Exception {
        nonRoot();
        final var settings = settings();
        for (final var override :
                List.of("if [ \"$1\" = exec ]; then exit 1; fi\n", "if [ \"$1\" = exec ]; then exec sleep 60; fi\n")) {
            final var executable = fake(override);
            // Leave enough client budget to reach the readiness probe under parallel reactor load.
            // The hung probe still must time out, never launch the agent, and clean owned resources.
            assertTimeoutPreemptively(
                    Duration.ofSeconds(6),
                    () -> assertThrows(
                            IllegalStateException.class,
                            () -> backend(executable, Duration.ofSeconds(1))
                                    .execute(
                                            attempt(),
                                            "yca-notready",
                                            "review",
                                            Map.of(),
                                            Map.of(),
                                            settings,
                                            secrets())));
            final var calls = Files.readAllLines(Path.of(executable + ".calls"));
            assertTrue(calls.stream().anyMatch(c -> c.startsWith("exec --user ")));
            assertFalse(calls.stream().anyMatch(c -> c.startsWith("run ")));
            assertTrue(calls.contains("rm --force yca-notready-services"));
            assertTrue(calls.contains("network rm yca-notready-services-net"));
        }
        final var executable =
                fake("if [ \"$1\" = exec ] && [ ! -f \"$0.probed\" ]; then : > \"$0.probed\"; exit 1; fi\n");
        assertEquals(
                0,
                backend(executable, Duration.ofSeconds(3))
                        .execute(attempt(), "yca-retry", "review", Map.of(), Map.of(), settings, secrets()));
        final var calls = Files.readAllLines(Path.of(executable + ".calls"));
        assertEquals(2, calls.stream().filter(c -> c.startsWith("exec --user ")).count());
    }

    @Test
    void requiresCompleteOpaqueCredentialsButAcceptsAnonymousUpstreamAndCustomTlsPassword() throws Exception {
        nonRoot();
        final var settings = settings();
        final var executable = fake("");
        final var gateway = new DockerRestrictedServices(
                executable.toString(), configuration("http://172.20.0.1:8080/v1"), Duration.ofSeconds(1));
        for (final var key : secrets().keySet()) {
            final var missing = new HashMap<>(secrets());
            missing.remove(key);
            assertThrows(IllegalArgumentException.class, () -> gateway.start("yca-missing", settings, missing));
        }
        for (final var key : List.of("YCA_CONTEXT_TOKEN", "YCA_CONTEXT_GIT_TOKEN", "YCA_SERVICES_PROVIDER_TOKEN")) {
            final var invalid = new HashMap<>(secrets());
            invalid.put(key, "control-secret");
            assertThrows(IllegalArgumentException.class, () -> gateway.start("yca-invalid", settings, invalid));
        }
        assertFalse(Files.exists(Path.of(executable + ".calls")));
        final var anonymous = new HashMap<>(secrets());
        anonymous.put("YCA_SERVICES_UPSTREAM_TOKEN", "");
        anonymous.put("TLS_PASSWORD", anonymous.remove("YCA_SERVICES_KEYSTORE_PASSWORD"));
        final var custom = new ExecutorConfiguration.Docker.Services(
                "trusted-gateway",
                "trusted-control",
                home.resolve("tls.p12").toString(),
                "TLS_PASSWORD",
                null,
                "http://172.20.0.1:8080/v1",
                new ExecutorConfiguration.Docker.Egress(null, List.of(), null, false));
        new DockerRestrictedServices(executable.toString(), custom, Duration.ofSeconds(1))
                .start("yca-anonymous", settings, anonymous);
        assertTrue(Files.readString(Path.of(executable + ".create.env")).contains("YCA_SERVICES_UPSTREAM_TOKEN=\n"));
    }

    @Test
    void settingsCannotBeMountedIntoAgent() throws Exception {
        nonRoot();
        settings();
        final var attempt = attempt();
        final var settings = Files.writeString(attempt.resolve("workspace/settings.json"), "{}");
        final var executable = fake("");
        assertThrows(
                IllegalArgumentException.class,
                () -> backend(executable, Duration.ofSeconds(1))
                        .execute(attempt, "yca-mount", "review", Map.of(), Map.of(), settings, secrets()));
        assertFalse(Files.exists(Path.of(executable + ".calls")));
    }

    private ExecutorConfiguration.Docker.Services configuration(final String target) {
        return new ExecutorConfiguration.Docker.Services(
                "trusted-gateway",
                "trusted-control",
                home.resolve("tls.p12").toString(),
                null,
                null,
                target,
                new ExecutorConfiguration.Docker.Egress(null, List.of(), null, false));
    }

    private DockerExecutionBackend backend(final Path executable, final Duration timeout) {
        return new DockerExecutionBackend(
                executable.toString(), "agent", timeout, "", null, configuration("http://172.20.0.1:8080/v1"), "shown");
    }

    private Map<String, String> secrets() {
        return Map.of(
                "YCA_CONTEXT_TOKEN",
                "c".repeat(43),
                "YCA_CONTEXT_GIT_TOKEN",
                "g".repeat(43),
                "YCA_SERVICES_PROVIDER_TOKEN",
                "p".repeat(43),
                "YCA_SERVICES_UPSTREAM_TOKEN",
                "upstream-secret",
                "YCA_SERVICES_KEYSTORE_PASSWORD",
                "tls-secret");
    }

    private void nonRoot() throws Exception {
        assumeTrue(((Number) Files.getAttribute(home, "unix:uid")).intValue() != 0);
    }

    private Path settings() throws Exception {
        Files.writeString(home.resolve("tls.p12"), "test TLS store");
        return Files.writeString(home.resolve("private-settings.json"), "{}");
    }

    private Path attempt() throws Exception {
        final var directory = Files.createTempDirectory(home, "attempt-");
        Files.writeString(directory.resolve("configuration.json"), "{}");
        for (final var name : List.of("storage", "workspace", "output")) {
            Files.createDirectory(directory.resolve(name));
        }
        return directory;
    }

    private Path fake(final String override) throws Exception {
        final var executable = Files.createTempFile(home, "docker-", ".sh");
        Files.writeString(executable, """
                #!/bin/sh
                printf '%s\\n' "$*" >> "$0.calls"
                """ + override + """
                for last; do :; done
                case "$last" in
                  *-egress|*-external)
                    runtime=${last%-egress}; runtime=${runtime%-external}
                    case "$1 $2" in
                      'container inspect'|'network inspect')
                        if [ -f "$0.$last" ]; then printf '%s|dependency-egress\\n' "$runtime"; exit 0; fi
                        printf 'Error: No such object: %s\\n' "$last"; exit 1 ;;
                      'network create') : > "$0.$last"; exit 0 ;;
                      'network rm') rm "$0.$last"; exit 0 ;;
                    esac
                    if [ "$1" = rm ]; then rm "$0.$last"; exit 0; fi ;;
                esac
                if [ "$1" = create ] && [ "$3" = "${3%-egress}-egress" ]; then
                  : > "$0.$3"
                  printf '%s\\n' "$@" > "$0.proxy.args"
                  env > "$0.proxy.env"
                  exit 0
                fi
                runtime=${last%-services-net}
                runtime=${runtime%-services}
                case "$1 $2" in
                  'network inspect')
                    if [ "$last" = trusted-control ]; then printf 'bridge|false\\n'; exit 0; fi
                    if [ -f "$0.network" ]; then printf '%s|services\\n' "$runtime"; else printf 'Error: No such network: %s\\n' "$last"; exit 1; fi ;;
                  'container inspect')
                    if [ "$4" = '{{.State.Running}}' ]; then
                      case "$last" in *-services) printf 'true\\n';; *) printf 'false\\n';; esac
                    elif [ -f "$0.container" ]; then printf '%s|services\\n' "$runtime"
                    else printf 'Error: No such container: %s\\n' "$last"; exit 1; fi ;;
                  'network create') : > "$0.network" ;;
                  'network rm') rm "$0.network" ;;
                esac
                if [ "$1" = version ]; then printf '28.1.0\\n'; fi
                if [ "$1" = create ]; then : > "$0.container"; fi
                if [ "$1" = rm ]; then rm "$0.container"; fi
                if [ "$1" = create ] || [ "$1" = run ]; then printf '%s\\n' "$@" > "$0.$1.args"; env > "$0.$1.env"; fi
                exit 0
                """);
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"));
        return executable;
    }
}
