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
package io.yupiik.ia.agent.github.configuration;

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;
import io.yupiik.fusion.framework.build.api.json.JsonModel;
import java.util.List;

/** Trusted launcher settings, separate from queue state and operation options. */
@RootConfiguration("github.executor")
public record ExecutorConfiguration(
        @Property(
                defaultValue = "io.yupiik.ia.agent.github.configuration.ExecutorConfiguration.Backend.DOCKER",
                documentation = "Executor backend: DOCKER, NONO or KUBERNETES.")
        Backend backend,

        @Property(
                defaultValue = "\"\"",
                documentation = "Private attempt root. Kubernetes requires this to be the mounted root of its dedicated"
                        + " attempt PVC; never use the queue PVC.")
        String attemptRoot,

        @Property(defaultValue = "3600", documentation = "Maximum execution duration in seconds.")
        int timeoutSeconds,

        @Property(defaultValue = "30", documentation = "Maximum executor control request duration in seconds.")
        int commandTimeoutSeconds,

        @Property(documentation = "Docker launcher settings.")
        Docker docker,

        @Property(documentation = "Nono launcher settings.") Nono nono,

        @Property(documentation = "Kubernetes launcher and client settings.")
        Kubernetes kubernetes,

        @Property(documentation = "Credential relay settings.")
        Relay relay) {
    @JsonModel
    public enum Backend {
        DOCKER,
        NONO,
        KUBERNETES
    }

    public record Docker(
            @Property(defaultValue = "\"docker\"", documentation = "Docker CLI executable.")
            String executable,

            @Property(defaultValue = "\"yca-github-agent:latest\"", documentation = "Trusted agent container image.")
            String image,

            @Property(
                    defaultValue = "\"\"",
                    documentation =
                            "Optional pre-created network for unrestricted mode; not an egress security boundary.")
            String network,

            @Property(documentation = "Optional enforced CONNECT gateway. Null disables enforcement.")
            Egress egress) {
        public Docker {
            // Fusion materializes nested records even when no nested property was supplied.
            if (egress != null
                    && (egress.image() == null || egress.image().isBlank())
                    && (egress.allowedHosts() == null || egress.allowedHosts().isEmpty())) {
                egress = null;
            }
        }

        public record Egress(
                @Property(
                        documentation = "Trusted gateway image, preferably pinned by digest. Required when configured.")
                String image,

                @Property(documentation = "Exact DNS hostnames allowed for CONNECT on port 443; no wildcards or IPs.")
                List<String> allowedHosts,

                @Property(
                        defaultValue = "\"127.0.0.1\"",
                        documentation =
                                "Agent DNS sink address; numeric IPv4 or IPv6 loopback. No external resolver is permitted in enforced mode.")
                String dns) {}
    }

    public record Nono(
            @Property(defaultValue = "\"nono\"", documentation = "Nono CLI executable.")
            String executable,

            @Property(
                    defaultValue = "\"\"",
                    documentation =
                            "Absolute trusted agent executable launcher within the Nono runtime root (not a JAR).")
            String agentExecutable,

            @Property(
                    defaultValue = "\"\"",
                    documentation = "Dedicated credential-free Nono runtime root containing JDK, agent and tools.")
            String runtimeRoot) {}

    public record Kubernetes(
            @Property(defaultValue = "\"yca-github\"", documentation = "Dedicated Kubernetes executor namespace.")
            String namespace,

            @Property(
                    defaultValue = "\"yca-attempts\"",
                    documentation = "Dedicated attempt PVC claim, containing no dispatcher state.")
            String claim,

            @Property(defaultValue = "\"yca-github-agent:latest\"", documentation = "Trusted agent container image.")
            String image,

            @Property(
                    defaultValue = "\"yca-agent\"",
                    documentation = "Unprivileged executor service account with token automount disabled.")
            String serviceAccount,

            @Property(defaultValue = "10001L", documentation = "Unprivileged executor user identifier.")
            long uid,

            @Property(defaultValue = "10001L", documentation = "Unprivileged executor group identifier.")
            long gid,

            @Property(
                    defaultValue = "\"\"",
                    documentation = "Kubernetes API URL; empty retains Fusion in-cluster discovery.")
            String master,

            @Property(
                    defaultValue = "\"/var/run/secrets/kubernetes.io/serviceaccount/token\"",
                    documentation = "Token file path, or none to disable token authentication.")
            String token,

            @Property(
                    defaultValue = "\"/var/run/secrets/kubernetes.io/serviceaccount/ca.crt\"",
                    documentation = "CA PEM content or certificate file path.")
            String certificates,

            @Property(defaultValue = "\"\"", documentation = "Client authentication private key PEM content.")
            String privateKey,

            @Property(defaultValue = "\"\"", documentation = "Client authentication X509 certificate PEM content.")
            String privateKeyCertificate,

            @Property(defaultValue = "false", documentation = "Disable Kubernetes certificate validation (insecure).")
            boolean skipTls,

            @Property(
                    defaultValue = "\"\"",
                    documentation =
                            "Private dispatcher JSON kubeconfig path. External exec/auth-provider plugins are rejected.")
            String kubeconfig) {}

    public record Relay(
            @Property(
                    defaultValue = "\"\"",
                    documentation =
                            "Optional numeric private listening address for a per-attempt OpenAI-compatible credential"
                                    + " relay; empty disables. Agent configuration receives only the ephemeral relay"
                                    + " token.")
            String bind,

            @Property(
                    defaultValue = "\"\"",
                    documentation =
                            "Relay base URL reachable from the selected sandbox, without port/path; the ephemeral port"
                                    + " is appended. Operator-managed routing and egress required.")
            String advertised) {}
}
