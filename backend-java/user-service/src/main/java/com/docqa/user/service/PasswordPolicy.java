package com.docqa.user.service;

import com.docqa.common.api.FieldViolation;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 口令强度策略。契约里 {10,72} 数的是字符，本类数的是 UTF-8 字节，
 * 因为 BCrypt 会静默丢弃第 72 字节之后的内容；两条规则同时生效时以更严的一侧为准。
 */
@Component
public class PasswordPolicy {

    static final int MIN_BYTES = 10;
    static final int MAX_BYTES = 72;

    private static final Pattern LOWER_OR_UPPER = Pattern.compile("[A-Za-z]");
    private static final Pattern DIGIT = Pattern.compile("\\d");

    public List<FieldViolation> violations(String field, String password) {
        List<FieldViolation> violations = new ArrayList<>(3);
        if (password == null || password.isEmpty()) {
            violations.add(FieldViolation.of(field, "must not be empty"));
            return violations;
        }
        int byteLength = password.getBytes(StandardCharsets.UTF_8).length;
        if (byteLength < MIN_BYTES || byteLength > MAX_BYTES) {
            violations.add(FieldViolation.of(field,
                    "must be between " + MIN_BYTES + " and " + MAX_BYTES + " UTF-8 bytes"));
        }
        if (!LOWER_OR_UPPER.matcher(password).find()) {
            violations.add(FieldViolation.of(field, "must contain at least one letter"));
        }
        if (!DIGIT.matcher(password).find()) {
            violations.add(FieldViolation.of(field, "must contain at least one digit"));
        }
        return violations;
    }

    public boolean isAcceptable(String password) {
        return violations("password", password).isEmpty();
    }
}
