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
package io.yupiik.ia.agent.core.configuration;

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.json.JsonModel;

@JsonModel
public record PruningConfiguration(
        @Property(
                defaultValue = "true",
                documentation = "Automatically prune the context (cheaper than compaction, applied first).")
        boolean enabled,

        @Property(defaultValue = "65", documentation = "Usable context window usage percentage triggering the pruning.")
        int thresholdPercent,

        @Property(
                defaultValue = "6",
                documentation =
                        "Minimum number of most recent messages never touched by the pruning (floor: the protected zone extends to whole conversation turns).")
        int keepRecent,

        @Property(
                defaultValue = "2",
                documentation =
                        "Most recent conversation turns (from a user prompt to the next one) never touched by the pruning.")
        int protectTurns,

        @Property(
                defaultValue = "40000",
                documentation =
                        "Estimated tokens of the most recent tool outputs protected from pruning, measured backwards from the protected turn boundary.")
        int protectRecentTokens,

        @Property(
                defaultValue = "20000",
                documentation =
                        "Minimum estimated tokens of old tool outputs required before pruning rewrites anything (avoids churn on small gains).")
        int minPruneTokens,

        @Property(
                defaultValue = "2000",
                documentation =
                        "Character cap (head and tail) applied to old verbose tool outputs by the first pruning pass, before any full clear; 0 disables the shrink pass.")
        int shrinkOutputChars,

        @Property(
                defaultValue = "80",
                documentation =
                        "Character cap (head and tail) used by the local truncation fallback applied to old messages when the compaction summarizer fails.")
        int toolOutputMaxChars,

        @Property(
                defaultValue = "20000",
                documentation =
                        "Character cap (head and tail) applied to tool outputs at ingestion time, before they enter the conversation; 0 disables the cap.")
        int ingestionOutputCapChars,

        @Property(
                defaultValue = "true",
                documentation =
                        "Remove duplicate tool calls (same tool with the same normalized arguments): only the latest occurrence is kept, earlier ones are replaced by a short marker before the request is sent.")
        boolean deduplicateToolCalls,

        @Property(
                defaultValue = "false",
                documentation =
                        "Replace the input of write operations (write_file/edit_file/apply_patch) that were overwritten by a later read of the same file with a short marker, keeping the write confirmation.")
        boolean supersedeOverwrittenWrites,

        @Property(
                defaultValue = "true",
                documentation =
                        "Remove the (potentially large) input of failed tool calls after the configured number of turns, keeping the error message.")
        boolean purgeFailedToolInputs,

        @Property(
                defaultValue = "4",
                documentation = "Number of turns after which a failed tool call input is purged (0 = immediately).")
        int purgeFailedToolInputsTurns,

        @Property(
                defaultValue = "0",
                documentation =
                        "Soft context-usage threshold (percent) above which, once iterationNudgeThreshold iterations ran since the last user message, a short nudge is appended to the session state to invite the model to finish or to compress; 0 disables the nudge.")
        int nudgeThresholdPercent,

        @Property(
                defaultValue = "15",
                documentation =
                        "Number of iterations since the last user message after which the soft nudge can be appended (ignored when nudgeThresholdPercent is 0).")
        int nudgeIterationThreshold,

        @Property(
                defaultValue =
                        "\"Context is at ~%USAGE%% of the usable window. Consider finishing the task soon, or running manage_context with action=compact or action=prune.\"",
                documentation =
                        "Template of the soft context-usage nudge; %USAGE% is replaced by the usage percentage.")
        String nudgeTemplate) {}
