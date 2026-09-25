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
import io.yupiik.ia.agent.core.context.ContextView;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.Schemas;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.tools.ToolResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lets the model inspect and manage its own context window: {@code status} reports the current
 * usage against the trigger thresholds, {@code compact} requests an LLM summary compaction and
 * {@code prune} a local clearing of old tool outputs. Requests become directives applied by the
 * agent loop after the current tool round, at most one per turn, and are refused while the context
 * usage is below the floor where a compaction could actually help.
 */
@ApplicationScoped
public class ManageContextTool implements EmbeddedTool {
    /** Usage floor (in percent of the usable window) under which compact/prune requests are refused. */
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
        return "manage_context";
    }

    @Override
    public String description() {
        return "Inspect and manage the conversation context window. action=status reports the current usage"
                + " and the compaction/prune thresholds; action=compact asks for the older conversation to be"
                + " summarized (optional focus hint folded into the summary); action=prune asks old tool outputs"
                + " to be cleared locally. compact/prune run after this tool round and are refused while usage"
                + " is below " + (int) ACTION_FLOOR_PERCENT + "% of the usable window.";
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
                                "enum", List.of("status", "compact", "prune"),
                                "description", "What to do with the context."),
                        "focus",
                        Schemas.str(
                                "Optional compact-only hint about what the summary should emphasize (goal, decisions, next steps)."),
                        "keep_last",
                        Schemas.intProp("Optional compact-only minimum number of recent messages to keep verbatim.")),
                "action");
    }

    @Override
    public ToolResult execute(final Map<String, Object> args) {
        return ToolResult.error("manage_context requires an active session context.");
    }

    @Override
    public ToolResult execute(final Map<String, Object> args, final ToolContext context) {
        final var contextView = context.contextView();
        final var usageTracker = context.usage();
        if (contextView == null || usageTracker == null) {
            return ToolResult.error("manage_context requires an active session context.");
        }
        if (configuration == null
                || configuration.tools() == null
                || configuration.tools().manageContext() == null
                || !configuration.tools().manageContext().enabled()) {
            return ToolResult.error("manage_context is disabled (yca.tools.manageContext.enabled=false).");
        }
        final var action =
                String.valueOf(args.getOrDefault("action", "status")).strip().toLowerCase();
        return switch (action) {
            case "status" -> status(contextView, usageTracker);
            case "compact" -> request(contextView, "compact", args);
            case "prune" -> request(contextView, "prune", args);
            default -> ToolResult.error("Unknown action \"" + action + "\": expected status, compact or prune.");
        };
    }

    private ToolResult status(
            final ContextView contextView, final io.yupiik.ia.agent.core.context.UsageTracker usageTracker) {
        final var snapshot = contextView.snapshot();
        final var out = new LinkedHashMap<String, Object>();
        if (snapshot != null) {
            out.put("messageCount", snapshot.messageCount());
            out.put("effectiveTokens", snapshot.effectiveTokens());
            out.put("contextWindow", snapshot.contextWindow());
            out.put("usableWindow", snapshot.usableWindow());
            out.put("percentUsed", Math.round(snapshot.percent() * 10.0) / 10.0);
            out.put("pruneTriggersAt", snapshot.pruneThresholdPercent());
            out.put("compactTriggersAt", snapshot.compactThresholdPercent());
        } else {
            final var window = usageTracker.contextWindow();
            out.put("contextWindow", window);
            out.put("usableWindow", usageTracker.usableWindow());
        }
        return new ToolResult(true, toJson(out));
    }

    private ToolResult request(final ContextView contextView, final String action, final Map<String, Object> args) {
        final var snapshot = contextView.snapshot();
        if (snapshot != null && snapshot.percent() < ACTION_FLOOR_PERCENT) {
            return new ToolResult(
                    true,
                    "Refused: context usage is " + Math.round(snapshot.percent() * 10.0) / 10.0
                            + "%, below the " + (int) ACTION_FLOOR_PERCENT + "% floor - there is nothing"
                            + " meaningful to reclaim yet. Use action=status to check usage.");
        }
        final var keepLast = args.get("keep_last") instanceof Number n ? n.intValue() : 0;
        final var focus = args.get("focus") instanceof String s ? s : null;
        final var queued = contextView.request(new ContextView.Directive(action, focus, keepLast, name()));
        if (!queued) {
            return new ToolResult(true, "A context operation is already pending this turn; only one can run per turn.");
        }
        return new ToolResult(
                true,
                action.equals("compact")
                        ? "Compaction scheduled: the older conversation will be summarized right after this"
                                + " tool round, keeping the recent turns verbatim."
                        : "Prune scheduled: old tool outputs will be cleared locally right after this tool round.");
    }

    private String toJson(final Map<String, Object> out) {
        try {
            return mapper.toString(out);
        } catch (final RuntimeException e) {
            return String.valueOf(out);
        }
    }
}
