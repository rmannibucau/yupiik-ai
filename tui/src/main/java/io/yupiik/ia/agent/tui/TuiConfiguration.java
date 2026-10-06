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
import java.util.List;

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
                defaultValue = "-1.0",
                documentation =
                        "Transparency (0..1) of the thinking/reasoning text toward the panel background: 0 is fully opaque, darker prose, "
                                + "higher values fade thoughts out (terminals have no alpha, the color is pre-blended). "
                                + "Use -1 (default) for a theme-aware value derived from thinkingOpacityDark/thinkingOpacityLight, "
                                + "so thoughts read as italic and slightly dimmed but stay clearly legible.")
        double thinkingOpacity,

        @Property(
                defaultValue = "0.22",
                documentation =
                        "Theme-aware default thought fade (see thinkingOpacity) used on the dark theme when thinkingOpacity is auto (-1).")
        double thinkingOpacityDark,

        @Property(
                defaultValue = "0.14",
                documentation =
                        "Theme-aware default thought fade (see thinkingOpacity) used on the light theme when thinkingOpacity is auto (-1).")
        double thinkingOpacityLight,

        @Property(
                defaultValue = "0.35",
                documentation =
                        "Transparency (0..1) of the tool output text toward the panel background: 0 is fully opaque, higher values fade tool output out (terminals have no alpha, the color is pre-blended).")
        double toolOutputOpacity,

        @Property(defaultValue = "\"#484848\"", documentation = "Panel border color (hex).")
        String border,

        @Property(defaultValue = "\"#606060\"", documentation = "Active/focused panel border color (hex).")
        String borderActive,

        @Property(defaultValue = "\"#141414\"", documentation = "Panel background color (hex).")
        String backgroundPanel,

        @Property(
                defaultValue = "\"auto\"",
                documentation = "Bold (**strong**) prose color (hex), or auto to follow the primary color.")
        String strongColor,

        @Property(
                defaultValue = "\"auto\"",
                documentation = "Inline code color (hex), or auto to follow the active code theme.")
        String inlineCodeColor,

        @Property(
                defaultValue = "4000L",
                documentation =
                        "How long (milliseconds) a transient toast notice stays visible. Set to 0 to disable toasts.")
        long toastDurationMillis,

        @Property(
                defaultValue = "3500L",
                documentation =
                        "Safety repaint: longest time (milliseconds) the UI can go without a full redraw while idle. "
                                + "Any state change still repaints instantly (on the next tick); this only bounds missed "
                                + "updates so the screen can never stay stale longer than this. Keep strictly below the "
                                + "5s render-stall timeout. Set to 0 to disable the safety repaint.")
        long heartbeatMillis,

        @Property(
                defaultValue = "true",
                documentation =
                        "Persist submitted prompts to <storageHome>/history so they survive restarts. "
                                + "Set to false to disable history persistence entirely (in-memory Up/Down recall for the current session stays active).")
        boolean history,

        @Property(
                defaultValue = "\"unicode\"",
                documentation = "Glyph set: unicode (default) or ascii for terminals with limited font coverage.")
        String glyphs,

        @Property(
                defaultValue = "\"auto\"",
                documentation = "Syntax highlighting color theme: auto (follows the UI theme), one-dark or one-light.")
        String codeTheme,

        @Property(
                defaultValue = "\"auto\"",
                documentation = "Tool output formatting: auto detects and reformats markdown/JSON/XML/YAML/diffs, "
                        + "off keeps raw output only.")
        String toolOutputFormat,

        @Property(
                documentation = "Optional pricing table used to estimate the session cost, entries formatted "
                        + "`model-substring=in/out` with USD per million tokens, e.g. `gpt-4.1-mini=0.4/1.6`. "
                        + "The first matching entry wins; leave empty to hide cost display.")
        List<String> pricing,

        @Property(documentation = "Conversation transcript retention settings.")
        TuiTranscriptConfiguration transcript,

        @Property(
                documentation =
                        "Per-level colors of the captured log regions (`yca.tui.log.<level>.color`, see TuiLogLevelConfiguration).")
        TuiLogConfiguration log,

        @Property(
                defaultValue = "true",
                documentation =
                        "Hide the terminal hardware cursor while the TUI runs: only the in-app drawn caret is shown "
                                + "on the prompt. Disable to keep the terminal cursor visible next to the drawn one.")
        boolean hideCursor,

        @Property(
                defaultValue = "3",
                documentation =
                        "Rows moved by a single mouse wheel notch, and by Up/Down, in the transcript and in every modal/overlay.")
        int scrollLines,

        @Property(
                defaultValue = "10",
                documentation = "Rows moved by PgUp/PgDn, in the transcript and in every modal/overlay.")
        int scrollPageLines,

        @Property(
                defaultValue = "\"\"",
                documentation = "Screen background color (hex) painted over the whole viewport before the UI widgets, "
                        + "or blank (default) to keep the terminal background. Panels and cards draw their own "
                        + "backgroundPanel-based colors on top.")
        String background,

        @Property(
                defaultValue = "\"#ff5555\"",
                documentation = "Yolo approval color (hex), used for the red 'yolo ⚠' badge in the status bar when the "
                        + "approval mode is set to yolo (danger mode).")
        String yolo) {}
