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
package io.yupiik.ia.agent.core.shell;

import io.yupiik.ia.agent.core.agent.AgentEvent;
import java.util.Locale;

/**
 * Formatting helpers shared by the turn metrics displays: the CLI status line, the TUI status strip
 * and the headless benchmark output all render the same metrics, in the same shape.
 */
public final class MetricsFormat {
    private MetricsFormat() {
        // no-op.
    }

    /**
     * Formats the output throughput segment, the measured rate and, when the provider publishes its
     * own generation time, the server view next to it. A turn whose throughput cannot be measured
     * (no output token, no known duration) yields an empty string so the caller can omit the segment.
     *
     * @param throughput the turn throughput, {@code null} tolerated.
     * @return for example {@code "42.5 tok/s (real 40.1 tok/s)"}, {@code "42.5 tok/s"},
     *         {@code "real 40.1 tok/s"} or {@code ""} when there is nothing to show.
     */
    public static String tokPerSec(final AgentEvent.Throughput throughput) {
        if (throughput == null) {
            return "";
        }
        final var measured = throughput.tokensPerSecond();
        final var reported = throughput.reportedTokensPerSecond();
        if (measured > 0 && reported > 0) {
            return String.format(Locale.ROOT, "%.1f tok/s (real %.1f tok/s)", measured, reported);
        }
        if (measured > 0) {
            return String.format(Locale.ROOT, "%.1f tok/s", measured);
        }
        if (reported > 0) {
            return String.format(Locale.ROOT, "real %.1f tok/s", reported);
        }
        return "";
    }
}
