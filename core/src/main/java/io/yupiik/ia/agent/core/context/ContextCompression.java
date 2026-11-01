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
package io.yupiik.ia.agent.core.context;

import io.yupiik.ia.agent.core.configuration.CompressConfiguration;
import io.yupiik.ia.agent.core.llm.Message;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * Wire-level, model-authored range compression, following opencode-dcp semantics:
 * the model references stable message ids, writes the replacement summary itself, and the
 * covered span is replaced with a {@code [Compressed conversation section @bN@]} block only in
 * outgoing requests. The session transcript itself stays append-only.
 *
 * <p>Compression is deliberately separate from automatic compaction: it is a view over the
 * conversation that keeps the prompt small in steady state, while compaction still guards the
 * hard model window (see {@link ContextManager}).
 *
 * <p>Concurrency: stateful operations are guarded by a {@link Lock}. The render-path scrubbing
 * ({@link #stripEchoedRefs}, {@link #withholdDanglingPrefix}) is lock-free so a UI thread never
 * blocks on an agent turn; it only reads the immutable values published through volatile fields,
 * never the working list the agent thread keeps mutating in place.
 */
public final class ContextCompression {
    public static final String BLOCK_MARKER = "compressedBlock";
    private static final Pattern BLOCK_REF = Pattern.compile("@b([1-9]\\d*)@");
    private static final Pattern MESSAGE_REF = Pattern.compile("@?(?:m)?(\\d+)@?");
    private static final Pattern PROTECT_TAG = Pattern.compile("<protect>(.*?)</protect>", Pattern.DOTALL);
    /** Echoed compact ref tags to remove from model output (strip-on-ingest defense). */
    private static final Pattern ECHOED_REF_LINE = Pattern.compile(
            "(?m)^[ \t]*+@(?:[1-9]\\d*|b[1-9]\\d*|blocked)@(?:[ \t]+\\[(?:low|medium|high)\\])?[ \t]*\\r?\\n");

    private static final Pattern ECHOED_REF_INLINE =
            Pattern.compile("(?<![ \t])[ \t]*+@(?:[1-9]\\d*|b[1-9]\\d*|blocked)@(?:[ \t]+\\[(?:low|medium|high)\\])?");
    /**
     * Glued seam-marker shape for the evidence-gated strip: an {@code @} at a word boundary
     * (not preceded by a letter/digit, so emails and identifiers like {@code a@b} are exempt),
     * optional blanks, then a letter-initial word that embeds a ref-id digit-run. The marker is
     * what flash-class models leak when they autocomplete a seam tag from the surrounding wire
     * content ("@EntCompactionTruncates10632" instead of "@10632@"); the word itself is real
     * content and must survive - only the {@code @} is removed.
     */
    private static final Pattern GLUED_SEAM_WORD = Pattern.compile("(?<![A-Za-z0-9])@([ \\t]*)([A-Za-z][A-Za-z0-9]*)");

    /**
     * Minimum digit-run length for the evidence-gated glued-seam strip: short runs ("step 12",
     * "issue #42") are far too common in real prose to gate on, while the observed leaks embed a
     * 3-5 digit ref id (125, 10632, 12504) and compaction refs are monotonically large in practice.
     */
    static final int MIN_GLUED_SEAM_DIGITS = 3;

    public enum Status {
        ACTIVE,
        SUPERSEDED,
        DECOMPRESSED,
        ABSORBED
    }

    /**
     * One compressed span. {@code startRef}/{@code endRef} are stable message references, not list
     * indexes, so appending messages never invalidates a block.
     */
    public record Block(
            String id,
            String topic,
            int startRef,
            int endRef,
            String summary,
            int summaryTokens,
            List<String> consumedBlockIds,
            Status status,
            String supersededBy,
            long createdAt) {}

    /** One model-requested range: inclusive stable message references plus the model-written summary. */
    public record Range(String startId, String endId, String summary) {}

    /** Result of a compress/decompress request, suitable to return to the model verbatim. */
    public record Result(boolean success, String message, String blockId) {}

    private final CompressConfiguration configuration;
    private final AtomicInteger nextRef = new AtomicInteger(1);
    private final AtomicInteger nextBlock = new AtomicInteger(1);
    private final Map<Message, Integer> refsByMessage = new IdentityHashMap<>();
    private final Map<Integer, Message> messagesByRef = new LinkedHashMap<>();
    private final List<Block> blocks = new ArrayList<>();
    /** Guards all mutable state below; reentrant because locked entry points call each other. */
    private final Lock lock = new ReentrantLock();
    /**
     * Live working list, owned by the agent thread. Kept by reference on purpose: the loop appends
     * to it in place between publishes, so it must never be iterated from another thread.
     */
    private volatile List<Message> published = List.of();
    /**
     * Complete seam tokens ({@code @N@}, {@code @bN@}, {@code @blocked@}) carried by the wire built
     * by the last {@link #renderRefs} call. Immutable and replaced wholesale, so render surfaces can
     * read it from any thread without touching {@link #published}.
     */
    private volatile Set<String> renderedRefs = Set.of("");

    public ContextCompression(final CompressConfiguration configuration) {
        this.configuration = configuration;
    }

    public boolean enabled() {
        return configuration != null && configuration.enabled();
    }

    /**
     * Highest reference that was ever assigned to a message (monotonic: refs are assigned once and
     * never reused, see {@link #refOf}), plus one. Every id in {@code [1, maxAssignedRef())} has
     * been assigned at some point; {@link #publish} pruning shrinks the live mapping but never this
     * high-water mark, so it is the complete, bounded evidence set for the glued-seam strip.
     * Lock-free on purpose: render surfaces may call it from any thread.
     */
    public int maxAssignedRef() {
        return nextRef.get();
    }

    public CompressConfiguration configuration() {
        return configuration;
    }

    /**
     * Publishes the live working list so the tool can resolve the references it rendered. The list
     * is kept by reference: the agent thread owns it and keeps appending in place, so cross-thread
     * readers must use {@link #renderedRefs} (refreshed by the next {@link #renderRefs}) instead.
     */
    public void publish(final List<Message> messages) {
        lock.lock();
        try {
            this.published = messages == null ? List.of() : messages;
            // the wire - hence the live seam set - is rebuilt by the next renderRefs; drop the
            // previous tags now so a ref that left the conversation can never be withheld
            this.renderedRefs = Set.of("");
            // memory bound: refs are assigned once and never reused, so pruning a map entry whose
            // Message left the wire only frees the retained object - it never renumbers anything
            // (see refOf). This mirrors ConversationTranscript.trim()'s history head-truncation.
            final var keep = java.util.Collections.newSetFromMap(new IdentityHashMap<Message, Boolean>());
            for (final var message : published) {
                if (message != null) {
                    keep.add(message);
                }
            }
            refsByMessage.keySet().retainAll(keep);
            messagesByRef.clear();
            for (final var entry : refsByMessage.entrySet()) {
                messagesByRef.put(entry.getValue(), entry.getKey());
            }
            // drop definitively-absorbed blocks so the blocks list stays bounded; ACTIVE/SUPERSEDED/
            // DECOMPRESSED blocks are still referenced by tools and stay.
            blocks.removeIf(b -> b.status() == Status.ABSORBED);
        } finally {
            lock.unlock();
        }
    }

    public List<Message> published() {
        lock.lock();
        try {
            return published;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Stable reference of a message ({@code @1@}, {@code @2@}, ...), assigned once per message
     * identity and never reused. References start at 1; the leading system prompt is never tagged.
     */
    public int refOf(final Message message) {
        lock.lock();
        try {
            final var existing = refsByMessage.get(message);
            if (existing != null) {
                return existing;
            }
            final var ref = nextRef.getAndIncrement();
            refsByMessage.put(message, ref);
            messagesByRef.put(ref, message);
            return ref;
        } finally {
            lock.unlock();
        }
    }

    /** The message a reference points to, or null when unknown. */
    public Message messageOf(final int ref) {
        lock.lock();
        try {
            return messagesByRef.get(ref);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns a copy of the wire messages with a small trailing {@code @N@} tag so the model
     * can name exact boundaries. System messages, synthetic compression blocks and markers are not
     * tagged; the tags are deterministic for a given message identity, so prompt prefixes stay stable.
     *
     * <p>The tag deliberately uses the compact {@code @N@} seam instead of a bracket-like
     * {@code [ref @N@]} line: bracket-shaped markers read as conversational text that flash-class
     * models tend to mirror into their own output (echoing them back into the transcript), while a
     * bare {@code @N@} marker has no natural-language shape to imitate, which strongly reduces the
     * echoed-refs failure mode (see {@link #stripEchoedRefs}).
     *
     * <p>Also publishes the exact seam tokens carried by the returned wire into
     * {@link #renderedRefs}, so render-time scrubbing keys off real refs without walking the live
     * working list.
     */
    public List<Message> renderRefs(final List<Message> messages) {
        lock.lock();
        try {
            if (!enabled() || messages == null) {
                return messages;
            }
            final var out = new ArrayList<Message>(messages.size());
            final var refs = new HashSet<String>();
            refs.add("");
            for (var i = 0; i < messages.size(); i++) {
                final var message = messages.get(i);
                if (i == 0
                        || "system".equals(message.role())
                        || isCompressedBlock(message)
                        || isVolatileTail(message)
                        || message.content() == null
                        || !("user".equals(message.role())
                                || "assistant".equals(message.role())
                                || "tool".equals(message.role()))) {
                    out.add(message);
                    continue;
                }
                final var content = message.content() instanceof String s ? s : String.valueOf(message.content());
                // compact seam: "@N@" rather than "[ref @N@]" - no bracket shape for the model to copy
                final var ref = refOf(message);
                refs.add("@" + ref + "@");
                final var tagged = content + "\n@" + ref + "@";
                out.add(new Message(
                        message.role(),
                        tagged,
                        message.name(),
                        message.toolCalls(),
                        message.toolCallId(),
                        message.others()));
            }
            renderedRefs = Set.copyOf(refs);
            return out;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Applies every active block to {@code messages}: each covered span becomes one synthetic user
     * message carrying the model's summary. The original list is never mutated.
     */
    public List<Message> rewrite(final List<Message> messages) {
        lock.lock();
        try {
            if (!enabled() || blocks.isEmpty()) {
                return messages;
            }
            final var active = new ArrayList<Block>();
            for (final var block : blocks) {
                if (block.status() == Status.ACTIVE) {
                    active.add(block);
                }
            }
            if (active.isEmpty()) {
                return messages;
            }
            active.sort(Comparator.comparingInt(Block::startRef));
            final var out = new ArrayList<Message>(messages.size());
            var index = 0;
            for (final var block : active) {
                final var start = indexOf(messages, block.startRef(), index);
                final var end = indexOf(messages, block.endRef(), start);
                if (start < 0 || end < 0 || end < start) {
                    // the block boundaries are no longer in this list (e.g. a core compaction rewrote it)
                    markAbsorbed(block);
                    continue;
                }
                if (start < index) {
                    // overlapping active block: keep the earliest one, mark this one absorbed
                    markAbsorbed(block);
                    continue;
                }
                out.addAll(messages.subList(index, start));
                out.add(new Message(
                        "user",
                        "[Compressed conversation section @" + block.id() + "@]\n" + block.summary(),
                        null,
                        null,
                        null,
                        Map.of(BLOCK_MARKER, block.id())));
                index = end + 1;
            }
            if (index < messages.size()) {
                out.addAll(messages.subList(index, messages.size()));
            }
            return out;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Compresses one or more non-overlapping closed ranges. A range that does not already start on a
     * user message is snapped forward to the nearest following user ref, and an end that is not at a
     * turn boundary is snapped backward to the nearest preceding boundary; a range the snaps cannot
     * rescue (start/end collide) is dropped, always returning a success with a corrective nudge to
     * the model rather than a hard rejection.
     */
    public Result compress(final String topic, final List<Range> ranges) {
        lock.lock();
        try {
            return compressLocked(topic, ranges);
        } finally {
            lock.unlock();
        }
    }

    /** Runs one compression request while {@link #lock} is held. */
    private Result compressLocked(final String topic, final List<Range> ranges) {
        if (!enabled()) {
            return new Result(false, "Compression is disabled (yca.compression.enabled=false).", null);
        }
        if (ranges == null || ranges.isEmpty()) {
            return new Result(false, "content must contain at least one {startId, endId, summary} range.", null);
        }
        final var resolved = new ArrayList<ResolvedRange>(ranges.size());
        final var nudges = new ArrayList<String>();
        final var skipped = new ArrayList<String>();
        for (final var range : ranges) {
            if (range == null || range.startId() == null || range.endId() == null) {
                skipped.add("range without both startId and endId (message refs like @12@)");
                continue;
            }
            final var startRef = parseRef(range.startId());
            final var endRef = parseRef(range.endId());
            if (startRef <= 0 || endRef <= 0) {
                skipped.add("range " + range.startId() + ".." + range.endId() + ": invalid message ref");
                continue;
            }
            final var start = indexOf(published, startRef, 0);
            final var end = indexOf(published, endRef, start);
            if (start < 0 || end < 0 || end < start) {
                skipped.add("range " + range.startId() + ".." + range.endId()
                        + ": not in the live conversation or is reversed");
                continue;
            }
            if (start == 0
                    || (published.get(start) != null
                            && "system".equals(published.get(start).role()))) {
                skipped.add(
                        "range " + range.startId() + ".." + range.endId() + ": the system prompt cannot be compressed");
                continue;
            }
            if (isVolatileTail(published.get(start)) || isVolatileTail(published.get(end))) {
                skipped.add(
                        "range " + range.startId() + ".." + range.endId() + ": touches the live session-state tail");
                continue;
            }
            // Snap the requested boundaries onto valid, safe ones instead of hard-rejecting a
            // sloppy flash-class boundary choice. A non-user START moves forward to the nearest
            // following user ref (the leading assistant/tool span is left verbatim - never user
            // content, and never detaching a tool result from its assistant caller); an END that is
            // not a turn boundary moves backward to the nearest preceding one. If the two snaps
            // collide (start >= end) or leave no valid range, the entry is dropped and the model is
            // nudged - never a hard failure.
            int effectiveStart = start;
            int effectiveEnd = end;
            final var startRole = published.get(start).role();
            if (!"user".equals(startRole)) {
                for (var i = start + 1; i <= end; i++) {
                    if ("user".equals(published.get(i).role())) {
                        effectiveStart = i;
                        break;
                    }
                }
            }
            if (!turnBoundary(end)) {
                for (var i = end - 1; i >= effectiveStart; i--) {
                    if (turnBoundary(i)) {
                        effectiveEnd = i;
                        break;
                    }
                }
            }
            final var drop = !"user".equals(published.get(effectiveStart).role())
                    || !turnBoundary(effectiveEnd)
                    || effectiveStart >= effectiveEnd;
            if (drop) {
                skipped.add("range " + range.startId() + ".." + range.endId()
                        + " cannot be bounded to a user-message start and a turn-boundary end"
                        + " (the requested span collapses)");
                continue;
            }
            final var effectiveStartRef = refOf(published.get(effectiveStart));
            final var effectiveEndRef = refOf(published.get(effectiveEnd));
            if (effectiveStartRef != startRef) {
                nudges.add("range " + range.startId() + ".." + range.endId() + " adjusted: start ref "
                        + range.startId() + " is " + startRole + "; compressed from @" + effectiveStartRef
                        + "@ instead - always start ranges on a user-message ref; if unsure, split"
                        + " into small disjoint ranges.");
            }
            if (effectiveEndRef != endRef) {
                nudges.add("range " + range.startId() + ".." + range.endId() + " adjusted: end ref "
                        + range.endId() + " was not at a turn boundary; compressed up to @" + effectiveEndRef
                        + "@ instead. End ranges just before the next user message (a turn boundary).");
            }
            if (configuration.turnProtection() && configuration.turnProtectionTurns() > 0) {
                final var protectedFrom = protectedTurnStart(configuration.turnProtectionTurns());
                if (protectedFrom >= 0 && effectiveEnd >= protectedFrom) {
                    skipped.add("range " + range.startId() + ".." + range.endId()
                            + " touches the last " + configuration.turnProtectionTurns()
                            + " protected user turn(s) (turnProtection)");
                    continue;
                }
            }
            if (overlapsActive(effectiveStartRef, effectiveEndRef)) {
                // a new range may cover an existing ACTIVE block only when it fully contains it AND
                // the summary summons it through its @bN@ placeholder - the block is then consumed
                // (superseded) instead of being applied twice by rewrite()
                final var overlapped = activeOverlappingBlocks(effectiveStartRef, effectiveEndRef);
                final var referenced = blockRefsIn(range.summary());
                final var rejected = overlapped.stream()
                        .filter(block -> block.startRef() < effectiveStartRef || block.endRef() > effectiveEndRef)
                        .findFirst()
                        .orElse(overlapped.stream()
                                .filter(block -> !referenced.contains(block.id()))
                                .findFirst()
                                .orElse(null));
                if (rejected != null) {
                    skipped.add("range " + range.startId() + ".." + range.endId()
                            + " overlaps active block @b" + rejected.id()
                            + "@ which must be fully contained by the new range and referenced"
                            + " through its @bN@ placeholder in the summary");
                    continue;
                }
            }
            if (resolved.stream()
                    .anyMatch(
                            previous -> effectiveStartRef <= previous.endRef && effectiveEndRef >= previous.startRef)) {
                final var previousFirst = resolved.stream()
                        .filter(previous ->
                                effectiveStartRef <= previous.endRef && effectiveEndRef >= previous.startRef)
                        .findFirst()
                        .orElseThrow();
                return new Result(
                        false,
                        "Ranges @" + range.startId() + "@..@" + range.endId() + "@ and @"
                                + previousFirst.startRef() + "@..@" + previousFirst.endRef()
                                + "@ overlap, and overlapping ranges cannot be applied deterministically."
                                + " Send disjoint small ranges as one array instead, or drop the smaller"
                                + " overlap; nothing was compressed.",
                        null);
            }
            resolved.add(new ResolvedRange(
                    effectiveStartRef, effectiveEndRef, range.summary() == null ? "" : range.summary()));
        }

        final var created = new ArrayList<String>();
        for (final var range : resolved) {
            final var blockId = "b" + nextBlock.getAndIncrement();
            final var consumed = new ArrayList<String>();
            final var expanded = expandPlaceholders(range.summary(), range.startRef, range.endRef, blockId, consumed);
            final var summary = appendProtected(expanded, range.startRef, range.endRef);
            final var block = new Block(
                    blockId,
                    topic == null || topic.isBlank() ? "compressed range" : topic.strip(),
                    range.startRef,
                    range.endRef,
                    summary,
                    TokenEstimator.estimate(summary),
                    List.copyOf(consumed),
                    Status.ACTIVE,
                    null,
                    System.currentTimeMillis());
            blocks.add(block);
            created.add("@" + blockId + "@");
        }
        final var base = created.isEmpty()
                ? "No ranges were compressed."
                : "Compressed " + created.size() + " range(s): " + String.join(", ", created)
                        + ". The transcript is unchanged; these blocks are now applied to outgoing requests.";
        final var parts = new ArrayList<String>(2);
        parts.add(base);
        if (!skipped.isEmpty()) {
            parts.add("skipped: " + String.join("; ", skipped)
                    + ". To free more closed history, send several small disjoint ranges as one array,"
                    + " each starting on a user-message ref and ending just before the next user message.");
        }
        if (!nudges.isEmpty()) {
            parts.add(String.join("\n", nudges));
        }
        final var message = parts.size() == 1 ? parts.get(0) : String.join("\n", parts);
        return new Result(
                true, message, created.isEmpty() ? null : created.get(0).replace("@", ""));
    }

    /** Re-enables a block (only while it is still active; superseded/absorbed blocks are gone). */
    public Result decompress(final String blockId) {
        lock.lock();
        try {
            if (!enabled()) {
                return new Result(false, "Compression is disabled (yca.compression.enabled=false).", null);
            }
            final var id = normalizeBlockId(blockId);
            final var block =
                    blocks.stream().filter(b -> b.id().equals(id)).findFirst().orElse(null);
            if (block == null) {
                return new Result(false, "Unknown compressed block @" + id + "@.", null);
            }
            if (block.status() != Status.ACTIVE) {
                return new Result(
                        false,
                        "Block @" + id + "@ is " + block.status().name().toLowerCase() + " and cannot be decompressed.",
                        null);
            }
            replaceBlock(
                    block,
                    new Block(
                            block.id(),
                            block.topic(),
                            block.startRef(),
                            block.endRef(),
                            block.summary(),
                            block.summaryTokens(),
                            block.consumedBlockIds(),
                            Status.DECOMPRESSED,
                            null,
                            block.createdAt()));
            return new Result(true, "Decompressed @" + id + "@: its original messages are sent again.", id);
        } finally {
            lock.unlock();
        }
    }

    /** Marks every active block as absorbed, e.g. after a core compaction replaced the history. */
    public void absorbAll() {
        lock.lock();
        try {
            if (blocks.isEmpty()) {
                return;
            }
            for (var i = 0; i < blocks.size(); i++) {
                final var block = blocks.get(i);
                if (block.status() == Status.ACTIVE) {
                    blocks.set(
                            i,
                            new Block(
                                    block.id(),
                                    block.topic(),
                                    block.startRef(),
                                    block.endRef(),
                                    block.summary(),
                                    block.summaryTokens(),
                                    block.consumedBlockIds(),
                                    Status.ABSORBED,
                                    null,
                                    block.createdAt()));
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /** Estimated tokens the active blocks remove from the live conversation. */
    public int savedTokens(final List<Message> messages) {
        lock.lock();
        try {
            if (!enabled() || blocks.isEmpty() || messages == null) {
                return 0;
            }
            var saved = 0;
            for (final var block : blocks) {
                if (block.status() != Status.ACTIVE) {
                    continue;
                }
                final var start = indexOf(messages, block.startRef(), 0);
                final var end = indexOf(messages, block.endRef(), start);
                if (start < 0 || end < start) {
                    continue;
                }
                var covered = 0;
                for (var i = start; i <= end; i++) {
                    covered += estimate(messages.get(i));
                }
                saved += Math.max(0, covered - block.summaryTokens());
            }
            return saved;
        } finally {
            lock.unlock();
        }
    }

    /** Effective prompt size after active compression: the raw estimate minus {@link #savedTokens}. */
    public int effectiveTokens(final List<Message> messages, final int rawTokens) {
        lock.lock();
        try {
            return Math.max(0, rawTokens - savedTokens(messages));
        } finally {
            lock.unlock();
        }
    }

    public List<Block> blocks() {
        lock.lock();
        try {
            return List.copyOf(blocks);
        } finally {
            lock.unlock();
        }
    }

    public int summaryTokens() {
        lock.lock();
        try {
            return blocks.stream()
                    .filter(b -> b.status() == Status.ACTIVE)
                    .mapToInt(Block::summaryTokens)
                    .sum();
        } finally {
            lock.unlock();
        }
    }

    /**
     * The resolved compression guidance targets for a given provider window, shared by the loop
     * (context-usage line, nudges) and the {@code compress} tool (status). The base {@code min} /
     * {@code max} are the configured absolute or percentage limits; when {@code summaryBuffer} is
     * enabled the hard target is extended by the active summary tokens, so an already-condensed
     * working set is not immediately hammered by the strong nudge. The extension is purely a
     * guidance target: {@link #effectiveTokens}/{@link #savedTokens} still count the summaries
     * toward real context usage and the compaction guard is untouched, so capacity accounting
     * never weakens.
     */
    public Targets targets(final int window) {
        lock.lock();
        try {
            final var summary = summaryTokens();
            final var baseMin = configuration == null
                    ? 0
                    : configuration.resolveLimit(configuration.minContextLimit(), window, 50000);
            final var baseMax = configuration == null
                    ? 0
                    : configuration.resolveLimit(configuration.maxContextLimit(), window, 100000);
            final var buffered = configuration != null && configuration.summaryBuffer() && summary > 0;
            return new Targets(window, baseMin, baseMax, buffered ? baseMax + summary : baseMax, summary, buffered);
        } finally {
            lock.unlock();
        }
    }

    /** Resolved compression guidance targets (see {@link #targets(int)}). */
    public record Targets(
            int window, int baseMin, int baseMax, int effectiveMax, int summaryTokens, boolean summaryBuffer) {}

    // --- internals ---------------------------------------------------------------------------

    private record ResolvedRange(int startRef, int endRef, String summary) {}

    private String expandPlaceholders(
            final String summary,
            final int startRef,
            final int endRef,
            final String newBlockId,
            final List<String> consumed) {
        final var matcher = BLOCK_REF.matcher(summary == null ? "" : summary);
        final var out = new StringBuilder();
        while (matcher.find()) {
            final var id = "b" + matcher.group(1);
            final var block =
                    blocks.stream().filter(b -> b.id().equals(id)).findFirst().orElse(null);
            if (block == null) {
                matcher.appendReplacement(out, "");
                // keep the literal when unknown: the caller validates refs, so this is only defensive
                out.append(matcher.group());
                continue;
            }
            matcher.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(stripHeader(block.summary())));
            if (block.status() == Status.ACTIVE && block.startRef() >= startRef && block.endRef() <= endRef) {
                consumed.add(block.id());
                replaceBlock(
                        block,
                        new Block(
                                block.id(),
                                block.topic(),
                                block.startRef(),
                                block.endRef(),
                                block.summary(),
                                block.summaryTokens(),
                                block.consumedBlockIds(),
                                Status.SUPERSEDED,
                                newBlockId,
                                block.createdAt()));
            }
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private String appendProtected(final String summary, final int startRef, final int endRef) {
        final var start = indexOf(published, startRef, 0);
        final var end = indexOf(published, endRef, start);
        if (start < 0 || end < start) {
            return summary;
        }
        final var out = new StringBuilder(summary == null ? "" : summary);
        final var toolNames = Message.toolNames(published);
        for (var i = start; i <= end; i++) {
            final var message = published.get(i);
            final var content = message.content() instanceof String s ? s : String.valueOf(message.content());
            if (configuration.protectUserMessages() && "user".equals(message.role()) && !isVolatileTail(message)) {
                out.append("\n\n[protected user message]\n").append(content);
            }
            if (configuration.protectTags()) {
                final var matcher = PROTECT_TAG.matcher(content);
                while (matcher.find()) {
                    out.append("\n\n[protected tag]\n").append(matcher.group(1));
                }
            }
            final var toolName = message.name() != null ? message.name() : toolNames.get(message.toolCallId());
            if ("tool".equals(message.role())
                    && toolName != null
                    && configuration.protectedTools() != null
                    && configuration.protectedTools().contains(toolName)) {
                out.append("\n\n[protected tool output: ")
                        .append(toolName)
                        .append("]\n")
                        .append(content);
            }
        }
        return out.toString();
    }

    private boolean overlapsActive(final int startRef, final int endRef) {
        return blocks.stream()
                .filter(b -> b.status() == Status.ACTIVE)
                .anyMatch(b -> startRef <= b.endRef() && endRef >= b.startRef());
    }

    private List<Block> activeOverlappingBlocks(final int startRef, final int endRef) {
        return blocks.stream()
                .filter(b -> b.status() == Status.ACTIVE && startRef <= b.endRef() && endRef >= b.startRef())
                .toList();
    }

    private static List<String> blockRefsIn(final String summary) {
        final var matcher = BLOCK_REF.matcher(summary == null ? "" : summary);
        final var out = new ArrayList<String>();
        while (matcher.find()) {
            out.add("b" + matcher.group(1));
        }
        return out;
    }

    private int protectedTurnStart(final int turns) {
        var remaining = turns;
        for (var i = published.size() - 1; i >= 0; i--) {
            final var message = published.get(i);
            if ("user".equals(message.role()) && !isVolatileTail(message)) {
                remaining--;
                if (remaining <= 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private void markAbsorbed(final Block block) {
        replaceBlock(
                block,
                new Block(
                        block.id(),
                        block.topic(),
                        block.startRef(),
                        block.endRef(),
                        block.summary(),
                        block.summaryTokens(),
                        block.consumedBlockIds(),
                        Status.ABSORBED,
                        null,
                        block.createdAt()));
    }

    private void replaceBlock(final Block old, final Block replacement) {
        for (var i = 0; i < blocks.size(); i++) {
            if (blocks.get(i) == old || blocks.get(i).id().equals(old.id())) {
                blocks.set(i, replacement);
                return;
            }
        }
    }

    private int indexOf(final List<Message> messages, final int ref, final int from) {
        // refs are resolved through the published identity map, so a block always points at the
        // exact message it was created from, never at a same-looking later message
        if (messages == null) {
            return -1;
        }
        final var target = messagesByRef.get(ref);
        if (target == null) {
            return -1;
        }
        for (var i = Math.max(0, from); i < messages.size(); i++) {
            if (messages.get(i) == target) {
                return i;
            }
        }
        return -1;
    }

    private static int parseRef(final String value) {
        if (value == null) {
            return -1;
        }
        final var matcher = MESSAGE_REF.matcher(value.strip());
        return matcher.matches() ? Integer.parseInt(matcher.group(1)) : -1;
    }

    private static String normalizeBlockId(final String value) {
        if (value == null) {
            return "";
        }
        var id = value.strip();
        if (id.startsWith("@")) {
            id = id.substring(1);
        }
        if (id.endsWith("@")) {
            id = id.substring(0, id.length() - 1);
        }
        return id.startsWith("b") ? id : "b" + id;
    }

    private static String stripHeader(final String summary) {
        if (summary == null) {
            return "";
        }
        final var marker = "[Compressed conversation section";
        if (summary.startsWith(marker)) {
            final var newline = summary.indexOf('\n');
            return newline < 0 ? "" : summary.substring(newline + 1);
        }
        return summary;
    }

    private static boolean isVolatileTail(final Message message) {
        return message != null
                && message.others() != null
                && Boolean.TRUE.equals(message.others().get("volatileTail"));
    }

    /**
     * True when the message at {@code index} is a clean end-of-turn boundary: there is nothing after
     * it, the follower is the start of the next user turn, or the follower is a synthetic tail. A
     * volatile-tail message itself is never a boundary (it cannot be compressed).
     */
    private boolean turnBoundary(final int index) {
        if (index < 0 || index >= published.size()) {
            return false;
        }
        if (isVolatileTail(published.get(index))) {
            return false;
        }
        return index + 1 >= published.size()
                || isVolatileTail(published.get(index + 1))
                || "user".equals(published.get(index + 1).role());
    }

    private static boolean isCompressedBlock(final Message message) {
        return message != null && message.others() != null && message.others().get(BLOCK_MARKER) != null;
    }

    private static int estimate(final Message message) {
        var total = 8 + TokenEstimator.estimate(message.role());
        total += TokenEstimator.estimate(message.content() instanceof String s ? s : String.valueOf(message.content()));
        total += TokenEstimator.estimate(message.name());
        total += TokenEstimator.estimate(message.toolCallId());
        if (message.toolCalls() != null) {
            for (final var call : message.toolCalls()) {
                total += TokenEstimator.estimate(
                        call.function() == null ? null : call.function().name());
                total += TokenEstimator.estimate(
                        call.function() == null ? null : call.function().arguments());
            }
        }
        return total;
    }

    /** @return true when {@code message} is one of the synthetic compressed blocks. */
    public static boolean isCompressedBlockMessage(final Message message) {
        return isCompressedBlock(message);
    }

    /**
     * Removes echoed compact {@code @N@} ref tags from model-generated text (assistant replies or
     * tool output). This is the strip-on-ingest defense: even a model that violates the "do not
     * output the injected markers" instruction cannot leak them into the persisted transcript.
     * Mirrors the compact-format scrub of the reference DCP implementation.
     *
     * @param text text to clean, may be null.
     * @return the cleaned text, or null when the input was null.
     */
    public String stripEchoedRefs(final String text) {
        if (text == null) {
            return null;
        }
        // whole echo lines first (the observed failure mode was a wall of consecutive tag lines),
        // then any inline tags that survived (e.g. a tag glued to prose on one line), then the
        // evidence-gated glued-marker strip: a flash-class model autocompleting a leaked '@' with
        // the surrounding wire content produces "@EntCompactionTruncates10632" instead of
        // "@10632@"; only the '@' is removed, the word (real content) survives.
        final var afterLineInline = ECHOED_REF_INLINE
                .matcher(ECHOED_REF_LINE.matcher(text).replaceAll(""))
                .replaceAll("");
        final var matcher = GLUED_SEAM_WORD.matcher(afterLineInline);
        final var maxRef = maxAssignedRef();
        final var sb = new StringBuilder(afterLineInline.length());
        var last = 0;
        var stripped = false;
        while (matcher.find()) {
            if (gluedSeamWordMatchesAssignedRef(matcher.group(2), maxRef)) {
                // keep the word (it is real content), drop only the leaked '@' but preserve the
                // separating blanks ("@ man12504que" -> " man12504que")
                sb.append(afterLineInline, last, matcher.start())
                        .append(afterLineInline, matcher.start() + 1, matcher.end());
                last = matcher.end();
                stripped = true;
            }
        }
        if (!stripped) {
            return afterLineInline;
        }
        return sb.append(afterLineInline, last, afterLineInline.length()).toString();
    }

    /**
     * True when the word right after a leaked {@code @} embeds a digit-run of at least
     * {@link #MIN_GLUED_SEAM_DIGITS} digits that equals an ever-assigned ref id. Any digit-run of
     * the word is considered, so both the suffix form ("@EntCompactionTruncates10632") and the
     * mid-word form ("@ man12504que") are caught; shorter runs ("@step12", "@m125") are far too
     * common in real prose to gate on.
     */
    private static boolean gluedSeamWordMatchesAssignedRef(final String word, final int maxRef) {
        var i = 0;
        while (i < word.length()) {
            if (Character.isDigit(word.charAt(i))) {
                final var start = i;
                while (i < word.length() && Character.isDigit(word.charAt(i))) {
                    i++;
                }
                final var digits = word.substring(start, i);
                if (digits.length() >= MIN_GLUED_SEAM_DIGITS) {
                    try {
                        final var ref = Integer.parseInt(digits);
                        if (ref >= 1 && ref < maxRef) {
                            return true;
                        }
                    } catch (final NumberFormatException nfe) {
                        // over-long digit-run (e.g. a 28-digit pinned token) can never be a ref
                    }
                }
            } else {
                i++;
            }
        }
        return false;
    }

    /**
     * Render-time view of {@code text} that, in addition to stripping complete echoed seams, also
     * hides the bare-anchor lines a flash-class model emits as partial echoes (e.g. {@code @1290} or
     * {@code @b7} without their closing {@code @}) - whether trailing or frozen mid-block once a
     * tool block follows. Runs on a copy only - the underlying buffer stays raw, so
     * {@code flushThinking()}/{@code flushPending()} still assemble the full text and a completed
     * tag is stripped normally.
     *
     * <p>This is deliberately <em>shape</em>-driven, not evidence-gated against {@link #renderedRefs}:
     * a leaked anchor always refers to a ref the current wire no longer renders (the wire moves on as
     * compaction happens, or the message left it), so a live-ref whitelist would leave exactly the
     * anchors this surface exists to hide. The anchor shape is narrow enough to be safe: a line whose
     * trimmed content starts with {@code @} followed by a short token (1..24 chars) that is all
     * digits, {@code b} + digits, or the literal {@code blocked}. Real markdown lines (@user,
     * @RequestMapping), lone {@code @}, over-long tokens, inline/glued fragments ("answer@1") and
     * fenced prose are untouched.
     *
     * <p>Lock-free on purpose: it only reads the immutable {@link #renderedRefs} snapshot (or
     * nothing), never the agent-owned {@link #published} list.
     */
    public String withholdDanglingPrefix(final String text) {
        if (text == null) {
            return null;
        }
        return stripBareAnchorLines(stripEchoedRefs(text));
    }

    /**
     * Drops every line that looks like a leaked seam-anchor fragment anywhere in the text - not just
     * at the tail, so a fragment frozen mid-block (followed by a tool card) is still hidden.
     * Extremely cheap on purpose: one manual pass over the existing {@code \n} spans, no auxiliary
     * matcher objects, no per-line regex runs.
     */
    private static String stripBareAnchorLines(final String text) {
        final var sb = new StringBuilder(text.length());
        var lineStart = 0;
        while (lineStart <= text.length()) {
            final var newline = text.indexOf('\n', lineStart);
            final var lineEnd = newline < 0 ? text.length() : newline;
            final var line = text.substring(lineStart, lineEnd);
            if (!isBareSeamAnchorLine(line)) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(line);
            }
            if (newline < 0) {
                break;
            }
            lineStart = newline + 1;
        }
        return sb.toString();
    }

    /** Cheap shape test for a leaked seam-anchor line: see {@link #withholdDanglingPrefix}. */
    private static boolean isBareSeamAnchorLine(final String line) {
        var i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t' || line.charAt(i) == '\r')) {
            i++; // leading blanks (and CR before LF) are display padding, never part of the token
        }
        if (i >= line.length() || line.charAt(i) != '@') {
            return false;
        }
        i++;
        final var tokenStart = i;
        while (i < line.length() && Character.isLetterOrDigit(line.charAt(i))) {
            i++;
        }
        final var tokenEnd = i;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t' || line.charAt(i) == '\r')) {
            i++; // trailing blanks are display padding too
        }
        if (i != line.length()) {
            return false; // anything else on the line (prose, punctuation) means real content
        }
        final var token = line.substring(tokenStart, tokenEnd);
        if (token.isEmpty()) {
            return false;
        }
        // value-based bound instead of the historical length carve-out: refs are ints, so a digit
        // run longer than 10 (Integer.MAX_VALUE) can never be a real ref - the pinned 28-digit
        // '@1234567890123456789012345678' shape survives *because it is not a ref*, exactly like a
        // 28-char arbitrary token used to. 'blocked' (7) and 'b'+10 digits (11) still fit.
        final var digits = token.charAt(0) == 'b' ? token.substring(1) : token;
        if (token.equals("blocked")) {
            return true;
        }
        if (digits.isEmpty() || digits.length() > 10 || !isAllDigits(digits)) {
            return false;
        }
        return token.charAt(0) == 'b' || isAllDigits(token);
    }

    private static boolean isAllDigits(final String token) {
        for (var i = 0; i < token.length(); i++) {
            if (!Character.isDigit(token.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
