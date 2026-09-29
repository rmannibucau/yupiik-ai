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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.PriorityQueue;
import java.util.regex.Pattern;

@ApplicationScoped
public class MemoryStore {
    /** The header line of {@link #HEADER}: filtered out of the prompt sections (it is not a fact). */
    public static final String HEADER_LINE = "Durable facts, decisions and preferences. One bullet per line.";

    public static final String HEADER = "# Memory\n\n" + HEADER_LINE + "\n\n";

    private final CodingAgentConfiguration configuration;

    /**
     * Content fingerprint of the memory files, cached and invalidated on every write
     * ({@link #append} / {@link #appendText}). Lets the agent loop detect a mid-turn
     * {@code memory_write} without re-reading the files at every LLM round.
     */
    private volatile int contentVersion = -1;

    public MemoryStore(final CodingAgentConfiguration configuration) {
        this.configuration = configuration;
    }

    protected MemoryStore() {
        this.configuration = null;
    }

    /**
     * Cached fingerprint of the current memory content (global + project). Changes whenever
     * memory is written through this store; a never-written store is stable across calls.
     */
    public int version() {
        var v = contentVersion;
        if (v < 0) {
            v = java.util.Objects.hash(readGlobal(), readProject());
            contentVersion = v;
        }
        return v;
    }

    public Path globalFile() {
        return Path.of(configuration.resolveStorageHome(), "memory", "MEMORY.md");
    }

    public Path projectFile() {
        return Path.of(".", ".yca", "memory", "MEMORY.md");
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
        if (!configuration.memory().enabled()) {
            return "";
        }
        final var global = readGlobal();
        final var project = readProject();
        if (global.isBlank() && project.isBlank()) {
            return "";
        }
        final var max = Math.max(500, configuration.memory().maxChars());
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

    private static ScopeSelection select(
            final String globalText,
            final String projectText,
            final String globalRaw,
            final String projectRaw,
            final String prompt,
            final int max) {
        // Global facts are durable high-value directives: keep their HEAD (top lines), never tail.
        final var global = globalText.isBlank() ? globalText : bounded(globalText, max / 2, false);
        final var total = global.length() + (projectText.isBlank() ? 0 : Math.min(projectText.length(), max / 2));
        if (total + MARKUP_OVERHEAD <= max) {
            return new ScopeSelection(globalText, projectText);
        }
        // over budget: keep global whole (bounded, head) and rank project lines against the prompt,
        // keeping only the most relevant ones until fitting the shared budget (incl. markup overhead).
        if (prompt == null || prompt.isBlank() || projectText.isBlank()) {
            // no query to rank against: fall back to keeping the tail of project lines
            final var g = bounded(globalText, max / 2, false);
            final var p = projectText.isBlank() ? "" : bounded(projectText, max - g.length() - MARKUP_OVERHEAD, true);
            return new ScopeSelection(g, p);
        }
        final var promptTokens = tokens(prompt);
        final var projectLines = projectText.lines().filter(l -> !l.isBlank()).toList();
        final List<Entry> ranked = rank(projectLines, promptTokens);
        final var g = bounded(globalText, max / 2, false);
        var remaining = max - g.length() - MARKUP_OVERHEAD;
        final var projectSb = new StringBuilder();
        for (final var entry : ranked) {
            final var wouldAdd = projectSb.length()
                    + (projectSb.isEmpty() ? 0 : 1)
                    + entry.line().length();
            if (wouldAdd > remaining) {
                break;
            }
            if (!projectSb.isEmpty()) {
                projectSb.append('\n');
            }
            projectSb.append(entry.line());
        }
        return new ScopeSelection(g, projectSb.toString());
    }

    private static String bounded(final String content, final int limit, final boolean tail) {
        if (content == null || content.isBlank() || content.length() <= limit) {
            return content == null ? "" : content;
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

    private static List<Entry> rank(final List<String> lines, final List<String> promptTokens) {
        final var pq = new PriorityQueue<Entry>(Comparator.comparingDouble(Entry::score));
        for (final var line : lines) {
            final var lineTokens = tokens(line);
            if (lineTokens.isEmpty()) {
                continue;
            }
            // BM25-lite: tf of shared tokens weighted by inverse rarity across the corpus (idf approx = log(1+N/nT))
            final var counts = new LinkedHashMap<String, Integer>();
            for (final var t : lineTokens) {
                counts.merge(t, 1, Integer::sum);
            }
            double score = 0;
            for (final var t : promptTokens) {
                final var tf = counts.getOrDefault(t, 0);
                if (tf > 0) {
                    final var idf = Math.log1p((double) lines.size() / Math.max(1, tokenDocFrequency(lines, t)));
                    score += tf / (tf + 1.3) * idf;
                }
            }
            if (score > 0) {
                pq.offer(new Entry(line, score));
                if (pq.size() > 64) {
                    pq.poll();
                }
            }
        }
        final var all = new ArrayList<>(pq);
        all.sort(Comparator.comparingDouble(Entry::score).reversed());
        return all;
    }

    private static int tokenDocFrequency(final List<String> lines, final String token) {
        int n = 0;
        for (final var line : lines) {
            if (tokens(line).contains(token)) {
                n++;
            }
        }
        return n;
    }

    private record Entry(String line, double score) {}

    private record ScopeSelection(String global, String project) {}

    public synchronized List<String> append(final boolean global, final List<String> facts) {
        return append(global, facts, true);
    }

    synchronized List<String> append(final boolean global, final List<String> facts, final boolean touchVersion) {
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
                    if (existing.add(normalized)) {
                        added.add(normalized);
                    }
                }
            }
            if (!added.isEmpty()) {
                write(file, existing);
                if (touchVersion) {
                    contentVersion = -1; // next version() recomputes from the fresh files
                }
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
