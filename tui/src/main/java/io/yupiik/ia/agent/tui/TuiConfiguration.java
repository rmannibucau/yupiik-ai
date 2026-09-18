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
package io.yupiik.ia.agent.tui;

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;
import io.yupiik.fusion.framework.build.api.json.JsonModel;

@RootConfiguration("yca.tui")
@JsonModel
public record TuiConfiguration(
        @Property(
                defaultValue = "\"#9d7cd8\"",
                documentation = "Accent color (hex), used for panel titles and highlights.")
        String accent,

        @Property(defaultValue = "\"#fab283\"", documentation = "Primary color (hex), used for labels and emphasis.")
        String primary,

        @Property(
                defaultValue = "\"#7fd88f\"",
                documentation = "Success color (hex), used for ready state and low context usage.")
        String success,

        @Property(
                defaultValue = "\"#f5a742\"",
                documentation = "Warning color (hex), used for busy state, approvals and medium context usage.")
        String warning,

        @Property(
                defaultValue = "\"#e06c75\"",
                documentation = "Error color (hex), used for errors and high context usage.")
        String error,

        @Property(
                defaultValue = "\"#56b6c2\"",
                documentation = "Info color (hex), used for gauges and informational values.")
        String info,

        @Property(defaultValue = "\"#eeeeee\"", documentation = "Main text color (hex).")
        String text,

        @Property(
                defaultValue = "\"#808080\"",
                documentation = "Muted text color (hex), used for hints and secondary values.")
        String textMuted,

        @Property(
                defaultValue = "0.85",
                documentation =
                        "Opacity (0..1) of the thinking/reasoning text, blended over the panel background, so thoughts stay distinguishable from the assistant output.")
        double thinkingOpacity,

        @Property(
                defaultValue = "0.85",
                documentation =
                        "Opacity (0..1) of the tool output text, blended over the panel background, so assistant answers stand out.")
        double toolOutputOpacity,

        @Property(defaultValue = "\"#484848\"", documentation = "Panel border color (hex).")
        String border,

        @Property(defaultValue = "\"#606060\"", documentation = "Active/focused panel border color (hex).")
        String borderActive,

        @Property(defaultValue = "\"#141414\"", documentation = "Panel background color (hex).")
        String backgroundPanel,

        @Property(
                defaultValue = "\"#ff5555\"",
                documentation = "Highlight color (hex) used when permissions are set to all (danger mode).")
        String permitAll,

        @Property(
                defaultValue = "4000L",
                documentation =
                        "How long (milliseconds) a transient toast notice stays visible. Set to 0 to disable toasts.")
        long toastDurationMillis,

        @Property(documentation = "Conversation transcript retention settings.")
        TuiTranscriptConfiguration transcript) {}
