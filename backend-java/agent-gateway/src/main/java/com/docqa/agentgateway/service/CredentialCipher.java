package com.docqa.agentgateway.service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 模型连接凭据的封装与解封（AES-256-GCM）。
 *
 * <p>密文格式 {@code 12B IV || ciphertext || 16B tag}，整体存 {@code model_connection.credential}。
 * AAD 绑定 {@code connectionId|tenantId}：密文被搬到别的连接或别的租户下都解不开，
 * 因此「改一下行的 id 就读到别人的密钥」这条路径在密码学层面就不成立。
 *
 * <p>主密钥来自 {@code DOCQA_CRED_MASTER_KEY}（base64，解码后必须 32 字节）。
 * {@code keyId} 是主密钥 SHA-256 的前 12 位十六进制，随密文入库；轮换时旧密钥经
 * {@code DOCQA_CRED_MASTER_KEY_PREVIOUS} 进入 keyring，解封按密文携带的 keyId 选密钥。
 * 未知 keyId 直接拒绝——静默换一把密钥去试只会把「配置错了」变成「解出乱码」。
 *
 * <p>Python 侧 {@code docqa_ai.governance.model_connection.CredentialCipher} 必须与本实现
 * 逐字节兼容，两侧共用同一份密文夹具做互操作测试。
 */
public final class CredentialCipher {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;
    private static final int KEY_ID_HEX_CHARS = 12;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKey key;
    private final String keyId;
    private final Map<String, SecretKey> keyring;

    public CredentialCipher(String base64Key, List<String> previousKeys) {
        byte[] raw = decodeKey(base64Key, "DOCQA_CRED_MASTER_KEY");
        this.key = new SecretKeySpec(raw, "AES");
        this.keyId = keyIdOf(raw);
        Map<String, SecretKey> ring = new HashMap<>();
        ring.put(this.keyId, this.key);
        for (String previous : previousKeys) {
            if (previous == null || previous.isBlank()) {
                continue;
            }
            byte[] previousRaw = decodeKey(previous, "DOCQA_CRED_MASTER_KEY_PREVIOUS");
            ring.put(keyIdOf(previousRaw), new SecretKeySpec(previousRaw, "AES"));
        }
        this.keyring = Map.copyOf(ring);
    }

    /** 当前主密钥的 keyId；新封装的密文都记这个值。 */
    public String keyId() {
        return keyId;
    }

    public static byte[] aad(UUID connectionId, UUID tenantId) {
        return (connectionId + "|" + tenantId).getBytes(StandardCharsets.UTF_8);
    }

    public byte[] seal(byte[] plaintext, byte[] aad) {
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad);
            byte[] sealed = cipher.doFinal(plaintext);
            return ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array();
        } catch (Exception ex) {
            throw new IllegalStateException("credential encryption failed", ex);
        }
    }

    /** 按密文入库时记录的 keyId 选密钥解封；未知 keyId 或 AAD 不匹配一律拒绝。 */
    public byte[] open(byte[] stored, byte[] aad, String storedKeyId) {
        SecretKey chosen = keyring.get(storedKeyId);
        if (chosen == null) {
            throw new IllegalStateException("unknown credential keyId: " + storedKeyId
                    + " (is DOCQA_CRED_MASTER_KEY_PREVIOUS configured?)");
        }
        if (stored == null || stored.length <= IV_BYTES) {
            throw new IllegalStateException("credential ciphertext malformed");
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(stored, 0, iv, 0, IV_BYTES);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, chosen, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad);
            return cipher.doFinal(stored, IV_BYTES, stored.length - IV_BYTES);
        } catch (Exception ex) {
            throw new IllegalStateException("credential decryption failed", ex);
        }
    }

    private static byte[] decodeKey(String base64Key, String name) {
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(name + " must be base64", ex);
        }
        if (raw.length != KEY_BYTES) {
            throw new IllegalStateException(name + " must decode to " + KEY_BYTES + " bytes, got " + raw.length);
        }
        return raw;
    }

    private static String keyIdOf(byte[] raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < KEY_ID_HEX_CHARS / 2; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("credential keyId derivation failed", ex);
        }
    }
}
