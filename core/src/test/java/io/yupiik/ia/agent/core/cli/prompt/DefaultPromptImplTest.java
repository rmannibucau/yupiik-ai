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
package io.yupiik.ia.agent.core.cli.prompt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class DefaultPromptImplTest {

    @Test
    void readsLinesAndEchoesThePrompt() throws Exception {
        final var in = new ByteArrayInputStream("hello\nworld\n".getBytes(StandardCharsets.UTF_8));
        final var out = new ByteArrayOutputStream();
        final var prompt = new DefaultPromptImpl(in, new PrintStream(out, true, StandardCharsets.UTF_8));

        assertEquals(Optional.of("hello"), prompt.readLine("> "));
        assertEquals(Optional.of("world"), prompt.readLine("> "));
        assertEquals(Optional.empty(), prompt.readLine("> "));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("> "));
        assertFalse(prompt.editing());
    }

    @Test
    void queuingModeBuffersTypeAheadAndDrainsInOrder() throws Exception {
        final var in = new ByteArrayInputStream("a\nb\n".getBytes(StandardCharsets.UTF_8));
        final var prompt = new DefaultPromptImpl(
                in, new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8), true);

        assertTrue(prompt.queuing());
        assertEquals(Optional.of("a"), prompt.readLine("> "));
        assertEquals(Optional.of("b"), prompt.readLine("> "));
        assertEquals(Optional.empty(), prompt.pollQueue());
    }

    @Test
    void queuingRoutesInteractionReadsAheadOfQueuedTurns() throws Exception {
        final var pipe = new PipedInputStream();
        final var writer = new PipedOutputStream(pipe);
        final var prompt = new DefaultPromptImpl(
                pipe, new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8), true);
        final var done = new CompletableFuture<Optional<String>>();
        Thread.ofPlatform().name("interaction-read").start(() -> {
            try {
                done.complete(prompt.readInteractionLine("> "));
            } catch (final IOException e) {
                done.completeExceptionally(e);
            }
        });
        Thread.sleep(100);
        writer.write("approve\n".getBytes(StandardCharsets.UTF_8));
        writer.flush();
        assertEquals(Optional.of("approve"), done.get(5, TimeUnit.SECONDS), "the interaction lane must win the line");
        writer.write("next\n".getBytes(StandardCharsets.UTF_8));
        writer.flush();
        assertEquals(Optional.of("next"), prompt.readLine("> "), "a later line still reaches the turn read");
    }
}
