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
package io.yupiik.ia.agent.core.security;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Reads and decrypts Maven {@code settings.xml} secret values with the Maven 4 algorithm, and
 * detects Maven 3 legacy ciphertext, using only the JDK ({@code javax.crypto}, no Maven dependency).
 *
 * <p>Maven 4 ({@code settings-security4.xml} + {@code {[name=...,cipher=...,version=4.0]base64}}):
 * the master dispatcher derives an AES key with {@code PBKDF2WithHmacSHA512} (310000 iterations,
 * 256-bit) from a master source value and a per-ciphertext salt, then AES/GCM/NoPadding (128-bit
 * tag) with a 12-byte IV. Ciphertext body layout: base64(IV[12] + salt[16] + GCM ciphertext). The
 * master source is resolved from the dispatcher's {@code source} property: {@code env:VAR},
 * {@code sysprop:NAME} or a plaintext file path.</p>
 *
 * <p>Maven 3 legacy {@code {base64}} is <em>detected</em> (and redacted as a secret) but not decrypted,
 * matching Maven 4's own guidance (MNG-8379): legacy "encryption" is obfuscation-grade and Maven 4
 * warns to re-encrypt with {@code mvnenc}. For redaction purposes we always treat both the ciphertext
 * and (when decryptable) the plaintext as secrets.</p>
 */
@ApplicationScoped
public class MavenSecretService {

    private static final Logger LOGGER = Logger.getLogger(MavenSecretService.class.getName());

    private static final String AES_GCM = "AES/GCM/NoPadding";
    private static final String PBKDF2 = "PBKDF2WithHmacSHA512";
    private static final int PBE_ITERATIONS = 310_000;
    private static final int PBE_KEY_SIZE = 256;
    private static final int IV_LENGTH = 12;
    private static final int SALT_LENGTH = 16;
    private static final int TAG_LENGTH_BITS = 128;

    /** Maven 4 self-describing ciphertext: {[name=...,cipher=...,version=...]base64}. */
    private static final Pattern MAVEN4_CIPHERTEXT =
            Pattern.compile("\\{\\[([^]]*)]((?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?)}");

    /** Maven 3 legacy ciphertext: {base64} (no self-describing metadata). */
    private static final Pattern LEGACY_CIPHERTEXT =
            Pattern.compile("\\{((?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?)}");

    /**
     * Collects the secret strings carried by a {@code settings.xml}: every Maven 4 / legacy ciphertext
     * and, when the master secret is resolvable, the decrypted plaintext. Used by the redactor's
     * harvest so both the encrypted and the real value never go over the wire.
     *
     * @param settingsXml path to settings.xml
     * @return a mutable set of secret values found
     */
    public Set<String> collectSecretNames(final Path settingsXml) {
        final var out = new HashSet<String>();
        final var xml = read(settingsXml);
        if (xml == null) {
            return out;
        }
        final var secretConfig = secretConfigFromSettings(settingsXml);
        final var masterSecret = secretConfig == null ? null : masterSecret(secretConfig);
        final var m4 = MAVEN4_CIPHERTEXT.matcher(xml);
        while (m4.find()) {
            final var ciphertext = m4.group();
            out.add(ciphertext);
            if (masterSecret != null) {
                try {
                    final var plain = decryptMaven4(m4.group(2), masterSecret);
                    if (plain != null && !plain.isBlank()) {
                        out.add(plain);
                    }
                } catch (final RuntimeException e) {
                    LOGGER.log(Level.FINE, () -> "Cannot decrypt Maven 4 value: " + e.getMessage());
                }
            }
        }
        final var legacy = LEGACY_CIPHERTEXT.matcher(xml);
        while (legacy.find()) {
            out.add(legacy.group());
        }
        return out;
    }

    /** True for a Maven 4 self-describing ciphertext. */
    public static boolean isMaven4Ciphertext(final String value) {
        return value != null && MAVEN4_CIPHERTEXT.matcher(value).matches();
    }

    /** True for a Maven 3 legacy ciphertext. */
    public static boolean isLegacyCiphertext(final String value) {
        return value != null && LEGACY_CIPHERTEXT.matcher(value).matches();
    }

    /**
     * Resolves the {@code <password>} of the settings.xml {@code <server>} whose {@code <id>} matches
     * {@code serverId}, ready to be used as a credential. The raw stored value is returned as-is when it
     * is clear text or a Maven {@code ${env.X}} / {@code ${sys.Y}} placeholder, and a Maven 4 ciphertext
     * is decrypted in memory against the master dispatcher resolved from the settings-security file
     * alongside the settings file.
     *
     * <p>A Maven 3 legacy {@code {base64}} password cannot be recovered (MNG-8379 deems it
     * obfuscation-grade and Maven 4 refuses to decrypt it), so its use here is a hard error pointing at
     * {@code mvnenc}: silently sending the ciphertext to a gateway would leak a useless value.</p>
     *
     * @param settingsXml path to settings.xml
     * @param serverId the server id to look up
     * @return the resolved server password, never null
     * @throws IllegalArgumentException when the server or its password is absent or unresolved
     */
    public String resolveServerPassword(final Path settingsXml, final String serverId) {
        if (settingsXml == null || serverId == null || serverId.isBlank()) {
            throw new IllegalArgumentException("A settings.xml path and a server id are required");
        }
        final var xml = read(settingsXml);
        if (xml == null) {
            throw new IllegalArgumentException("Cannot read Maven settings '" + settingsXml + "'");
        }
        final var password = findServerPassword(xml, serverId);
        if (password == null) {
            throw new IllegalArgumentException(
                    "No <server><id>" + serverId + "</id></server> entry (or no <password>) found in " + settingsXml);
        }
        if (isLegacyCiphertext(password)) {
            // Maven 4 and plexus-sec-dispatcher refuse legacy ciphertext; surface it loudly rather than
            // sending an encrypted blob to a gateway expecting the real credential.
            throw new IllegalArgumentException(
                    "Server '" + serverId + "' uses a Maven 3 legacy {base64} password which cannot be "
                            + "decrypted; re-encrypt it with 'mvnenc' first so {{mavenServerPassword}} can "
                            + "decipher it in memory");
        }
        if (!isMaven4Ciphertext(password)) {
            return resolvePlaceholders(password);
        }
        final var secretConfig = secretConfigFromSettings(settingsXml);
        final var masterSecret = secretConfig == null ? null : masterSecret(secretConfig);
        if (masterSecret == null) {
            throw new IllegalArgumentException("Cannot decipher server '" + serverId
                    + "': no resolvable master secret found (" + "settings-security4.xml with a 'source' dispatcher)");
        }
        final var matcher = MAVEN4_CIPHERTEXT.matcher(password);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Malformed Maven 4 ciphertext for server '" + serverId + "'");
        }
        return resolvePlaceholders(decryptMaven4(matcher.group(2), masterSecret));
    }

    /**
     * Expands Maven's property placeholders used in server passwords: {@code ${env.NAME}} and
     * {@code ${sys.NAME}} (dot form; the plexus-style {@code ${env:NAME}} / {@code ${sys:NAME}} is also
     * accepted) for an environment variable / system property. An unresolved name expands to the empty
     * string; anything else is returned verbatim.
     */
    private static String resolvePlaceholders(final String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        // capture the source (env|sys) and the name, tolerating a '.' or ':' separator
        final var placeholder = Pattern.compile("\\$\\{(env|sys)[.:]([^}]+)}");
        final var matcher = placeholder.matcher(value);
        final var sb = new StringBuilder();
        while (matcher.find()) {
            final var source = matcher.group(1);
            final var name = matcher.group(2);
            final var resolved = "env".equals(source) ? System.getenv(name) : System.getProperty(name);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(resolved == null ? "" : resolved));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /** Finds the {@code <password>} of the {@code <server><id>serverId</id>...} entry in the settings XML. */
    private static String findServerPassword(final String xml, final String serverId) {
        try {
            final var dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(false);
            final var doc = dbf.newDocumentBuilder()
                    .parse(new java.io.ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            for (final var server : descendantElements(doc.getDocumentElement(), "server")) {
                final var id = childText(server, "id");
                if (serverId.equals(id)) {
                    return childText(server, "password");
                }
            }
        } catch (final Exception e) {
            throw new IllegalArgumentException("Invalid Maven settings XML: " + e.getMessage(), e);
        }
        return null;
    }

    /* ---------------------------------------------------------------- */

    private SecretConfig secretConfigFromSettings(final Path settingsXml) {
        // derive the settings-security4.xml alongside the settings file (default ~/.m2)
        final var dir = settingsXml == null
                ? null
                : settingsXml.toAbsolutePath().normalize().getParent();
        if (dir == null) {
            return null;
        }
        final var v4 = dir.resolve("settings-security4.xml");
        final var v3 = dir.resolve("settings-security.xml");
        final var candidate = Files.isRegularFile(v4) ? v4 : (Files.isRegularFile(v3) ? v3 : null);
        if (candidate == null) {
            return null;
        }
        try {
            return parseSecurityFile(candidate);
        } catch (final RuntimeException e) {
            LOGGER.log(Level.FINE, () -> "Cannot parse Maven security file " + candidate + ": " + e.getMessage());
            return null;
        }
    }

    private String masterSecret(final SecretConfig cfg) {
        if (cfg == null || cfg.properties == null) {
            return null;
        }
        final var source = cfg.properties.getOrDefault("source", "");
        if (source == null || source.isBlank()) {
            return null;
        }
        if (source.startsWith("env:")) {
            return System.getenv(source.substring(4));
        }
        if (source.startsWith("sysprop:")) {
            return System.getProperty(source.substring(8));
        }
        if (source.startsWith("property:")) {
            return System.getProperty(source.substring(9));
        }
        // else: a plaintext file path (Maven's FileMasterSource)
        try {
            final var p = Path.of(CodingAgentConfiguration.expandHome(source));
            return Files.exists(p) ? Files.readString(p).trim() : null;
        } catch (final IOException e) {
            LOGGER.log(Level.FINE, () -> "Cannot read master source file " + source + ": " + e.getMessage());
            return null;
        }
    }

    private static String decryptMaven4(final String base64Body, final String masterSecret) {
        try {
            final var body = Base64.getDecoder().decode(base64Body.getBytes(StandardCharsets.UTF_8));
            if (body.length < IV_LENGTH + SALT_LENGTH) {
                return null;
            }
            final var buf = ByteBuffer.wrap(body);
            final var iv = new byte[IV_LENGTH];
            buf.get(iv);
            final var salt = new byte[SALT_LENGTH];
            buf.get(salt);
            final var ciphertext = new byte[buf.remaining()];
            buf.get(ciphertext);
            final var key = getAesKey(masterSecret.toCharArray(), salt);
            final var cipher = Cipher.getInstance(AES_GCM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (final Exception e) {
            throw new IllegalStateException("Cannot decrypt Maven 4 secret: " + e.getMessage(), e);
        }
    }

    private static javax.crypto.SecretKey getAesKey(final char[] password, final byte[] salt) throws Exception {
        final var spec = new PBEKeySpec(password, salt, PBE_ITERATIONS, PBE_KEY_SIZE);
        final var key = SecretKeyFactory.getInstance(PBKDF2).generateSecret(spec);
        return new SecretKeySpec(key.getEncoded(), "AES");
    }

    private static SecretConfig parseSecurityFile(final Path file) {
        final var xml = read(file);
        if (xml == null) {
            return new SecretConfig("master", Map.of());
        }
        String defaultDispatcher = "master";
        try {
            final var dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(false);
            final var doc = dbf.newDocumentBuilder().parse(file.toFile());
            defaultDispatcher = firstText(doc, "defaultDispatcher", "master");
            final var configurations = doc.getElementsByTagName("configuration");
            for (int i = 0; i < configurations.getLength(); i++) {
                final var node = configurations.item(i);
                final var name = childText(node, "name");
                if (!defaultDispatcher.equals(name)) {
                    continue;
                }
                final var props = new LinkedHashMap<String, String>();
                // properties live under <properties><property><name>/<value></property></properties>
                final var propertyNodes = descendantElements(node, "property");
                for (final var p : propertyNodes) {
                    final var k = childText(p, "name");
                    final var v = childText(p, "value");
                    if (k != null) {
                        props.put(k, v == null ? "" : v);
                    }
                }
                if (props.containsKey("source")) {
                    return new SecretConfig(defaultDispatcher, props);
                }
            }
        } catch (final Exception e) {
            LOGGER.log(Level.FINE, () -> "Invalid Maven security XML " + file + ": " + e.getMessage());
        }
        return new SecretConfig(defaultDispatcher, Map.of());
    }

    private static String firstText(final org.w3c.dom.Document doc, final String tag, final String fallback) {
        final var list = doc.getElementsByTagName(tag);
        return list.getLength() > 0 ? textOf(list.item(0)) : fallback;
    }

    private static String childText(final org.w3c.dom.Node parent, final String tag) {
        for (var child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE && tag.equals(child.getNodeName())) {
                return textOf(child);
            }
        }
        return null;
    }

    /** All descendant elements with the given tag (depth-first), used for the nested properties block. */
    private static List<org.w3c.dom.Node> descendantElements(final org.w3c.dom.Node parent, final String tag) {
        final var out = new ArrayList<org.w3c.dom.Node>();
        for (var child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE) {
                if (tag.equals(child.getNodeName())) {
                    out.add(child);
                }
                out.addAll(descendantElements(child, tag));
            }
        }
        return out;
    }

    private static String textOf(final org.w3c.dom.Node node) {
        final var sb = new StringBuilder();
        for (var child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == org.w3c.dom.Node.TEXT_NODE
                    || child.getNodeType() == org.w3c.dom.Node.CDATA_SECTION_NODE) {
                sb.append(child.getNodeValue());
            }
        }
        return sb.toString().trim();
    }

    private static String read(final Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return null;
        }
        try (final InputStream in = Files.newInputStream(path)) {
            final var out = new ByteArrayOutputStream();
            final var buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8);
        } catch (final IOException e) {
            LOGGER.log(Level.FINE, () -> "Cannot read " + path + ": " + e.getMessage());
            return null;
        }
    }

    private record SecretConfig(String dispatcher, Map<String, String> properties) {}
}
