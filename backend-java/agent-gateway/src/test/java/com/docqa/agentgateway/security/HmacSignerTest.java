package com.docqa.agentgateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 交叉验证：Java {@link HmacSigner} 与 {@code ai-python/src/docqa_ai/api/internal_auth.py}
 * 的 {@code sign()} 在同一组确定性输入下必须产出完全相同的签名。
 *
 * <p>预期值由 Python 端运行 {@code docqa_ai.api.internal_auth.sign(...)} 得出，
 * 见 {@code ai-python/tests/test_hmac_internal_auth.py} 的同名向量。
 * 任一侧改动规范串或哈希算法，本测试与 Python 侧测试会同时失败。
 */
class HmacSignerTest {

    // 与 Python test_hmac_internal_auth.py::test_cross_validate_java_vector 同向量
    private static final String SECRET = "cross-validate-secret";
    private static final String METHOD = "POST";
    private static final String PATH = "/agent/ask";
    private static final byte[] BODY = "{\"question\":\"hi\"}".getBytes();
    private static final long TIMESTAMP = 1_700_000_000L;
    private static final String NONCE = "nonce-fixed-001";

    // 由 Python sign(...) 计算的预期签名（HMAC-SHA256，hex）
    private static final String EXPECTED_SIGNATURE =
            "472e26a3a1140e812d3f6d39eca02a8ceee8beb22727dccf83313bce2f49e44f";

    @Test
    void crossValidateWithPython() {
        HmacSigner.HmacHeaders headers =
                HmacSigner.sign(SECRET, METHOD, PATH, BODY, TIMESTAMP, NONCE);
        assertThat(headers.timestamp()).isEqualTo(String.valueOf(TIMESTAMP));
        assertThat(headers.nonce()).isEqualTo(NONCE);
        assertThat(headers.signature()).isEqualTo(EXPECTED_SIGNATURE);
    }

    @Test
    void emptyBodyProducesStableSignature() {
        HmacSigner.HmacHeaders empty =
                HmacSigner.sign(SECRET, "GET", "/internal/memories", new byte[0], TIMESTAMP, NONCE);
        // 空 body 的 sha256 hex 是已知常量 e3b0c442...；这里只校验签名稳定且非空
        assertThat(empty.signature()).isNotBlank();
        // 同输入两次签名必须一致（确定性）
        HmacSigner.HmacHeaders again =
                HmacSigner.sign(SECRET, "GET", "/internal/memories", new byte[0], TIMESTAMP, NONCE);
        assertThat(again.signature()).isEqualTo(empty.signature());
    }

    @Test
    void blankSecretIsNoOpAtApplyLayer() {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        HmacRequestSigner.apply(headers, "", "POST", "/agent/ask", BODY);
        assertThat(headers).isEmpty();
        HmacRequestSigner.apply(headers, null, "POST", "/agent/ask", BODY);
        assertThat(headers).isEmpty();
    }
}
