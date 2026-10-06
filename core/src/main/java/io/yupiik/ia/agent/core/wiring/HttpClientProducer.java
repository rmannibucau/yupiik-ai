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
package io.yupiik.ia.agent.core.wiring;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.scanning.Bean;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClientConfiguration;
import io.yupiik.fusion.httpclient.core.listener.RequestListener;
import io.yupiik.fusion.httpclient.core.listener.impl.CurlLoggerListener;
import io.yupiik.fusion.httpclient.core.listener.impl.SetUserAgent;
import io.yupiik.fusion.httpclient.core.request.UnlockedHttpRequest;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

@DefaultScoped
public class HttpClientProducer {
    private static final String DEFAULT_CHROME_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    @Bean
    @ApplicationScoped
    public ExtendedHttpClient httpClient(final CodingAgentConfiguration configuration) {
        final var cfg = configuration.httpClient();
        final var builder = HttpClient.newBuilder()
                .version(version(cfg.version()))
                .connectTimeout(Duration.ofSeconds(cfg.connectTimeoutSeconds() <= 0 ? 30 : cfg.connectTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NORMAL);
        final var proxy = cfg.proxy();
        if (proxy != null && proxy.host() != null && !proxy.host().isBlank()) {
            builder.proxy(ProxySelector.of(InetSocketAddress.createUnresolved(proxy.host(), proxy.port())));
            if (proxy.user() != null && !proxy.user().isBlank()) {
                builder.authenticator(new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        if (getRequestorType() == RequestorType.PROXY) {
                            return new PasswordAuthentication(
                                    proxy.user(), (proxy.password() == null ? "" : proxy.password()).toCharArray());
                        }
                        return null;
                    }
                });
            }
        }
        if (cfg.disableTlsCheck()) {
            builder.sslContext(trustAllSslContext());
            builder.sslParameters(new javax.net.ssl.SSLParameters() {
                {
                    setEndpointIdentificationAlgorithm(null);
                }
            });
        }
        final var listeners = new ArrayList<RequestListener<?>>();
        if (cfg.headers() != null && !cfg.headers().isEmpty()) {
            listeners.add(new SetHeader(cfg.headers()));
        }
        listeners.add(new SetUserAgent(DEFAULT_CHROME_UA));
        if (cfg.debug()) {
            listeners.add(new CurlLoggerListener(
                    new CurlLoggerListener.Configuration(java.util.logging.Logger.getLogger("yca.http"))
                            .setLogPayload(true)));
        }
        return new ExtendedHttpClient(new ExtendedHttpClientConfiguration()
                .setDelegate(builder.build())
                .setRequestListeners(listeners));
    }

    private static HttpClient.Version version(final String value) {
        if (value != null
                && value.trim()
                        .toUpperCase(Locale.ROOT)
                        .replace('-', '_')
                        .replace('/', '_')
                        .contains("HTTP_2")) {
            return HttpClient.Version.HTTP_2;
        }
        return HttpClient.Version.HTTP_1_1;
    }

    private static SSLContext trustAllSslContext() {
        final var tm = new X509TrustManager() {
            @Override
            public void checkClientTrusted(final X509Certificate[] chain, final String authType) {}

            @Override
            public void checkServerTrusted(final X509Certificate[] chain, final String authType) {}

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        try {
            final var ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[] {tm}, new SecureRandom());
            return ctx;
        } catch (final Exception e) {
            throw new IllegalStateException("Cannot build trust-all SSL context", e);
        }
    }

    private record SetHeader(Map<String, String> headers) implements RequestListener<Void> {
        private SetHeader {
            final var normalized = new java.util.LinkedHashMap<String, String>();
            for (final var e : headers.entrySet()) {
                normalized.put(headerName(e.getKey()), e.getValue());
            }
            headers = Map.copyOf(normalized);
        }

        private static String headerName(final String key) {
            final var sb = new StringBuilder(key.length());
            boolean upper = true;
            for (final char c : key.toCharArray()) {
                if (c == '-' || c == '_') {
                    upper = true;
                    sb.append('-');
                } else if (upper) {
                    sb.append(Character.toUpperCase(c));
                    upper = false;
                } else {
                    sb.append(Character.toLowerCase(c));
                }
            }
            return sb.toString();
        }

        @Override
        public State<Void> before(final long count, final HttpRequest request) {
            final var map = request.headers().map();
            final var newMap = new TreeMap<String, List<String>>(String.CASE_INSENSITIVE_ORDER);
            newMap.putAll(map);
            headers.forEach((k, v) -> newMap.put(k, List.of(v)));
            return new State<>(
                    new UnlockedHttpRequest(
                            request.bodyPublisher(),
                            request.method(),
                            request.timeout(),
                            request.expectContinue(),
                            request.uri(),
                            request.version(),
                            HttpHeaders.of(newMap, (k, v) -> true)),
                    null);
        }
    }
}
