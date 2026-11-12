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
package io.yupiik.ia.agent.core.cli;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.test.TestJsonMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

@ExtendWith(TestJsonMapper.class)
class RunResultContractTest {
    @TempDir
    Path temp;

    @Test
    void validatesOnlyActualNonblankStringFields(final JsonMapper mapper) throws Exception {
        final var file = temp.resolve("result.json");
        final var contract = new RunResultContract(mapper, file, "summary|failure, evidence", 1000);
        for (final var json : List.of(
                "{}",
                "{\"summary\":\" \"}",
                "{\"summary\":true,\"evidence\":\"ok\"}",
                "{\"summary\":\"ok\",\"evidence\":null}",
                "{\"summary\":\"ok\",\"evidence\":{}}")) {
            Files.writeString(file, json);
            assertNotNull(contract.validationError(), json);
        }
        for (final var field : List.of("summary", "failure")) {
            Files.writeString(file, "{\"" + field + "\":\"actual result\",\"evidence\":\"checked\"}");
            assertNull(contract.validationError());
        }
        assertTrue(contract.correctionPrompt("missing field").contains("summary|failure"));
    }

    @Test
    void rejectsMissingNonregularMalformedAndOversizedArtifacts(final JsonMapper mapper) throws Exception {
        final var file = temp.resolve("result.json");
        final var contract = new RunResultContract(mapper, file, "", 32);
        assertNotNull(contract.validationError());
        Files.createDirectory(file);
        assertNotNull(contract.validationError());
        Files.delete(file);
        for (final var json : List.of(
                "",
                "null",
                "[]",
                "\"text\"",
                "{oops}",
                "{} {}",
                "{} trailing",
                "{\"long\":\"" + "x".repeat(32) + "\"}")) {
            Files.writeString(file, json);
            assertNotNull(contract.validationError(), json);
        }
        Files.write(file, new byte[] {'{', '"', 'x', '"', ':', '"', (byte) 0xff, '"', '}'});
        assertNotNull(contract.validationError());
        Files.writeString(file, "{}");
        assertNull(new RunResultContract(mapper, file, "", 2).validationError());
        assertNotNull(new RunResultContract(mapper, file, "", 1).validationError());
    }

    @Test
    void rejectsSymlinksInFileAndParentSegments(final JsonMapper mapper) throws Exception {
        final var directory = Files.createDirectory(temp.resolve("real"));
        final var result = Files.writeString(directory.resolve("result.json"), "{}");
        final var fileLink = Files.createSymbolicLink(temp.resolve("file-link"), result);
        final var directoryLink = Files.createSymbolicLink(temp.resolve("dir-link"), directory);
        assertNotNull(new RunResultContract(mapper, fileLink, "", 1000).validationError());
        assertNotNull(new RunResultContract(mapper, directoryLink.resolve("result.json"), "", 1000).validationError());
    }

    @Test
    void rejectsInvalidConfiguration(final JsonMapper mapper) {
        assertThrows(IllegalArgumentException.class, () -> new RunResultContract(mapper, temp, "", 0));
        for (final var fields : List.of("summary,", "|failure", "summary||failure", "summary, ,failure")) {
            assertThrows(IllegalArgumentException.class, () -> new RunResultContract(mapper, temp, fields, 1000));
        }
    }
}
