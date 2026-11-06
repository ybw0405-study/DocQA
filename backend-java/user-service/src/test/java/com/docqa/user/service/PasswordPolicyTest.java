package com.docqa.user.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.docqa.common.api.FieldViolation;
import java.util.List;
import org.junit.jupiter.api.Test;

class PasswordPolicyTest {

    private final PasswordPolicy policy = new PasswordPolicy();

    @Test
    void acceptsAPasswordWithEnoughBytesLettersAndDigits() {
        assertThat(policy.violations("password", "correct-horse-9")).isEmpty();
        assertThat(policy.isAcceptable("correct-horse-9")).isTrue();
    }

    @Test
    void rejectsMissingPassword() {
        assertThat(policy.violations("password", null)).singleElement()
                .satisfies(violation -> assertThat(violation.field()).isEqualTo("password"));
        assertThat(policy.violations("password", "")).hasSize(1);
    }

    @Test
    void countsUtf8BytesNotCharacters() {
        String twentyFourHanCharacters = "密".repeat(24);

        assertThat(twentyFourHanCharacters).hasSize(24);
        assertThat(twentyFourHanCharacters.getBytes(java.nio.charset.StandardCharsets.UTF_8)).hasSize(72);
        assertThat(policy.violations("password", twentyFourHanCharacters))
                .extracting(FieldViolation::message)
                .containsOnly("must contain at least one letter", "must contain at least one digit");
    }

    @Test
    void rejectsPasswordsAboveTheBcryptByteLimit() {
        String seventyThreeAscii = "a1" + "x".repeat(71);

        assertThat(policy.violations("password", seventyThreeAscii)).singleElement()
                .satisfies(violation -> assertThat(violation.message()).contains("72"));
    }

    @Test
    void rejectsShortPasswords() {
        assertThat(policy.violations("newPassword", "short1")).singleElement()
                .satisfies(violation -> assertThat(violation.field()).isEqualTo("newPassword"));
    }

    @Test
    void rejectsPasswordsWithoutLettersOrDigits() {
        assertThat(policy.violations("password", "1234567890"))
                .extracting(FieldViolation::message)
                .containsExactly("must contain at least one letter");
        assertThat(policy.violations("password", "onlyletters"))
                .extracting(FieldViolation::message)
                .containsExactly("must contain at least one digit");
    }

    @Test
    void reportsEveryViolationAtOnce() {
        List<FieldViolation> violations = policy.violations("password", "1234");

        assertThat(violations).hasSize(2);
        assertThat(violations).allMatch(violation -> violation.field().equals("password"));
    }
}
