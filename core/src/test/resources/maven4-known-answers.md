# Maven 4 known-answer test vectors

These are synthetic, public test credentials, not real secrets.
`MavenSecretServiceTest` and `MavenServerPasswordTemplateHelperTest` embed the respective ciphertext constants to avoid runtime encryption and its extra PBKDF2 work.
The tests still exercise production decryption at the full 310,000 iterations; do not reduce that compatibility setting.

## Inputs and format

| Test | Master (UTF-8, no newline) | Plaintext (UTF-8, no newline) | Salt (16 bytes, hex) | IV (12 bytes, hex) |
| --- | --- | --- | --- | --- |
| `MavenSecretServiceTest` | `YCA_TEST_MASTER_SECRET` | `YCA_TEST_SERVER_PASSWORD_1234` | `000102030405060708090a0b0c0d0e0f` | `101112131415161718191a1b` |
| `MavenServerPasswordTemplateHelperTest` | `YCA_HELPER_MASTER_SECRET` | `YCA_HELPER_SERVER_PASSWORD` | `202122232425262728292a2b2c2d2e2f` | `303132333435363738393a3b` |

Derive a 256-bit key using PBKDF2-HMAC-SHA512 with 310,000 iterations, then encrypt with AES-256-GCM (no padding, empty additional authenticated data, 128-bit authentication tag).
The Maven value is `{[name=master,cipher=AES/GCM/NoPadding,version=4.0]` followed by standard Base64 of `IV || salt || ciphertext || tag`, followed by `}`.
Fixed salt/IV values are for these test vectors only, not a production encryption example.

## Independent generation recipe

Generated on 2026-11-16 with OpenSSL **4.0.3 (29 Sep 2026)** using its C libcrypto API, not the Java encryptors formerly in the tests and not the production decryptor.
OpenSSL's `enc` CLI does not support GCM, so the standalone program below uses `PKCS5_PBKDF2_HMAC` and `EVP_aes_256_gcm` directly.
This is a manual reproduction recipe only: neither compilation nor encryption runs as part of the tests.
Save the following as `/tmp/yca-maven4-known-answer.c`, compile, and run:

```sh
cc -Wall -Wextra -Werror /tmp/yca-maven4-known-answer.c -o /tmp/yca-maven4-known-answer $(pkg-config --cflags --libs openssl)
/tmp/yca-maven4-known-answer
```

```c
#include <openssl/evp.h>
#include <openssl/crypto.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static void require(int ok) {
    if (ok != 1) {
        fputs("OpenSSL operation failed\n", stderr);
        exit(1);
    }
}

static void generate(const char *master, const char *plaintext, unsigned char start) {
    unsigned char salt[16], iv[12], key[32], body[256], base64[345];
    for (int i = 0; i < 16; i++) {
        salt[i] = start + i;
    }
    for (int i = 0; i < 12; i++) {
        iv[i] = start + 16 + i;
    }
    require(PKCS5_PBKDF2_HMAC(master, (int)strlen(master), salt, sizeof(salt),
                            310000, EVP_sha512(), sizeof(key), key));
    EVP_CIPHER_CTX *ctx = EVP_CIPHER_CTX_new();
    if (ctx == NULL) {
        exit(1);
    }
    require(EVP_EncryptInit_ex(ctx, EVP_aes_256_gcm(), NULL, NULL, NULL));
    require(EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_SET_IVLEN, sizeof(iv), NULL));
    require(EVP_EncryptInit_ex(ctx, NULL, NULL, key, iv));
    memcpy(body, iv, sizeof(iv));
    memcpy(body + sizeof(iv), salt, sizeof(salt));
    int length = 0, tail = 0;
    require(EVP_EncryptUpdate(ctx, body + 28, &length,
                             (const unsigned char *)plaintext, (int)strlen(plaintext)));
    require(EVP_EncryptFinal_ex(ctx, body + 28 + length, &tail));
    length += tail;
    require(EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_GET_TAG, 16, body + 28 + length));
    EVP_EncodeBlock(base64, body, 28 + length + 16);
    printf("master=%s\nplaintext=%s\n", master, plaintext);
    printf("{[name=master,cipher=AES/GCM/NoPadding,version=4.0]%s}\n", base64);
    EVP_CIPHER_CTX_free(ctx);
    OPENSSL_cleanse(key, sizeof(key));
}

int main(void) {
    puts(OpenSSL_version(OPENSSL_VERSION));
    generate("YCA_TEST_MASTER_SECRET", "YCA_TEST_SERVER_PASSWORD_1234", 0x00);
    generate("YCA_HELPER_MASTER_SECRET", "YCA_HELPER_SERVER_PASSWORD", 0x20);
    return 0;
}
```

Observed output (the two ciphertexts are the embedded test constants):

```text
OpenSSL 4.0.3 29 Sep 2026
master=YCA_TEST_MASTER_SECRET
plaintext=YCA_TEST_SERVER_PASSWORD_1234
{[name=master,cipher=AES/GCM/NoPadding,version=4.0]EBESExQVFhcYGRobAAECAwQFBgcICQoLDA0OD6gsNIpbHt5RVOOH8wCKrZfIWvx0XICoi5L02wPNNUYOvcr608O0a24a9B4DHg==}
master=YCA_HELPER_MASTER_SECRET
plaintext=YCA_HELPER_SERVER_PASSWORD
{[name=master,cipher=AES/GCM/NoPadding,version=4.0]MDEyMzQ1Njc4OTo7ICEiIyQlJicoKSorLC0uL3wGicWimiArTLNFI2W3zICctKr13Oq7RQTgB8zlUzRZm/EehIWxTODOhg==}
```
