/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.yupiik.ia.agent.notifications;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServiceConfigurationTest {
    @Test
    void loadsUtf8AndStripsSurroundingWhitespace(@TempDir final Path directory) throws Exception {
        final var file =
                Files.writeString(directory.resolve("token"), " \tshared-secret-\u00e9\r\n", StandardCharsets.UTF_8);
        assertEquals("shared-secret-\u00e9", new ServiceConfiguration(0, null, true, file.toString()).token());
        assertEquals("shared-secret-\u00e9", new ServiceConfiguration(0, " \t", true, file.toString()).token());
    }

    @Test
    void leavesTokenValidationToEndpoint(@TempDir final Path directory) throws Exception {
        final var file = Files.writeString(directory.resolve("token"), "\n");
        assertEquals("", new ServiceConfiguration(0, null, true, file.toString()).token());
        assertEquals("short", new ServiceConfiguration(0, "short", true, " \t").token());
        assertNull(new ServiceConfiguration(0, null, true, null).token());
    }

    @Test
    void rejectsSimultaneousSourcesWithoutDisclosingEither() {
        final var error = assertThrows(
                IllegalArgumentException.class,
                () -> new ServiceConfiguration(0, "secret-token", true, "secret-file-path"));
        assertEquals(
                "Set only one of notification-service-token and notification-service-token-file.", error.getMessage());
        assertNull(error.getCause());
    }

    @Test
    void readFailuresDoNotDisclosePathsContentsOrCauses(@TempDir final Path directory) throws Exception {
        final var malformed = Files.write(directory.resolve("private-token"), new byte[] {'s', 'e', 'c', (byte) 0xff});
        // A directory is not a readable token file, including when tests run as root.
        for (final var path : new String[] {
            directory.resolve("missing-secret").toString(), directory.toString(), "secret\0path", malformed.toString()
        }) {
            final var error =
                    assertThrows(IllegalArgumentException.class, () -> new ServiceConfiguration(0, null, true, path));
            assertEquals("Cannot read notification-service-token-file as UTF-8.", error.getMessage());
            assertNull(error.getCause());
            assertEquals(0, error.getSuppressed().length);
        }
    }
}
