/*
 * Experimental, single-request TLS 1.3 diagnostic. Not a production transport.
 * Implements RFC 8446 framing/key schedule using JDK crypto, not JSSE sockets.
 */
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.IDN;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.interfaces.XECPublicKey;
import javax.crypto.spec.GCMParameterSpec;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.NamedParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.security.spec.XECPublicKeySpec;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.KEM;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

public final class Tls13Probe {
    private static final int LIMIT = 4 * 1024 * 1024;
    private static final byte[] EMPTY = new byte[0];
    private static final SecureRandom RANDOM = new SecureRandom();
    private final ByteArrayOutputStream transcript = new ByteArrayOutputStream();
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
    private final String host;
    private final X509TrustManager trust;
    private final KeyPair x25519;
    private final KeyPair kem;
    private final byte[] session = random(32);
    private InputStream input;
    private OutputStream output;
    private String hash;
    private String hmac;
    private int hashSize;
    private int suite;
    private Traffic incoming;
    private Traffic outgoing;
    private int group;

    private Tls13Probe(final String host, final Path trustStore) throws Exception {
        this.host = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).toLowerCase(java.util.Locale.ROOT);
        final var factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        KeyStore store = null;
        if (trustStore != null) {
            store = KeyStore.getInstance("PKCS12");
            try (final var in = Files.newInputStream(trustStore)) {
                store.load(in, "changeit".toCharArray());
            }
        }
        factory.init(store);
        trust = Arrays.stream(factory.getTrustManagers()).filter(X509TrustManager.class::isInstance)
                .map(X509TrustManager.class::cast).findFirst().orElseThrow();
        x25519 = KeyPairGenerator.getInstance("X25519").generateKeyPair();
        final var generator = KeyPairGenerator.getInstance("ML-KEM");
        generator.initialize(NamedParameterSpec.ML_KEM_768, RANDOM);
        kem = generator.generateKeyPair();
    }

    public static void main(final String[] args) throws Exception {
        if (args.length < 4 || args.length > 5) {
            throw new IllegalArgumentException("Usage: Tls13Probe host port request-file response-file [trust-p12]");
        }
        final var probe = new Tls13Probe(args[0], args.length == 5 ? Path.of(args[4]) : null);
        final var request = Files.readAllBytes(Path.of(args[2]));
        if (request.length > LIMIT) {
            throw new IOException("Request too large");
        }
        try (final var socket = new Socket()) {
            socket.connect(new InetSocketAddress(args[0], Integer.parseInt(args[1])), 10000);
            socket.setSoTimeout(15000);
            probe.input = socket.getInputStream();
            probe.output = socket.getOutputStream();
            probe.handshake();
            for (int offset = 0; offset < request.length; offset += 16384) {
                probe.outgoing.write(23, Arrays.copyOfRange(request, offset, Math.min(offset + 16384, request.length)));
            }
            final var response = probe.response();
            Files.write(Path.of(args[3]), response);
            probe.outgoing.write(21, new byte[]{1, 0});
            System.out.println("TLSv1.3 suite=" + Integer.toHexString(probe.suite) + " group=" + probe.group
                    + " peer_verified=true response_bytes=" + response.length);
        }
    }

    private void handshake() throws Exception {
        final var hello = clientHello();
        record(22, 0x0301, hello);
        transcript.writeBytes(hello);
        final var server = nextHandshake(false);
        require(server[0] == 2, "Expected ServerHello");
        final var reader = new Reader(Arrays.copyOfRange(server, 4, server.length));
        require(reader.u16() == 0x0303, "Invalid ServerHello version");
        final var serverRandom = reader.take(32);
        require(!Arrays.equals(serverRandom, java.util.HexFormat.of().parseHex(
                "cf21ad74e59a6111be1d8c021e65b891c2a211167abb8c5e079e09e2c8a8339c")), "HelloRetryRequest unsupported");
        require(Arrays.equals(reader.vector(1), session), "Session echo mismatch");
        suite = reader.u16();
        require(suite >= 0x1301 && suite <= 0x1303, "Unsupported cipher suite");
        hash = suite == 0x1302 ? "SHA-384" : "SHA-256";
        hmac = suite == 0x1302 ? "HmacSHA384" : "HmacSHA256";
        hashSize = suite == 0x1302 ? 48 : 32;
        require(reader.u8() == 0, "Invalid compression");
        final var extensions = extensions(reader.vector(2));
        reader.end();
        require(extensions.size() == 2 && Arrays.equals(extensions.get(43), new byte[]{3, 4})
                && extensions.containsKey(51), "Invalid ServerHello extensions / TLS downgrade");
        final var share = new Reader(extensions.get(51));
        group = share.u16();
        final var key = share.vector(2);
        share.end();
        final byte[] secret;
        if (group == 29) {
            require(key.length == 32, "Invalid X25519 share");
            secret = xSecret(key);
        } else if (group == 4588) {
            require(key.length == 1120, "Invalid hybrid share");
            final var pq = KEM.getInstance("ML-KEM").newDecapsulator(kem.getPrivate())
                    .decapsulate(Arrays.copyOf(key, 1088)).getEncoded();
            secret = concat(pq, xSecret(Arrays.copyOfRange(key, 1088, 1120)));
        } else {
            throw new IOException("Unsupported selected group");
        }
        require(pending.size() == 0, "Unexpected plaintext handshake suffix");
        transcript.writeBytes(server);
        final var early = extract(new byte[hashSize], new byte[hashSize]);
        final var handshake = extract(derive(early, "derived", digest(EMPTY)), secret);
        final var clientHandshake = derive(handshake, "c hs traffic", digest(transcript.toByteArray()));
        final var serverHandshake = derive(handshake, "s hs traffic", digest(transcript.toByteArray()));
        incoming = new Traffic(serverHandshake);
        outgoing = new Traffic(clientHandshake);
        record(20, 0x0303, new byte[]{1});
        final var ee = nextHandshake(true);
        require(ee[0] == 8, "Expected EncryptedExtensions");
        final var eeReader = new Reader(Arrays.copyOfRange(ee, 4, ee.length));
        final var eeExtensions = extensions(eeReader.vector(2));
        eeReader.end();
        require(eeExtensions.keySet().stream().allMatch(id -> id == 0 || id == 16 || id == 10),
                "Unsupported EncryptedExtensions");
        require(Arrays.equals(eeExtensions.get(16), new byte[]{0, 9, 8, 'h', 't', 't', 'p', '/', '1', '.', '1'}),
                "Expected HTTP/1.1 ALPN");
        transcript.writeBytes(ee);
        final var certificate = nextHandshake(true);
        require(certificate[0] == 11, "Expected Certificate (client authentication unsupported)");
        final var chain = certificates(certificate);
        verifyPeer(chain);
        transcript.writeBytes(certificate);
        final var verify = nextHandshake(true);
        require(verify[0] == 15, "Expected CertificateVerify");
        verifySignature(chain[0], verify);
        transcript.writeBytes(verify);
        final var finished = nextHandshake(true);
        require(finished[0] == 20 && finished.length == 4 + hashSize, "Expected Finished");
        final var expected = mac(expand(serverHandshake, "finished", EMPTY, hashSize), digest(transcript.toByteArray()));
        require(MessageDigest.isEqual(expected, Arrays.copyOfRange(finished, 4, finished.length)), "Server Finished mismatch");
        transcript.writeBytes(finished);
        require(pending.size() == 0, "Unexpected handshake suffix");
        final var master = extract(derive(handshake, "derived", digest(EMPTY)), new byte[hashSize]);
        final var serverApplication = derive(master, "s ap traffic", digest(transcript.toByteArray()));
        final var clientApplication = derive(master, "c ap traffic", digest(transcript.toByteArray()));
        outgoing.write(22, message(20, mac(expand(clientHandshake, "finished", EMPTY, hashSize), digest(transcript.toByteArray()))));
        incoming = new Traffic(serverApplication);
        outgoing = new Traffic(clientApplication);
    }

    private byte[] clientHello() throws Exception {
        final var ext = new ByteArrayOutputStream();
        // Ordered extension/cipher profile captured from Bun 1.4.4. Fresh keys/random on every connection.
        extension(ext, 0, vector(2, concat(new byte[]{0}, vector(2, host.getBytes(StandardCharsets.US_ASCII)))));
        extension(ext, 23, EMPTY);
        extension(ext, 65281, new byte[]{0});
        extension(ext, 10, vector(2, words(4588, 29, 23, 24)));
        extension(ext, 11, new byte[]{1, 0});
        extension(ext, 35, EMPTY);
        extension(ext, 16, new byte[]{0, 9, 8, 'h', 't', 't', 'p', '/', '1', '.', '1'});
        extension(ext, 5, new byte[]{1, 0, 0, 0, 0});
        extension(ext, 13, vector(2, words(0x0403, 0x0804, 0x0401, 0x0503, 0x0805, 0x0501, 0x0806, 0x0601, 0x0807, 0x0603, 0x0201)));
        extension(ext, 18, EMPTY);
        final var encoded = kem.getPublic().getEncoded();
        // ML-KEM SubjectPublicKeyInfo ends with the 1184-byte encapsulation key.
        final var pq = Arrays.copyOfRange(encoded, encoded.length - 1184, encoded.length);
        final var x = littleEndian(((XECPublicKey) x25519.getPublic()).getU(), 32);
        extension(ext, 51, vector(2, concat(words(4588), vector(2, concat(pq, x)), words(29), vector(2, x))));
        extension(ext, 45, new byte[]{1, 1});
        // TLS 1.2 is present for wire-profile parity only; any selection below 1.3 fails closed.
        extension(ext, 43, new byte[]{4, 3, 4, 3, 3});
        return message(1, concat(words(0x0303), random(32), vector(1, session), vector(2,
                words(0x1301, 0x1302, 0x1303, 0xc02b, 0xc02f, 0xc02c, 0xc030, 0xcca9, 0xcca8,
                        0xc009, 0xc013, 0xc00a, 0xc014, 0x009c, 0x009d, 0x002f, 0x0035)),
                new byte[]{1, 0}, vector(2, ext.toByteArray())));
    }

    private byte[] xSecret(final byte[] wire) throws Exception {
        final var encoded = wire.clone();
        encoded[31] &= 0x7f;
        reverse(encoded);
        final var publicKey = KeyFactory.getInstance("X25519").generatePublic(
                new XECPublicKeySpec(NamedParameterSpec.X25519, new BigInteger(1, encoded)));
        final var agreement = KeyAgreement.getInstance("X25519");
        agreement.init(x25519.getPrivate());
        agreement.doPhase(publicKey, true);
        final var secret = agreement.generateSecret();
        require(!MessageDigest.isEqual(secret, new byte[32]), "All-zero X25519 secret");
        return secret;
    }

    private X509Certificate[] certificates(final byte[] handshake) throws Exception {
        final var reader = new Reader(Arrays.copyOfRange(handshake, 4, handshake.length));
        require(reader.vector(1).length == 0, "Unexpected certificate context");
        final var list = new Reader(reader.vector(3));
        reader.end();
        final var certificates = new java.util.ArrayList<X509Certificate>();
        final var factory = CertificateFactory.getInstance("X.509");
        while (list.remaining() > 0) {
            require(certificates.size() < 16, "Certificate chain too long");
            final var bytes = new ByteArrayInputStream(list.vector(3));
            certificates.add((X509Certificate) factory.generateCertificate(bytes));
            require(bytes.available() == 0, "Certificate suffix");
            extensions(list.vector(2));
        }
        require(!certificates.isEmpty(), "Empty certificate chain");
        return certificates.toArray(X509Certificate[]::new);
    }

    private void verifyPeer(final X509Certificate[] chain) throws Exception {
        // TLS 1.3 authentication is independent of the key-exchange group.
        trust.checkServerTrusted(chain, "UNKNOWN");
        chain[0].checkValidity();
        final var usage = chain[0].getKeyUsage();
        require(usage == null || usage[0], "Certificate cannot sign");
        final var eku = chain[0].getExtendedKeyUsage();
        require(eku == null || eku.contains("1.3.6.1.5.5.7.3.1") || eku.contains("2.5.29.37.0"), "Not a server certificate");
        final var names = chain[0].getSubjectAlternativeNames();
        require(names != null, "Certificate has no subjectAltName");
        boolean match = false;
        for (final var name : names) {
            if (Integer.valueOf(2).equals(name.get(0)) && !host.matches("[0-9.]+")) {
                final var dns = ((String) name.get(1)).toLowerCase(java.util.Locale.ROOT);
                match |= dns.equals(host) || (dns.startsWith("*.") && dns.indexOf('*', 1) < 0
                        && dns.substring(2).contains(".") && host.contains(".")
                        && host.substring(host.indexOf('.') + 1).equals(dns.substring(2)));
            } else if (Integer.valueOf(7).equals(name.get(0)) && host.matches("[0-9.]+")) {
                match |= host.equals(name.get(1));
            }
        }
        require(match, "Certificate hostname mismatch");
    }

    private void verifySignature(final X509Certificate certificate, final byte[] handshake) throws Exception {
        final var reader = new Reader(Arrays.copyOfRange(handshake, 4, handshake.length));
        final var algorithm = reader.u16();
        final var bytes = reader.vector(2);
        reader.end();
        final String name;
        final String digestName;
        final int salt;
        switch (algorithm) {
            case 0x0804 -> { name = "RSASSA-PSS"; digestName = "SHA-256"; salt = 32; }
            case 0x0805 -> { name = "RSASSA-PSS"; digestName = "SHA-384"; salt = 48; }
            case 0x0806 -> { name = "RSASSA-PSS"; digestName = "SHA-512"; salt = 64; }
            case 0x0403 -> { name = "SHA256withECDSA"; digestName = null; salt = 0; }
            case 0x0503 -> { name = "SHA384withECDSA"; digestName = null; salt = 0; }
            case 0x0603 -> { name = "SHA512withECDSA"; digestName = null; salt = 0; }
            case 0x0807 -> { name = "Ed25519"; digestName = null; salt = 0; }
            default -> throw new IOException("Unsupported CertificateVerify algorithm");
        }
        final var key = certificate.getPublicKey();
        if (name.equals("RSASSA-PSS")) {
            require(key instanceof RSAPublicKey rsa && rsa.getModulus().bitLength() >= 2048, "Weak/wrong RSA key");
        } else if (name.endsWith("ECDSA")) {
            final int bits = algorithm == 0x0403 ? 256 : algorithm == 0x0503 ? 384 : 521;
            require(key instanceof ECPublicKey ec && ec.getParams().getCurve().getField().getFieldSize() == bits,
                    "Wrong ECDSA curve");
        }
        final var signature = Signature.getInstance(name);
        if (digestName != null) {
            signature.setParameter(new PSSParameterSpec(digestName, "MGF1", new MGF1ParameterSpec(digestName), salt, 1));
        }
        signature.initVerify(key);
        final var spaces = new byte[64];
        Arrays.fill(spaces, (byte) 32);
        signature.update(concat(spaces, "TLS 1.3, server CertificateVerify\0".getBytes(StandardCharsets.US_ASCII),
                digest(transcript.toByteArray())));
        require(signature.verify(bytes), "CertificateVerify signature mismatch");
    }

    private byte[] nextHandshake(final boolean encrypted) throws Exception {
        while (true) {
            final var bytes = pending.toByteArray();
            if (bytes.length >= 4) {
                final int length = ((bytes[1] & 255) << 16) | ((bytes[2] & 255) << 8) | (bytes[3] & 255);
                require(length <= LIMIT, "Handshake too large");
                if (bytes.length >= length + 4) {
                    pending.reset();
                    pending.write(bytes, length + 4, bytes.length - length - 4);
                    return Arrays.copyOf(bytes, length + 4);
                }
            }
            final var record = encrypted ? incoming.read() : plainRecord();
            require(record.type == 22, "Unexpected record during handshake");
            require(pending.size() + record.bytes.length <= LIMIT, "Handshake buffer too large");
            pending.writeBytes(record.bytes);
        }
    }

    private byte[] response() throws Exception {
        final var response = new ByteArrayOutputStream();
        int tickets = 0;
        while (true) {
            final var record = incoming.read();
            if (record.type == 23) {
                require(pending.size() == 0, "Interleaved post-handshake message");
                require(response.size() + record.bytes.length <= LIMIT, "Response too large");
                response.writeBytes(record.bytes);
                if (httpComplete(response.toByteArray())) {
                    return response.toByteArray();
                }
            } else if (record.type == 21) {
                require(record.bytes.length == 2 && record.bytes[1] == 0, "TLS alert");
                require(pending.size() == 0, "Truncated post-handshake message");
                final var bytes = response.toByteArray();
                final var text = new String(bytes, StandardCharsets.ISO_8859_1);
                final int end = text.indexOf("\r\n\r\n");
                require(end >= 0, "Closed before HTTP headers");
                final var headers = text.substring(0, end).toLowerCase(java.util.Locale.ROOT);
                require(httpComplete(bytes) || (!headers.contains("\r\ncontent-length:")
                        && !headers.contains("\r\ntransfer-encoding:")), "Truncated framed HTTP response");
                return bytes;
            } else if (record.type == 22) {
                pending.writeBytes(record.bytes);
                require(pending.size() <= LIMIT, "Post-handshake message too large");
                while (pending.size() >= 4) {
                    final var all = pending.toByteArray();
                    final var r = new Reader(all);
                    require(r.u8() == 4, "Unsupported post-handshake message (including KeyUpdate)");
                    final int size = r.number(3);
                    require(size <= 65536, "Ticket too large");
                    if (all.length < 4 + size) {
                        break;
                    }
                    require(++tickets <= 8, "Too many session tickets");
                    // Tickets are authenticated but deliberately not retained or used.
                    pending.reset();
                    pending.write(all, 4 + size, all.length - 4 - size);
                }
            } else {
                throw new IOException("Unexpected application record");
            }
        }
    }

    private static boolean httpComplete(final byte[] response) throws IOException {
        final var text = new String(response, StandardCharsets.ISO_8859_1);
        final int end = text.indexOf("\r\n\r\n");
        if (end < 0) {
            require(response.length < 65536, "HTTP headers too large");
            return false;
        }
        final var lines = text.substring(0, end).split("\r\n");
        require(lines[0].matches("HTTP/1\\.[01] [2-5][0-9]{2}.*"), "Unsupported HTTP status / informational response");
        final var headers = new HashMap<String, String>();
        for (int i = 1; i < lines.length; i++) {
            final int colon = lines[i].indexOf(':');
            require(colon > 0, "Malformed HTTP header");
            final var key = lines[i].substring(0, colon).toLowerCase(java.util.Locale.ROOT);
            require(headers.putIfAbsent(key, lines[i].substring(colon + 1).trim()) == null, "Duplicate HTTP header unsupported");
        }
        if (lines[0].startsWith("HTTP/1.1 204 ") || lines[0].startsWith("HTTP/1.1 304 ")) {
            return true;
        }
        if (headers.containsKey("transfer-encoding")) {
            require(headers.get("transfer-encoding").equalsIgnoreCase("chunked"), "Unsupported transfer coding");
            int offset = end + 4;
            while (true) {
                final int lineEnd = text.indexOf("\r\n", offset);
                if (lineEnd < 0) {
                    return false;
                }
                final var sizeText = text.substring(offset, lineEnd).split(";", 2)[0];
                require(sizeText.matches("[0-9a-fA-F]{1,7}"), "Invalid HTTP chunk size");
                final int size = Integer.parseInt(sizeText, 16);
                require(size <= LIMIT, "HTTP chunk too large");
                offset = lineEnd + 2;
                if (size == 0) {
                    return text.startsWith("\r\n", offset) || text.indexOf("\r\n\r\n", offset) >= 0;
                }
                if (response.length < offset + size + 2) {
                    return false;
                }
                require(text.startsWith("\r\n", offset + size), "Invalid HTTP chunk terminator");
                offset += size + 2;
            }
        }
        if (headers.containsKey("content-length")) {
            final var length = headers.get("content-length");
            require(length.matches("[0-9]{1,8}"), "Invalid Content-Length");
            final int size = Integer.parseInt(length);
            require(size <= LIMIT, "HTTP body too large");
            return response.length >= end + 4 + size;
        }
        return false;
    }

    private Packet plainRecord() throws Exception {
        for (int ccs = 0; ccs < 4; ccs++) {
            final var header = readExactly(5);
            final int size = ((header[3] & 255) << 8) | (header[4] & 255);
            require(header[1] == 3 && header[2] == 3 && size <= 16640 && size > 0, "Invalid TLS record header");
            final var bytes = readExactly(size);
            if (header[0] == 20) {
                require(Arrays.equals(bytes, new byte[]{1}), "Invalid ChangeCipherSpec");
                continue;
            }
            return new Packet(header[0] & 255, bytes);
        }
        throw new IOException("Too many ChangeCipherSpec records");
    }

    private byte[] readExactly(final int size) throws IOException {
        final var bytes = input.readNBytes(size);
        if (bytes.length != size) {
            throw new EOFException("Truncated TLS connection");
        }
        return bytes;
    }

    private void record(final int type, final int version, final byte[] bytes) throws IOException {
        output.write(concat(new byte[]{(byte) type}, words(version, bytes.length), bytes));
        output.flush();
    }

    private final class Traffic {
        private final byte[] key;
        private final byte[] iv;
        private long sequence;

        private Traffic(final byte[] secret) throws Exception {
            key = expand(secret, "key", EMPTY, suite == 0x1301 ? 16 : 32);
            iv = expand(secret, "iv", EMPTY, 12);
        }

        private Cipher cipher(final int mode, final byte[] header) throws Exception {
            require(sequence >= 0 && sequence < (1L << 24), "Record limit reached");
            final var nonce = iv.clone();
            final long value = sequence++;
            for (int i = 0; i < 8; i++) {
                nonce[nonce.length - 1 - i] ^= (byte) (value >>> (8 * i));
            }
            final var cipher = Cipher.getInstance(suite == 0x1303 ? "ChaCha20-Poly1305" : "AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, suite == 0x1303 ? "ChaCha20" : "AES"),
                    suite == 0x1303 ? new IvParameterSpec(nonce) : new GCMParameterSpec(128, nonce));
            cipher.updateAAD(header);
            return cipher;
        }

        private Packet read() throws Exception {
            final var record = plainRecord();
            require(record.type == 23 && record.bytes.length >= 17, "Expected encrypted record");
            final var header = concat(new byte[]{23, 3, 3}, words(record.bytes.length));
            final var bytes = cipher(Cipher.DECRYPT_MODE, header).doFinal(record.bytes);
            require(bytes.length <= 16385, "Plaintext record too large");
            int last = bytes.length - 1;
            while (last >= 0 && bytes[last] == 0) {
                last--;
            }
            require(last >= 0, "Missing inner content type");
            return new Packet(bytes[last] & 255, Arrays.copyOf(bytes, last));
        }

        private void write(final int type, final byte[] bytes) throws Exception {
            require(bytes.length <= 16384, "Plaintext record too large");
            final var header = concat(new byte[]{23, 3, 3}, words(bytes.length + 17));
            output.write(header);
            output.write(cipher(Cipher.ENCRYPT_MODE, header).doFinal(concat(bytes, new byte[]{(byte) type})));
            output.flush();
        }
    }

    private byte[] digest(final byte[] bytes) throws Exception {
        return MessageDigest.getInstance(hash).digest(bytes);
    }

    private byte[] mac(final byte[] key, final byte[] bytes) throws Exception {
        final var mac = Mac.getInstance(hmac);
        mac.init(new SecretKeySpec(key, hmac));
        return mac.doFinal(bytes);
    }

    private byte[] extract(final byte[] salt, final byte[] inputKey) throws Exception {
        return mac(salt, inputKey);
    }

    private byte[] derive(final byte[] secret, final String label, final byte[] contextHash) throws Exception {
        return expand(secret, label, contextHash, hashSize);
    }

    private byte[] expand(final byte[] secret, final String label, final byte[] context, final int length) throws Exception {
        final var info = concat(words(length), vector(1, ("tls13 " + label).getBytes(StandardCharsets.US_ASCII)), vector(1, context));
        final var result = new ByteArrayOutputStream();
        byte[] previous = EMPTY;
        for (int i = 1; result.size() < length; i++) {
            previous = mac(secret, concat(previous, info, new byte[]{(byte) i}));
            result.writeBytes(previous);
        }
        return Arrays.copyOf(result.toByteArray(), length);
    }

    private static Map<Integer, byte[]> extensions(final byte[] bytes) throws IOException {
        final var reader = new Reader(bytes);
        final var result = new HashMap<Integer, byte[]>();
        while (reader.remaining() > 0) {
            require(result.putIfAbsent(reader.u16(), reader.vector(2)) == null, "Duplicate extension");
        }
        return result;
    }

    private static void extension(final ByteArrayOutputStream out, final int type, final byte[] bytes) {
        out.writeBytes(concat(words(type), vector(2, bytes)));
    }

    private static byte[] message(final int type, final byte[] bytes) {
        return concat(new byte[]{(byte) type}, vector(3, bytes));
    }

    private static byte[] vector(final int width, final byte[] bytes) {
        final var length = new byte[width];
        for (int i = 0; i < width; i++) {
            length[width - i - 1] = (byte) (bytes.length >>> (i * 8));
        }
        return concat(length, bytes);
    }

    private static byte[] words(final int... words) {
        final var out = new ByteArrayOutputStream();
        for (final int word : words) {
            out.write(word >>> 8);
            out.write(word);
        }
        return out.toByteArray();
    }

    private static byte[] concat(final byte[]... arrays) {
        final var out = new ByteArrayOutputStream();
        for (final var array : arrays) {
            out.writeBytes(array);
        }
        return out.toByteArray();
    }

    private static byte[] random(final int size) {
        final var bytes = new byte[size];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private static byte[] littleEndian(final BigInteger integer, final int length) {
        final var bytes = integer.toByteArray();
        reverse(bytes);
        return Arrays.copyOf(bytes, length);
    }

    private static void reverse(final byte[] bytes) {
        for (int i = 0; i < bytes.length / 2; i++) {
            final byte value = bytes[i];
            bytes[i] = bytes[bytes.length - i - 1];
            bytes[bytes.length - i - 1] = value;
        }
    }

    private static void require(final boolean condition, final String message) throws IOException {
        if (!condition) {
            throw new IOException(message);
        }
    }

    private record Packet(int type, byte[] bytes) {}

    private static final class Reader {
        private final byte[] bytes;
        private int position;

        private Reader(final byte[] bytes) {
            this.bytes = bytes;
        }

        private int remaining() {
            return bytes.length - position;
        }

        private int u8() throws IOException {
            require(remaining() > 0, "Truncated structure");
            return bytes[position++] & 255;
        }

        private int u16() throws IOException {
            return number(2);
        }

        private int number(final int width) throws IOException {
            int result = 0;
            for (int i = 0; i < width; i++) {
                result = (result << 8) | u8();
            }
            return result;
        }

        private byte[] take(final int size) throws IOException {
            require(size >= 0 && size <= remaining(), "Truncated vector");
            final var result = Arrays.copyOfRange(bytes, position, position + size);
            position += size;
            return result;
        }

        private byte[] vector(final int width) throws IOException {
            return take(number(width));
        }

        private void end() throws IOException {
            require(remaining() == 0, "Unexpected structure suffix");
        }
    }
}
