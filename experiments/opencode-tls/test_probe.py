#!/usr/bin/env python3
"""Local-only tests for the adjacent Tls13Probe.java (JDK 25 required).

Run: python3 experiments/opencode-tls/test_probe.py -v
The probe takes host, port, binary HTTP request file, binary HTTP response file,
and optionally a PKCS12 trust store (password: changeit). Response files contain
raw HTTP wire bytes, including headers and chunk framing, not just the body.
All generated files and compiled classes live in a TemporaryDirectory.

Python's ssl API cannot select individual TLS 1.3 cipher suites. A generated
JSSE fixture tests each suite with X25519 and, when Java 27 is available, hybrid
X25519MLKEM768. Set TLS_PROBE_JAVA27 to its java executable to override discovery.
The Python hybrid test is skipped if OpenSSL lacks that group. No external calls
are made.
"""

import contextlib
import os
import pathlib
import select
import shutil
import socket
import ssl
import subprocess
import tempfile
import threading
import time
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]
SOURCE = pathlib.Path(__file__).with_name("Tls13Probe.java")
FIXTURE = ROOT / "core/src/test/resources/io/yupiik/ia/agent/core/wiring/localhost.p12"
FALLBACK = pathlib.Path("/tmp/yca-bun-wire-20261106")
TIMEOUT = 10
BODY = b"local TLS probe\n"
FIXED = (
    b"HTTP/1.1 200 OK\r\nContent-Length: " + str(len(BODY)).encode()
    + b"\r\nConnection: close\r\n\r\n" + BODY
)
CHUNKED = (
    b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n"
    b"6\r\nlocal \r\n9\r\nTLS probe\r\n1\r\n\n\r\n0\r\n\r\n"
)
CLOSE = b"HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n" + BODY


JSSE_SOURCE = r"""
import java.io.EOFException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

public class LocalTlsServer {
    public static void main(final String[] args) throws Exception {
        final var store = KeyStore.getInstance("PKCS12");
        try (final var input = Files.newInputStream(Path.of(args[0]))) {
            store.load(input, "changeit".toCharArray());
        }
        final var managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        managers.init(store, "changeit".toCharArray());
        final var context = SSLContext.getInstance("TLS");
        context.init(managers.getKeyManagers(), null, null);
        try (final var listener = (SSLServerSocket) context.getServerSocketFactory()
                .createServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            listener.setSoTimeout(10000);
            listener.setEnabledProtocols(new String[]{"TLSv1.3"});
            listener.setEnabledCipherSuites(new String[]{args[1]});
            final var parameters = listener.getSSLParameters();
            parameters.setApplicationProtocols(new String[]{"http/1.1"});
            listener.setSSLParameters(parameters);
            System.out.println(listener.getLocalPort());
            System.out.flush();
            try (final var socket = (SSLSocket) listener.accept()) {
                socket.setSoTimeout(10000);
                socket.startHandshake();
                final var input = socket.getInputStream();
                int tail = 0;
                int size = 0;
                while (tail != 0x0d0a0d0a) {
                    final int value = input.read();
                    if (value < 0) {
                        throw new EOFException("Incomplete HTTP headers");
                    }
                    if (++size > 65536) {
                        throw new IllegalStateException("HTTP headers exceed limit");
                    }
                    tail = (tail << 8) | value;
                }
                final var output = socket.getOutputStream();
                output.write(("HTTP/1.1 200 OK\r\nContent-Length: 16\r\n"
                        + "Connection: close\r\n\r\nlocal TLS probe\n")
                        .getBytes(StandardCharsets.US_ASCII));
                output.flush();
            }
        }
    }
}
"""


class JavaServer:
    """One-shot JSSE subprocess; all waits, including cleanup, are bounded."""

    def __init__(self, java, classes, store, suite, group):
        self.args = [java, f"-Djdk.tls.namedGroups={group}", "-cp", str(classes),
                     "LocalTlsServer", str(store), suite]

    def __enter__(self):
        self.process = subprocess.Popen(self.args, stdout=subprocess.PIPE,
                                        stderr=subprocess.PIPE)
        try:
            deadline = time.monotonic() + TIMEOUT
            line = bytearray()
            # Read bytes only when ready: readline() could wait past the deadline.
            while not line.endswith(b"\n") and len(line) < 16:
                remaining = deadline - time.monotonic()
                if remaining <= 0 or not select.select(
                        [self.process.stdout], [], [], remaining)[0]:
                    raise AssertionError("JSSE server did not publish its port")
                value = os.read(self.process.stdout.fileno(), 1)
                if not value:
                    _, error = self.process.communicate(timeout=TIMEOUT)
                    raise AssertionError(f"JSSE server exited: {error.decode(errors='replace')}")
                line.extend(value)
            if not line.strip().isdigit() or not line.endswith(b"\n"):
                raise AssertionError(f"Unexpected JSSE server stdout: {line!r}")
            self.port = int(line)
            if not 0 < self.port < 65536:
                raise AssertionError(f"Invalid JSSE port: {self.port}")
            return self
        except BaseException:
            self.__exit__(None, None, None)
            raise

    def finish(self):
        output, error = self.process.communicate(timeout=TIMEOUT)
        if self.process.returncode or output:
            raise AssertionError(f"JSSE server failed ({self.process.returncode}): "
                                 f"stdout={output!r} stderr={error!r}")

    def __exit__(self, *_):
        if self.process.poll() is None:
            self.process.kill()
        self.process.communicate(timeout=TIMEOUT)


def command(*args):
    result = subprocess.run(args, capture_output=True, timeout=30, check=False)
    if result.returncode:
        raise RuntimeError(
            f"Command failed ({result.returncode}): {args!r}\n"
            + result.stderr.decode(errors="replace")
        )
    return result.stdout


def close_socket(sock):
    with contextlib.suppress(OSError):
        sock.shutdown(socket.SHUT_RDWR)
    sock.close()


class Worker:
    """One-connection loopback worker with bounded shutdown and error capture."""

    def __init__(self):
        self.listener = socket.socket()
        self.listener.bind(("127.0.0.1", 0))
        self.listener.listen(1)
        self.listener.settimeout(TIMEOUT)
        self.port = self.listener.getsockname()[1]
        self.sockets = [self.listener]
        self.lock = threading.Lock()
        self.stopping = threading.Event()
        self.errors = []
        self.thread = threading.Thread(target=self._run, daemon=True)

    def track(self, sock):
        sock.settimeout(TIMEOUT)
        with self.lock:
            self.sockets.append(sock)
            if self.stopping.is_set():
                close_socket(sock)
        return sock

    def _run(self):
        try:
            self.serve()
        except Exception as error:
            if not self.stopping.is_set():
                self.errors.append(error)

    def __enter__(self):
        self.thread.start()
        return self

    def finish(self):
        self.thread.join(TIMEOUT + 1)
        if self.thread.is_alive():
            raise AssertionError("Loopback worker did not finish before its deadline")

    def __exit__(self, *_):
        self.stopping.set()
        with self.lock:
            for sock in reversed(self.sockets):
                close_socket(sock)
        self.thread.join(TIMEOUT + 1)
        if self.thread.is_alive():
            raise AssertionError("Loopback worker leaked a thread")


class Server(Worker):
    def __init__(self, context, response=FIXED, bare_close=False, fragmented=False):
        super().__init__()
        self.context = context
        self.response = response
        self.bare_close = bare_close
        self.fragmented = fragmented
        self.received = bytearray()
        self.version = None
        self.cipher = None

    def serve(self):
        raw, _ = self.listener.accept()
        self.track(raw)
        # Track the SSLSocket before the handshake so teardown can interrupt it.
        tls = self.track(self.context.wrap_socket(
            raw, server_side=True, do_handshake_on_connect=False
        ))
        tls.do_handshake()
        self.version = tls.version()
        self.cipher = tls.cipher()[0]
        while b"\r\n\r\n" not in self.received:
            data = tls.recv(4096)
            if not data:
                return
            self.received.extend(data)
            if len(self.received) > 65536:
                raise AssertionError("HTTP request exceeds harness limit")
        if self.fragmented:
            # Each write is a separate TLS application record, splitting headers,
            # chunk framing, and body across record boundaries.
            for value in self.response:
                tls.sendall(bytes([value]))
        else:
            tls.sendall(self.response)
        if self.bare_close:
            # Detach to prevent SSLSocket from emitting close_notify.
            with socket.socket(fileno=tls.detach()) as plain:
                plain.shutdown(socket.SHUT_RDWR)
            return
        # A close-delimited TLS response requires authenticated close_notify;
        # bare TCP EOF must be rejected as possible truncation.
        plain = tls.unwrap()
        self.track(plain)
        close_socket(plain)


class Relay(Worker):
    """Forward TLS records, corrupting or truncating the server handshake."""

    def __init__(self, upstream_port, mode):
        super().__init__()
        self.upstream_port = upstream_port
        self.mode = mode
        self.changed = False
        self.saw_server_hello = False

    def serve(self):
        client, _ = self.listener.accept()
        self.track(client)
        upstream = self.track(socket.create_connection(
            ("127.0.0.1", self.upstream_port), timeout=TIMEOUT
        ))
        pending = bytearray()
        deadline = time.monotonic() + TIMEOUT
        try:
            while time.monotonic() < deadline:
                ready, _, _ = select.select([client, upstream], [], [], 0.2)
                for source in ready:
                    data = source.recv(65536)
                    if not data:
                        return
                    if source is client:
                        upstream.sendall(data)
                        continue
                    pending.extend(data)
                    while len(pending) >= 5:
                        size = int.from_bytes(pending[3:5], "big")
                        if size > 18432:
                            raise AssertionError("Invalid TLS record length")
                        if len(pending) < size + 5:
                            break
                        record = bytearray(pending[:size + 5])
                        del pending[:size + 5]
                        if record[0] == 22 and len(record) > 5 and record[5] == 2:
                            self.saw_server_hello = True
                            if self.mode == "truncate":
                                client.sendall(record[:min(12, len(record) - 1)])
                                self.changed = True
                                return
                        # TLS 1.3 encrypted handshake records use application_data
                        # (23); leave ServerHello and compatibility CCS untouched.
                        if (self.mode == "tamper" and self.saw_server_hello
                                and record[0] == 23 and not self.changed):
                            record[-1] ^= 1
                            self.changed = True
                        client.sendall(record)
            raise TimeoutError("Relay exceeded its deadline")
        finally:
            close_socket(client)
            close_socket(upstream)


class ProbeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not SOURCE.is_file():
            raise unittest.SkipTest(f"Parent source not present: {SOURCE}")
        for tool in ("java", "javac", "openssl"):
            if not shutil.which(tool):
                raise RuntimeError(f"Required executable not found: {tool}")
        if not ssl.HAS_TLSv1_3:
            raise RuntimeError("Python's OpenSSL does not support TLS 1.3")
        cls.temporary = tempfile.TemporaryDirectory(prefix="yca-tls-probe-")
        cls.addClassCleanup(cls.temporary.cleanup)
        cls.directory = pathlib.Path(cls.temporary.name)
        cls.classes = cls.directory / "classes"
        cls.classes.mkdir()
        cls.server_source = cls.directory / "LocalTlsServer.java"
        cls.server_source.write_text(JSSE_SOURCE, encoding="ascii")
        command("javac", "--release", "25", "-d", str(cls.classes),
                str(SOURCE), str(cls.server_source))
        cls.java27 = os.environ.get("TLS_PROBE_JAVA27")
        if not cls.java27:
            candidate = pathlib.Path(
                "/home/rmannibucau/.sdkman/candidates/java/27.0.0+35-zulu/bin/java")
            if candidate.is_file():
                cls.java27 = str(candidate)
        cls.cert = cls.directory / "cert.pem"
        cls.key = cls.directory / "key.pem"
        cls.trust = cls.directory / "trust.p12"
        if FIXTURE.is_file():
            command("openssl", "pkcs12", "-in", str(FIXTURE), "-passin",
                    "pass:changeit", "-clcerts", "-nokeys", "-out", str(cls.cert))
            command("openssl", "pkcs12", "-in", str(FIXTURE), "-passin",
                    "pass:changeit", "-nocerts", "-nodes", "-out", str(cls.key))
            shutil.copyfile(FIXTURE, cls.trust)
        elif (FALLBACK / "cert.pem").is_file() and (FALLBACK / "key.pem").is_file():
            shutil.copyfile(FALLBACK / "cert.pem", cls.cert)
            shutil.copyfile(FALLBACK / "key.pem", cls.key)
            cls.export_trust(cls.cert, cls.key, cls.trust)
        else:
            raise RuntimeError(f"Neither {FIXTURE} nor fallback PEM files exist")
        cls.key.chmod(0o600)
        cls.san = command("openssl", "x509", "-in", str(cls.cert), "-noout",
                          "-ext", "subjectAltName").decode()
        # The repository fixture includes both localhost and 127.0.0.1. Use a
        # separately trusted wrong-name certificate to isolate name validation.
        cls.wrong_cert = cls.directory / "wrong.pem"
        cls.wrong_key = cls.directory / "wrong.key"
        cls.wrong_trust = cls.directory / "wrong.p12"
        if "IP Address:127.0.0.1" in cls.san:
            command("openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes",
                    "-days", "2", "-subj", "/CN=wrongname.invalid", "-addext",
                    "subjectAltName=DNS:wrongname.invalid", "-addext",
                    "basicConstraints=critical,CA:TRUE", "-keyout", str(cls.wrong_key),
                    "-out", str(cls.wrong_cert))
            cls.wrong_key.chmod(0o600)
            cls.export_trust(cls.wrong_cert, cls.wrong_key, cls.wrong_trust)
        else:
            cls.wrong_cert, cls.wrong_key, cls.wrong_trust = cls.cert, cls.key, cls.trust

    @staticmethod
    def export_trust(cert, key, target):
        command("openssl", "pkcs12", "-export", "-in", str(cert), "-inkey", str(key),
                "-out", str(target), "-passout", "pass:changeit", "-name", "probe")

    def context(self, wrong=False, tls12=False, group="X25519"):
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        version = ssl.TLSVersion.TLSv1_2 if tls12 else ssl.TLSVersion.TLSv1_3
        context.minimum_version = context.maximum_version = version
        context.load_cert_chain(self.wrong_cert if wrong else self.cert,
                                self.wrong_key if wrong else self.key)
        context.set_ecdh_curve(group)
        context.set_alpn_protocols(["http/1.1"])
        return context

    def probe(self, port, trusted=True, host="localhost", trust=None):
        request = self.directory / "request.bin"
        response = self.directory / "response.bin"
        response.unlink(missing_ok=True)
        wire = (f"GET /probe HTTP/1.1\r\nHost: {host}:{port}\r\n"
                "Connection: close\r\n\r\n").encode("ascii")
        request.write_bytes(wire)
        args = ["java", "-cp", str(self.classes), "Tls13Probe", host, str(port),
                str(request), str(response)]
        if trusted:
            args.append(str(trust or self.trust))
        try:
            result = subprocess.run(args, capture_output=True, timeout=TIMEOUT, check=False)
        except subprocess.TimeoutExpired as error:
            self.fail(f"Probe timed out rather than completing/rejecting: {error}")
        return result, response.read_bytes() if response.exists() else b"", wire

    def assert_success(self, response, group="X25519", fragmented=False):
        with Server(self.context(group=group), response, fragmented=fragmented) as server:
            result, actual, request = self.probe(server.port)
            server.finish()
            self.assertEqual(result.returncode, 0, result.stderr.decode(errors="replace"))
            self.assertEqual(server.errors, [])
            self.assertEqual(server.received, request)
            self.assertEqual(server.version, "TLSv1.3")
            self.assertIn(server.cipher, {"TLS_AES_128_GCM_SHA256",
                                         "TLS_AES_256_GCM_SHA384",
                                         "TLS_CHACHA20_POLY1305_SHA256"})
            self.assertEqual(actual, response)

    def assert_rejected(self, server, port=None, **kwargs):
        result, response, _ = self.probe(port or server.port, **kwargs)
        server.finish()
        diagnostics = (result.stdout + result.stderr).decode(errors="replace")
        self.assertNotEqual(result.returncode, 0, diagnostics)
        self.assertEqual(response, b"", "Rejected connection produced HTTP output")
        self.assertEqual(server.received, b"", "Invalid peer received HTTP application data")
        self.assertTrue(server.errors, "Expected a server-side handshake rejection")
        for error in server.errors:
            self.assertIsInstance(error, (ssl.SSLError, ConnectionError), repr(error))

    def jsse_success(self, suite, suite_id, java="java", group="X25519", group_id=29):
        with JavaServer(java, self.classes, self.trust, suite, group) as server:
            result, actual, _ = self.probe(server.port)
            self.assertEqual(result.returncode, 0, result.stderr.decode(errors="replace"))
            self.assertEqual(actual, FIXED)
            self.assertIn(f"TLSv1.3 suite={suite_id} group={group_id} ",
                          result.stdout.decode("ascii"))
            self.assertIn(b"peer_verified=true", result.stdout)
            server.finish()

    def test_jsse_aes128(self):
        self.jsse_success("TLS_AES_128_GCM_SHA256", "1301")

    def test_jsse_aes256(self):
        self.jsse_success("TLS_AES_256_GCM_SHA384", "1302")

    def test_jsse_chacha(self):
        self.jsse_success("TLS_CHACHA20_POLY1305_SHA256", "1303")

    def test_jsse_hybrid_when_java27_available(self):
        if not self.java27:
            self.skipTest("Java 27 unavailable; set TLS_PROBE_JAVA27 to its java executable")
        self.jsse_success("TLS_AES_128_GCM_SHA256", "1301", java=self.java27,
                          group="X25519MLKEM768", group_id=4588)

    def test_fragmented_responses(self):
        for response in (FIXED, CHUNKED, CLOSE):
            with self.subTest(response=response):
                self.assert_success(response, fragmented=True)

    def test_bare_tcp_close_rejected(self):
        # Neither an apparently complete close-delimited body nor a truncated
        # Content-Length response may be accepted on unauthenticated TCP EOF.
        for response in (CLOSE, FIXED[:-1]):
            with self.subTest(response=response):
                with Server(self.context(), response, bare_close=True) as server:
                    result, actual, request = self.probe(server.port)
                    server.finish()
                    self.assertEqual(server.errors, [])
                    self.assertEqual(server.received, request)
                    self.assertNotEqual(result.returncode, 0,
                                        (result.stdout + result.stderr).decode(errors="replace"))
                    self.assertEqual(actual, b"", "Truncated connection produced HTTP output")

    def test_fixed_length(self):
        self.assert_success(FIXED)

    def test_chunked(self):
        self.assert_success(CHUNKED)

    def test_connection_close(self):
        self.assert_success(CLOSE)

    def test_hybrid_when_available(self):
        try:
            self.context(group="X25519MLKEM768")
        except (ValueError, ssl.SSLError) as error:
            self.skipTest(f"Python OpenSSL hybrid group unavailable: {error}")
        self.assert_success(FIXED, group="X25519MLKEM768")

    def test_untrusted_certificate(self):
        with Server(self.context()) as server:
            self.assert_rejected(server, trusted=False)

    def test_wrong_hostname(self):
        with Server(self.context(wrong=True)) as server:
            self.assert_rejected(server, host="127.0.0.1", trust=self.wrong_trust)

    def test_tls12_downgrade(self):
        with Server(self.context(tls12=True)) as server:
            self.assert_rejected(server)

    def relay_rejection(self, mode):
        with Server(self.context()) as server:
            with Relay(server.port, mode) as relay:
                self.assert_rejected(server, port=relay.port)
                relay.finish()
                self.assertTrue(relay.saw_server_hello, "Relay never saw ServerHello")
                self.assertTrue(relay.changed, "Relay did not inject the fault")
                for error in relay.errors:
                    self.assertIsInstance(error, ConnectionError, repr(error))

    def test_encrypted_record_tamper(self):
        self.relay_rejection("tamper")

    def test_truncated_handshake(self):
        self.relay_rejection("truncate")


if __name__ == "__main__":
    unittest.main()
