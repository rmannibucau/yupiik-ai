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
package io.yupiik.ia.agent.core.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.framework.handlebars.HandlebarsCompiler;
import io.yupiik.fusion.framework.handlebars.helper.HelperContext;
import io.yupiik.fusion.framework.handlebars.spi.Template;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration$FusionConfigurationFactory;
import io.yupiik.ia.agent.core.security.MavenSecretService;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies the {@code {{mavenServerPassword "serverId"}}} dynamic-header helper: it resolves a Maven
 * settings.xml server password, deciphering a Maven 4 ciphertext in memory (clear text and
 * {@code ${env.X}} / {@code ${sys.Y}} pass through), and fails loudly on a missing server, a
 * missing password or a Maven 3 legacy {@code {base64}} value. Uses a fake {@code .m2} in a temp dir
 * with a plaintext-file master source so the test is independent of the process environment.
 */
class MavenServerPasswordTemplateHelperTest {

    private static final String MASTER = "YCA_HELPER_MASTER_SECRET";
    private static final String SERVER_PASSWORD = "YCA_HELPER_SERVER_PASSWORD";

    @TempDir
    Path tmp;

    @Test
    void decryptsMaven4CiphertextAndPlainPassword() throws Exception {
        final var m2 = new FakeM2(tmp);
        m2.writeSettings(MASTER, m2.cipher(SERVER_PASSWORD), """
                <server>
                  <id>other</id>
                  <username>u</username>
                  <password>clear-text</password>
                </server>
                """);

        final var helper = helper(m2);
        // the ciphered server is deciphered in memory
        assertEquals(SERVER_PASSWORD, render(helper, "{{mavenServerPassword \"central\"}}"));
        // the clear-text server is returned as-is
        assertEquals("clear-text", render(helper, "{{mavenServerPassword \"other\"}}"));
    }

    @Test
    void resolvesSystemPropertyPlaceholder() throws Exception {
        System.setProperty("yca.test.server.password", SERVER_PASSWORD);
        try {
            final var m2 = new FakeM2(tmp);
            m2.writeSettings(MASTER, "${sys.test.never.set.placeholder}", """
                    <server>
                      <id>prop</id>
                      <username>u</username>
                      <password>${sys.yca.test.server.password}</password>
                    </server>
                    """);

            final var helper = helper(m2);
            // a resolved ${sys.NAME} placeholder expands to the system property value
            assertEquals(SERVER_PASSWORD, render(helper, "{{mavenServerPassword \"prop\"}}"));
            // an unresolved ${sys.NAME} placeholder resolves to an empty value and fails the render
            final var e =
                    assertThrows(RuntimeException.class, () -> render(helper, "{{mavenServerPassword \"central\"}}"));
            assertTrue(e.getMessage().contains("central"), e.getMessage());
        } finally {
            System.clearProperty("yca.test.server.password");
        }
    }

    @Test
    void resolvesEnvPlaceholderWhenPresent() throws Exception {
        final var value = System.getenv("YCA_TEST_MAVEN_SERVER_PASSWORD");
        if (value == null) {
            // cannot mutate the process environment portably; skip when the CI env has no sample value
            return;
        }
        final var m2 = new FakeM2(tmp);
        m2.writeSettings(MASTER, "${env.YCA_TEST_MAVEN_SERVER_PASSWORD}", "");
        assertEquals(value, render(helper(m2), "{{mavenServerPassword \"central\"}}"));
    }

    @Test
    void missingServerFailsTheRender() throws Exception {
        final var m2 = new FakeM2(tmp);
        m2.writeSettings(MASTER, SERVER_PASSWORD, "");
        final var helper = helper(m2);
        final var e = assertThrows(RuntimeException.class, () -> render(helper, "{{mavenServerPassword \"absent\"}}"));
        assertTrue(e.getMessage().contains("absent"), e.getMessage());
    }

    @Test
    void legacyCiphertextFailsWithReEncryptHint() throws Exception {
        final var m2 = new FakeM2(tmp);
        m2.writeSettings(MASTER, "{BteqUEnqHecHM7MZfnj9FwLcYbdInWxou1C929Txa0A=}", "");
        final var helper = helper(m2);
        final var e = assertThrows(RuntimeException.class, () -> render(helper, "{{mavenServerPassword \"central\"}}"));
        assertTrue(e.getMessage().contains("mvnenc"), e.getMessage());
    }

    @Test
    void rejectedWhenNoMasterSecretIsResolvable() throws Exception {
        // a settings-security4.xml with no resolvable source -> must fail rather than leak ciphertext
        final var m2 = new FakeM2(tmp);
        m2.writeSettings(MASTER, m2.cipher(SERVER_PASSWORD), "");
        // overwrite the security file produced by writeSettings with a dispatcher carrying no source
        Files.writeString(m2.security(), """
                <settingsSecurity>
                  <defaultDispatcher>master</defaultDispatcher>
                  <configurations>
                    <configuration>
                      <name>master</name>
                    </configuration>
                  </configurations>
                </settingsSecurity>
                """);
        final var helper = helper(m2);
        final var e = assertThrows(RuntimeException.class, () -> render(helper, "{{mavenServerPassword \"central\"}}"));
        assertTrue(e.getMessage().contains("master secret"), e.getMessage());
    }

    private MavenServerPasswordTemplateHelper helper(final FakeM2 m2) {
        final var entries = new HashMap<String, String>();
        entries.put("yca.secrets.maven.settings", m2.settings().toString());
        entries.put("yca.secrets.maven.masterSecret", m2.security().toString());
        final var configuration = new CodingAgentConfiguration$FusionConfigurationFactory(
                        key -> Optional.ofNullable(entries.get(key)))
                .get();
        return new MavenServerPasswordTemplateHelper(configuration, new MavenSecretService());
    }

    private static String render(final MavenServerPasswordTemplateHelper helper, final String source) {
        final Function<HelperContext, Object> fn = helper;
        final Template template = new HandlebarsCompiler()
                .compile(new HandlebarsCompiler.CompilationContext(
                        new HandlebarsCompiler.Settings().helpers(Map.of(helper.name(), fn)), source));
        return template.render(Map.of()).toString();
    }

    /** A minimal fake Maven tree (a {@code .m2} clone) with settings.xml and settings-security4.xml. */
    private static final class FakeM2 {
        private final Path root;

        private FakeM2(final Path tmp) throws Exception {
            root = tmp.resolve(".m2");
            Files.createDirectories(root);
        }

        private Path settings() {
            return root.resolve("settings.xml");
        }

        private Path security() {
            return root.resolve("settings-security4.xml");
        }

        private String cipher(final String plaintext) throws Exception {
            return encryptV4(plaintext, MASTER);
        }

        private void writeSettings(final String master, final String centralPassword, final String extraServers)
                throws Exception {
            final var masterFile = root.resolve("master.txt");
            Files.writeString(masterFile, master);
            Files.writeString(security(), """
                    <settingsSecurity>
                      <defaultDispatcher>master</defaultDispatcher>
                      <configurations>
                        <configuration>
                          <name>master</name>
                          <properties>
                            <property>
                              <name>source</name>
                              <value>%s</value>
                            </property>
                            <property>
                              <name>cipher</name>
                              <value>AES/GCM/NoPadding</value>
                            </property>
                          </properties>
                        </configuration>
                      </configurations>
                    </settingsSecurity>
                    """.formatted(masterFile.toAbsolutePath()));
            Files.writeString(settings(), """
                    <settings>
                      <servers>
                        <server>
                          <id>central</id>
                          <username>u</username>
                          <password>%s</password>
                        </server>
                        %s
                      </servers>
                    </settings>
                    """.formatted(centralPassword, extraServers));
        }

        /** Reference encrypt matching Maven 4 plexus-sec-dispatcher AESGCMNoPadding (iv||salt||ct). */
        private static String encryptV4(final String plaintext, final String master) throws Exception {
            final var salt = new byte[16];
            final var iv = new byte[12];
            new SecureRandom().nextBytes(salt);
            new SecureRandom().nextBytes(iv);
            final var keySpec = new PBEKeySpec(master.toCharArray(), salt, 310_000, 256);
            final var key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512").generateSecret(keySpec);
            final var aes = new SecretKeySpec(key.getEncoded(), "AES");
            final var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, aes, new GCMParameterSpec(128, iv));
            final var ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            final var buf = ByteBuffer.allocate(iv.length + salt.length + ct.length);
            buf.put(iv).put(salt).put(ct);
            return "{[name=master,cipher=AES/GCM/NoPadding,version=4.0]"
                    + Base64.getEncoder().encodeToString(buf.array()) + "}";
        }
    }
}
