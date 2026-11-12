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
package io.yupiik.ia.agent.github;

import io.yupiik.fusion.framework.build.api.configuration.Property;

public record WorkerOptions(
        @Property(
                defaultValue = "2",
                documentation = "Execution retries after initial attempt (2 means at most 3 attempts).")
        int maxRetries,

        @Property(
                defaultValue = "1",
                documentation = "Maximum concurrent worker processes sharing this queue, with durable task leases and"
                        + " fixed slot locks. Each invocation processes at most one task.")
        int parallelism,

        @Property(defaultValue = "\"\"", documentation = "Optional path to trusted agent configuration.")
        String agentConfiguration,

        @Property(
                defaultValue = "\"\"",
                documentation = "Required fork owner for solve tasks. Fork must be pre-provisioned unless organization"
                        + " provisioning is enabled.")
        String forkOwner,

        @Property(
                defaultValue = "true",
                documentation =
                        "Allow agent GitHub credentials. False withholds both agent and fork credentials; host"
                                + " publishes structured reviews and bounded strict-solve changes using host credentials."
                                + " Strict solve requires host contents-write access to the verified fork and PR-write access upstream.")
        boolean agentCommenting,

        @Property(
                defaultValue = "false",
                documentation = "Opt in to durable organization fork provisioning. Fork owner must be an organization"
                        + " and host credential must have required fork permissions.")
        boolean provisionFork,

        @Property(
                defaultValue = "\"\"",
                documentation = "Optional private host bare-clone cache for anonymous public github.com repositories."
                        + " Never mounted in agents; empty disables caching.")
        String cacheRoot,

        @Property(
                defaultValue = "\"git\"",
                documentation = "Trusted Git executable used by the optional host clone cache.")
        String cacheGit,

        @Property(
                defaultValue = "false",
                documentation =
                        "Stage a bounded, integrity-checked private source snapshot for strict reviews using host authentication. Requires repository contents read permission; no credentials enter the snapshot.")
        boolean privateSource) {}
