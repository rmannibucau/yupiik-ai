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
package io.yupiik.ia.agent.bot.runtime;

import io.yupiik.fusion.json.internal.JsonMapperImpl;
import io.yupiik.fusion.json.serialization.JsonCodec;
import io.yupiik.ia.agent.bot.runtime.execution.NonoExecutionBackend$Profile$FusionJsonCodec;
import io.yupiik.ia.agent.bot.runtime.execution.NonoExecutionBackend$ProfileEnvironment$FusionJsonCodec;
import io.yupiik.ia.agent.bot.runtime.execution.NonoExecutionBackend$ProfileFilesystem$FusionJsonCodec;
import io.yupiik.ia.agent.bot.runtime.execution.NonoExecutionBackend$ProfileGroups$FusionJsonCodec;
import io.yupiik.ia.agent.bot.runtime.execution.NonoExecutionBackend$ProfileLinux$FusionJsonCodec;
import io.yupiik.ia.agent.bot.runtime.execution.NonoExecutionBackend$ProfileMeta$FusionJsonCodec;
import io.yupiik.ia.agent.bot.runtime.execution.NonoExecutionBackend$ProfileSecurity$FusionJsonCodec;
import io.yupiik.ia.agent.bot.runtime.execution.NonoExecutionBackend$ProfileWorkdir$FusionJsonCodec;
import io.yupiik.ia.agent.bot.runtime.execution.NonoExecutionBackend$Session$FusionJsonCodec;
import io.yupiik.ia.agent.bot.runtime.execution.NonoExecutionBackend$Sessions$FusionJsonCodec;
import io.yupiik.ia.agent.bot.runtime.model.AgentConfigurationDocument$FusionJsonCodec;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Optional;
import java.util.function.Function;

public final class RuntimeJsonMapper {
    private RuntimeJsonMapper() {}

    public static JsonMapperImpl create(
            final Collection<JsonCodec<?>> codecs, final Function<String, Optional<String>> configuration) {
        final var all = new ArrayList<JsonCodec<?>>(codecs);
        all.add(new AgentConfigurationDocument$FusionJsonCodec());
        all.add(new NonoExecutionBackend$Profile$FusionJsonCodec());
        all.add(new NonoExecutionBackend$ProfileMeta$FusionJsonCodec());
        all.add(new NonoExecutionBackend$ProfileGroups$FusionJsonCodec());
        all.add(new NonoExecutionBackend$ProfileWorkdir$FusionJsonCodec());
        all.add(new NonoExecutionBackend$ProfileFilesystem$FusionJsonCodec());
        all.add(new NonoExecutionBackend$ProfileLinux$FusionJsonCodec());
        all.add(new NonoExecutionBackend$ProfileSecurity$FusionJsonCodec());
        all.add(new NonoExecutionBackend$ProfileEnvironment$FusionJsonCodec());
        all.add(new NonoExecutionBackend$Sessions$FusionJsonCodec());
        all.add(new NonoExecutionBackend$Session$FusionJsonCodec());
        return new JsonMapperImpl(all, configuration::apply);
    }
}
