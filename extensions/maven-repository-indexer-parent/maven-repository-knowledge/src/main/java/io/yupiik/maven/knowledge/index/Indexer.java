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
package io.yupiik.maven.knowledge.index;

import io.yupiik.maven.knowledge.Gav;
import io.yupiik.maven.knowledge.config.KnowledgeConfig;
import io.yupiik.maven.knowledge.content.ContentAcquired;
import io.yupiik.maven.knowledge.content.ContentAcquirer;
import io.yupiik.maven.knowledge.llm.KnowledgeLlm;
import io.yupiik.maven.knowledge.manifest.ManifestWriter;
import io.yupiik.maven.knowledge.skill.SkillWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Orchestrates indexing of a set of GAVs:
 * <ol>
 *     <li>Resolve + acquire local content (sources/javadoc/doc/classes) for every GAV.</li>
 *     <li>Immutable-skip release artifacts that already have knowledge; always (re)generate SNAPSHOTs unless force.</li>
 *     <li>Bulk entry-point discovery: many GAVs per single LLM call ({@code batchSize}).</li>
 *     <li>Synthesize each skill (LLM) and write it + the remote manifest.</li>
 * </ol>
 * When the LLM is unavailable, the pipeline degrades: classes-only artifacts are skipped with a clear reason
 * rather than failing the whole run.
 */
public class Indexer {

    private final ContentAcquirer acquirer;
    private final SkillWriter skillWriter;
    private final ManifestWriter manifestWriter;
    private final KnowledgeLlm llm;
    private final KnowledgeConfig config;
    private final ExecutorService executor;

    private final Map<Gav, String> expectedFingerprints;

    public Indexer(
            final ContentAcquirer acquirer,
            final SkillWriter skillWriter,
            final ManifestWriter manifestWriter,
            final KnowledgeLlm llm,
            final KnowledgeConfig config) {
        this(acquirer, skillWriter, manifestWriter, llm, config, null);
    }

    /**
     * @param expectedFingerprints optional per-GAV build-declaration fingerprints; a GAV whose stored
     *                             fingerprint equals its expected fingerprint is skipped as unchanged (so a
     *                             pom/gradle change re-indexes only the affected dependencies). When an
     *                             expected fingerprint is absent for a GAV, the legacy immutable-skip applies.
     */
    public Indexer(
            final ContentAcquirer acquirer,
            final SkillWriter skillWriter,
            final ManifestWriter manifestWriter,
            final KnowledgeLlm llm,
            final KnowledgeConfig config,
            final Map<Gav, String> expectedFingerprints) {
        this.acquirer = acquirer;
        this.skillWriter = skillWriter;
        this.manifestWriter = manifestWriter;
        this.llm = llm;
        this.config = config;
        this.expectedFingerprints = expectedFingerprints == null ? Map.of() : expectedFingerprints;
        this.executor = Executors.newFixedThreadPool(Math.max(1, config.concurrency()));
    }

    /**
     * Index the given GAVs, reporting each outcome through {@code onResult}. Blocking.
     */
    public IndexResult.Results index(final List<Gav> gavs, final Consumer<IndexResult> onResult) {
        return index(gavs, onResult, null);
    }

    /**
     * Index the given GAVs, reporting each outcome through {@code onResult}. Blocking.
     *
     * @param beforeBatch optional callback invoked before each entry-points batch is processed, with the
     *                    cumulative number of artifacts already reported (skipped + indexed) so a caller can
     *                    surface progress (eg. a per-batch "~NN% (done/total)" line).
     */
    public IndexResult.Results index(
            final List<Gav> gavs, final Consumer<IndexResult> onResult, final IntConsumer beforeBatch) {
        final var results = new java.util.concurrent.CopyOnWriteArrayList<IndexResult>();
        final var toProcess = new ArrayList<Gav>();
        for (final var gav : gavs) {
            final var skip = shouldSkip(gav);
            if (skip != null) {
                final var result = IndexResult.skipped(gav, skip);
                results.add(result);
                if (onResult != null) {
                    onResult.accept(result);
                }
            } else {
                toProcess.add(gav);
            }
        }
        // Acquire every remaining artifact once, then chunk into entry-points batches. The sizer picks
        // the mode by maxBatchChars: -1 = greedy to the model token budget, > 0 = hard char cap, else GAV count.
        final var contentByGav = new LinkedHashMap<Gav, ContentAcquired>();
        for (final var gav : toProcess) {
            contentByGav.put(gav, acquirer.acquire(gav));
        }
        for (final var batch : BatchSizer.batches(toProcess, contentByGav, config)) {
            if (beforeBatch != null) {
                beforeBatch.accept(results.size());
            }
            runBatch(batch, contentByGav, results, onResult);
        }
        return IndexResult.Results.summarize(results);
    }

    /**
     * @return a skip reason, or {@code null} when the GAV must be (re)indexed.
     */
    private String shouldSkip(final Gav gav) {
        if (config.force() || isSnapshot(gav)) {
            return null;
        }
        final var expectedFp = expectedFingerprints.get(gav);
        if (expectedFp != null) {
            // build-declaration aware: re-index only when the declaring files changed since last index
            if (!skillWriter.alreadyIndexed(gav)) {
                return null;
            }
            return expectedFp.equals(skillWriter.readFingerprint(gav))
                    ? "unchanged: already indexed with current dependencies"
                    : null; // declaring build files changed -> re-index
        }
        return skillWriter.alreadyIndexed(gav) ? "immutable-skip: release already indexed" : null;
    }

    static boolean isSnapshot(final Gav gav) {
        return gav.version() != null && gav.version().contains("SNAPSHOT");
    }

    private void runBatch(
            final List<Gav> batch,
            final Map<Gav, ContentAcquired> contentByGav,
            final List<IndexResult> results,
            final Consumer<IndexResult> onResult) {
        final var llmInputs = new LinkedHashMap<Gav, String>();

        for (final var gav : batch) {
            final var acquired = contentByGav.get(gav);
            if (acquired == null || !acquired.present()) {
                final var res = IndexResult.skipped(gav, "no usable local content (sources/javadoc/doc/classes)");
                results.add(res);
                if (onResult != null) {
                    onResult.accept(res);
                }
            } else {
                llmInputs.put(gav, acquired.content());
            }
        }
        if (llmInputs.isEmpty()) {
            return;
        }
        final Map<Gav, List<String>> entryPoints;
        if (llm.available()) {
            entryPoints = llm.entryPoints(llmInputs);
        } else {
            entryPoints = Map.of();
        }
        // Synthesize + write concurrently (batches already serialised by caller, fine for bounded concurrency).
        final var futures = new ArrayList<Future<?>>();
        for (final var entry : llmInputs.entrySet()) {
            futures.add(executor.submit(() -> {
                final var gav = entry.getKey();
                final var acquired = contentByGav.get(gav);
                final var eps = entryPoints.getOrDefault(gav, List.of());
                final var skill = llm.available()
                        ? llm.synthesizeSkill(gav, gav.artifactId(), acquired.content(), eps)
                        : fallbackSkill(gav, acquired, eps);
                final var path = skillWriter.write(gav, gav.artifactId(), skill);
                if (config.remoteManifest()) {
                    manifestWriter.write(gav, List.of(gav.artifactId()));
                }
                final var expectedFp = expectedFingerprints.get(gav);
                if (expectedFp != null) {
                    skillWriter.writeFingerprint(gav, expectedFp);
                }
                final var res = IndexResult.indexed(gav, "written " + path);
                results.add(res);
                if (onResult != null) {
                    onResult.accept(res);
                }
            }));
        }
        for (final var future : futures) {
            try {
                future.get();
            } catch (final Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /**
     * Minimal knowledge usable when no LLM is reachable: header + entry points + the acquired content
     * verbatim so the next run with a reachable LLM can upgrade it.
     */
    private static String fallbackSkill(final Gav gav, final ContentAcquired acquired, final List<String> entryPoints) {
        final var sb = new StringBuilder();
        sb.append("---\n");
        sb.append("name: ").append(gav.artifactId()).append('\n');
        sb.append("description: ")
                .append(gav.artifactId())
                .append(" - ")
                .append(gav.coordinate())
                .append(" (generated, LLM unavailable).\n");
        sb.append("---\n\n");
        sb.append("# ").append(gav.artifactId()).append('\n');
        if (!entryPoints.isEmpty()) {
            sb.append("\n## Entry points\n");
            for (final var ep : entryPoints) {
                sb.append("- `").append(ep).append("`\n");
            }
        }
        sb.append("\n## Module rules\n");
        sb.append("Generated from local Maven repository content; entry points may be incomplete because the LLM ");
        sb.append("was not reachable.\n");
        return sb.toString();
    }

    /**
     * Close the internal executor. Call when done.
     */
    public void close() {
        executor.shutdownNow();
    }
}
