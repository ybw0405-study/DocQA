package com.docqa.agentgateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.docqa.common.error.BusinessException;
import com.docqa.common.error.ErrorCode;
import org.junit.jupiter.api.Test;

class IdempotencyKeysTest {

    @Test
    void acceptsUuidStyleKeys() {
        String key = "6c1f3f5e-1d2a-4b3c-8d4e-5f6a7b8c9d0e";

        assertThat(IdempotencyKeys.validate(key)).isEqualTo(key);
    }

    @Test
    void trimsSurroundingWhitespace() {
        assertThat(IdempotencyKeys.validate("  request-key-01  ")).isEqualTo("request-key-01");
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> IdempotencyKeys.validate(null))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.AGT_VALIDATION_001);
    }

    @Test
    void rejectsShortKey() {
        assertThatThrownBy(() -> IdempotencyKeys.validate("short"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsKeyWithIllegalCharacters() {
        assertThatThrownBy(() -> IdempotencyKeys.validate("invalid key with spaces"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsOverlongKey() {
        assertThatThrownBy(() -> IdempotencyKeys.validate("a".repeat(65)))
                .isInstanceOf(BusinessException.class);
    }
}
