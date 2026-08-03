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

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import java.math.BigInteger;
import java.util.Base64;

/**
 * The JWS encoding rules shared by {@link CardSigner} and {@link CardVerifier}: the algorithm names, base64url and the
 * ECDSA signature form.
 */
@ApplicationScoped
public class Jws {
    /**
     * ECDSA on P-256 with SHA-256, the algorithm the specification uses in its examples.
     */
    public static final String ES256 = "ES256";

    /**
     * RSASSA-PKCS1-v1_5 with SHA-256.
     */
    public static final String RS256 = "RS256";

    protected static final int P256_COORDINATE_LENGTH = 32;

    /**
     * @param algorithm a JWS {@code alg} value.
     * @return the JCA signature algorithm name.
     * @throws IllegalArgumentException on an algorithm this implementation does not know.
     */
    public String jcaAlgorithm(final String algorithm) {
        return switch (algorithm) {
            case ES256 -> "SHA256withECDSA";
            case RS256 -> "SHA256withRSA";
            default ->
                throw new IllegalArgumentException(
                        "Unsupported signature algorithm '" + algorithm + "', use " + ES256 + " or " + RS256);
        };
    }

    /**
     * @param value bytes to encode.
     * @return their base64url form, unpadded, as JOSE requires everywhere.
     */
    public String base64Url(final byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    /**
     * @param value a base64url string, padded or not.
     * @return the decoded bytes.
     */
    public byte[] fromBase64Url(final String value) {
        return Base64.getUrlDecoder().decode(value);
    }

    /**
     * Converts the DER encoded ECDSA signature the JCA produces to the fixed size {@code R || S} form JWS requires.
     * <p>
     * This conversion is <b>not</b> optional: skipping it produces a signature every JOSE library rejects, which is the
     * classic way an ES256 implementation looks correct against itself and fails against everyone else. RSA signatures
     * need nothing, their JCA output is already what JWS wants.
     *
     * @param der the DER {@code SEQUENCE { INTEGER r, INTEGER s }}.
     * @return the 64 bytes of {@code R || S}, left padded with zeroes.
     */
    public byte[] derToJose(final byte[] der) {
        var offset = 0;
        if (der.length < 8 || der[offset++] != 0x30) {
            throw new IllegalArgumentException("Not a DER sequence");
        }
        if ((der[offset] & 0xff) > 0x80) { // a long form length: that byte says how many length bytes follow
            // the value is unsigned, and 0x81 read as a signed byte would send the offset backwards
            offset += (der[offset] & 0xff) - 0x80 + 1;
        } else {
            offset++;
        }

        if (der[offset++] != 0x02) {
            throw new IllegalArgumentException("Missing the r INTEGER");
        }
        final var rLength = der[offset++];
        final var r = new BigInteger(der, offset, rLength);
        offset += rLength;

        if (der[offset++] != 0x02) {
            throw new IllegalArgumentException("Missing the s INTEGER");
        }
        final var sLength = der[offset++];
        final var s = new BigInteger(der, offset, sLength);

        final var jose = new byte[2 * P256_COORDINATE_LENGTH];
        copyCoordinate(r, jose, 0);
        copyCoordinate(s, jose, P256_COORDINATE_LENGTH);
        return jose;
    }

    /**
     * The reverse of {@link #derToJose(byte[])}, needed to verify a signature with the JCA.
     *
     * @param jose the 64 bytes of {@code R || S}.
     * @return the DER encoded signature.
     */
    public byte[] joseToDer(final byte[] jose) {
        if (jose.length != 2 * P256_COORDINATE_LENGTH) {
            throw new IllegalArgumentException(
                    "An ES256 signature is " + (2 * P256_COORDINATE_LENGTH) + " bytes, got " + jose.length);
        }
        final var r = new BigInteger(1, jose, 0, P256_COORDINATE_LENGTH).toByteArray();
        final var s = new BigInteger(1, jose, P256_COORDINATE_LENGTH, P256_COORDINATE_LENGTH).toByteArray();

        final var length = 2 + r.length + 2 + s.length;
        final var der = new byte[2 + length];
        var offset = 0;
        der[offset++] = 0x30;
        der[offset++] = (byte) length;
        der[offset++] = 0x02;
        der[offset++] = (byte) r.length;
        System.arraycopy(r, 0, der, offset, r.length);
        offset += r.length;
        der[offset++] = 0x02;
        der[offset++] = (byte) s.length;
        System.arraycopy(s, 0, der, offset, s.length);
        return der;
    }

    /**
     * Writes an unsigned coordinate right aligned in its fixed size slot, dropping the sign byte {@code BigInteger} adds
     * and left padding a short value with zeroes.
     */
    protected void copyCoordinate(final BigInteger coordinate, final byte[] target, final int at) {
        final var bytes = coordinate.toByteArray();
        final var from = Math.max(0, bytes.length - P256_COORDINATE_LENGTH);
        final var length = Math.min(bytes.length, P256_COORDINATE_LENGTH);
        System.arraycopy(bytes, from, target, at + P256_COORDINATE_LENGTH - length, length);
    }
}
