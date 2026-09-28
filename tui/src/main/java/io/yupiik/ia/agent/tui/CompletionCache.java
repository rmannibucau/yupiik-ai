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

import java.util.List;
import java.util.Map;

/**
 * Cached completion value sources. Populated asynchronously on the shared
 * executor so the render thread never blocks on a network/IO round-trip
 * while completing. Each field is replaced atomically with an immutable
 * list (or map); readers just call get.
 *
 * <p>Model listings are provider-scoped: {@link #invalidateModels()} is called
 * when the provider switches so a stale list from the previous provider (whose
 * model ids may look alike, e.g. {@code glm-5.3} vs {@code z-ai/glm-5.3}) is
 * never offered for completion. The {@code modelGeneration} monotonically
 * grows with every models/variants update; a background refresh that started
 * before the invalidation has its result dropped when it lands afterwards,
 * so an old provider's refresh can never overwrite a newer list.</p>
 */
public final class CompletionCache {
    private volatile List<String> models = List.of();
    private volatile List<String> sessions = List.of();
    private volatile List<String> tools = List.of();
    private volatile Map<String, List<String>> variants = Map.of();
    private volatile long modelGeneration;

    /** The generation of the models/variants cache; increases on every update and invalidation. */
    public long modelGeneration() {
        return modelGeneration;
    }

    public List<String> models() {
        return models;
    }

    public void models(final List<String> value) {
        models = value == null ? List.of() : value;
        modelGeneration++;
    }

    /**
     * Applies a fresh model list only when the cache generation still matches {@code expectedGeneration}
     * (captured before an asynchronous fetch). Returns {@code false} when the cache changed meanwhile -
     * e.g. a provider switch invalidated it - so an old provider's refresh never overwrites a newer list.
     */
    public boolean updateModels(final long expectedGeneration, final List<String> value) {
        if (modelGeneration != expectedGeneration) {
            return false;
        }
        models = value == null ? List.of() : value;
        modelGeneration++;
        return true;
    }

    /** Clears the provider-scoped models and variants, bumping the generation so in-flight refreshes are dropped. */
    public void invalidateModels() {
        models = List.of();
        variants = Map.of();
        modelGeneration++;
    }

    public List<String> sessions() {
        return sessions;
    }

    public void sessions(final List<String> value) {
        sessions = value == null ? List.of() : value;
    }

    public List<String> tools() {
        return tools;
    }

    public void tools(final List<String> value) {
        tools = value == null ? List.of() : value;
    }

    public List<String> variants(final String model) {
        final var map = variants;
        return model == null ? List.of() : map.getOrDefault(model, List.of());
    }

    public void variants(final Map<String, List<String>> value) {
        variants = value == null ? Map.of() : value;
        modelGeneration++;
    }

    /**
     * Applies a fresh variants map only when the cache generation still matches {@code expectedGeneration}
     * (captured before the asynchronous lookup). Returns {@code false} when the cache changed meanwhile.
     */
    public boolean updateVariants(final long expectedGeneration, final Map<String, List<String>> value) {
        if (modelGeneration != expectedGeneration) {
            return false;
        }
        variants = value == null ? Map.of() : value;
        modelGeneration++;
        return true;
    }
}
