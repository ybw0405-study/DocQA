package com.docqa.agentgateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CredentialCipherTest {

    private static final UUID CONNECTION_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_CONNECTION_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002");
    private static final UUID OTHER_TENANT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    /**
     * 跨语言互操作夹具：由 Python {@code cryptography.AESGCM} 用固定 nonce 生成，
     * 同一份 base64 也写死在 {@code ai-python/tests/test_model_connection.py} 里。
     * 两侧都能解开它，才证明「网关封装、数据面解封」这条链在字节层面成立。
     * 密钥由公开常量字符串派生，不是任何真实凭据。
     */
    private static final String FIXTURE_KEY_B64 = "Ol6+TCExErUZz413bhIyEaI79pogf5/Bh3FAtPF0dtU=";
    private static final String FIXTURE_KEY_ID = "3a990d9492e6";
    private static final String FIXTURE_CIPHERTEXT_B64 =
            "AAECAwQFBgcICQoLmb9lA5TMAFaUcL2oVa25OreCz+djACScj7NQ350pgbgWcINWq5W3EgU=";
    private static final String FIXTURE_PLAINTEXT = "sk-test-vector-0123456789";

    private static final String KEY_B64 = base64Of(sha256("docqa-unit-test-master-key"));
    private static final String OLD_KEY_B64 = base64Of(sha256("docqa-unit-test-previous-key"));

    @Test
    void sealsAndOpensWithTheCurrentKey() {
        CredentialCipher cipher = new CredentialCipher(KEY_B64, List.of());
        byte[] aad = CredentialCipher.aad(CONNECTION_ID, TENANT_ID);

        byte[] sealed = cipher.seal(FIXTURE_PLAINTEXT.getBytes(StandardCharsets.UTF_8), aad);

        assertThat(sealed).hasSize(12 + FIXTURE_PLAINTEXT.length() + 16);
        assertThat(new String(sealed, StandardCharsets.UTF_8)).doesNotContain(FIXTURE_PLAINTEXT);
        assertThat(cipher.open(sealed, aad, cipher.keyId()))
                .isEqualTo(FIXTURE_PLAINTEXT.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void usesAFreshIvForEverySeal() {
        CredentialCipher cipher = new CredentialCipher(KEY_B64, List.of());
        byte[] aad = CredentialCipher.aad(CONNECTION_ID, TENANT_ID);
        byte[] plaintext = FIXTURE_PLAINTEXT.getBytes(StandardCharsets.UTF_8);

        byte[] first = cipher.seal(plaintext, aad);
        byte[] second = cipher.seal(plaintext, aad);

        assertThat(first).isNotEqualTo(second);
        assertThat(cipher.open(first, aad, cipher.keyId())).isEqualTo(plaintext);
        assertThat(cipher.open(second, aad, cipher.keyId())).isEqualTo(plaintext);
    }

    /** 密文被搬到别的连接或别的租户下必须解不开——AAD 就是为这条路径存在的。 */
    @Test
    void refusesCiphertextMovedToAnotherConnectionOrTenant() {
        CredentialCipher cipher = new CredentialCipher(KEY_B64, List.of());
        byte[] sealed = cipher.seal(
                FIXTURE_PLAINTEXT.getBytes(StandardCharsets.UTF_8), CredentialCipher.aad(CONNECTION_ID, TENANT_ID));

        assertThatThrownBy(() -> cipher.open(
                sealed, CredentialCipher.aad(OTHER_CONNECTION_ID, TENANT_ID), cipher.keyId()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher.open(
                sealed, CredentialCipher.aad(CONNECTION_ID, OTHER_TENANT_ID), cipher.keyId()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void opensHistoricalCiphertextWithThePreviousKeyAfterRotation() {
        CredentialCipher oldCipher = new CredentialCipher(OLD_KEY_B64, List.of());
        byte[] aad = CredentialCipher.aad(CONNECTION_ID, TENANT_ID);
        byte[] historical = oldCipher.seal(FIXTURE_PLAINTEXT.getBytes(StandardCharsets.UTF_8), aad);

        CredentialCipher rotated = new CredentialCipher(KEY_B64, List.of(OLD_KEY_B64));

        assertThat(rotated.keyId()).isNotEqualTo(oldCipher.keyId());
        assertThat(rotated.open(historical, aad, oldCipher.keyId()))
                .isEqualTo(FIXTURE_PLAINTEXT.getBytes(StandardCharsets.UTF_8));
        // 轮换后新写入一律用当前密钥，不再产生旧 keyId 的密文。
        assertThat(rotated.keyId()).isEqualTo(keyIdOf(KEY_B64));
    }

    /** 旧密钥已从 keyring 移除时必须硬失败，而不是拿当前密钥去试出一堆乱码。 */
    @Test
    void refusesUnknownKeyId() {
        CredentialCipher cipher = new CredentialCipher(KEY_B64, List.of());
        byte[] aad = CredentialCipher.aad(CONNECTION_ID, TENANT_ID);
        byte[] sealed = cipher.seal(FIXTURE_PLAINTEXT.getBytes(StandardCharsets.UTF_8), aad);

        assertThatThrownBy(() -> cipher.open(sealed, aad, "ffffffffffff"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown credential keyId");
    }

    @Test
    void refusesMalformedCiphertext() {
        CredentialCipher cipher = new CredentialCipher(KEY_B64, List.of());
        byte[] aad = CredentialCipher.aad(CONNECTION_ID, TENANT_ID);

        assertThatThrownBy(() -> cipher.open(new byte[12], aad, cipher.keyId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("malformed");
        assertThatThrownBy(() -> cipher.open(null, aad, cipher.keyId()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsMasterKeysThatAreNotBase64Of32Bytes() {
        assertThatThrownBy(() -> new CredentialCipher("not-base64!!", List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be base64");
        assertThatThrownBy(() -> new CredentialCipher(base64Of(new byte[16]), List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must decode to 32 bytes");
        assertThatThrownBy(() -> new CredentialCipher(KEY_B64, List.of(base64Of(new byte[31]))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DOCQA_CRED_MASTER_KEY_PREVIOUS");
    }

    @Test
    void keyIdIsTheFirstTwelveHexCharsOfTheKeyDigest() {
        assertThat(new CredentialCipher(KEY_B64, List.of()).keyId()).isEqualTo(keyIdOf(KEY_B64));
        assertThat(new CredentialCipher(FIXTURE_KEY_B64, List.of()).keyId()).isEqualTo(FIXTURE_KEY_ID);
    }

    /** 与 Python 侧共用同一份夹具：解不开就说明两侧的格式/AAD/keyId 约定漂了。 */
    @Test
    void opensTheCrossLanguageFixture() {
        CredentialCipher cipher = new CredentialCipher(FIXTURE_KEY_B64, List.of());
        byte[] sealed = Base64.getDecoder().decode(FIXTURE_CIPHERTEXT_B64);

        byte[] opened = cipher.open(sealed, CredentialCipher.aad(CONNECTION_ID, TENANT_ID), FIXTURE_KEY_ID);

        assertThat(new String(opened, StandardCharsets.UTF_8)).isEqualTo(FIXTURE_PLAINTEXT);
    }

    private static String keyIdOf(String base64Key) {
        return hexPrefix(sha256(Base64.getDecoder().decode(base64Key)));
    }

    private static String hexPrefix(byte[] digest) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            sb.append(String.format("%02x", digest[i]));
        }
        return sb.toString();
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String base64Of(byte[] value) {
        return Base64.getEncoder().encodeToString(value);
    }
}
