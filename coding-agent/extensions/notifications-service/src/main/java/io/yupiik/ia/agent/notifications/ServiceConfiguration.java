/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.yupiik.ia.agent.notifications;

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

@RootConfiguration("notification-service")
public record ServiceConfiguration(
        @Property(defaultValue = "11026", documentation = "Local notification service port.")
        int port,

        @Property(documentation = "Shared notification service authentication token.")
        String token,

        @Property(defaultValue = "true", documentation = "Blink the tray icon for unread activity.")
        boolean blink,

        @Property(
                value = "token-file",
                documentation = "Optional UTF-8 file containing the shared authentication token.")
        String tokenFile) {
    public ServiceConfiguration {
        if (tokenFile != null && !tokenFile.isBlank()) {
            if (token != null && !token.isBlank()) {
                throw new IllegalArgumentException(
                        "Set only one of notification-service-token and notification-service-token-file.");
            }
            try {
                token = Files.readString(Path.of(tokenFile), StandardCharsets.UTF_8)
                        .strip();
            } catch (final IOException | InvalidPathException | SecurityException error) {
                throw new IllegalArgumentException("Cannot read notification-service-token-file as UTF-8.");
            }
        }
    }
}
