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
package io.yupiik.ia.agent.core.tools.builtin;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.context.ContextCompression;
import io.yupiik.ia.agent.core.context.ContextView;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.Schemas;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.tools.ToolResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Model-driven context compression, following opencode-dcp semantics: the model names a closed
 * range of messages by their stable {@code @N@} references and writes the replacement summary
 * itself. The covered span is only rewritten in outgoing requests, so the session transcript stays
 * append-only and the work remains inspectable.
 *
 * <p>{@code status} reports both the raw and compressed context sizes; {@code decompress} restores
 * a block; {@code compact} remains the manual escape hatch for a whole-context summary.
 */
@ApplicationScoped
public class ManageContextTool implements EmbeddedTool {
    /** Usage floor (in percent of the usable window) under which a legacy prune request is refused. */
    static final double ACTION_FLOOR_PERCENT = 30.0;

    private final CodingAgentConfiguration configuration;
    private final JsonMapper mapper;

    public ManageContextTool(final CodingAgentConfiguration configuration, final JsonMapper mapper) {
        this.configuration = configuration;
        this.mapper = mapper;
    }

    protected ManageContextTool() {
        this.configuration = null;
        this.mapper = null;
    }

    @Override
    public String name() {
        return "compress";
    }

    @Override
    public boolean usable() {
        return compressionEnabled();
    }

    @Override
    public String description() {
        return "Manage the conversation context. action=compress (default) summarizes genuinely closed"
                + " conversation ranges: each message sent to the provider ends with a stable compact"
                + " seam tag (an at-sign, the message number, an at-sign); pass topic plus"
                + " content=[{startId,endId,summary}] with ranges that start on a user message"
                + " and end at a turn boundary. The seam tags are environment-injected metadata and"
                + " must never be echoed in output; treat them as reserved tokens (do not emit them"
                + " except when naming a startId/endId). Write the summary yourself (decisions,"
                + " findings, file paths, next steps; be exhaustive), and use @bN@ placeholders to"
                + " nest an already compressed block when its information must be preserved. Never"
                + " compress work still in progress. The transcript is unchanged: only outgoing"
                + " requests are rewritten. action=status reports the raw/effective token counts and"
                + " active blocks; action=decompress restores a block; action=compact asks for a"
                + " whole-context summary (optional focus hint and keep_last).";
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.AGENT;
    }

    @Override
    public Map<String, Object> schema() {
        return Schemas.object(
                Map.of(
                        "action",
                        Map.of(
                                "type", "string",
                                "enum", List.of("compress", "status", "decompress", "compact", "prune"),
                                "description", "What to do with the context."),
                        "topic",
                        Schemas.str("Short topic label for the compressed block (compress only)."),
                        "content",
                        Schemas.arrayOf(
                                "Closed ranges to compress. Ranges must not overlap.",
                                Schemas.object(
                                        Map.of(
                                                "startId",
                                                Schemas.str(
                                                        "First message ref, for example @12@ (must be a user message)."),
                                                "endId",
                                                Schemas.str(
                                                        "Last message ref, inclusive (must end at a turn boundary)."),
                                                "summary",
                                                Schemas.str(
                                                        "Exhaustive model-written summary replacing the range; keep decisions, file paths, findings and next steps. Use @bN@ placeholders to preserve nested compressed blocks.")),
                                        "startId",
                                        "endId",
                                        "summary")),
                        "blockId",
                        Schemas.str("Block to restore (decompress only), for example b1 or @b1@."),
                        "focus",
                        Schemas.str(
                                "Optional whole-context compaction hint about what the summary should emphasize (compact only)."),
                        "keep_last",
                        Schemas.intProp("Optional minimum number of recent messages to keep verbatim (compact only).")),
                "action");
    }

    @Override
    public ToolResult execute(final Map<String, Object> args) {
        return ToolResult.error("compress requires an active session context.");
    }

    @Override
    public ToolResult execute(final Map<String, Object> args, final ToolContext context) {
        final var contextView = context.contextView();
        final var usageTracker = context.usage();
        if (contextView == null || usageTracker == null) {
            return ToolResult.error("compress requires an active session context.");
        }
        if (!compressionEnabled()) {
            return ToolResult.error("compress is disabled (yca.compression.enabled=false).");
        }
        final var action =
                String.valueOf(args.getOrDefault("action", "compress")).strip().toLowerCase();
        return switch (action) {
            case "status" -> status(contextView, usageTracker);
            case "compress" -> compress(contextView, args);
            case "decompress" -> decompress(contextView, args);
            case "compact" -> request(contextView, "compact", args);
            case "prune" -> request(contextView, "prune", args);
            default ->
                ToolResult.error(
                        "Unknown action \"" + action + "\": expected compress, status, decompress, compact or prune.");
        };
    }

    private ToolResult status(
            final ContextView contextView, final io.yupiik.ia.agent.core.context.UsageTracker usageTracker) {
        final var compression = contextView.compression();
        final var messages = contextView.messages();
        final var snapshot = contextView.snapshot();
        final var window = usageTracker.contextWindowTokenCount().value();
        final var usable = usageTracker.usableWindow();
        final var raw = snapshot != null ? snapshot.effectiveTokens() : usageTracker.effectiveContext(messages, 0);
        final var effective = compression.effectiveTokens(messages, raw);
        final var out = new LinkedHashMap<String, Object>();
        out.put("messageCount", snapshot != null ? snapshot.messageCount() : messages.size());
        out.put("contextWindow", window);
        out.put("usableWindow", usable);
        out.put("rawTokens", raw);
        out.put("effectiveTokens", effective);
        out.put("savedTokens", compression.savedTokens(messages));
        out.put("summaryTokens", compression.summaryTokens());
        out.put("percentUsed", usable <= 0 ? 0 : Math.round(effective * 1000.0 / usable) / 10.0);
        if (configuration.compression() != null) {
            out.put(
                    "compressAtTokens",
                    configuration
                            .compression()
                            .resolveLimit(configuration.compression().minContextLimit(), window, 50000));
            out.put(
                    "mustCompressAtTokens",
                    configuration
                            .compression()
                            .resolveLimit(configuration.compression().maxContextLimit(), window, 100000));
        }
        final var blocks = new ArrayList<Map<String, Object>>();
        for (final var block : compression.blocks()) {
            final var row = new LinkedHashMap<String, Object>();
            row.put("id", block.id());
            row.put("topic", block.topic());
            row.put("startRef", block.startRef());
            row.put("endRef", block.endRef());
            row.put("status", block.status().name().toLowerCase());
            row.put("summaryTokens", block.summaryTokens());
            row.put("consumedBlockIds", block.consumedBlockIds());
            blocks.add(row);
        }
        out.put("blocks", blocks);
        return ToolResult.ok(toJson(out));
    }

    private ToolResult compress(final ContextView contextView, final Map<String, Object> args) {
        if (!(args.get("content") instanceof List<?> content) || content.isEmpty()) {
            return ToolResult.error("content must be a non-empty array of {startId,endId,summary} ranges.");
        }
        final var ranges = new ArrayList<ContextCompression.Range>(content.size());
        for (final var item : content) {
            if (!(item instanceof Map<?, ?> range)) {
                return ToolResult.error("Each content entry must be an object with startId, endId and summary.");
            }
            ranges.add(new ContextCompression.Range(
                    str(range.get("startId")), str(range.get("endId")), str(range.get("summary"))));
        }
        final var result = contextView.compression().compress(str(args.get("topic")), ranges);
        return result.success() ? ToolResult.ok(result.message()) : ToolResult.error(result.message());
    }

    private ToolResult decompress(final ContextView contextView, final Map<String, Object> args) {
        final var blockId = str(args.get("blockId"));
        if (blockId.isBlank()) {
            return ToolResult.error("blockId is required for decompress.");
        }
        final var result = contextView.compression().decompress(blockId);
        return result.success() ? ToolResult.ok(result.message()) : ToolResult.error(result.message());
    }

    private ToolResult request(final ContextView contextView, final String action, final Map<String, Object> args) {
        final var snapshot = contextView.snapshot();
        if ("prune".equals(action)) {
            if (configuration.pruning() == null || !configuration.pruning().enabled()) {
                return ToolResult.error(
                        "prune is disabled (yca.pruning.enabled=false); use action=compress on a closed range instead.");
            }
            if (snapshot != null && snapshot.percent() < ACTION_FLOOR_PERCENT) {
                return ToolResult.ok("Refused: context usage is " + Math.round(snapshot.percent() * 10.0) / 10.0
                        + "%, below the " + (int) ACTION_FLOOR_PERCENT + "% floor - there is nothing"
                        + " meaningful to reclaim yet. Use action=status to check usage.");
            }
        }
        final var keepLast = args.get("keep_last") instanceof Number n ? n.intValue() : 0;
        final var focus = args.get("focus") instanceof String s ? s : null;
        final var queued = contextView.request(new ContextView.Directive(action, focus, keepLast, name(), true));
        if (!queued) {
            return ToolResult.ok("A context operation is already pending this turn; only one can run per turn.");
        }
        return ToolResult.ok(
                "compact".equals(action)
                        ? "Compaction scheduled: the older conversation will be summarized right after this tool"
                                + " round, keeping the recent turns verbatim."
                        : "Prune scheduled: old tool outputs will be cleared locally right after this tool round.");
    }

    private boolean compressionEnabled() {
        if (configuration == null || configuration.compression() == null) {
            return false;
        }
        final var legacy =
                configuration.tools() == null ? null : configuration.tools().manageContext();
        // yca.tools.manageContext.enabled is accepted as a one-minor alias for the new switch.
        return configuration.compression().enabled() && (legacy == null || legacy.enabled());
    }

    private static String str(final Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }

    private String toJson(final Map<String, Object> out) {
        try {
            return mapper.toString(out);
        } catch (final RuntimeException e) {
            return String.valueOf(out);
        }
    }
}
