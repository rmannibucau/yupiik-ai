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
import java.util.List;

@JsonModel
public record ProviderSubagentConfiguration(
        @Property(
                defaultValue = "null",
                documentation =
                        "Allow automatic alternative-model routing; null inherits yca.subagents.allowModelOverride (false by default).")
        Boolean allowModelOverride,

        @Property(
                documentation =
                        "Exact $provider/model routes automatically available when overrides are enabled. Null inherits global includes; empty permits any configured destination.")
        List<String> includes,

        @Property(
                documentation =
                        "Exact $provider/model hard denies, in addition to global excludes. User approval cannot override these.")
        List<String> excludes,

        @Property(
                documentation =
                        "Default sub-agent model, subject to route policy. Null inherits the global defaultModel; blank inherits the parent route. Ignored when overrides are disabled.")
        String defaultModel) {
    public ProviderSubagentConfiguration {
        includes = includes == null ? null : List.copyOf(includes);
        excludes = excludes == null ? null : List.copyOf(excludes);
    }
}
