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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Generation-guarded completion cache: a slow background refresh of an old
 * provider must never overwrite the model list of the provider that was
 * selected while the refresh was in flight (e.g. {@code glm-5.3} vs
 * {@code z-ai/glm-5.3}).
 */
class CompletionCacheTest {

    @Test
    void updateModelsAppliesWhenGenerationIsCurrent() {
        final var cache = new CompletionCache();
        final var generation = cache.modelGeneration();
        assertTrue(cache.updateModels(generation, List.of("z-ai/glm-5.3-flash")));
        assertEquals(List.of("z-ai/glm-5.3-flash"), cache.models());
    }

    @Test
    void updateModelsIsDroppedWhenCacheWasInvalidatedMeanwhile() {
        final var cache = new CompletionCache();
        final var generation = cache.modelGeneration();
        cache.invalidateModels(); // e.g. the user switched provider while the fetch was in flight
        assertFalse(cache.updateModels(generation, List.of("glm-5.3")));
        assertTrue(cache.models().isEmpty(), "the stale list must not be applied");
    }

    @Test
    void updateModelsIsDroppedWhenANewerListAlreadyLanded() {
        final var cache = new CompletionCache();
        final var staleGeneration = cache.modelGeneration();
        cache.models(List.of("nvidia/current"));
        assertFalse(cache.updateModels(staleGeneration, List.of("old-provider/stale")));
        assertEquals(List.of("nvidia/current"), cache.models());
    }

    @Test
    void invalidateClearsModelsAndVariantsAndAdvancesGeneration() {
        final var cache = new CompletionCache();
        cache.models(List.of("a", "b"));
        cache.variants(Map.of("a", List.of("default")));
        final var before = cache.modelGeneration();
        cache.invalidateModels();
        assertTrue(cache.models().isEmpty());
        assertTrue(cache.variants("a").isEmpty());
        assertTrue(cache.modelGeneration() > before);
    }

    @Test
    void updateVariantsIsDroppedWhenCacheWasInvalidatedMeanwhile() {
        final var cache = new CompletionCache();
        final var generation = cache.modelGeneration();
        cache.invalidateModels();
        assertFalse(cache.updateVariants(generation, Map.of("m", List.of("default"))));
        assertTrue(cache.variants("m").isEmpty());
    }

    @Test
    void updateVariantsAppliesWhenGenerationIsCurrent() {
        final var cache = new CompletionCache();
        final var generation = cache.modelGeneration();
        assertTrue(cache.updateVariants(generation, Map.of("m", List.of("default", "fast"))));
        assertEquals(List.of("default", "fast"), cache.variants("m"));
    }
}
