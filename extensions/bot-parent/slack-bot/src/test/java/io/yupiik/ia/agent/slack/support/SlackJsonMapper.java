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
package io.yupiik.ia.agent.slack.support;

import io.yupiik.fusion.json.internal.JsonMapperImpl;
import io.yupiik.fusion.json.internal.codec.EnumJsonCodec;
import io.yupiik.fusion.json.serialization.JsonCodec;
import io.yupiik.ia.agent.slack.model.*;
import io.yupiik.ia.agent.slack.service.SlackWorker$Result$FusionJsonCodec;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/** Explicit wire codecs for tests which intentionally do not boot the container. */
public final class SlackJsonMapper {
    private SlackJsonMapper() {}

    public static JsonMapperImpl create(final JsonCodec<?>... additional) {
        return new JsonMapperImpl(
                Stream.concat(
                                Stream.of(additional),
                                Stream.of(
                                        new EnumJsonCodec<>(
                                                TaskStatus.class, List.of(TaskStatus.values()), TaskStatus::name),
                                        new SlackWorker$Result$FusionJsonCodec(),
                                        new SlackJson$ApiResponse$FusionJsonCodec(),
                                        new SlackJson$ConnectionResponse$FusionJsonCodec(),
                                        new SlackJson$UserInfoResponse$FusionJsonCodec(),
                                        new SlackJson$Member$FusionJsonCodec(),
                                        new SlackJson$Profile$FusionJsonCodec(),
                                        new SlackJson$ThreadRequest$FusionJsonCodec(),
                                        new SlackJson$ThreadResponse$FusionJsonCodec(),
                                        new SlackJson$ThreadMessage$FusionJsonCodec(),
                                        new SlackJson$ThreadEdited$FusionJsonCodec(),
                                        new SlackJson$SearchRequest$FusionJsonCodec(),
                                        new SlackJson$SearchResponse$FusionJsonCodec(),
                                        new SlackJson$SearchResults$FusionJsonCodec(),
                                        new SlackJson$SearchMessage$FusionJsonCodec(),
                                        new SlackJson$ResponseMetadata$FusionJsonCodec(),
                                        new SlackJson$ViewIdentity$FusionJsonCodec(),
                                        new SlackJson$Envelope$FusionJsonCodec(),
                                        new SlackJson$Payload$FusionJsonCodec(),
                                        new SlackJson$Mention$FusionJsonCodec(),
                                        new SlackJson$Identity$FusionJsonCodec(),
                                        new SlackJson$Message$FusionJsonCodec(),
                                        new SlackJson$Action$FusionJsonCodec(),
                                        new SlackJson$View$FusionJsonCodec(),
                                        new SlackJson$InputState$FusionJsonCodec(),
                                        new SlackJson$Reply$FusionJsonCodec(),
                                        new SlackJson$Ack$FusionJsonCodec(),
                                        new SlackJson$PostMessage$FusionJsonCodec(),
                                        new SlackJson$OpenView$FusionJsonCodec(),
                                        new SlackJson$Text$FusionJsonCodec(),
                                        new SlackJson$Modal$FusionJsonCodec()))
                        .toList(),
                ignored -> Optional.empty());
    }
}
