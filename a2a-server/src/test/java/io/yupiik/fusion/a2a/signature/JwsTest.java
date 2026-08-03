/*
 * Copyright (c) 2026 - present - Yupiik SAS - https://www.yupiik.com
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
package io.yupiik.fusion.a2a.signature;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.a2a.test.Keys;
import java.security.Signature;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * The JOSE encoding rules, and in particular the ECDSA signature form.
 * <p>
 * The DER to {@code R || S} conversion is the classic way an ES256 implementation looks correct against itself and is
 * rejected by every other library, so it is tested against the JCA output directly - including the short coordinates a
 * random key eventually produces, which is what the padding is for.
 */
class JwsTest {
    private final Jws jws = new Jws();

    @Test
    void algorithms() {
        assertEquals("SHA256withECDSA", jws.jcaAlgorithm(Jws.ES256));
        assertEquals("SHA256withRSA", jws.jcaAlgorithm(Jws.RS256));
    }

    @Test
    void anUnknownAlgorithmSaysWhatIsSupported() {
        final var error = assertThrows(IllegalArgumentException.class, () -> jws.jcaAlgorithm("HS256"));

        assertTrue(error.getMessage().contains("HS256"), error::getMessage);
        assertTrue(error.getMessage().contains("ES256"), error::getMessage);
        assertTrue(error.getMessage().contains("RS256"), error::getMessage);
    }

    @Test
    void base64UrlIsUnpadded() {
        // JOSE uses base64url without padding everywhere, and '+' and '/' must not appear
        assertEquals("AP8-_w", jws.base64Url(new byte[] {0, -1, 62, -1}));
        assertEquals("", jws.base64Url(new byte[0]));
        assertArrayEquals("hello".getBytes(UTF_8), jws.fromBase64Url(jws.base64Url("hello".getBytes(UTF_8))));
        // and a padded value is still read, since that is what some producers send
        assertArrayEquals("hello".getBytes(UTF_8), jws.fromBase64Url("aGVsbG8="));
    }

    @Test
    void aJcaSignatureIsConvertedToTheJoseForm() throws Exception {
        final var keys = Keys.ec();
        final var signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(keys.getPrivate());

        // several signatures: ECDSA is randomized, so this is what eventually exercises a short coordinate
        for (int i = 0; i < 50; i++) {
            signer.update(("payload-" + i).getBytes(UTF_8));
            final var der = signer.sign();

            final var jose = jws.derToJose(der);
            assertEquals(64, jose.length, "an ES256 signature is exactly two 32 byte coordinates");
            // and the round trip verifies with the JCA, which only speaks DER
            final var verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(keys.getPublic());
            verifier.update(("payload-" + i).getBytes(UTF_8));
            assertTrue(verifier.verify(jws.joseToDer(jose)), "The round trip should verify");
        }
    }

    @Test
    void aCoordinateIsLeftPaddedToItsFixedSize() {
        // r = 1 and s = 1: the shortest coordinates there are, so the padding is all there is to check
        final var der = new byte[] {0x30, 0x06, 0x02, 0x01, 0x01, 0x02, 0x01, 0x01};

        final var jose = jws.derToJose(der);

        assertEquals(64, jose.length);
        assertEquals(1, jose[31], "r must be right aligned in its slot");
        assertEquals(1, jose[63], "and so must s");
        assertEquals(0, jose[0]);
        assertEquals(0, jose[32]);
    }

    @Test
    void aLongFormLengthIsRead() {
        // r = 1 and s = 1, re-encoded with the long form: DER allows it, and the length byte is unsigned - reading 0x81
        // as a signed byte would send the offset backwards
        final var shortForm = new byte[] {0x30, 0x06, 0x02, 0x01, 0x01, 0x02, 0x01, 0x01};
        final var oneLengthByte = new byte[] {0x30, (byte) 0x81, 0x06, 0x02, 0x01, 0x01, 0x02, 0x01, 0x01};
        final var twoLengthBytes = new byte[] {0x30, (byte) 0x82, 0x00, 0x06, 0x02, 0x01, 0x01, 0x02, 0x01, 0x01};

        final var expected = jws.derToJose(shortForm);
        assertArrayEquals(expected, jws.derToJose(oneLengthByte));
        assertArrayEquals(expected, jws.derToJose(twoLengthBytes));
    }

    @Test
    void aMalformedDerSignatureIsRejected() {
        assertEquals(
                "Not a DER sequence",
                assertThrows(IllegalArgumentException.class, () -> jws.derToJose(new byte[] {0x30, 0x02}))
                        .getMessage(),
                "anything shorter than eight bytes cannot hold two integers");
        assertEquals(
                "Not a DER sequence",
                assertThrows(
                                IllegalArgumentException.class,
                                () -> jws.derToJose(new byte[] {0x31, 0x06, 0x02, 0x01, 0x01, 0x02, 0x01, 0x01}))
                        .getMessage());
        assertEquals(
                "Missing the r INTEGER",
                assertThrows(
                                IllegalArgumentException.class,
                                () -> jws.derToJose(new byte[] {0x30, 0x06, 0x03, 0x01, 0x01, 0x02, 0x01, 0x01}))
                        .getMessage());
        assertEquals(
                "Missing the s INTEGER",
                assertThrows(
                                IllegalArgumentException.class,
                                () -> jws.derToJose(new byte[] {0x30, 0x06, 0x02, 0x01, 0x01, 0x03, 0x01, 0x01}))
                        .getMessage());
    }

    @Test
    void aJoseSignatureHasAFixedSize() {
        final var error = assertThrows(IllegalArgumentException.class, () -> jws.joseToDer(new byte[63]));

        assertTrue(error.getMessage().contains("64 bytes"), error::getMessage);
        assertTrue(error.getMessage().contains("63"), error::getMessage);
        assertThrows(IllegalArgumentException.class, () -> jws.joseToDer(new byte[0]));
    }

    @Test
    void aTamperedSignatureIsStillDecodable() {
        // the encoders are not validators: a wrong but well formed value must produce bytes, not an exception, so the
        // verification is what rejects it
        final var jose = new byte[64];
        Arrays.fill(jose, (byte) 0x7f);

        assertEquals(70, jws.joseToDer(jose).length, "a sequence header plus two 32 byte integers with their own");
        assertArrayEquals(jose, jws.derToJose(jws.joseToDer(jose)));
    }

    @Test
    void base64UrlDecodingRejectsGarbage() {
        assertThrows(IllegalArgumentException.class, () -> jws.fromBase64Url("not base64!!"));
    }
}
