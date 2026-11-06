package com.docqa.agentgateway.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class PiiMaskerTest {

    private final PiiMasker masker = new PiiMasker();

    @Test
    void masksEmail() {
        PiiMaskingResult result = masker.mask("联系我 student@example.com");

        assertThat(result.masked()).isEqualTo("联系我 [EMAIL]");
        assertThat(result.categories()).containsExactly("EMAIL");
        assertThat(result.count()).isEqualTo(1);
    }

    @Test
    void masksPhoneNumber() {
        PiiMaskingResult result = masker.mask("手机号 13812345678");

        assertThat(result.masked()).isEqualTo("手机号 [PHONE]");
        assertThat(result.categories()).containsExactly("PHONE");
    }

    @Test
    void masksIdCard() {
        PiiMaskingResult result = masker.mask("身份证 11010519491231002X");

        assertThat(result.masked()).isEqualTo("身份证 [ID_CARD]");
        assertThat(result.categories()).containsExactly("ID_CARD");
    }

    @Test
    void stripsZeroWidthCharactersBeforeDetection() {
        PiiMaskingResult result = masker.mask("1381\u200B2345678");

        assertThat(result.masked()).isEqualTo("[PHONE]");
        assertThat(result.count()).isEqualTo(1);
    }

    @Test
    void reportsMultipleCategoriesInFixedOrder() {
        PiiMaskingResult result = masker.mask(
                "邮箱 a@b.com，手机 13812345678，身份证 11010519491231002X");

        assertThat(result.categories()).containsExactly("EMAIL", "ID_CARD", "PHONE");
        assertThat(result.count()).isEqualTo(3);
    }

    @Test
    void leavesPlainTextUntouched() {
        PiiMaskingResult result = masker.mask("请解释梯度下降");

        assertThat(result.masked()).isEqualTo("请解释梯度下降");
        assertThat(result.categories()).isEqualTo(List.of());
        assertThat(result.count()).isZero();
        assertThat(masker.containsPii("请解释梯度下降")).isFalse();
    }

    @Test
    void handlesNullInput() {
        PiiMaskingResult result = masker.mask(null);

        assertThat(result.masked()).isEmpty();
        assertThat(result.count()).isZero();
    }
}
