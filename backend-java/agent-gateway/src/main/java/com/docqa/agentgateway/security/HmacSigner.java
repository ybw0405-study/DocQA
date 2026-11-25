package com.docqa.agentgateway.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 网关 → Python 数据面的 HMAC 签名器（与 {@code ai-python/src/docqa_ai/api/internal_auth.py} 同规范）。
 *
 * <p>规范串（按行拼接，UTF-8）：
 * <pre>
 * METHOD
 * path            （不含 query）
 * timestamp       （秒级 unix 时间戳字符串）
 * nonce           （一次性随机串）
 * sha256(body).hexdigest()
 * </pre>
 * 签名 = HMAC-SHA256(secret, 规范串)，十六进制小写。
 *
 * <p>空 body 用空字节数组参与哈希。nonce 默认随机 UUID（无连字符）。
 * 时间戳默认当前秒；测试可注入确定性值交叉验证。
 */
public final class HmacSigner {

    public static final String TIMESTAMP_HEADER = "x-internal-timestamp";
    public static final String SIGNATURE_HEADER = "x-internal-signature";
    public static final String NONCE_HEADER = "x-internal-nonce";

    private HmacSigner() {}

    public record HmacHeaders(String timestamp, String nonce, String signature) {}

    public static HmacHeaders sign(String secret, String method, String path, byte[] body) {
        return sign(secret, method, path, body == null ? new byte[0] : body,
                epochSecond(), UUID.randomUUID().toString().replace("-", ""));
    }

    public static HmacHeaders sign(String secret, String method, String path, byte[] body,
                                   long timestamp, String nonce) {
        String ts = String.valueOf(timestamp);
        String bodyHash = sha256Hex(body == null ? new byte[0] : body);
        byte[] canonical = (method.toUpperCase() + "\n"
                + path + "\n"
                + ts + "\n"
                + nonce + "\n"
                + bodyHash).getBytes(StandardCharsets.UTF_8);
        String signature = hmacSha256Hex(secret.getBytes(StandardCharsets.UTF_8), canonical);
        return new HmacHeaders(ts, nonce, signature);
    }

    private static long epochSecond() {
        return System.currentTimeMillis() / 1000L;
    }

    private static String sha256Hex(byte[] data) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String hmacSha256Hex(byte[] secret, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return hex(mac.doFinal(data));
        } catch (NoSuchAlgorithmException | java.security.InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 not available", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16))
              .append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
