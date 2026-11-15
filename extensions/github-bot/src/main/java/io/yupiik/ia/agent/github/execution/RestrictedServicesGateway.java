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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.json.internal.JsonMapperImpl;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIMatcher;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;

/** Fixed services only. Numeric private HTTP targets require an operator-trusted control network. */
public final class RestrictedServicesGateway implements AutoCloseable {
    public static final String YCA_CONTEXT_TOKEN = "YCA_CONTEXT_TOKEN";
    public static final String YCA_CONTEXT_GIT_TOKEN = "YCA_CONTEXT_GIT_TOKEN";
    public static final String YCA_SERVICES_PROVIDER_TOKEN = "YCA_SERVICES_PROVIDER_TOKEN";
    public static final String YCA_SERVICES_UPSTREAM_TOKEN = "YCA_SERVICES_UPSTREAM_TOKEN";
    public static final String YCA_SERVICES_KEYSTORE_PASSWORD = "YCA_SERVICES_KEYSTORE_PASSWORD";
    private static final String UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final Pattern CONTEXT = Pattern.compile("/context/" + UUID);
    private static final Pattern INFERENCE = Pattern.compile("/inference/" + UUID + "/chat/completions");
    private static final Pattern GIT = Pattern.compile("[A-Za-z0-9-]{1,80}\\.git/(info/refs|git-upload-pack)");
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final Pattern ENV = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");
    private static final Set<String> RESERVED_ENV = Set.of(
            "PATH",
            "HOME",
            "JAVA_TOOL_OPTIONS",
            "JDK_JAVA_OPTIONS",
            "_JAVA_OPTIONS",
            "ENV",
            "BASH_ENV",
            "SHELLOPTS",
            "BASHOPTS",
            "CDPATH",
            "IFS",
            "SSH_AUTH_SOCK",
            YCA_CONTEXT_TOKEN,
            YCA_CONTEXT_GIT_TOKEN,
            YCA_SERVICES_PROVIDER_TOKEN,
            YCA_SERVICES_UPSTREAM_TOKEN);
    private static final Pattern DNS = Pattern.compile(
            "(?=.{1,253}$)[a-z](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*");
    private static final Pattern LENGTH = Pattern.compile("[0-9]{1,10}");
    private static final Pattern IPV4 = Pattern.compile("[0-9]{1,3}(?:\\.[0-9]{1,3}){3}");
    private static final Pattern FIXED_PATH = Pattern.compile("/(?:[A-Za-z0-9_-]+/)*[A-Za-z0-9_-]+");
    private final Settings settings;
    private final HttpsServer server;
    private final HttpClient client;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledThreadPoolExecutor watchdog = new ScheduledThreadPoolExecutor(1);
    private final Semaphore slots = new Semaphore(4);
    private final Set<Lease> active = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final long expires;
    private volatile String control;
    private volatile String git;
    private volatile String provider;
    private volatile String upstream;

    @JsonModel
    public record Settings(
            String serverName,
            int port,
            String keyStore,
            String keyStorePasswordEnv,
            String contextRoute,
            String contextTarget,
            String inferencePath,
            String inferenceTarget,
            long lifetimeSeconds,
            int timeoutSeconds,
            long maxInferenceBytes,
            long maxGitBytes) {
        public Settings {
            if (serverName == null
                    || !DNS.matcher(serverName).matches()
                    || port < 0
                    || port > 65535
                    || keyStore == null
                    || keyStore.isBlank()
                    || keyStorePasswordEnv == null
                    || !ENV.matcher(keyStorePasswordEnv).matches()
                    || RESERVED_ENV.contains(keyStorePasswordEnv)
                    || keyStorePasswordEnv.startsWith("DOCKER_")
                    || keyStorePasswordEnv.startsWith("LD_")
                    || keyStorePasswordEnv.startsWith("DYLD_")
                    || keyStorePasswordEnv.startsWith("XDG_")
                    || contextRoute == null
                    || !CONTEXT.matcher(contextRoute).matches()
                    || inferencePath == null
                    || !INFERENCE.matcher(inferencePath).matches()
                    || lifetimeSeconds < 1
                    || lifetimeSeconds > 86_400
                    || timeoutSeconds < 1
                    || timeoutSeconds > 900
                    || maxInferenceBytes < 1
                    || maxInferenceBytes > 16 * 1024 * 1024
                    || maxGitBytes < 1
                    || maxGitBytes > 1024L * 1024 * 1024) {
                throw new IllegalArgumentException("Invalid services settings");
            }
            target(contextTarget, true);
            target(inferenceTarget, false);
        }
    }

    public RestrictedServicesGateway(final Settings settings, final Map<String, String> secrets) throws IOException {
        this.settings = settings;
        control = "Bearer " + secret(secrets, YCA_CONTEXT_TOKEN);
        git = "Basic "
                + Base64.getEncoder()
                        .encodeToString(("context:" + secret(secrets, YCA_CONTEXT_GIT_TOKEN))
                                .getBytes(StandardCharsets.US_ASCII));
        provider = "Bearer " + secret(secrets, YCA_SERVICES_PROVIDER_TOKEN);
        final var credential = secrets.get(YCA_SERVICES_UPSTREAM_TOKEN);
        if (credential != null
                && (credential.length() > 4096 || credential.chars().anyMatch(c -> c < 33 || c > 126))) {
            if (!credential.isEmpty()) {
                throw new IllegalArgumentException("Invalid upstream credential");
            }
        }
        upstream = credential == null || credential.isEmpty() ? null : "Bearer " + credential;
        final var tls = tls(settings, secrets.get(settings.keyStorePasswordEnv()));
        expires = System.nanoTime() + TimeUnit.SECONDS.toNanos(settings.lifetimeSeconds());
        server = HttpsServer.create(new InetSocketAddress("0.0.0.0", settings.port()), 4);
        server.setHttpsConfigurator(new HttpsConfigurator(tls) {
            @Override
            public void configure(final HttpsParameters parameters) {
                final var ssl = tls.getDefaultSSLParameters();
                ssl.setProtocols(new String[] {"TLSv1.3", "TLSv1.2"});
                ssl.setSNIMatchers(List.of(new SNIMatcher(0) {
                    @Override
                    public boolean matches(final SNIServerName name) {
                        return name instanceof SNIHostName host
                                && settings.serverName().equals(host.getAsciiName());
                    }
                }));
                parameters.setSSLParameters(ssl);
            }
        });
        client = HttpClient.newBuilder()
                .executor(executor)
                .connectTimeout(Duration.ofSeconds(settings.timeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .proxy(new ProxySelector() {
                    @Override
                    public List<Proxy> select(final URI uri) {
                        return List.of(Proxy.NO_PROXY);
                    }

                    @Override
                    public void connectFailed(final URI uri, final SocketAddress address, final IOException failure) {
                        // No destination or credential diagnostics.
                    }
                })
                .build();
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        watchdog.setRemoveOnCancelPolicy(true);
        server.start();
        watchdog.schedule(this::close, Math.max(0, expires - System.nanoTime()), TimeUnit.NANOSECONDS);
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private void handle(final HttpExchange exchange) {
        if (closed.get() || !slots.tryAcquire()) {
            fail(exchange, 503);
            return;
        }
        final var lease = new Lease(exchange);
        active.add(lease);
        final long remaining =
                Math.min(expires - System.nanoTime(), TimeUnit.SECONDS.toNanos(settings.timeoutSeconds()));
        if (remaining <= 0 || closed.get()) {
            lease.close();
            active.remove(lease);
            slots.release();
            return;
        }
        final var deadline =
                watchdog.schedule(() -> Thread.ofVirtual().start(lease::close), remaining, TimeUnit.NANOSECONDS);
        try {
            final var uri = exchange.getRequestURI();
            final var path = uri.getRawPath();
            final var headers = exchange.getRequestHeaders();
            final boolean inference = settings.inferencePath().equals(path);
            final boolean rpc = (settings.contextRoute() + "/rpc").equals(path);
            final var gitPrefix = settings.contextRoute() + "/git/";
            final boolean gitRoute = path != null
                    && path.startsWith(gitPrefix)
                    && GIT.matcher(path.substring(gitPrefix.length())).matches();
            if (!validHeaders(exchange)
                    || uri.isAbsolute()
                    || uri.getRawAuthority() != null
                    || uri.getRawFragment() != null
                    || path == null
                    || path.length() > 512
                    || (!inference && !rpc && !gitRoute)) {
                fail(exchange, 400);
                return;
            }
            final var authorization = headers.getFirst("Authorization");
            if (authorization == null
                    || !MessageDigest.isEqual(
                            authorization.getBytes(StandardCharsets.UTF_8),
                            (inference ? provider : rpc ? control : git).getBytes(StandardCharsets.US_ASCII))) {
                exchange.getResponseHeaders().set("WWW-Authenticate", gitRoute ? "Basic realm=\"context\"" : "Bearer");
                fail(exchange, 401);
                return;
            }
            final boolean discovery = gitRoute && path.endsWith("/info/refs");
            if (!(discovery ? "GET" : "POST").equals(exchange.getRequestMethod())
                    || (discovery ? !"service=git-upload-pack".equals(uri.getRawQuery()) : uri.getRawQuery() != null)) {
                fail(exchange, 405);
                return;
            }
            final var contentType = headers.getFirst("Content-Type");
            final var media = contentType == null ? "" : contentType.split(";", 2)[0].strip();
            final var encoding = headers.getFirst("Content-Encoding");
            final var protocol = headers.getFirst("Git-Protocol");
            if ((!discovery && !(gitRoute ? "application/x-git-upload-pack-request" : "application/json").equals(media))
                    || (encoding != null && !(gitRoute && !discovery && "gzip".equals(encoding)))
                    || (protocol != null
                            && (!gitRoute || !List.of("version=1", "version=2").contains(protocol)))) {
                fail(exchange, 415);
                return;
            }
            final long maxRequest =
                    inference ? settings.maxInferenceBytes() : rpc ? 16 * 1024 : discovery ? 0 : 64 * 1024;
            final var length = headers.getFirst("Content-Length");
            if (length != null && (!LENGTH.matcher(length).matches() || Long.parseLong(length) > maxRequest)) {
                fail(exchange, 413);
                return;
            }
            if (discovery && headers.containsKey("Transfer-Encoding")) {
                fail(exchange, 400);
                return;
            }
            final var destination = inference
                    ? URI.create(settings.inferenceTarget())
                    : URI.create((settings.contextTarget().endsWith("/")
                                    ? settings.contextTarget()
                                            .substring(
                                                    0, settings.contextTarget().length() - 1)
                                    : settings.contextTarget())
                            + path
                            + (discovery ? "?service=git-upload-pack" : ""));
            final var request = HttpRequest.newBuilder(destination).timeout(Duration.ofNanos(remaining));
            if (!inference || upstream != null) {
                request.header("Authorization", inference ? upstream : rpc ? control : git);
            }
            if (contentType != null) {
                request.header("Content-Type", contentType);
            }
            if (encoding != null) {
                request.header("Content-Encoding", encoding);
            }
            if (protocol != null) {
                request.header("Git-Protocol", protocol);
            }
            if (discovery) {
                request.GET();
            } else {
                request.POST(
                        HttpRequest.BodyPublishers.ofInputStream(() -> bounded(exchange.getRequestBody(), maxRequest)));
            }
            lease.future = client.sendAsync(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            lease.future.thenAccept(response -> {
                lease.body = response.body();
                if (lease.cancelled.get()) {
                    lease.close();
                }
            });
            if (lease.cancelled.get()) {
                lease.future.cancel(true);
                return;
            }
            final var response = lease.future.get(remaining, TimeUnit.NANOSECONDS);
            lease.body = response.body();
            if (lease.cancelled.get()) {
                return;
            }
            // No redirects, cookies, location, authentication challenges or arbitrary upstream headers escape.
            if (response.statusCode() >= 300 && response.statusCode() < 400) {
                fail(exchange, 502);
                return;
            }
            final var type = response.headers().firstValue("content-type");
            type.ifPresent(value -> exchange.getResponseHeaders().set("Content-Type", value));
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            final long maxResponse = rpc
                    ? 512 * 1024
                    : inference ? Math.min(settings.maxGitBytes(), 16 * 1024 * 1024) : settings.maxGitBytes();
            if (response.headers().firstValueAsLong("content-length").orElse(0) > maxResponse) {
                fail(exchange, 502);
                return;
            }
            exchange.sendResponseHeaders(response.statusCode(), 0);
            try (final var input = bounded(lease.body, maxResponse);
                    final var output = exchange.getResponseBody()) {
                final var buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                    output.flush();
                }
            }
        } catch (final Exception rejected) {
            // Deliberately never expose bodies, routes, credentials or transport exception details.
            fail(exchange, 502);
        } finally {
            deadline.cancel(false);
            lease.close();
            active.remove(lease);
            slots.release();
        }
    }

    private boolean validHeaders(final HttpExchange exchange) {
        final var headers = exchange.getRequestHeaders();
        long size = 0;
        if (headers.size() > 64
                || !List.of(settings.serverName() + ":" + port()).equals(headers.get("Host"))) {
            return false;
        }
        for (final var entry : headers.entrySet()) {
            size += entry.getKey().length() + 4;
            for (final var value : entry.getValue()) {
                size += value.length() + 2;
            }
            if (size > 16 * 1024 || entry.getValue().size() != 1) {
                return false;
            }
        }
        return !headers.containsKey("Origin")
                && !headers.containsKey("Upgrade")
                && !headers.containsKey("Expect")
                && (!headers.containsKey("Connection")
                        || List.of("close", "keep-alive").contains(headers.getFirst("Connection")))
                && !headers.containsKey("Proxy-Authorization")
                && (!headers.containsKey("Transfer-Encoding")
                        || ("chunked".equals(headers.getFirst("Transfer-Encoding"))
                                && !headers.containsKey("Content-Length")));
    }

    private static InputStream bounded(final InputStream input, final long maximum) {
        return new FilterInputStream(input) {
            private long count;

            @Override
            public int read() throws IOException {
                final int value = super.read();
                if (value >= 0 && ++count > maximum) {
                    throw new IOException("Limit exceeded");
                }
                return value;
            }

            @Override
            public int read(final byte[] bytes, final int offset, final int length) throws IOException {
                final int read = in.read(bytes, offset, (int) Math.min(length, maximum - count + 1));
                if (read > 0 && (count += read) > maximum) {
                    throw new IOException("Limit exceeded");
                }
                return read;
            }
        };
    }

    private static void fail(final HttpExchange exchange, final int status) {
        try {
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.getResponseHeaders().set("Connection", "close");
            exchange.sendResponseHeaders(status, -1);
        } catch (final IOException | RuntimeException ignored) {
            // Connection may already be closed by its deadline.
        } finally {
            exchange.close();
        }
    }

    private static String secret(final Map<String, String> secrets, final String name) {
        final var value = secrets.get(name);
        if (value == null || !TOKEN.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid services credential");
        }
        return value;
    }

    private static URI target(final String value, final boolean origin) {
        try {
            final var uri = URI.create(value);
            final var host = uri.getHost();
            if (!"http".equals(uri.getScheme())
                    || host == null
                    || !IPV4.matcher(host).matches()
                    || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null
                    || uri.getPort() < 1
                    || uri.getPort() > 65535
                    || (origin
                            ? !(uri.getRawPath().isEmpty() || "/".equals(uri.getRawPath()))
                            : !FIXED_PATH.matcher(uri.getRawPath()).matches())) {
                throw new IllegalArgumentException();
            }
            final var parts = host.split("\\.");
            final var octets = new int[4];
            for (int i = 0; i < 4; i++) {
                octets[i] = Integer.parseInt(parts[i]);
                if (octets[i] > 255 || !Integer.toString(octets[i]).equals(parts[i])) {
                    throw new IllegalArgumentException();
                }
            }
            if (!(octets[0] == 127
                    || octets[0] == 10
                    || (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31)
                    || (octets[0] == 192 && octets[1] == 168))) {
                throw new IllegalArgumentException();
            }
            for (final var segment : uri.getRawPath().split("/")) {
                if (segment.equals(".") || segment.equals("..")) {
                    throw new IllegalArgumentException();
                }
            }
            return uri;
        } catch (final RuntimeException invalid) {
            throw new IllegalArgumentException("Invalid trusted services target");
        }
    }

    private static SSLContext tls(final Settings settings, final String password) throws IOException {
        if (password == null || password.isEmpty()) {
            throw new IllegalArgumentException("Missing services keystore credential");
        }
        final var chars = password.toCharArray();
        try (final var input = Files.newInputStream(Path.of(settings.keyStore()))) {
            final var store = KeyStore.getInstance("PKCS12");
            store.load(input, chars);
            int keys = 0;
            for (final var aliases = store.aliases(); aliases.hasMoreElements(); ) {
                final var alias = aliases.nextElement();
                if (store.isKeyEntry(alias)) {
                    keys++;
                    final var cert = (X509Certificate) store.getCertificate(alias);
                    cert.checkValidity();
                    if (cert.getBasicConstraints() >= 0
                            || cert.getSubjectAlternativeNames() == null
                            || cert.getSubjectAlternativeNames().stream()
                                    .noneMatch(san -> Integer.valueOf(2).equals(san.get(0))
                                            && settings.serverName().equals(san.get(1)))) {
                        throw new GeneralSecurityException();
                    }
                }
            }
            if (keys != 1) {
                throw new GeneralSecurityException();
            }
            final var managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            managers.init(store, chars);
            final var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trust.init(store);
            final var context = SSLContext.getInstance("TLS");
            context.init(managers.getKeyManagers(), trust.getTrustManagers(), null);
            return context;
        } catch (final GeneralSecurityException invalid) {
            throw new IOException("Invalid services TLS configuration");
        } finally {
            Arrays.fill(chars, '\0');
        }
    }

    private static final class Lease implements AutoCloseable {
        private final HttpExchange exchange;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile CompletableFuture<HttpResponse<InputStream>> future;
        private volatile InputStream body;

        private Lease(final HttpExchange exchange) {
            this.exchange = exchange;
        }

        @Override
        public void close() {
            cancelled.set(true);
            if (future != null) {
                future.cancel(true);
            }
            exchange.close();
            final var stream = body;
            if (stream != null) {
                try {
                    stream.close();
                } catch (final IOException ignored) {
                    // Cancellation carries no diagnostics.
                }
            }
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            control = "";
            git = "";
            provider = "";
            upstream = null;
            server.stop(0);
            active.forEach(lease -> Thread.ofVirtual().start(lease::close));
            client.shutdownNow();
            watchdog.shutdownNow();
            executor.shutdownNow();
            stopped.countDown();
        }
    }

    public static void main(final String[] args) {
        if (args.length != 2 || !("--config".equals(args[0]) || "--check".equals(args[0]))) {
            throw new IllegalArgumentException("Expected --config or --check file");
        }
        try (final var json = new JsonMapperImpl(
                        List.of(new RestrictedServicesGateway$Settings$FusionJsonCodec()),
                        ignored -> Optional.empty());
                final var reader = Files.newBufferedReader(Path.of(args[1]))) {
            final var settings = json.read(Settings.class, reader);
            if ("--check".equals(args[0])) {
                final var context = tls(settings, System.getenv(settings.keyStorePasswordEnv()));
                try (final var plain = new Socket()) {
                    plain.connect(new InetSocketAddress("127.0.0.1", settings.port()), 2000);
                    try (final var socket = (SSLSocket) context.getSocketFactory()
                            .createSocket(plain, settings.serverName(), settings.port(), true)) {
                        socket.setSoTimeout(2000);
                        final var parameters = socket.getSSLParameters();
                        parameters.setEndpointIdentificationAlgorithm("HTTPS");
                        parameters.setServerNames(List.of(new SNIHostName(settings.serverName())));
                        socket.setSSLParameters(parameters);
                        socket.startHandshake();
                    }
                }
                return;
            }
            // JDK parser limits are read once. Configure only this dedicated process, never embedding JVMs/tests.
            System.setProperty("sun.net.httpserver.maxReqHeaders", "64");
            System.setProperty("sun.net.httpserver.maxReqHeaderSize", "16384");
            System.setProperty("jdk.httpserver.maxConnections", "8");
            System.setProperty("sun.net.httpserver.maxIdleConnections", "4");
            System.setProperty("sun.net.httpserver.maxReqTime", "" + settings.timeoutSeconds());
            System.setProperty("sun.net.httpserver.maxRspTime", "" + settings.timeoutSeconds());
            try (final var gateway = new RestrictedServicesGateway(settings, System.getenv())) {
                final var hook = new Thread(gateway::close, "services-shutdown");
                Runtime.getRuntime().addShutdownHook(hook);
                try {
                    gateway.stopped.await();
                } finally {
                    try {
                        Runtime.getRuntime().removeShutdownHook(hook);
                    } catch (final IllegalStateException stopping) {
                        // JVM shutdown already owns the hook.
                    }
                }
            }
        } catch (final Exception unavailable) {
            if (!"--check".equals(args[0])) {
                System.getLogger(RestrictedServicesGateway.class.getName())
                        .log(System.Logger.Level.ERROR, "Services gateway unavailable");
            }
            System.exit(1);
        }
    }
}
