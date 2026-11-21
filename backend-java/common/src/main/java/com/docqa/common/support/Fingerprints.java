package com.docqa.common.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 方案 4.2 步 4：Fingerprints×3 的纯工具实现下沉到 common。
 *
 * <p>三个服务原先各自复制了同一份 SHA-256 十六进制工具（仅 reminder 版多一个 byte[] 重载）。
 * 这里取三者并集作为唯一实现；各服务保留同名薄委托类，调用点、包路径、方法签名一律不变，
 * 因此这次下沉不触碰任何业务边界，也不改变任何调用方。
 */
public final class Fingerprints {

    private Fingerprints() {
    }

    public static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256(byte[] value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}
