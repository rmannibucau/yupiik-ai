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

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies the Maven 4 settings decryption (AES/GCM/NoPadding + PBKDF2WithHmacSHA512, matching
 * plexus-sec-dispatcher 4.2.0) and the Maven 3 legacy ciphertext detection/redaction, against a
 * synthetic settings-security4.xml + settings.xml in a temp dir. Uses a plaintext-file master
 * source so the test is independent of the process environment (no env var to set).
 */
class MavenSecretServiceTest {

    private static final String MASTER = "YCA_TEST_MASTER_SECRET";
    private static final String SERVER_PASSWORD = "YCA_TEST_SERVER_PASSWORD_1234";

    @TempDir
    Path tmp;

    @Test
    void decryptMaven4AndHarvest() throws Exception {
        final var security = tmp.resolve("settings-security4.xml");
        final var settings = tmp.resolve("settings.xml");
        final var masterFile = tmp.resolve("master.txt");
        Files.writeString(masterFile, MASTER);

        Files.writeString(security, """
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

        final var ciphertext = encryptV4(SERVER_PASSWORD, MASTER);
        Files.writeString(settings, """
                <settings>
                  <servers>
                    <server>
                      <id>test</id>
                      <username>testuser</username>
                      <password>%s</password>
                    </server>
                  </servers>
                </settings>
                """.formatted(ciphertext));

        final var service = new MavenSecretService();
        final Set<String> secrets = service.collectSecretNames(settings);
        // both the ciphertext and the decrypted plaintext are treated as secrets
        assertTrue(secrets.contains(ciphertext), "ciphertext must be a secret: " + secrets);
        assertTrue(secrets.contains(SERVER_PASSWORD), "decrypted plaintext must be a secret: " + secrets);
        // a legacy {base64} value is detected as a secret too (not decrypted)
        assertTrue(MavenSecretService.isLegacyCiphertext("{BteqUEnqHecHM7MZfnj9FwLcYbdInWxou1C929Txa0A=}"));
    }

    @Test
    void isMaven4Ciphertext() {
        assertTrue(
                MavenSecretService.isMaven4Ciphertext(
                        "{[name=master,cipher=AES/GCM/NoPadding,version=4.0]w+C6vJwryWolUYkLtAMCkMGCWfB09haER/AiingAH4NEDr37euLdwMYYLvxaSQe/97OJ+A==}"));
    }

    @Test
    void harvestsProfilePropertiesAndHttpHeadersWithBuiltInDefaults() throws Exception {
        final var settings = tmp.resolve("settings-props.xml");
        Files.writeString(settings, """
                <settings>
                  <profiles>
                    <profile>
                      <id>sign</id>
                      <properties>
                        <gpg.keyname>YZTE648RT4R4D74974F97GR97FG497GF97GF79GF</gpg.keyname>
                        <gpg.passphrase>my-gpg-passphrase-value</gpg.passphrase>
                        <deploy.password>plain-deploy-secret</deploy.password>
                        <harmless.buildFlag>true</harmless.buildFlag>
                      </properties>
                    </profile>
                  </profiles>
                  <servers>
                    <server>
                      <id>bearer</id>
                      <configuration>
                        <httpHeaders>
                          <property>
                            <name>Authorization</name>
                            <value>Bearer bearer-token-value</value>
                          </property>
                          <property>
                            <name>X-Api-Key</name>
                            <value>plain-api-key-value</value>
                          </property>
                        </httpHeaders>
                      </configuration>
                    </server>
                    <server>
                      <id>basic</id>
                      <configuration>
                        <httpHeaders>
                          <property>
                            <name>authorization</name>
                            <value>Basic dXNlcjpwYXNz</value>
                          </property>
                        </httpHeaders>
                      </configuration>
                    </server>
                  </servers>
                </settings>
                """);

        final var secrets = new MavenSecretService().collectSecretNames(settings);

        // profile properties: explicit allowlist (gpg.keyname) and keyword-derived (passphrase, password)
        assertTrue(secrets.contains("YZTE648RT4R4D74974F97GR97FG497GF97GF79GF"), "gpg.keyname allowlist: " + secrets);
        assertTrue(secrets.contains("my-gpg-passphrase-value"), "gpg.passphrase keyword: " + secrets);
        assertTrue(secrets.contains("plain-deploy-secret"), "deploy.password keyword: " + secrets);
        // a harmless property not matching allowlist or keyword must be ignored
        assertTrue(!secrets.contains("true"), "harmless property must not be a secret: " + secrets);

        // server httpHeaders: only the credential part after the scheme is harvested
        assertTrue(secrets.contains("bearer-token-value"), "Bearer token after scheme: " + secrets);
        assertTrue(secrets.contains("plain-api-key-value"), "X-Api-Key without space keeps full value: " + secrets);
        assertTrue(secrets.contains("dXNlcjpwYXNz"), "Basic token after scheme: " + secrets);
        // the scheme prefix alone must not be harvested
        assertTrue(!secrets.contains("Bearer"), "scheme prefix alone must not be a secret: " + secrets);
    }

    @Test
    void harvestsHttpHeaderPlaceholdersResolved() throws Exception {
        final var settings = tmp.resolve("settings-header-placeholder.xml");
        Files.writeString(settings, """
                <settings>
                  <servers>
                    <server>
                      <id>ph</id>
                      <configuration>
                        <httpHeaders>
                          <property>
                            <name>Authorization</name>
                            <value>Bearer ${sys.yca.test.header.token}</value>
                          </property>
                        </httpHeaders>
                      </configuration>
                    </server>
                  </servers>
                </settings>
                """);
        System.setProperty("yca.test.header.token", "resolved-placeholder-secret");

        try {
            final var secrets = new MavenSecretService().collectSecretNames(settings);
            assertTrue(secrets.contains("resolved-placeholder-secret"), "placeholder must resolve: " + secrets);
        } finally {
            System.clearProperty("yca.test.header.token");
        }
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
