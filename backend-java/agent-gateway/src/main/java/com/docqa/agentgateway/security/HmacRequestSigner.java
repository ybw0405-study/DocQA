package com.docqa.agentgateway.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;

/**
 * 把 {@link HmacSigner} 接入 {@code WebClient} 调用点的薄封装。
 *
 * <p>调用方在 {@code .bodyValue(obj)} 之前先用 {@link #bodyBytes} 把同一个对象序列化，
 * 算出签名后通过 {@code .headers(h -> apply(...))} 注入三个头。WebClient 的
 * {@code Jackson2JsonEncoder} 默认就用同一个 {@link ObjectMapper} bean 做
 * {@code writeValueAsBytes}，故签名输入与实际发送字节一致。
 *
 * <p>secret 为空（dev / 未配置）时本方法 no-op，保留上一轮静态 token 兼容期。
 */
public final class HmacRequestSigner {

    private HmacRequestSigner() {}

    public static byte[] bodyBytes(ObjectMapper mapper, Object body) {
        try {
            return body == null ? new byte[0] : mapper.writeValueAsBytes(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("HMAC body serialization failed", e);
        }
    }

    public static void apply(HttpHeaders headers, String secret,
                             String method, String path, byte[] bodyBytes) {
        if (secret == null || secret.isBlank()) {
            return;
        }
        HmacSigner.HmacHeaders signed = HmacSigner.sign(secret, method, path, bodyBytes);
        headers.add(HmacSigner.TIMESTAMP_HEADER, signed.timestamp());
        headers.add(HmacSigner.SIGNATURE_HEADER, signed.signature());
        headers.add(HmacSigner.NONCE_HEADER, signed.nonce());
    }
}
