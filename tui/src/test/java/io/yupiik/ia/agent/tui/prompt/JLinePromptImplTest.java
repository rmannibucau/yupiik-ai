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
package io.yupiik.ia.agent.tui.prompt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.core.cli.prompt.PromptInterruptException;
import java.lang.reflect.Proxy;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.UserInterruptException;
import org.junit.jupiter.api.Test;

class JLinePromptImplTest {

    @Test
    void mapsJLineExceptionsToThePromptContract() throws Exception {
        final var calls = new AtomicInteger();
        final var reader = (LineReader) Proxy.newProxyInstance(
                LineReader.class.getClassLoader(), new Class<?>[] {LineReader.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("readLine")) {
                        return null;
                    }
                    return switch (calls.getAndIncrement()) {
                        case 0 -> "hello";
                        case 1 -> throw new UserInterruptException("ctrl-c");
                        default -> throw new EndOfFileException();
                    };
                });
        final var prompt = new JLinePromptImpl(null, reader);

        assertTrue(prompt.editing());
        assertEquals(Optional.of("hello"), prompt.readLine("> "));
        assertThrows(PromptInterruptException.class, () -> prompt.readLine("> "));
        assertEquals(Optional.empty(), prompt.readLine("> "));
    }
}
