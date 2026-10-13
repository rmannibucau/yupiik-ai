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
package io.yupiik.ia.agent.core.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.configuration.TestConfigs;
import io.yupiik.ia.agent.test.TestJsonMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

@ExtendWith(TestJsonMapper.class)
class ContextWindowCacheTest {
    @TempDir
    Path storageHome;

    private final Clock clock = Clock.systemUTC();

    private ContextWindowCache cache(final JsonMapper mapper) {
        final var configuration = TestConfigs.codingAgent(Map.of("yca.storageHome", storageHome.toString()));
        return new ContextWindowCache(configuration, mapper, clock);
    }

    private Path cacheFile() {
        return Path.of(
                TestConfigs.codingAgent(Map.of("yca.storageHome", storageHome.toString()))
                        .resolveStorageHome(),
                "context-window",
                "windows.jsonl");
    }

    private long now() {
        return clock.millis();
    }

    @Test
    void holdingAtMostOneEntryPerProviderAndModel(final JsonMapper mapper) throws Exception {
        final var cache = cache(mapper);
        cache.put("freeinference", "deepseek-v4-flash", 1_000_000, 393_216, now());
        cache.put("freeinference", "glm-5.3", 1_000_000, 131_072, now());
        // re-fetch of the same pair must replace, not append
        cache.put("freeinference", "deepseek-v4-flash", 1_000_000, 393_216, now() + 1);

        assertSingleEntryPerKey(cache, mapper);
        assertEquals(Optional.of(1_000_000), cache.get("freeinference", "deepseek-v4-flash"));
        assertEquals(Optional.of(1_000_000), cache.get("freeinference", "glm-5.3"));
    }

    @Test
    void freshestOverflowHalvingReplacesTheDetectedWindow(final JsonMapper mapper) throws Exception {
        final var cache = cache(mapper);
        cache.put("freeinference", "deepseek-v4-flash", 500_000, 131_072, now()); // /models detection
        // overflow halving writes the corrected (fresher) value for the same pair
        cache.put("freeinference", "deepseek-v4-flash", 250_000, null, now() + 1);

        assertSingleEntryPerKey(cache, mapper);
        assertEquals(Optional.of(250_000), cache.get("freeinference", "deepseek-v4-flash"));
        assertEquals(Optional.empty(), cache.getMaxTokens("freeinference", "deepseek-v4-flash"));
    }

    @Test
    void distinctProvidersAndModelsArePreserved(final JsonMapper mapper) throws Exception {
        final var cache = cache(mapper);
        cache.put("freeinference", "deepseek-v4-flash", 1_000_000, 393_216, now());
        cache.put("pro", "deepseek-v4-flash", 128_000, 32_768, now()); // same model, other provider
        cache.put("pro", "other-model", 64_000, 16_384, now());

        assertSingleEntryPerKey(cache, mapper);
        assertEquals(Optional.of(1_000_000), cache.get("freeinference", "deepseek-v4-flash"));
        assertEquals(Optional.of(128_000), cache.get("pro", "deepseek-v4-flash"));
        assertEquals(Optional.of(64_000), cache.get("pro", "other-model"));
    }

    @Test
    void legacyDuplicateLinesSelfHealOnNextWrite(final JsonMapper mapper) throws Exception {
        final var file = cacheFile();
        Files.createDirectories(file.getParent());
        // duplicate lines for the same pair (as legacy appends produce), both fresh enough to be read
        Files.writeString(file, """
                {"providerId":"freeinference","model":"deepseek-v4-flash","window":1000000,"maxTokens":393216,"fetchedAt":%s}
                {"providerId":"freeinference","model":"deepseek-v4-flash","window":1000000,"maxTokens":393216,"fetchedAt":%s}
                """.formatted(now(), now() + 1));

        final var cache = cache(mapper);
        // read still resolves to the freshest duplicate
        assertEquals(Optional.of(1_000_000), cache.get("freeinference", "deepseek-v4-flash"));
        // a write to a *different* key must collapse the duplicates for a key that is re-written too
        cache.put("freeinference", "glm-5.3", 1_000_000, 131_072, now());
        assertSingleEntryPerKey(cache, mapper);
        assertEquals(Optional.of(1_000_000), cache.get("freeinference", "deepseek-v4-flash"));
        assertEquals(Optional.of(1_000_000), cache.get("freeinference", "glm-5.3"));
    }

    @Test
    void tmpFileIsNotLeftBehind(final JsonMapper mapper) throws Exception {
        final var cache = cache(mapper);
        cache.put("freeinference", "deepseek-v4-flash", 1_000_000, 393_216, now());
        assertTrue(Files.list(cacheFile().getParent())
                .noneMatch(p -> p.getFileName().toString().endsWith(".tmp")));
    }

    private void assertSingleEntryPerKey(final ContextWindowCache cache, final JsonMapper mapper) throws Exception {
        final var file = cacheFile();
        assertTrue(Files.isRegularFile(file), "cache file must exist after a write");
        final var lines =
                Files.readAllLines(file).stream().filter(l -> !l.isBlank()).toList();
        final var keys = lines.stream()
                .map(l -> mapper.fromString(ContextWindowCache.Entry.class, l))
                .map(e -> e.providerId() + "/" + e.model())
                .toList();
        assertEquals(keys.stream().distinct().toList(), keys, "no duplicate (providerId, model) pair may appear");
    }
}
