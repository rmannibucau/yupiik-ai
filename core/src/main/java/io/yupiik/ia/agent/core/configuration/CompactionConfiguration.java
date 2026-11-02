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
public record CompactionConfiguration(
        @Property(
                defaultValue = "true",
                documentation = "Automatically compact the context when the threshold is reached.")
        boolean enabled,

        @Property(
                defaultValue = "0",
                documentation =
                        "Legacy compaction trigger as a percentage of the usable window; 0 (default) uses the opencode-style buffer trigger instead (window minus max(bufferPercent, bufferMinTokens)). Set to a positive value to restore the historical percentage trigger.")
        int thresholdPercent,

        @Property(
                defaultValue = "90",
                documentation =
                        "Buffer mode safety cap: the compaction trigger never exceeds this percentage of the usable window, so automatic compaction always fires before the context gauge reaches 100%. A large declared output cap can push the raw opencode-style buffer trigger above the usable window; without this cap the trigger clamps exactly onto the usable window and compaction only starts at the very edge. 100 keeps the legacy clamp behavior (trigger equals the usable window); values below 100 add real headroom.")
        int triggerSafetyPercent,

        @Property(
                defaultValue = "12",
                documentation =
                        "Maximum compaction-summarizer attempts of the automatic compaction gate before it declares the context cannot be compacted further and escalates to a pinned error (see gateMaxDurationMs). 0 disables the gate (the historical warn-once-and-continue behavior).")
        int gateMaxAttempts,

        @Property(
                defaultValue = "300000",
                documentation =
                        "Wall-clock budget in milliseconds of the automatic compaction gate: once exceeded, even if more attempts remain, the gate escalates to a pinned error telling the user to run /compact urgent. Bounds how long a turn can block on a flaky summarizer. 0 disables the time bound.")
        int gateMaxDurationMs,

        @Property(
                defaultValue = "15",
                documentation =
                        "Buffer mode trigger: compact when the estimated prompt reaches this percentage of the raw context window. Matches opencode's default 15% buffer.")
        int bufferPercent,

        @Property(
                defaultValue = "16000",
                documentation =
                        "Buffer mode trigger floor: the reserved headroom is at least this many tokens, so small windows keep a usable safety margin. Matches opencode's 16k minimum buffer.")
        int bufferMinTokens,

        @Property(
                defaultValue = "0",
                documentation =
                        "Token budget of the verbatim recent tail kept by compaction (0 = auto: 25% of the usable window clamped to [2000, 15000]). The tail is extended or reduced at whole conversation turns and never splits an assistant tool call from its results.")
        int tailBudgetTokens,

        @Property(
                defaultValue = "800",
                documentation =
                        "Character cap (head and tail) applied to tool call arguments when the compaction summarizer "
                                + "serializes assistant history for summarization (0 disables the cap and keeps "
                                + "arguments verbatim). Large arguments (bash scripts, big JSON payloads) dominate "
                                + "the summarizer input and multiply the number of rolling-summary LLM calls.")
        int toolArgumentsCapChars,

        @Property(
                defaultValue = "0",
                documentation =
                        "Character cap of a single summarizer input chunk (0 = auto: a quarter of the compact model's "
                                + "usable window, floored at summarizerMinChunkChars). One chunk is one LLM call, so "
                                + "large windows compact in a single call; when a summarizer call fails because the "
                                + "input is too large, the cap is halved (down to the floor) and compaction retried.")
        int summarizerChunkChars,

        @Property(
                defaultValue = "12000",
                documentation =
                        "Floor of the auto-derived summarizer chunk cap and of the halving retries: the legacy fixed "
                                + "12k-char chunk, now just a configured lower bound, so even small summarizer models "
                                + "can never receive a chunk below this.")
        int summarizerMinChunkChars,

        @Property(
                defaultValue = "0",
                documentation =
                        "Character cap of the rolling digest accumulated across chunks and injected into merge calls "
                                + "(0 = auto: half the effective chunk cap, so a merge call carries at most ~1.5x a chunk).")
        int summarizerDigestChars,

        @Property(
                defaultValue = "2000",
                documentation =
                        "Character cap applied to each tool output when the history is serialized into the summarizer "
                                + "input (0 disables the cap).")
        int summarizerToolOutputChars,

        @Property(
                defaultValue = "500",
                documentation =
                        "Character cap (head and tail) applied to kept-tail messages when the compacted context still "
                                + "exceeds the provider window after compaction.")
        int overflowSpliceChars,

        @Property(
                defaultValue = "\"off\"",
                documentation =
                        "Speculative compaction policy: 'off' (default) keeps the synchronous compaction; 'concurrent' "
                                + "lets a background summarizer request compute a compaction candidate before the trigger "
                                + "while foreground inference continues. Only opt in when the effective provider/model "
                                + "routing accepts overlapping requests (a single-slot local server cannot). History is "
                                + "still only ever replaced by the foreground checkpoint, and a failed speculation never "
                                + "touches it.")
        String speculativePolicy,

        @Property(
                defaultValue = "80",
                documentation =
                        "Percentage of the automatic compaction trigger at which speculative compaction launches "
                                + "(speculativePolicy=concurrent only); must stay between 1 and 99. Example: with a "
                                + "100k-token trigger and 80, the background summary starts around 80k estimated tokens.")
        int speculativeStartPercent,

        @Property(
                defaultValue = "false",
                documentation =
                        "When a compaction summarizer call exhausts all its retries on a transient 5xx/network failure, "
                                + "retry the summarizer once on the provider's main (conversation) model before giving up "
                                + "and skipping the compaction. Off by default because the main model is usually costlier; "
                                + "a per-provider compaction.fallbackToMainModel value overrides this global default.")
        boolean fallbackToMainModel,

        @Property(
                defaultValue = "3",
                documentation =
                        "Retry budget of the compaction summarizer (the coordinator-owned retry loop). Scoped: this "
                                + "value feeds the compaction retries only; conversation requests keep yca.llm.retriesMaxAttempts. "
                                + "Because the coordinator is the single retry authority (the resilience wrapper skips its "
                                + "own retry loop for summarizer calls), this is the only retry budget compaction pays. "
                                + "A per-provider compaction.retriesMaxAttempts overrides it; must be >= 1.")
        int retriesMaxAttempts) {
    public CompactionConfiguration {
        if (!"off".equals(speculativePolicy) && !"concurrent".equals(speculativePolicy)) {
            throw new IllegalArgumentException("speculativePolicy must be off or concurrent");
        }
        if (speculativeStartPercent < 1 || speculativeStartPercent > 99) {
            throw new IllegalArgumentException("speculativeStartPercent must be between 1 and 99");
        }
        if (triggerSafetyPercent < 1 || triggerSafetyPercent > 100) {
            throw new IllegalArgumentException("triggerSafetyPercent must be between 1 and 100");
        }
        if (gateMaxAttempts < 0) {
            throw new IllegalArgumentException("gateMaxAttempts must be zero or positive");
        }
        if (gateMaxDurationMs < 0) {
            throw new IllegalArgumentException("gateMaxDurationMs must be zero or positive");
        }
        if (retriesMaxAttempts < 1) {
            throw new IllegalArgumentException("retriesMaxAttempts must be at least 1");
        }
    }
}
