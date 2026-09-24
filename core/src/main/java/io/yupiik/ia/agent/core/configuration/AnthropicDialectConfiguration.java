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
package io.yupiik.ia.agent.core.configuration;

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.json.JsonModel;
import java.util.Locale;

/**
 * Anthropic wire dialect options.
 *
 * <p>{@code splitBlocks} controls the volatile session-state tail split (see the Anthropic dialect):
 * when enabled, the tail rides in its own content block and the second {@code cache_control}
 * breakpoint sits on the stable prefix. That is a win on real Anthropic endpoints (block-granular
 * caching: history stays cached too) but a loss on Anthropic-compatible gateways backed by
 * token-level prefix caches - the split message serializes as blocks on its first send yet as a
 * plain string once it rides in history, so the byte prefix never matches (measured on an
 * anthropic-style gateway: 0 cached tokens with the split, 6,144 without).</p>
 */
@JsonModel
public record AnthropicDialectConfiguration(
        @Property(
                defaultValue = "\"auto\"",
                documentation =
                        "Volatile session-state tail split on the Anthropic wire. auto (default): enable it only for anthropic.com endpoints (real Anthropic caches per content block, the second breakpoint then keeps the conversation history cached too) and keep the tail inline elsewhere (token-level gateway caches break on the block/string serialization mismatch). true/false force the behaviour regardless of the endpoint host.")
        String splitBlocks) {

    /**
     * The explicit split flag: {@link Boolean#TRUE} for {@code true}, {@link Boolean#FALSE} for
     * {@code false}, {@code null} when unset/blank/unknown (the dialect then auto-detects from the
     * endpoint host).
     */
    public Boolean explicitSplitBlocks() {
        if (splitBlocks == null) {
            return null;
        }
        final var norm = splitBlocks.strip().toLowerCase(Locale.ROOT);
        return switch (norm) {
            case "true" -> Boolean.TRUE;
            case "false" -> Boolean.FALSE;
            default -> null;
        };
    }
}
