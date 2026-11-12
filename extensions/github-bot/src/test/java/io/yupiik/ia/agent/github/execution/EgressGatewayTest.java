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
package io.yupiik.ia.agent.github.execution;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EgressGatewayTest {
    @Test
    void exactHostValidationIsBoundedAndRejectsWildcardAuthorityAndIpForms() {
        assertEquals(Set.of("api.github.com"), EgressGateway.validateHosts(List.of("API.GitHub.COM")));
        for (final var host : List.of(
                "*.github.com",
                "github.com:443",
                "127.0.0.1",
                "::1",
                "localhost",
                "github.com.",
                "user@github.com",
                "a..com",
                "-a.com",
                "a-.com",
                "a".repeat(254))) {
            assertThrows(IllegalArgumentException.class, () -> EgressGateway.validateHosts(List.of(host)), host);
        }
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
            assertThrows(
                    IllegalArgumentException.class, () -> EgressGateway.validateHosts(List.of("a".repeat(1_000_000))));
        });
    }

    @Test
    void rejectsPrivateSpecialAndMappedAddresses() throws Exception {
        for (final var ip : List.of(
                "0.0.0.0",
                "127.0.0.1",
                "10.1.2.3",
                "172.16.0.1",
                "192.168.1.1",
                "169.254.169.254",
                "100.64.0.1",
                "192.0.2.1",
                "198.18.0.1",
                "198.51.100.1",
                "203.0.113.1",
                "224.0.0.1",
                "240.1.2.3",
                "::",
                "::1",
                "fe80::1",
                "fc00::1",
                "ff02::1",
                "::ffff:127.0.0.1",
                "64:ff9b::a00:1",
                "2001:db8::1",
                "2001::1",
                "3fff::1")) {
            assertFalse(EgressGateway.isPublicAddress(InetAddress.getByName(ip)), ip);
        }
        assertTrue(EgressGateway.isPublicAddress(InetAddress.getByName("1.1.1.1")));
        assertTrue(EgressGateway.isPublicAddress(InetAddress.getByName("2606:4700:4700::1111")));
    }

    @Test
    void loopbackProxyDeniesMethodsPortsUnlistedNamesAndSmugglingWithoutDns() throws Exception {
        try (final var gateway = new EgressGateway(
                new InetSocketAddress("127.0.0.1", 0), List.of("api.github.com"), Duration.ofSeconds(2))) {
            for (final var request : List.of(
                    "GET https://api.github.com/ HTTP/1.1\r\n\r\n",
                    "CONNECT api.github.com:80 HTTP/1.1\r\n\r\n",
                    "CONNECT evil.example:443 HTTP/1.1\r\n\r\n",
                    "CONNECT api.github.com.evil.example:443 HTTP/1.1\r\n\r\n",
                    "CONNECT api.github.com:443 HTTP/1.1\r\nContent-Length: 5\r\n\r\nhello",
                    "CONNECT api.github.com:443 HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n")) {
                try (final var socket = new Socket("127.0.0.1", gateway.port())) {
                    socket.setSoTimeout(3_000);
                    socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
                    assertTrue(new String(socket.getInputStream().readNBytes(12), StandardCharsets.US_ASCII)
                            .startsWith("HTTP/1.1 403"));
                }
            }
        }
    }

    @Test
    void boundsOversizedSlowAndIdleClientsAndCloseReleasesConnections() throws Exception {
        final var gateway = new EgressGateway(
                new InetSocketAddress("127.0.0.1", 0), List.of("api.github.com"), Duration.ofMillis(150));
        try (gateway;
                final var socket = new Socket("127.0.0.1", gateway.port())) {
            socket.setSoTimeout(2_000);
            socket.getOutputStream().write('C');
            assertTimeoutPreemptively(
                    Duration.ofSeconds(2),
                    () -> assertEquals(-1, socket.getInputStream().read()));
        }
        try (final var second = new EgressGateway(
                        new InetSocketAddress("127.0.0.1", 0), List.of("api.github.com"), Duration.ofSeconds(2));
                final var socket = new Socket("127.0.0.1", second.port())) {
            socket.setSoTimeout(3_000);
            socket.getOutputStream().write("X".repeat(8192).getBytes(StandardCharsets.US_ASCII));
            assertTrue(new String(socket.getInputStream().readNBytes(12), StandardCharsets.US_ASCII)
                    .startsWith("HTTP/1.1 403"));
        }
    }
}
