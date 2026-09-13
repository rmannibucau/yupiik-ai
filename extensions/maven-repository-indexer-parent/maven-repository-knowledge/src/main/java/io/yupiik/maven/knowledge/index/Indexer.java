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
import io.yupiik.maven.knowledge.KnowledgeFactory;
import io.yupiik.maven.knowledge.config.KnowledgeConfig;
import io.yupiik.maven.knowledge.content.ContentAcquired;
import io.yupiik.maven.knowledge.content.ContentAcquirer;
import io.yupiik.maven.knowledge.llm.KnowledgeLlm;
import io.yupiik.maven.knowledge.write.KnowledgeWriter;
import java.nio.file.Path;
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
 *     <li>Resolve + acquire local content (sources/javadoc/doc/classes) for every GAV, plus a deterministic
 *     bytecode-derived entry-point + API view of the main jar.</li>
 *     <li>Immutable-skip release artifacts that already have knowledge; always (re)generate SNAPSHOTs unless force.</li>
 *     <li>Write the whole {@link KnowledgeFactory} knowledge set for every artifact
 *     ({@code -knowledge-index/api/&lt;kind&gt;/skill-*.md} siblings) - always deterministic, never LLM-gated.</li>
 *     <li>Optional, validated LLM refinement of entry points and gold-format skills only when
 *     {@code config.useLlm()} is true and the LLM is available; invalid/placeholder output is discarded.</li>
 * </ol>
 * The pipeline never depends on the LLM producing the knowledge: without it the output is fully deterministic.
 */
public class Indexer {

    private final ContentAcquirer acquirer;
    private final KnowledgeWriter writer;
    private final KnowledgeFactory factory;
    private final KnowledgeLlm llm;
    private final KnowledgeConfig config;
    private final ExecutorService executor;

    private final Map<Gav, String> expectedFingerprints;

    public Indexer(
            final ContentAcquirer acquirer,
            final KnowledgeWriter writer,
            final KnowledgeFactory factory,
            final KnowledgeLlm llm,
            final KnowledgeConfig config) {
        this(acquirer, writer, factory, llm, config, null);
    }

    /**
     * @param expectedFingerprints optional per-GAV build-declaration fingerprints; a GAV whose stored
     *                             fingerprint equals its expected fingerprint is skipped as unchanged (so a
     *                             pom/gradle change re-indexes only the affected dependencies). When an
     *                             expected fingerprint is absent for a GAV, the legacy immutable-skip applies.
     */
    public Indexer(
            final ContentAcquirer acquirer,
            final KnowledgeWriter writer,
            final KnowledgeFactory factory,
            final KnowledgeLlm llm,
            final KnowledgeConfig config,
            final Map<Gav, String> expectedFingerprints) {
        this.acquirer = acquirer;
        this.writer = writer;
        this.factory = factory;
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
        // Acquire content window by window (evict each before the next) so the in-memory acquired-content map
        // stays bounded to a window no matter the cohort size.
        final var runBudget = config.runContentBudget();
        long consumedChars = 0;
        for (final var window : BatchSizer.windows(toProcess, config)) {
            final var contentByGav = new LinkedHashMap<Gav, ContentAcquired>();
            final var eligible = new ArrayList<Gav>();
            var budgetExhausted = false;
            for (final var gav : window) {
                if (runBudget > 0 && consumedChars >= runBudget) {
                    budgetExhausted = true;
                    final var result = IndexResult.skipped(gav, "run content budget exceeded");
                    results.add(result);
                    if (onResult != null) {
                        onResult.accept(result);
                    }
                    continue;
                }
                final var acquired = acquirer.acquire(gav);
                if (acquired != null && acquired.content() != null) {
                    consumedChars += acquired.content().length();
                    contentByGav.put(gav, acquired);
                } else {
                    contentByGav.put(gav, ContentAcquired.none(gav));
                }
                eligible.add(gav);
            }
            if (!eligible.isEmpty() || !budgetExhausted) {
                for (final var batch : BatchSizer.batches(eligible, contentByGav, config)) {
                    if (beforeBatch != null) {
                        beforeBatch.accept(results.size());
                    }
                    runBatch(batch, contentByGav, results, onResult);
                }
            }
            // evict: content is only held for the current window
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
            if (!writer.alreadyIndexed(gav)) {
                return null;
            }
            return expectedFp.equals(writer.readFingerprint(gav))
                    ? "unchanged: already indexed with current dependencies"
                    : null; // declaring build files changed -> re-index
        }
        return writer.alreadyIndexed(gav) ? "immutable-skip: release already indexed" : null;
    }

    static boolean isSnapshot(final Gav gav) {
        return gav.version() != null && gav.version().contains("SNAPSHOT");
    }

    private void runBatch(
            final List<Gav> batch,
            final Map<Gav, ContentAcquired> contentByGav,
            final List<IndexResult> results,
            final Consumer<IndexResult> onResult) {
        final var futures = new ArrayList<Future<?>>();
        for (final var gav : batch) {
            futures.add(executor.submit(() -> {
                final Path path;
                try {
                    path = generate(gav, contentByGav.get(gav));
                } catch (final RuntimeException e) {
                    final var res = IndexResult.skipped(gav, "failed to index: " + e.getMessage());
                    results.add(res);
                    if (onResult != null) {
                        onResult.accept(res);
                    }
                    return;
                }
                final var expectedFp = expectedFingerprints.get(gav);
                if (expectedFp != null) {
                    writer.writeFingerprint(gav, expectedFp);
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
     * Deterministically generate + write the knowledge set for one artifact, optionally refining it with the
     * LLM when {@code useLlm} is enabled. Returns the index file path.
     */
    private Path generate(final Gav gav, final ContentAcquired acquired) {
        final var entryPoints = acquirer.entryPoints(gav);
        final var apiMarkdown = acquirer.api(gav);
        final var moduleConfig = acquirer.moduleConfig(gav);
        final var skills = new ArrayList<KnowledgeFactory.SkillDoc>();
        if (config.useLlm() && llm.available()) {
            skills.addAll(LlmRefinement.refine(gav, acquired, entryPoints, apiMarkdown, llm));
        }
        return factory.writeArtifact(gav, acquirer.contentParts(gav), apiMarkdown, moduleConfig, entryPoints, skills);
    }

    /**
     * Close the internal executor. Call when done.
     */
    public void close() {
        executor.shutdownNow();
    }
}
