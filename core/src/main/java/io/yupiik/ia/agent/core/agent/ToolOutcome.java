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
package io.yupiik.ia.agent.core.agent;

import java.util.List;

/**
 * Outcome of a single tool call, decoupled from message/session mutation so calls can run
 * concurrently while results still land in the conversation in the model's call order.
 * {@code paths} carries the file paths a read or write tool touched, for stale-read invalidation.
 * {@code durationMs} is the individual tool execution time, used for per-turn metrics.
 */
public record ToolOutcome(
        String callId,
        String name,
        String output,
        boolean success,
        boolean pending,
        boolean persist,
        List<String> paths,
        long durationMs,
        boolean informative,
        List<String> coverage,
        boolean refused) {

    ToolOutcome(
            final String callId,
            final String name,
            final String output,
            final boolean success,
            final boolean pending,
            final boolean persist,
            final List<String> paths,
            final long durationMs,
            final boolean informative,
            final List<String> coverage) {
        this(callId, name, output, success, pending, persist, paths, durationMs, informative, coverage, false);
    }

    ToolOutcome(
            final String callId,
            final String name,
            final String output,
            final boolean success,
            final boolean persist,
            final List<String> paths,
            final long durationMs,
            final boolean informative,
            final List<String> coverage) {
        this(callId, name, output, success, false, persist, paths, durationMs, informative, coverage);
    }

    // refused-carrying 10-arg form (the model-loop doom-guard refusal path); pending is false
    ToolOutcome(
            final String callId,
            final String name,
            final String output,
            final boolean success,
            final boolean persist,
            final List<String> paths,
            final long durationMs,
            final boolean informative,
            final List<String> coverage,
            final boolean refused) {
        this(callId, name, output, success, false, persist, paths, durationMs, informative, coverage, refused);
    }

    ToolOutcome(
            final String callId,
            final String name,
            final String output,
            final boolean success,
            final boolean persist,
            final List<String> paths,
            final long durationMs,
            final boolean informative) {
        this(callId, name, output, success, persist, paths, durationMs, informative, List.of());
    }

    ToolOutcome(
            final String callId,
            final String name,
            final String output,
            final boolean success,
            final boolean persist,
            final List<String> paths,
            final long durationMs) {
        this(callId, name, output, success, persist, paths, durationMs, true, List.of());
    }

    ToolOutcome(
            final String callId,
            final String name,
            final String output,
            final boolean success,
            final boolean persist,
            final List<String> paths) {
        this(callId, name, output, success, persist, paths, 0L, true);
    }

    ToolOutcome(
            final String callId, final String name, final String output, final boolean success, final boolean persist) {
        this(callId, name, output, success, persist, List.of(), 0L, true);
    }
}
