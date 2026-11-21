package com.docqa.reminder.support;

/**
 * 方案 4.2 步 4：纯算法实现已下沉 common，这里保留同名薄委托（含 byte[] 重载）以固定调用点。
 * 业务边界（谁调用、算什么指纹）不变，只把 SHA-256 收敛成单一来源。
 */
public final class Fingerprints {

    private Fingerprints() {
    }

    public static String sha256(String value) {
        return com.docqa.common.support.Fingerprints.sha256(value);
    }

    public static String sha256(byte[] value) {
        return com.docqa.common.support.Fingerprints.sha256(value);
    }
}
