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

import com.sun.management.ThreadMXBean;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.widget.Widget;
import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration$FusionConfigurationFactory;
import io.yupiik.ia.agent.core.shell.ThinkTagSplitter;
import java.lang.management.ManagementFactory;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Opt-in headless stress harness, deliberately not a JUnit test.
 * Run on the TUI test classpath with -Xmx2g. Argument 'mock' feeds the original
 * 109M whitespace-token workload through the real transcript model (not a tokenizer).
 * No provider requests, persistence, terminal, native binary or timing assertions.
 */
public final class TranscriptRenderBenchmark {
    private TranscriptRenderBenchmark() {}

    public static void main(final String[] args) throws Exception {
        final var command = command();
        final var state = new UiState(new ThinkTagSplitter(), Clock.systemUTC());
        final var render = TuiCommand.class.getDeclaredMethod("layoutContent", String.class, int.class, UiState.class);
        render.setAccessible(true);
        final var legacy = TuiCommand.class.getDeclaredMethod(
                "contentChunks",
                List.class,
                String.class,
                int.class,
                UiState.class,
                List.class,
                List.class,
                List.class,
                List.class);
        legacy.setAccessible(true);
        for (var i = 0; i < 12; i++) {
            render.invoke(command, "**Assistant**\n\n" + "word ".repeat(4000) + i, 80, state);
        }
        final String source;
        if (args.length > 0 && "mock".equals(args[0])) {
            final var payload = ("word ".repeat(100) + "\n\n").repeat(10_000);
            final var ingestion = System.nanoTime();
            for (var i = 0; i < 109; i++) {
                state.model.startUserTurn("synthetic turn " + i);
                state.model.onEvent(new AgentEvent.TurnDone(payload, "mock", null, null));
                state.model.trim();
            }

            source = state.model.markdown("RUNNING", "WELCOME");
            System.out.printf(
                    "mockTokens=109000000 retainedChars=%d ingestionSeconds=%.3f%n",
                    source.length(), (System.nanoTime() - ingestion) / 1_000_000_000.0);
        } else {
            final var chars = args.length > 0 ? Integer.parseInt(args[0]) : 10_000_000;
            final var builder = new StringBuilder(chars + 1000);
            for (var i = 0; builder.length() < chars; i++) {
                builder.append(i % 2 == 0 ? "**You**\n\n" : "**Assistant**\n\n")
                        .append("turn-")
                        .append(i)
                        .append(' ')
                        .append("word ".repeat(190))
                        .append("\n\n");
            }
            source = builder.substring(0, chars);
            System.out.println("distinctTurnChars=" + source.length());
        }
        measure("cold layout", 1, () -> render.invoke(command, source, 80, state));
        measure(
                "unchanged original entrypoint",
                4,
                () -> legacy.invoke(
                        command,
                        new ArrayList<>(),
                        source,
                        80,
                        state,
                        new ArrayList<>(),
                        new ArrayList<>(),
                        new ArrayList<>(),
                        new ArrayList<>()));
        measure("unchanged viewport", 20, () -> viewport(render.invoke(command, source, 80, state)));
        measure(
                "same-turn large-body delta",
                1,
                () -> viewport(render.invoke(command, source + " streaming delta", 80, state)));
        final var appended = source + "\n\n**Assistant**\n\nnew tail";
        measure("append turn and viewport", 1, () -> viewport(render.invoke(command, appended, 80, state)));
        for (var i = 0; i < 3; i++) {
            final var growing = appended + " streaming".repeat(i + 1);
            measure("tail delta " + i, 1, () -> viewport(render.invoke(command, growing, 80, state)));
        }
    }

    private static TuiCommand command() throws Exception {
        final var configuration = new CodingAgentConfiguration$FusionConfigurationFactory(k -> Optional.empty()).get();
        final var tui = new TuiConfiguration(
                null, "#9d7cd8", "#fab283", "#7fd88f", "#f5a742", "#e06c75", "#b896d8", "#eeeeee", "#808080", -1.0,
                0.22, 0.14, 0.85, "#484848", "#606060", "#141414", "auto", "auto", 4000L, 3500L, false, "unicode",
                "auto", "auto", List.of(), null, null, true, 3, 10, "", 0.8, "#ff5555", null, null);
        final var constructor = TuiCommand.class.getConstructors()[0];
        final var parameters = new Object[constructor.getParameterCount()];
        for (var i = 0; i < parameters.length; i++) {
            parameters[i] = switch (constructor.getParameterTypes()[i].getSimpleName()) {
                case "TuiConf" -> new TuiCommand.TuiConf("", "", KeyBindingPreset.STANDARD, true, "dark");
                case "TuiConfiguration" -> tui;
                case "CodingAgentConfiguration" -> configuration;
                case "Clock" -> Clock.systemUTC();
                case "ThinkTagSplitter" -> new ThinkTagSplitter();
                default -> null;
            };
        }
        return (TuiCommand) constructor.newInstance(parameters);
    }

    private static void viewport(final Object result) throws Exception {
        final var accessor = result.getClass().getDeclaredMethod("index");
        accessor.setAccessible(true);
        final var index = (TranscriptLayout<?>) accessor.invoke(result);
        final var area = new Rect(0, 0, 80, 40);
        final var buffer = Buffer.empty(area);
        final var offset = Math.max(0, index.height() - area.height());
        for (var i = index.firstVisible(offset); i < index.size(); i++) {
            final var chunk = index.get(i);
            final var widget = chunk.getClass().getDeclaredMethod("widget", int.class);
            widget.setAccessible(true);
            final var y = index.row(i) - offset;
            final var h = index.row(i + 1) - index.row(i);
            final var skip = Math.max(0, -y);
            ((Widget) widget.invoke(chunk, skip))
                    .render(
                            new Rect(0, Math.max(0, y), 80, Math.min(h - skip, area.height() - Math.max(0, y))),
                            buffer);
        }
    }

    private static void measure(final String label, final int count, final Work work) throws Exception {
        final var bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        final var thread = Thread.currentThread().threadId();
        final var cpu = bean.getCurrentThreadCpuTime();
        final var bytes = bean.getThreadAllocatedBytes(thread);
        final var wall = System.nanoTime();
        for (var i = 0; i < count; i++) {
            work.run();
        }
        System.out.printf(
                "%s wallMs=%.3f cpuMs=%.3f allocatedMiB=%.3f%n",
                label,
                (System.nanoTime() - wall) / (1_000_000.0 * count),
                (bean.getCurrentThreadCpuTime() - cpu) / (1_000_000.0 * count),
                (bean.getThreadAllocatedBytes(thread) - bytes) / (1024.0 * 1024 * count));
    }

    @FunctionalInterface
    private interface Work {
        void run() throws Exception;
    }
}
