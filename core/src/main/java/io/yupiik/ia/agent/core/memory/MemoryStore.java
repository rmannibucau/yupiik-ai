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
package io.yupiik.ia.agent.core.memory;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.regex.Pattern;

@ApplicationScoped
public class MemoryStore {
    /** The header line of {@link #HEADER}: filtered out of the prompt sections (it is not a fact). */
    public static final String HEADER_LINE = "Durable facts, decisions and preferences. One bullet per line.";

    public static final String HEADER = "# Memory\n\n" + HEADER_LINE + "\n\n";

    private final CodingAgentConfiguration configuration;

    /**
     * Cached content fingerprint of the memory files, protected by a last-modified/size guard so an
     * external edit (which bypasses {@link #append}) also invalidates it. Lets the agent loop detect
     * a mid-turn {@code memory_write} without re-hashing the files at every LLM round.
     */
    private volatile int contentVersion = -1;

    /**
     * Facts appended since the last time the wire diff was drained for each session
     * (mid-turn memory_write): keyed by session id so parallel sessions never see each
     * other's memory deltas. A small LRU guard prevents unbounded growth.
     */
    private final java.util.Map<String, java.util.Deque<String>> pendingDiffs =
            new java.util.LinkedHashMap<>(8, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(final java.util.Map.Entry<String, java.util.Deque<String>> eldest) {
                    return size() > 64;
                }
            };

    private volatile long globalMtime = Long.MIN_VALUE;
    private volatile long globalSize = Long.MIN_VALUE;
    private volatile long projectMtime = Long.MIN_VALUE;
    private volatile long projectSize = Long.MIN_VALUE;

    /**
     * Tokenized memory corpus cache (access-order LRU, so unchanged content costs one {@code get}
     * per turn instead of a full re-tokenization). Keyed by the actual scoped content read from
     * disk on every {@link #promptSection(String)} call: any change to the memory files - including
     * external edits outside {@link #append} - produces a key mismatch and a safe recompute, so the
     * cache can never return a stale corpus.
     */
    private final Map<String, Corpus> corpusCache = new LinkedHashMap<>(8, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(final Map.Entry<String, Corpus> eldest) {
            return size() > 4;
        }
    };

    public MemoryStore(final CodingAgentConfiguration configuration) {
        this.configuration = configuration;
    }

    protected MemoryStore() {
        this.configuration = null;
    }

    /**
     * Cached fingerprint of the current memory content (global + project), recomputed only when
     * the files change (detected through last-modified/size, so in-store writes and external edits
     * both invalidate it). A never-written store is stable across calls.
     */
    public int version() {
        var v = contentVersion;
        if (v >= 0 && (configuration == null || !stampsChanged())) {
            return v;
        }
        synchronized (this) {
            v = contentVersion;
            if (v < 0 || (configuration != null && stampsChanged())) {
                v = java.util.Objects.hash(readGlobal(), readProject());
                contentVersion = v;
                if (configuration != null) {
                    snapshotStamps();
                }
            }
            return v;
        }
    }

    private boolean stampsChanged() {
        return globalMtime != mtime(globalFile())
                || globalSize != size(globalFile())
                || projectMtime != mtime(projectFile())
                || projectSize != size(projectFile());
    }

    private void snapshotStamps() {
        globalMtime = mtime(globalFile());
        globalSize = size(globalFile());
        projectMtime = mtime(projectFile());
        projectSize = size(projectFile());
    }

    private static long mtime(final Path file) {
        try {
            return Files.isRegularFile(file) ? Files.getLastModifiedTime(file).toMillis() : -1;
        } catch (final IOException e) {
            return Long.MIN_VALUE; // unreadable: always report a change, recompute is the safe side
        }
    }

    private static long size(final Path file) {
        try {
            return Files.isRegularFile(file) ? Files.size(file) : -1;
        } catch (final IOException e) {
            return Long.MIN_VALUE;
        }
    }

    public Path globalFile() {
        return Path.of(configuration.resolveStorageHome(), "memory", "MEMORY.md");
    }

    public Path projectFile() {
        return configuration
                .resolveWorkingDir()
                .resolve(".yca")
                .resolve("memory")
                .resolve("MEMORY.md");
    }

    public String readGlobal() {
        return read(globalFile());
    }

    public String readProject() {
        return read(projectFile());
    }

    public String promptSection() {
        return promptSection(null);
    }

    /**
     * Builds the {@code <memory>} section for the system prompt.
     * <p>
     * When a {@code prompt} is provided AND the combined memory exceeds the configured budget, the least
     * relevant project entries (ranked with a lightweight BM25-style overlap against the prompt) are dropped
     * first, while global facts are always kept. This bounds in-context memory cost without ever silently
     * truncating high-value durable directives. When no prompt is available (eg. a context dump or resume),
     * the full memory is returned capped at the configured budget, keeping the tail (most recent) lines.
     *
     * @param prompt the user prompt used to rank relevance; may be null
     * @return the memory section, or empty when no memory is stored
     */
    public String promptSection(final String prompt) {
        return promptSection(
                prompt,
                Math.max(500, configuration == null ? 0 : configuration.memory().maxChars()));
    }

    /**
     * Builds the {@code <memory>} section with an explicit character budget.
     * {@code budget <= 0} (memory auto-disabled, eg. when the resolved ratio budget
     * is too small) returns an empty section without reading the files.
     */
    public String promptSection(final String prompt, final int budget) {
        if (configuration == null || !configuration.memory().enabled() || budget < 500) {
            return "";
        }
        final var global = readGlobal();
        final var project = readProject();
        if (global.isBlank() && project.isBlank()) {
            return "";
        }
        final var max = budget;
        final var globalText = withScope("global", global);
        final var projectText = withScope("project", project);
        final var selected = select(globalText, projectText, global, project, prompt, max);
        final var sb = new StringBuilder("<memory>\n");
        if (!selected.global().isBlank()) {
            sb.append("<global>\n").append(selected.global()).append("\n</global>\n");
        }
        if (!selected.project().isBlank()) {
            sb.append("<project>\n").append(selected.project()).append("\n</project>\n");
        }
        sb.append("</memory>");
        return sb.toString();
    }

    private static String withScope(final String scope, final String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        // strip the markdown header/blank lines and the file header line (not a fact),
        // keep only the durable bullet lines, deduplicated
        final var seen = new LinkedHashSet<String>();
        for (final var line : content.lines().toList()) {
            final var s = line.strip();
            if (s.isEmpty()
                    || s.startsWith("#")
                    || HEADER_LINE.equals(s)
                    || "Memory".equals(s)
                    || ("-" + HEADER_LINE).equals(s)) {
                continue;
            }
            seen.add(s);
        }
        return String.join("\n", seen) + (seen.isEmpty() ? "" : "\n");
    }

    private static final int MARKUP_OVERHEAD = 57;

    private ScopeSelection select(
            final String globalText,
            final String projectText,
            final String globalRaw,
            final String projectRaw,
            final String prompt,
            final int max) {
        if (max <= 0) {
            return new ScopeSelection("", "");
        }
        // Both scopes get the same treatment: rank every bullet against the query (the latest
        // user turn + todo items), keep the top scorers per scope, then spill the remaining
        // budget to whichever scope has the most relevant leftovers. Global keeps a small
        // recency tie-break (its file is the flat append-only store where the newest facts
        // sit at the tail and would otherwise be starved by the older head).
        final var query = prompt == null || prompt.isBlank() ? List.<String>of() : tokens(prompt);
        if (query.isEmpty()) {
            // no ranking signal (context dump, resume, bare prompt): keep the head of each scope,
            // capped at half the budget - deterministic and still bounded.
            return new ScopeSelection(
                    bounded(globalText, max / 2, false),
                    bounded(projectText, max - (max / 2) - MARKUP_OVERHEAD, false));
        }
        final var globalEntries = ranked(globalText, query, true);
        final var projectEntries = ranked(projectText, query, false);
        final var gSb = new StringBuilder();
        final var pSb = new StringBuilder();
        final var half = max / 2;
        final var usedAfterFirst = fill(globalEntries, gSb, half) + fill(projectEntries, pSb, half);
        final var remaining = max - usedAfterFirst - MARKUP_OVERHEAD - (gSb.length() > 0 && pSb.length() > 0 ? 1 : 0);
        if (remaining > 0) {
            // spillover: global first (directives are usually the higher-value), then project
            final var afterGlobal = fill(globalEntries, gSb, half + remaining) + pSb.length();
            fill(projectEntries, pSb, half + remaining + (afterGlobal - usedAfterFirst));
        }
        // durable-directive floor: even when no global bullet matches the query, keep the head of
        // the global scope (the most established directives) so global memory is never fully
        // dropped - only its stale tail is. This preserves the pre-ranking behavior when there is
        // no query signal while still favoring relevance when there is one.
        if (gSb.isEmpty() && !globalText.isBlank()) {
            gSb.append(bounded(globalText, Math.min(globalText.length(), half * 2 / 3), false));
        }
        return new ScopeSelection(gSb.toString(), pSb.toString());
    }

    /** Deterministic ranked entries: score desc, then file line index asc (stable). */
    private List<Entry> ranked(final String text, final List<String> query, final boolean recencyBoost) {
        if (text == null || text.isBlank() || query.isEmpty()) {
            return List.of();
        }
        final var ranked = rank(text, query);
        if (!recencyBoost) {
            return ranked;
        }
        // tie-break only: at most +10% for the newest bullet (tail of the file), so a
        // recently appended durable fact wins over an equally-relevant older one, without
        // letting recency override relevance. index is the line position in the corpus.
        final var lineCount = Math.max(1, text.lines().toList().size() - 1);
        final var boosted = new ArrayList<Entry>(ranked.size());
        for (final var entry : ranked) {
            boosted.add(
                    new Entry(entry.line(), entry.score * (1 + 0.1 * ((double) entry.index / lineCount)), entry.index));
        }
        boosted.sort(Comparator.comparingDouble(Entry::score).reversed().thenComparingInt(Entry::index));
        return boosted;
    }

    /** Adds entries until the budget is exhausted; returns the chars consumed. */
    private int fill(final List<Entry> entries, final StringBuilder sb, final int budget) {
        if (budget <= 0) {
            return sb.length();
        }
        var remaining = budget;
        for (final var entry : entries) {
            final var line = entry.line();
            final var add = line.length() + (sb.length() == 0 ? 0 : 1);
            if (add > remaining) {
                break;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(line);
            remaining -= add;
        }
        return sb.length();
    }

    private static String bounded(final String content, final int limit, final boolean tail) {
        if (content == null || content.isBlank() || limit <= 0) {
            return "";
        }
        if (content.length() <= limit) {
            return content;
        }
        return tail ? content.substring(content.length() - limit) : content.substring(0, limit);
    }

    /**
     * Splits a durable fact longer than {@code maxLen} into a list of chunks, each at most
     * {@code maxLen} characters, breaking at word boundaries where possible. A single short fact is
     * returned as-is. Used so long extracted knowledge is never silently discarded.
     */
    private static java.util.List<String> wrapLine(final String line, final int maxLen) {
        if (line.length() <= maxLen) {
            return java.util.List.of(line);
        }
        final var out = new java.util.ArrayList<String>();
        final var words = line.split(" ");
        final var current = new StringBuilder();
        for (final var word : words) {
            if (!current.isEmpty() && current.length() + 1 + word.length() > maxLen) {
                out.add(current.toString());
                current.setLength(0);
            }
            if (current.isEmpty()) {
                current.append(word);
            } else {
                current.append(' ').append(word);
            }
        }
        if (!current.isEmpty()) {
            out.add(current.toString());
        }
        return out;
    }

    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}_]+");

    private static List<String> tokens(final String text) {
        final var m = WORD.matcher(text.toLowerCase(Locale.ROOT));
        final var list = new ArrayList<String>();
        while (m.find()) {
            list.add(m.group());
        }
        return list;
    }

    /**
     * Tokenized shape of a memory corpus, computed once per distinct content and cached (LRU).
     * {@code lineTokens} holds the tokens of each non-blank line (same order), {@code documentFrequency}
     * counts how many lines contain each token (presence, not frequency); both are used to score lines
     * in {@link #rank(String, List)} without any re-tokenization.
     */
    private record Corpus(List<String> lines, List<List<String>> lineTokens, Map<String, Integer> documentFrequency) {

        private static Corpus of(final String text) {
            final var lines = text.lines().filter(l -> !l.isBlank()).toList();
            final var lineTokens = new ArrayList<List<String>>(lines.size());
            final var df = new HashMap<String, Integer>();
            for (final var line : lines) {
                final var toks = tokens(line);
                lineTokens.add(toks);
                final var seen = new HashSet<String>(toks.size());
                for (final var token : toks) {
                    if (seen.add(token)) {
                        df.merge(token, 1, Integer::sum);
                    }
                }
            }
            return new Corpus(lines, lineTokens, df);
        }
    }

    private synchronized Corpus corpus(final String content) {
        final var cached = corpusCache.get(content);
        if (cached != null) {
            return cached;
        }
        final var built = Corpus.of(content);
        corpusCache.put(content, built);
        return built;
    }

    private List<Entry> rank(final String projectText, final List<String> promptTokens) {
        final var corpus = corpus(projectText);
        final var lines = corpus.lines();
        final var lineTokens = corpus.lineTokens();
        final var df = corpus.documentFrequency();
        final var pq = new PriorityQueue<Entry>(Comparator.comparingDouble(Entry::score));
        for (var i = 0; i < lines.size(); i++) {
            final var currentTokens = lineTokens.get(i);
            if (currentTokens.isEmpty()) {
                continue;
            }
            // BM25-lite: tf of shared tokens weighted by inverse rarity across the corpus (idf approx = log(1+N/nT))
            final var counts = new LinkedHashMap<String, Integer>();
            for (final var t : currentTokens) {
                counts.merge(t, 1, Integer::sum);
            }
            double score = 0;
            for (final var t : promptTokens) {
                final var tf = counts.getOrDefault(t, 0);
                if (tf > 0) {
                    final var idf = Math.log1p((double) lines.size() / Math.max(1, df.getOrDefault(t, 0)));
                    score += tf / (tf + 1.3) * idf;
                }
            }
            if (score > 0) {
                pq.offer(new Entry(lines.get(i), score, i));
                if (pq.size() > 64) {
                    pq.poll();
                }
            }
        }
        final var all = new ArrayList<>(pq);
        all.sort(Comparator.comparingDouble(Entry::score).reversed().thenComparingInt(Entry::index));
        return all;
    }

    private record Entry(String line, double score, int index) {}

    private record ScopeSelection(String global, String project) {}

    public synchronized List<String> append(final boolean global, final List<String> facts) {
        if (facts == null || facts.isEmpty()) {
            return List.of();
        }
        final var file = global ? globalFile() : projectFile();
        try {
            Files.createDirectories(file.getParent());
            final var existing = new LinkedHashSet<String>(existingLines(file));
            final var added = new ArrayList<String>();
            for (final var fact : facts) {
                final var line = fact.strip();
                if (line.isEmpty()) {
                    continue;
                }
                // Long durable facts are wrapped across bullets rather than silently dropped, so no
                // extracted knowledge is lost to the 500-char line cap.
                final var wrapped = wrapLine(line, 500);
                for (final var piece : wrapped) {
                    final var normalized = piece.startsWith("-") ? piece.strip() : "- " + piece;
                    final var replaced = fuzzyReplace(existing, normalized);
                    if (replaced != null) {
                        // a near-duplicate bullet was superseded by the new wording
                        existing.remove(replaced);
                        existing.add(normalized);
                        added.add(normalized);
                    } else if (existing.add(normalized)) {
                        added.add(normalized);
                    }
                    // exact duplicate: already present, nothing to do
                }
            }
            if (!added.isEmpty()) {
                write(file, existing);
            }
            // deterministic floor: cap the store size per scope so memory cannot grow unbounded
            // even when the LLM-based compaction is disabled or below its size threshold.
            final var maxLines = configuration.memory().maxLines();
            if (maxLines > 0 && existing.size() > maxLines) {
                final var capped = new LinkedHashSet<String>();
                var i = 0;
                for (final var line : existing) {
                    if (i >= existing.size() - maxLines) {
                        capped.add(line);
                    }
                    i++;
                }
                write(file, capped);
            }
            return added;
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Stores a free-text memory block (eg. a compressed conversation snippet), split into bullet lines. */
    public synchronized List<String> appendText(final boolean global, final String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return append(
                global,
                text.lines().map(String::strip).filter(l -> !l.isEmpty()).toList());
    }

    /**
     * Near-duplicate detection: when a bullet already covers the new fact (normalized token
     * Dice similarity {@code >= 0.8} with at least 4 shared tokens), the new wording supersedes
     * the old one and the existing bullet is returned for replacement. This keeps the store from
     * growing with reformulations ("supersedes" observations, reworded decisions) while never
     * merging distinct facts, thanks to the conservative token-overlap floor.
     */
    private static String fuzzyReplace(final LinkedHashSet<String> existing, final String normalized) {
        final var newTokens = set(tokens(normalized));
        if (newTokens.size() < 4) {
            return null;
        }
        String best = null;
        double bestScore = 0;
        for (final var bullet : existing) {
            final var oldTokens = set(tokens(bullet));
            if (oldTokens.size() < 4 || Math.min(oldTokens.size(), newTokens.size()) < 4) {
                continue;
            }
            int common = 0;
            for (final var t : newTokens) {
                if (oldTokens.contains(t)) {
                    common++;
                }
            }
            if (common < 4) {
                continue;
            }
            // Dice coefficient on token sets
            final var dice = 2.0 * common / (oldTokens.size() + newTokens.size());
            if (dice >= 0.8 && dice > bestScore) {
                best = bullet;
                bestScore = dice;
            }
        }
        return best;
    }

    private static java.util.Set<String> set(final List<String> tokens) {
        return new java.util.HashSet<>(tokens);
    }

    /**
     * Replaces the whole memory content (used by the compactor): writes the given
     * bullet lines atomically (keeping the file header). The previous file is kept
     * as a {@code .bak} sibling so a bad rewrite can be restored.
     *
     * @return the number of bytes written, or -1 when the rewrite was rejected
     */
    public synchronized long replace(final boolean global, final List<String> bullets) {
        try {
            final var file = global ? globalFile() : projectFile();
            Files.createDirectories(file.getParent());
            if (Files.exists(file)) {
                Files.copy(
                        file,
                        file.resolveSibling(file.getFileName() + ".bak"),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            final var content = new StringBuilder(HEADER);
            bullets.stream()
                    .map(b -> b.startsWith("-") ? b : "- " + b)
                    .forEach(b -> content.append(b).append('\n'));
            final var tmp = file.resolveSibling(file.getFileName() + ".tmp-"
                    + Thread.currentThread().getName().hashCode());
            Files.writeString(tmp, content.toString());
            Files.move(
                    tmp,
                    file,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return content.length();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Drains and returns the facts appended since the last call (mid-turn memory_write diff).
     * Used by the volatile session tail so a memory refresh only re-sends what changed
     * instead of the whole section. Returns the facts in insertion order.
     */
    /**
     * Records the facts just written by {@code sessionId} so the next wire tail of that session
     * can re-send only what changed. Call from the session-aware execution path.
     */
    public void recordDiff(final String sessionId, final java.util.List<String> facts) {
        if (sessionId == null || sessionId.isBlank() || facts == null || facts.isEmpty()) {
            return;
        }
        final java.util.Deque<String> queue;
        synchronized (pendingDiffs) {
            queue = pendingDiffs.computeIfAbsent(sessionId, k -> new java.util.ArrayDeque<>());
        }
        synchronized (queue) {
            queue.addAll(facts);
        }
    }

    /** Drains and returns this session's pending memory diff (facts written since the last tail). */
    public java.util.List<String> drainPendingDiff(final String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return java.util.List.of();
        }
        final java.util.Deque<String> queue;
        synchronized (pendingDiffs) {
            queue = pendingDiffs.get(sessionId);
            if (queue == null) {
                return java.util.List.of();
            }
        }
        final var out = new java.util.ArrayList<String>();
        synchronized (queue) {
            String fact;
            while ((fact = queue.poll()) != null) {
                out.add(fact);
            }
            if (queue.isEmpty()) {
                synchronized (pendingDiffs) {
                    pendingDiffs.remove(sessionId);
                }
            }
        }
        return out;
    }

    private void write(final Path file, final LinkedHashSet<String> lines) throws IOException {
        final var content = new StringBuilder(HEADER);
        lines.forEach(l -> content.append(l).append('\n'));
        final var tmp = file.resolveSibling(
                file.getFileName() + ".tmp-" + Thread.currentThread().getName().hashCode());
        Files.createDirectories(file.getParent());
        Files.writeString(tmp, content.toString());
        try {
            Files.move(
                    tmp,
                    file,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (final java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (final java.io.IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
    }

    private List<String> existingLines(final Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                return List.of();
            }
            return Files.readAllLines(file).stream()
                    .map(String::strip)
                    .filter(l -> !l.isEmpty() && !l.startsWith("#") && !HEADER_LINE.equals(l))
                    .toList();
        } catch (final IOException e) {
            return List.of();
        }
    }

    private String read(final Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                return "";
            }
            return Files.readString(file);
        } catch (final IOException e) {
            return "";
        }
    }
}
