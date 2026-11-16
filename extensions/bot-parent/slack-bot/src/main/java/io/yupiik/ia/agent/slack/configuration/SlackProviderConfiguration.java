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
package io.yupiik.ia.agent.slack.configuration;

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;

/** Trusted backend-specific provider deployment; never derived from Slack or repository content. */
@RootConfiguration("slack.providerServices")
public record SlackProviderConfiguration(
        @Property(
                defaultValue = "\"\"",
                documentation = "Exact selected provider target: HTTPS for Nono, numeric private HTTP for Kubernetes.")
        String upstream,

        @Property(documentation = "Qualified native socket deployment.")
        Native nativeBridge,

        @Property(documentation = "Qualified Kubernetes provider deployment.")
        Kubernetes kubernetes) {
    public record Native(
            @Property(
                    defaultValue = "\"\"",
                    documentation =
                            "Private short absolute socket root outside runtime, attempts, state and budget roots.")
            String socketRoot,

            @Property(
                    defaultValue = "false",
                    documentation =
                            "Operator confirms qualified Linux/Nono pathname mediation and credential-free runtime.")
            boolean qualified,

            @Property(
                    defaultValue = "false",
                    documentation =
                            "Operator confirms stronger outer isolation hiding host IPC endpoints; required because pathname mediation has a sockaddr race.")
            boolean outerIsolationQualified) {}

    public record Kubernetes(
            @Property(defaultValue = "\"\"", documentation = "Trusted provider gateway image.")
            String image,

            @Property(defaultValue = "\"\"", documentation = "Private TLS Secret containing gateway.p12.")
            String tlsSecret,

            @Property(
                    defaultValue = "\"attempt-services.yca.internal\"",
                    documentation = "Pinned TLS identity trusted by the agent image.")
            String serverName,

            @Property(
                    defaultValue = "\"\"",
                    documentation = "Qualified node containing the shared local budget filesystem.")
            String nodeName,

            @Property(defaultValue = "\"\"", documentation = "Private local budget PVC, never the attempt PVC.")
            String budgetClaim,

            @Property(defaultValue = "\"default\"", documentation = "Gateway service account; no token mounted.")
            String serviceAccount,

            @Property(defaultValue = "8443", documentation = "Gateway TLS port.")
            int port,

            @Property(
                    defaultValue = "false",
                    documentation =
                            "Operator confirms enforcing CNI, no additive policies, node/metadata isolation and qualified same-host local budget storage.")
            boolean qualified) {}
}
