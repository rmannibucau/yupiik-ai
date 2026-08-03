/*
 * Copyright (c) 2026 - present - Yupiik SAS - https://www.yupiik.com
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
package io.yupiik.fusion.a2a.model;

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.json.JsonModel;
import java.util.Base64;
import java.util.Map;

/**
 * A section of communication content: some text, a file - inline or by reference - or a structured JSON value.
 * <p>
 * {@code text}, {@code raw}, {@code url} and {@code data} are the branches of a proto {@code oneof} so exactly one of
 * them must be set: prefer the factory methods over the canonical constructor, they guarantee it.
 *
 * @param text      the textual content.
 * @param raw       the inline file content, base64 encoded - use {@link #raw(String, byte[], String)} to build it.
 * @param url       where the file content can be fetched.
 * @param data      an arbitrary JSON value: an object ({@code Map}), an array ({@code List}) or a scalar.
 * @param metadata  free form metadata about this part.
 * @param filename  the file name when the part carries a file, {@code report.pdf} for example.
 * @param mediaType the media type of the content, it is meaningful for all the branches.
 */
@JsonModel
public record Part(
        @Property(documentation = "The textual content.") String text,

        @Property(documentation = "The inline file content, base64 encoded.")
        String raw,

        @Property(documentation = "Where the file content can be fetched.")
        String url,

        @Property(documentation = "An arbitrary JSON value: an object (Map), an array (List) or a scalar.")
        Object data,

        @Property(documentation = "Free form metadata about this part.")
        Map<String, Object> metadata,

        @Property(documentation = "The file name when the part carries a file, report.pdf for example.")
        String filename,

        @Property(documentation = "The media type of the content, it is meaningful for all the branches.")
        String mediaType) {
    /**
     * @param text the content.
     * @return a {@code text/plain} text part.
     */
    public static Part text(final String text) {
        return new Part(text, null, null, null, null, null, "text/plain");
    }

    /**
     * @param mediaType the media type of {@code text}, {@code text/markdown} for example.
     * @param text      the content.
     * @return a text part with an explicit media type.
     */
    public static Part text(final String mediaType, final String text) {
        return new Part(text, null, null, null, null, null, mediaType);
    }

    /**
     * @param mediaType the file media type.
     * @param content   the file content, it is base64 encoded as the specification requires.
     * @param filename  the file name, it can be {@code null}.
     * @return an inline file part.
     */
    public static Part raw(final String mediaType, final byte[] content, final String filename) {
        return new Part(null, Base64.getEncoder().encodeToString(content), null, null, null, filename, mediaType);
    }

    /**
     * @param mediaType the file media type.
     * @param url       where the content can be fetched.
     * @param filename  the file name, it can be {@code null}.
     * @return a file part the client will have to download.
     */
    public static Part url(final String mediaType, final String url, final String filename) {
        return new Part(null, null, url, null, null, filename, mediaType);
    }

    /**
     * @param data any JSON value: a {@code Map}, a {@code List}, a {@code String}, a number or a boolean.
     * @return an {@code application/json} structured part.
     */
    public static Part data(final Object data) {
        return new Part(null, null, null, data, null, null, "application/json");
    }

    /**
     * @return the inline file content, decoded, or {@code null} when this part carries no inline file.
     */
    public byte[] rawBytes() {
        return raw == null ? null : Base64.getDecoder().decode(raw);
    }
}
