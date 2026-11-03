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

/**
 * Root {@code yca.notifications} settings: opt-in OS notifications fired when an agent turn ends for a
 * session. This is a direct configuration root (not a hook): it controls an in-process desktop
 * notification rather than an external-program callback.
 */
@JsonModel
public record NotificationsConfiguration(
        @Property(
                defaultValue = "false",
                documentation = "Send an OS notification when an agent turn ends for a session (success or failure)."
                        + " Disabled by default; when enabled the notification is best-effort and never"
                        + " fails or slows the turn.")
        boolean enabled,

        @Property(
                documentation = "Optional custom program run instead of the built-in OS integration. Each entry is one"
                        + " argument (command and its args). The program receives the notification data as"
                        + " environment variables: YCA_NOTIFICATION_SESSION, YCA_NOTIFICATION_TITLE,"
                        + " YCA_NOTIFICATION_MODEL, YCA_NOTIFICATION_PROVIDER, YCA_NOTIFICATION_SUCCESS and"
                        + " YCA_NOTIFICATION_BODY. When set and non-empty it is used instead of the default"
                        + " OS integration.")
        List<String> command) {}
