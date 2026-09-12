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
package io.yupiik.ia.agent.core.maven;

import io.yupiik.fusion.framework.api.lifecycle.Start;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.event.OnEvent;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.runtime.VirtualThreadExecutor;
import io.yupiik.maven.knowledge.Gav;
import io.yupiik.maven.knowledge.config.KnowledgeConfig;
import io.yupiik.maven.knowledge.content.ContentAcquirer;
import io.yupiik.maven.knowledge.content.LocalRepository;
import io.yupiik.maven.knowledge.content.WebFetch;
import io.yupiik.maven.knowledge.fingerprint.ProjectFingerprint;
import io.yupiik.maven.knowledge.index.IndexResult;
import io.yupiik.maven.knowledge.index.Indexer;
import io.yupiik.maven.knowledge.llm.KnowledgeLlm;
import io.yupiik.maven.knowledge.manifest.ManifestWriter;
import io.yupiik.maven.knowledge.skill.SkillWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Shared pipeline for the embedded Maven repository knowledge feature. The embedded tool, the
 * {@code /maven-repository-knowledge} slash command and the session-start skill loader all delegate
 * to this service so the indexing logic lives in a single, framework-agnostic place.
 */
@ApplicationScoped
public class MavenRepositoryKnowledgeService {

    private final CodingAgentConfiguration configuration;
    private final KnowledgeLlm llm;
    private final VirtualThreadExecutor executor;
    private final ExtendedHttpClient httpClient;
    private final AtomicReference<List<String>> sessionSkillBlocks = new AtomicReference<>(List.of());
    private final AtomicBoolean sessionSkillsRequested = new AtomicBoolean();
    private final AtomicReference<IndexRun> runningIndex = new AtomicReference<>();

    public MavenRepositoryKnowledgeService(
            final CodingAgentConfiguration configuration,
            final KnowledgeLlm llm,
            final VirtualThreadExecutor executor,
            final ExtendedHttpClient httpClient) {
        this.configuration = configuration;
        this.llm = llm;
        this.executor = executor;
        this.httpClient = httpClient;
    }

    protected MavenRepositoryKnowledgeService() {
        this.configuration = null;
        this.llm = null;
        this.executor = null;
        this.httpClient = null;
    }

    /**
     * Kick off an asynchronous warm-up of the per-session artifact skills once indexed artifacts are
     * discovered, so the first system prompt does not block on a slow LLM call.
     */
    public void onStart(@OnEvent final Start start) {
        if (!enabled()) {
            return;
        }
        if (!configuration.mavenRepositoryKnowledge().skillsAtSessionStart()) {
            return;
        }
        if (llm.available()) {
            executor.execute(this::loadSessionSkillsLazily);
        }
    }

    /**
     * Load (once per process) the <code>&lt;skill&gt;</code> blocks for the session's main entry-point
     * artifacts, by suggesting GAVs through the LLM and dereferencing their {@code -skills.md} manifest.
     * Returns cached blocks if already computed; never blocks the caller for more than the first call.
     */
    public List<String> sessionSkillBlocks() {
        if (!enabled() || !configuration.mavenRepositoryKnowledge().skillsAtSessionStart()) {
            return List.of();
        }
        return sessionSkillBlocks.get();
    }

    private void loadSessionSkillsLazily() {
        if (!sessionSkillsRequested.compareAndSet(false, true)) {
            return;
        }
        try {
            final var suggested = suggest();
            final var config = knowledgeConfig();
            final var writer = new SkillWriter(config.localRepository());
            final var manifestWriter = new ManifestWriter(config.localRepository());
            final var blocks = new ArrayList<String>();
            final var seen = new LinkedHashSet<String>();
            for (final var gav : suggested) {
                final var manifest = manifestWriter.manifestPath(gav);
                if (manifest == null || !Files.isRegularFile(manifest)) {
                    continue;
                }
                for (final var marker : writer.resolvedMarkers(gav)) {
                    final var skillFile = marker.resolve("SKILL.md");
                    if (!Files.isRegularFile(skillFile)) {
                        continue;
                    }
                    final var name = marker.getFileName().toString();
                    if (!seen.add(name)) {
                        continue;
                    }
                    try {
                        final var raw = Files.readString(skillFile, StandardCharsets.UTF_8);
                        blocks.add("<skill name=\"" + name + "\">\n" + raw + "\n</skill>");
                    } catch (final IOException e) {
                        // skip unreadable skill
                    }
                }
            }
            sessionSkillBlocks.set(List.copyOf(blocks));
        } catch (final RuntimeException e) {
            sessionSkillBlocks.set(List.of());
        }
    }

    private boolean enabled() {
        return configuration != null
                && configuration.mavenRepositoryKnowledge() != null
                && configuration.mavenRepositoryKnowledge().enabled()
                && hasBuildFile();
    }

    /**
     * Only act when the current project is a Maven or Gradle project (a pom.xml or a build.gradle).
     * This keeps the embedded knowledge feature quiet when working in a plain directory.
     */
    private boolean hasBuildFile() {
        final var dir = configuration.resolveProjectDir();
        if (Files.exists(dir.resolve("pom.xml"))) {
            return true;
        }
        return Files.exists(dir.resolve("build.gradle"))
                || Files.exists(dir.resolve("build.gradle.kts"))
                || Files.exists(dir.resolve("settings.gradle"))
                || Files.exists(dir.resolve("settings.gradle.kts"));
    }

    /**
     * Choose the session's main entry-point artifacts: only artifacts that exist locally but are not yet
     * indexed are considered candidates; the LLM picks the main ones (silently returning all when
     * structured output is unsupported).
     */
    private List<Gav> suggest() {
        final var config = knowledgeConfig();
        final var repo = new LocalRepository(config.localRepository());
        final var writer = new SkillWriter(config.localRepository());
        final var candidates = new ArrayList<Gav>();
        for (final var gav : localGavs(config.localRepository())) {
            if (!writer.alreadyIndexed(gav) && repo.resolve(gav).present()) {
                candidates.add(gav);
            }
        }
        if (candidates.isEmpty()) {
            return List.of();
        }
        if (!llm.available()) {
            return candidates;
        }
        try {
            return llm.suggestEntryGavs(candidates);
        } catch (final RuntimeException e) {
            return candidates;
        }
    }

    /**
     * Index the given GAVs. Returns a human readable report.
     */
    public String index(final List<String> gavStrings, final boolean force) {
        if (!enabled()) {
            return "maven repository knowledge is disabled";
        }
        final var config = knowledgeConfig(force);
        final var effective = new KnowledgeConfig(
                config.localRepository(),
                config.concurrency(),
                config.batchSize(),
                config.remoteManifest(),
                force,
                config.boundedFetch(),
                config.maxBatchChars(),
                config.tokenBudget());
        final var repo = new LocalRepository(config.localRepository());
        final var writer = new SkillWriter(config.localRepository());
        final var manifestWriter = new ManifestWriter(config.localRepository());
        final var gavs = new ArrayList<Gav>();
        final var problems = new StringBuilder();
        for (final var raw : gavStrings) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            try {
                gavs.add(Gav.parse(raw));
            } catch (final IllegalArgumentException e) {
                if (problems.length() > 0) {
                    problems.append(", ");
                }
                problems.append(raw).append(" (invalid)");
            }
        }
        final var sb = new StringBuilder();
        if (problems.length() > 0) {
            sb.append("skipped invalid GAVs: ").append(problems).append('\n');
        }
        final var indexer = newIndexer(effective, fingerprints(gavs));
        try {
            final var results = indexer.index(gavs, result -> {});
            sb.append("indexed ")
                    .append(results.indexedCount())
                    .append(", skipped ")
                    .append(results.skippedCount());
            if (!results.items().isEmpty()) {
                sb.append('\n');
                final var limit = Math.min(results.items().size(), 50);
                for (var i = 0; i < limit; i++) {
                    final var r = results.items().get(i);
                    sb.append("- ")
                            .append(r.gav().coordinate())
                            .append(r.indexed() ? " indexed" : r.skipped() ? " skipped: " + r.reason() : " missing")
                            .append('\n');
                }
                if (results.items().size() > limit) {
                    sb.append("- ... and ")
                            .append(results.items().size() - limit)
                            .append(" more\n");
                }
            }
            return sb.toString().stripTrailing();
        } finally {
            indexer.close();
        }
    }

    /**
     * Refresh command entry point for the {@code /maven-repository-knowledge refresh} slash command.
     * With no GAV, re-runs the baseline over the project's local artifacts; with a GAV, force-regenerates
     * that single artifact (bypassing the immutable-skip rule). Runs asynchronously on the shared executor
     * and returns immediately with a short acknowledgement.
     */
    public List<String> refresh(final String gav) {
        return refresh(gav, null, null);
    }

    /**
     * Same as {@link #refresh(String)} but with progress + completion callbacks so a frontend can surface
     * per-batch progress lines and the final summary.
     */
    public List<String> refresh(final String gav, final Consumer<String> progress, final Consumer<String> onFinished) {
        if (!enabled()) {
            return List.of("maven repository knowledge is disabled");
        }
        final var config = knowledgeConfig();
        final List<String> targets;
        final boolean force;
        if (gav == null || gav.isBlank()) {
            targets = localGavs(config.localRepository()).stream()
                    .map(Gav::coordinate)
                    .toList();
            force = false;
        } else {
            targets = List.of(Gav.parse(gav.strip()).coordinate());
            force = true;
        }
        return startIndex(targets, force, progress, onFinished);
    }

    /**
     * Start an asynchronous index of the given GAV coordinates. The task runs on the shared executor; the
     * returned push-back is populated immediately.
     *
     * @param gavs        coordinates to index (already validated).
     * @param force       whether to bypass the immutable-skip rule.
     * @param progress    optional per-batch progress consumer, receiving lines like {@code ~NN% (done/total)}.
     * @param onFinished  optional consumer run on the executor thread once indexing completes, with the
     *                    final "indexed X, skipped Y" summary (may be null).
     * @return an acknowledgement of the starting run (one line).
     */
    public List<String> startIndex(
            final List<String> gavs,
            final boolean force,
            final Consumer<String> progress,
            final Consumer<String> onFinished) {
        if (!enabled()) {
            return List.of("maven repository knowledge is disabled");
        }
        if (gavs == null || gavs.isEmpty()) {
            return List.of("nothing to index");
        }
        final var config = knowledgeConfig(force);
        final var effective = new KnowledgeConfig(
                config.localRepository(),
                config.concurrency(),
                config.batchSize(),
                config.remoteManifest(),
                force,
                config.boundedFetch(),
                config.maxBatchChars(),
                config.tokenBudget());
        final List<Gav> parsed = new ArrayList<>();
        for (final var raw : gavs) {
            try {
                parsed.add(Gav.parse(raw.strip()));
            } catch (final IllegalArgumentException ignored) {
                // skip invalid coordinates; they surface as nothing to do for them
            }
        }
        if (parsed.isEmpty()) {
            return List.of("no valid GAVs to index");
        }
        final var total = parsed.size();
        final var run = new IndexRun(total);
        runningIndex.set(run);
        executor.execute(() -> {
            final var indexer = newIndexer(effective, fingerprints(parsed));
            try {
                final var results = indexer.index(parsed, result -> run.done(), batch -> fireProgress(run, progress));
                fireProgress(run, progress); // final ~100% (done/total) line once every artifact is processed
                final var summary = summarize(results);
                run.summary(summary);
                if (onFinished != null) {
                    onFinished.accept(summary);
                }
            } finally {
                run.complete();
                indexer.close();
                runningIndex.compareAndSet(run, null);
            }
        });
        return List.of("started indexing " + total + " artifact(s)");
    }

    /**
     * @return the currently running index (never {@code null}); {@link IndexRun#inFlight()} tells whether
     *         an index is still in progress, so callers can ask before sending a prompt.
     */
    public IndexRun currentIndex() {
        final var run = runningIndex.get();
        return run == null ? new IndexRun(0) : run;
    }

    private void fireProgress(final IndexRun run, final Consumer<String> progress) {
        if (progress == null || run.total() <= 0) {
            return;
        }
        final var pct = (int) (100.0 * run.doneCount() / run.total());
        progress.accept("~" + pct + "% (" + run.doneCount() + "/" + run.total() + ")");
    }

    private static String summarize(final IndexResult.Results results) {
        final var sb = new StringBuilder();
        sb.append("indexed ")
                .append(results.indexedCount())
                .append(", skipped ")
                .append(results.skippedCount());
        if (!results.items().isEmpty()) {
            sb.append('\n');
            final var limit = Math.min(results.items().size(), 50);
            for (var i = 0; i < limit; i++) {
                final var r = results.items().get(i);
                sb.append("- ")
                        .append(r.gav().coordinate())
                        .append(r.indexed() ? " indexed" : r.skipped() ? " skipped: " + r.reason() : " missing")
                        .append('\n');
            }
            if (results.items().size() > limit) {
                sb.append("- ... and ").append(results.items().size() - limit).append(" more\n");
            }
        }
        return sb.toString().stripTrailing();
    }

    private Indexer newIndexer(final KnowledgeConfig config, final Map<Gav, String> expectedFingerprints) {
        return new Indexer(
                new ContentAcquirer(new LocalRepository(config.localRepository()), webFetch(), config),
                new SkillWriter(config.localRepository()),
                new ManifestWriter(config.localRepository()),
                llm,
                config,
                expectedFingerprints);
    }

    /**
     * @return per-GAV expected build-declaration fingerprints, so a pom/gradle change re-indexes only the
     *         dependencies whose declaring build files changed (unchanged, already-indexed ones are skipped).
     */
    private Map<Gav, String> fingerprints(final List<Gav> gavs) {
        return ProjectFingerprint.of(gavs, projectBuildFiles());
    }

    /**
     * @return the project's Maven/Gradle build files (present in the project dir) that feed the fingerprints.
     */
    private List<Path> projectBuildFiles() {
        final var dir = configuration.resolveProjectDir();
        final var out = new ArrayList<Path>();
        try (final var stream = Files.walk(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        final var name = p.getFileName().toString();
                        return name.equals("pom.xml")
                                || name.equals("build.gradle")
                                || name.equals("build.gradle.kts")
                                || name.equals("settings.gradle")
                                || name.equals("settings.gradle.kts");
                    })
                    .forEach(out::add);
        } catch (final IOException e) {
            // ignore unreadable project dir
        }
        return out;
    }

    private WebFetch webFetch() {
        return (url, timeoutMs, maxBytes) -> {
            final var uri = java.net.URI.create(url);
            final var request = java.net.http.HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(java.time.Duration.ofMillis(timeoutMs))
                    .header("accept", "text/html,application/xhtml+xml,text/plain,application/json,*/*;q=0.8")
                    .GET()
                    .build();
            final java.net.http.HttpResponse<byte[]> response;
            try {
                response = httpClient.send(request, java.net.http.HttpResponse.BodyHandlers.ofByteArray());
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted fetching " + url, e);
            }
            if (response.statusCode() >= 400) {
                throw new IOException("HTTP " + response.statusCode() + " for " + url);
            }
            final var body = response.body();
            if (body == null || body.length == 0) {
                return "";
            }
            final var size = Math.min(body.length, maxBytes);
            return new String(body, 0, size, StandardCharsets.UTF_8);
        };
    }

    private KnowledgeConfig knowledgeConfig() {
        return knowledgeConfig(configuration.mavenRepositoryKnowledge().force());
    }

    private KnowledgeConfig knowledgeConfig(final boolean force) {
        final var cfg = configuration.mavenRepositoryKnowledge();
        return new KnowledgeConfig(
                resolveLocalRepository(cfg.localRepository()),
                cfg.concurrency(),
                cfg.batchSize(),
                cfg.remoteManifest(),
                force,
                KnowledgeConfig.BoundedFetch.of(60, 30_000L, 200_000),
                cfg.maxBatchChars(),
                llm == null ? null : llm.tokenBudget());
    }

    private static Path resolveLocalRepository(final String raw) {
        final var override = System.getProperty("yca.mavenRepositoryKnowledge.localRepository");
        final var value = raw == null || raw.isBlank()
                ? (override == null || override.isBlank() ? "~/.m2/repository" : override)
                : raw;
        final var home = value.startsWith("~/") ? System.getProperty("user.home") + value.substring(1) : value;
        return Path.of(home).toAbsolutePath().normalize();
    }

    private static List<Gav> localGavs(final Path root) {
        final var out = new ArrayList<Gav>();
        collect(root, root, out);
        return out;
    }

    private static void collect(final Path root, final Path dir, final List<Gav> out) {
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        try (final var entries = Files.list(dir)) {
            final var children = entries.sorted().toList();
            for (final var child : children) {
                if (Files.isDirectory(child)) {
                    collect(root, child, out);
                }
            }
        } catch (final IOException e) {
            // ignore unreadable
        }
        // a "<version>" directory directly contains jars; treat its parent as artifactId level if it has jars
        final var version = dir.getFileName() == null ? null : dir.getFileName().toString();
        if (version == null || version.isBlank()) {
            return;
        }
        boolean hasJars = false;
        try (final var files = Files.list(dir)) {
            hasJars = files.anyMatch(
                    f -> Files.isRegularFile(f) && f.getFileName().toString().endsWith(".jar"));
        } catch (final IOException e) {
            return;
        }
        if (!hasJars) {
            return;
        }
        final var artifactDir = dir.getParent();
        final var artifact = artifactDir == null
                ? null
                : artifactDir.getFileName() == null
                        ? null
                        : artifactDir.getFileName().toString();
        final var groupDir = artifactDir == null ? null : artifactDir.getParent();
        if (artifact == null || groupDir == null) {
            return;
        }
        final var groupPath = root.relativize(groupDir).toString().replace(java.io.File.separatorChar, '.');
        out.add(Gav.of(groupPath, artifact, version));
    }

    /**
     * Handle on an asynchronous indexing run, so callers (CLI/TUI gating) can ask whether it is still in
     * flight and how far it has progressed.
     */
    public static final class IndexRun {
        private final int total;
        private final AtomicInteger done = new AtomicInteger();
        private final AtomicBoolean finished = new AtomicBoolean();
        private volatile String summary;

        IndexRun(final int total) {
            this.total = total;
        }

        void done() {
            done.incrementAndGet();
        }

        void summary(final String summary) {
            this.summary = summary;
        }

        void complete() {
            finished.set(true);
        }

        /** {@code true} while the indexing run is still in progress (nothing to index is never in-flight). */
        public boolean inFlight() {
            return !finished.get() && total > 0;
        }

        public int doneCount() {
            return done.get();
        }

        public int total() {
            return total;
        }

        /** Final "indexed X, skipped Y" summary, {@code null} until the run completed. */
        public String summary() {
            return summary;
        }
    }
}
