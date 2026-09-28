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

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.yupiik.ia.agent.core.agent.AgentEvent;
import org.junit.jupiter.api.Test;

class MetricsFormatTest {
    @Test
    void measuredRateOnly() {
        assertEquals("42.5 tok/s", MetricsFormat.tokPerSec(new AgentEvent.Throughput(85, 2_000, 0)));
    }

    @Test
    void measuredRateNextToTheServerView() {
        assertEquals(
                "42.5 tok/s (real 40.0 tok/s)", MetricsFormat.tokPerSec(new AgentEvent.Throughput(85, 2_000, 2_125)));
    }

    @Test
    void serverViewAloneWhenNothingWasMeasured() {
        assertEquals("real 40.0 tok/s", MetricsFormat.tokPerSec(new AgentEvent.Throughput(85, 0, 2_125)));
    }

    @Test
    void nothingMeasurableYieldsNoSegment() {
        assertEquals("", MetricsFormat.tokPerSec(AgentEvent.Throughput.EMPTY));
        assertEquals("", MetricsFormat.tokPerSec(new AgentEvent.Throughput(0, 1_000, 0)), "no output token");
        assertEquals("", MetricsFormat.tokPerSec(new AgentEvent.Throughput(100, 0, 0)), "no time known");
        assertEquals("", MetricsFormat.tokPerSec(null));
    }
}
