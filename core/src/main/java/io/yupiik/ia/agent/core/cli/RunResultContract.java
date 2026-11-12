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

import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.framework.build.api.json.JsonOthers;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.json.internal.parser.BufferProvider;
import io.yupiik.fusion.json.internal.parser.JsonParser;
import io.yupiik.fusion.json.spi.Parser;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Opt-in structural completion check, not a host authorization or identity boundary. */
public final class RunResultContract {
    private final JsonMapper mapper;
    private final Path file;
    private final List<List<String>> requiredFields;
    private final int maxBytes;

    public RunResultContract(
            final JsonMapper mapper, final Path file, final String requiredFields, final int maxBytes) {
        if (maxBytes < 1) {
            throw new IllegalArgumentException("run.result-max-bytes must be positive");
        }
        this.mapper = mapper;
        this.file = file.toAbsolutePath();
        this.maxBytes = maxBytes;
        this.requiredFields = requiredFields == null || requiredFields.isBlank()
                ? List.of()
                : Arrays.stream(requiredFields.split(",", -1))
                        .map(group -> Arrays.stream(group.split("\\|", -1))
                                .map(String::strip)
                                .toList())
                        .toList();
        if (this.requiredFields.stream().flatMap(List::stream).anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("run.result-required-fields contains an empty field name");
        }
    }

    /** Returns a diagnostic, or null for a valid artifact. Never writes or fabricates result fields. */
    public String validationError() {
        try {
            // Check the original (not normalized) segments so a symlink followed by '..' is rejected too.
            var segment = file.getRoot();
            for (final var name : file) {
                segment = segment.resolve(name);
                if (Files.isSymbolicLink(segment)) {
                    return "result path must not contain symbolic links";
                }
            }
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                return "result must be an existing regular file";
            }
            if (Files.size(file) > maxBytes) {
                return "result exceeds " + maxBytes + " bytes";
            }
            final byte[] bytes;
            try (final var input = Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                bytes = input.readNBytes(maxBytes);
                if (input.read() != -1) {
                    return "result exceeds " + maxBytes + " bytes";
                }
            }
            final var json = StandardCharsets.UTF_8
                    .newDecoder()
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
                    .strip();
            if (!json.startsWith("{") || !json.endsWith("}")) {
                return "result must be a JSON object";
            }
            try (final var parser = new JsonParser(new StringReader(json), 8192, new BufferProvider(8192, 2), true)) {
                parser.enforceNext(Parser.Event.START_OBJECT);
                parser.skipObject();
                if (parser.hasNext()) {
                    return "result must contain exactly one JSON object";
                }
            }
            final var document = mapper.fromString(ResultDocument.class, json);
            if (document == null) {
                return "result must be a JSON object";
            }
            final var fields = document.fields() == null ? Map.<String, Object>of() : document.fields();
            for (final var alternatives : requiredFields) {
                if (alternatives.stream()
                        .noneMatch(name -> fields.get(name) instanceof final String value && !value.isBlank())) {
                    return "result requires a nonblank string field: " + String.join("|", alternatives);
                }
            }
            return null;
        } catch (final IOException | RuntimeException e) {
            return "result must be readable valid JSON (" + e.getClass().getSimpleName() + ")";
        }
    }

    public String correctionPrompt(final String validationError) {
        return "Completion artifact validation failed: " + validationError + ".\n"
                + "Write or correct the JSON object at " + file + " (maximum " + maxBytes + " bytes).\n"
                + "Required nonblank string fields (each group requires one alternative): "
                + requiredFields.stream().map(group -> String.join("|", group)).toList() + ".\n"
                + "Repair only this artifact from the existing task context and actual evidence; do not fabricate results. "
                + "Do not repeat implementation, tests, commits, pushes, or publication. "
                + "This is the only correction attempt. A chat answer does not replace the artifact.";
    }

    @JsonModel
    public record ResultDocument(@JsonOthers Map<String, Object> fields) {}
}
