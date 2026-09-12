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
package io.yupiik.maven.knowledge.config;

import java.nio.file.Path;

/**
 * Configuration for the {@code maven-repository-knowledge} pipeline. Plain Java on purpose so the
 * knowledge module stays free of any runtime framework; both the embedded yca tool and the MCP server
 * map their own configuration onto this record.
 *
 * @param localRepository local Maven repository root, typically {@code ~/.m2/repository}.
 * @param concurrency     maximum number of artifacts processed concurrently (batching happens before this).
 * @param batchSize       maximum number of GAVs per single LLM call (also the hard fence bounding JSON output).
 * @param remoteManifest  whether to also write the remote {@code -skills.md} manifest per artifact.
 * @param force           always regenerate, bypassing the immutable-skip rule.
 * @param boundedFetch    webfetch limits (when the pom url fallback is used).
 * @param maxBatchChars   batch sizing mode: {@code -1} suits the batch greedily to the model {@code tokenBudget}
 *                        (context input + estimated JSON output, with a conservative margin, falling back to
 *                        GAV-count batching when the budget is unknown); {@code > 0} is a hard character cap on
 *                        the cumulative input content; {@code 0} keeps GAV-count batching only. Whichever limit
 *                        ({@code batchSize} or the selected character/budget limit) is reached first closes a batch.
 * @param tokenBudget     model token limits for the auto ({@code -1}) mode; ignored otherwise.
 */
public record KnowledgeConfig(
        Path localRepository,
        int concurrency,
        int batchSize,
        boolean remoteManifest,
        boolean force,
        BoundedFetch boundedFetch,
        int maxBatchChars,
        TokenBudget tokenBudget) {

    public static KnowledgeConfig defaults(final Path localRepository) {
        return new KnowledgeConfig(
                resolveLocalRepository(localRepository),
                4,
                8,
                true,
                false,
                BoundedFetch.of(1, 30_000L, 65536),
                0,
                null);
    }

    private static Path resolveLocalRepository(final Path localRepository) {
        if (localRepository != null) {
            return localRepository;
        }
        final var home = System.getProperty("user.home", ".");
        return Path.of(home, ".m2", "repository");
    }

    /**
     * Limits applied when the indexer falls back to fetching documentation from a pom {@code <url>}.
     *
     * @param maxPerMinute maximum webfetch requests per minute; &lt;= 0 disables the rate limit.
     * @param timeoutMs    per-request timeout in milliseconds.
     * @param maxBytes     maximum bytes kept of a fetched page.
     */
    public record BoundedFetch(int maxPerMinute, long timeoutMs, int maxBytes) {

        public static BoundedFetch of(final int maxPerMinute, final long timeoutMs, final int maxBytes) {
            return new BoundedFetch(maxPerMinute, timeoutMs, maxBytes);
        }
    }
}
