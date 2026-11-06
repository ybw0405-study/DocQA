package com.docqa.agentgateway.service;

import com.docqa.common.api.FieldViolation;
import com.docqa.common.error.BusinessException;
import com.docqa.common.error.ErrorCode;
import java.util.List;
import java.util.regex.Pattern;

public final class IdempotencyKeys {

    private static final Pattern PATTERN = Pattern.compile("^[A-Za-z0-9._:-]{8,64}$");

    private IdempotencyKeys() {
    }

    public static String validate(String idempotencyKey) {
        String value = idempotencyKey == null ? "" : idempotencyKey.trim();
        if (!PATTERN.matcher(value).matches()) {
            throw new BusinessException(
                    ErrorCode.AGT_VALIDATION_001,
                    null,
                    List.of(FieldViolation.of(
                            "Idempotency-Key",
                            "must be 8 to 64 characters using letters, digits, dot, underscore, colon or hyphen")));
        }
        return value;
    }
}
