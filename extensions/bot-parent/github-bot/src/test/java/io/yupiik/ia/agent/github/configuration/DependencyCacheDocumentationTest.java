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
package io.yupiik.ia.agent.github.configuration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.json.configuration.JsonConfigurationSource;
import io.yupiik.ia.agent.github.command.WorkerCommand$Options$FusionConfigurationFactory;
import io.yupiik.ia.agent.github.execution.cache.DependencyCacheProvisioning;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DependencyCacheDocumentationTest {
    @TempDir
    Path temporary;

    @Test
    void childPageJsonExamplesBindThroughTheRealConfigurationSourceAndProvision() throws Exception {
        final var document = Path.of("../../../documentation/src/main/minisite/content/github-bot/cache.adoc")
                .toAbsolutePath()
                .normalize();
        final var source = Files.readString(document);
        final var matcher =
                Pattern.compile("(?ms)^\\[source,json]\\R----\\R(.*?)\\R----").matcher(source);
        var examples = 0;
        while (matcher.find()) {
            final var example = matcher.group(1);
            final var json = new JsonConfigurationSource(() -> new StringReader(example), key -> key.replace('-', '.'));
            final var options = new WorkerCommand$Options$FusionConfigurationFactory(
                            key -> Optional.ofNullable(json.get(key)))
                    .get();
            final var policy = options.worker().dependencyCache();
            final var attempt = temporary.resolve("example-" + examples++);
            final var environment = DependencyCacheProvisioning.provision(policy, attempt, true);
            if (policy.enabled()) {
                assertFalse(policy.resolvedEntries().isEmpty());
                environment.forEach((key, value) -> assertFalse(value.contains("${"), key));
                for (final var file : policy.resolvedFiles()) {
                    assertTrue(Files.isRegularFile(attempt.resolve("storage").resolve(file.path())));
                    assertFalse(Files.readString(attempt.resolve("storage").resolve(file.path()))
                            .contains("${"));
                }
            } else {
                assertTrue(environment.isEmpty());
                assertFalse(Files.exists(attempt));
            }
        }
        assertTrue(examples >= 3, "Defaults, preset override and custom cache examples must remain executable");
    }
}
