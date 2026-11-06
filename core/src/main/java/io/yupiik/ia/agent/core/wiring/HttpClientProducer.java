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
import io.yupiik.ia.agent.core.configuration.HttpClientCertificateConfiguration;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieManager;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

@DefaultScoped
public class HttpClientProducer {
    @Bean
    @ApplicationScoped
    public ExtendedHttpClient httpClient(final CodingAgentConfiguration configuration) {
        final var cfg = configuration.httpClient();
        if (cfg.disableTlsCheck()
                && System.getProperty("jdk.internal.httpclient.disableHostnameVerification") == null) {
            System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
        }
        final var builder = HttpClient.newBuilder()
                .version(version(cfg.version()))
                .connectTimeout(Duration.ofSeconds(cfg.connectTimeoutSeconds() <= 0 ? 30 : cfg.connectTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NORMAL);
        if (cfg.enableCookies()) {
            builder.cookieHandler(new CookieManager());
        }
        final var proxy = cfg.proxy();
        if (proxy != null && proxy.host() != null && !proxy.host().isBlank()) {
            final var noProxyHosts = proxy.noProxyHosts() == null ? List.<String>of() : proxy.noProxyHosts();
            final var address = InetSocketAddress.createUnresolved(proxy.host(), proxy.port());
            builder.proxy(proxySelector(address, noProxyHosts));
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
            builder.sslParameters(new SSLParameters() {
                {
                    setEndpointIdentificationAlgorithm(null);
                }
            });
        } else if (hasCertificates(cfg.certificates())) {
            builder.sslContext(certificateSslContext(cfg.certificates()));
        }
        final var listeners = new ArrayList<RequestListener<?>>();
        if (cfg.headers() != null && !cfg.headers().isEmpty()) {
            listeners.add(new SetHeader(cfg.headers()));
        }
        listeners.add(
                new SetUserAgent(
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"));
        if (cfg.debug()) {
            listeners.add(new CurlLoggerListener(
                    new CurlLoggerListener.Configuration(Logger.getLogger("yca.http")).setLogPayload(true)));
        }
        final var transport = new OpenCodeHttpClient(
                builder.build(), hasCertificates(cfg.certificates()) ? certificateKeyStore(cfg.certificates()) : null);
        return new ExtendedHttpClient(
                new ExtendedHttpClientConfiguration().setDelegate(transport).setRequestListeners(listeners)) {
            @Override
            public void close() {
                try {
                    super.close();
                } finally {
                    transport.close();
                }
            }
        };
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

    private static boolean hasCertificates(final HttpClientCertificateConfiguration certificates) {
        return certificates != null
                && certificates.custom() != null
                && !certificates.custom().isEmpty();
    }

    /**
     * A proxy selector honoring the configured no-proxy hosts. With an empty list it behaves
     * exactly like {@link ProxySelector#of(InetSocketAddress)} (the historical behavior):
     * every URI resolves to the single configured proxy, and {@link Proxy#NO_PROXY} otherwise
     * (blank-host URI which carries no proxy concept).
     */
    private static ProxySelector proxySelector(final InetSocketAddress proxy, final List<String> noProxyHosts) {
        if (noProxyHosts.isEmpty()) {
            return ProxySelector.of(proxy);
        }
        return new ProxySelector() {
            @Override
            public List<Proxy> select(final URI uri) {
                final var host = uri.getHost();
                if (host == null || matchesNoProxy(host, noProxyHosts)) {
                    return List.of(Proxy.NO_PROXY);
                }
                return List.of(new Proxy(Proxy.Type.HTTP, proxy));
            }

            @Override
            public void connectFailed(final URI uri, final SocketAddress sa, final IOException ioe) {
                // nothing to do, matching ProxySelector.of
            }
        };
    }

    /**
     * Case-insensitive no-proxy matching. An entry is one of: {@code *} (everything),
     * {@code .domain} (the domain itself and any sub-domain), {@code *.domain} (any
     * sub-domain, not the domain itself), or an exact host. A single {@code *} can appear
     * anywhere in a pattern ({@code *corp*}); blank entries are ignored.
     */
    static boolean matchesNoProxy(final String host, final List<String> entries) {
        final var h = host.toLowerCase(Locale.ROOT);
        for (final var raw : entries) {
            final var entry = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
            if (entry.isEmpty()) {
                continue;
            }
            if ("*".equals(entry) || glob(entry, h)) {
                return true;
            }
            final var dotSuffix = entry.startsWith(".");
            final var core = dotSuffix ? entry.substring(1) : entry;
            if (h.equals(core) || (dotSuffix && h.endsWith("." + core))) {
                return true;
            }
        }
        return false;
    }

    private static boolean glob(final String pattern, final String value) {
        if (pattern.indexOf('*') < 0) {
            return false;
        }
        final var regex = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            final var c = pattern.charAt(i);
            regex.append(c == '*' ? ".*" : Pattern.quote(String.valueOf(c)));
        }
        return value.matches(regex.toString());
    }

    /** Builds an SSL context trusting the JVM global trust store (optional) plus the configured PEMs. */
    private static SSLContext certificateSslContext(final HttpClientCertificateConfiguration certificates) {
        try {
            final var tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(certificateKeyStore(certificates));
            final var ctx = SSLContext.getInstance("TLS");
            ctx.init(null, tmf.getTrustManagers(), new SecureRandom());
            return ctx;
        } catch (final Exception e) {
            throw new IllegalStateException("Cannot build custom-certificates SSL context", e);
        }
    }

    private static KeyStore certificateKeyStore(final HttpClientCertificateConfiguration certificates) {
        try {
            final var keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            keyStore.load(null, null);
            if (certificates.importGlobalCertificate()) {
                importGlobalTrustStore(keyStore);
            }
            final var factory = CertificateFactory.getInstance("X.509");
            final var custom = certificates.custom();
            if (custom != null) {
                for (int i = 0; i < custom.size(); i++) {
                    final var pem = custom.get(i).pem();
                    if (pem == null || pem.isBlank()) {
                        continue;
                    }
                    final var certificate = (X509Certificate) factory.generateCertificate(
                            new ByteArrayInputStream(Base64.getMimeDecoder().decode(pemBody(pem))));
                    keyStore.setCertificateEntry("custom-" + i, certificate);
                }
            }
            return keyStore;
        } catch (final Exception e) {
            throw new IllegalStateException("Cannot build custom-certificates SSL context", e);
        }
    }

    /**
     * Seeds {@code keyStore} with the JVM global trust store: {@code javax.net.ssl.trustStore}
     * when set and loadable, else the JDK cacerts (falling back from the default password to
     * an empty one). Failures to load the global store are ignored (the agent keeps working
     * with the custom certificates only).
     */
    private static void importGlobalTrustStore(final KeyStore keyStore) {
        try {
            final var props = System.getProperties();
            final var configured = props.getProperty("javax.net.ssl.trustStore");
            final var path = configured == null || configured.isBlank()
                    ? Path.of(System.getProperty("java.home"), "lib", "security", "cacerts")
                    : Path.of(configured);
            if (!Files.isReadable(path)) {
                return;
            }
            final var type = props.getProperty("javax.net.ssl.trustStoreType", KeyStore.getDefaultType());
            final var source = KeyStore.getInstance(type);
            final var password = props.getProperty("javax.net.ssl.trustStorePassword", "changeit");
            try (final var in = Files.newInputStream(path)) {
                try {
                    source.load(in, password.toCharArray());
                } catch (final IOException wrong) {
                    try (final var retry = Files.newInputStream(path)) {
                        source.load(retry, new char[0]);
                    }
                }
            }
            final var aliases = source.aliases();
            while (aliases.hasMoreElements()) {
                final var alias = aliases.nextElement();
                if (source.isCertificateEntry(alias)) {
                    keyStore.setCertificateEntry("global-" + alias, source.getCertificate(alias));
                }
            }
        } catch (final Exception e) {
            LOGGER.warning("Cannot import the JVM global trust store: " + e.getMessage());
        }
    }

    private static String pemBody(final String pem) {
        final var matcher = PEM_BEGIN.matcher(pem);
        if (!matcher.find()) {
            throw new IllegalArgumentException(
                    "No PEM certificate marker (-----BEGIN CERTIFICATE-----) found in: " + pem);
        }
        final var end = matcher.end();
        final var closing = pem.indexOf("-----END CERTIFICATE-----", end);
        if (closing < 0) {
            throw new IllegalArgumentException("Missing PEM end marker (-----END CERTIFICATE-----) in: " + pem);
        }
        return pem.substring(end, closing);
    }

    private static final Logger LOGGER = Logger.getLogger(HttpClientProducer.class.getName());
    private static final Pattern PEM_BEGIN = Pattern.compile("-----BEGIN CERTIFICATE-----\\s*", Pattern.DOTALL);

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
            final var normalized = new LinkedHashMap<String, String>();
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
