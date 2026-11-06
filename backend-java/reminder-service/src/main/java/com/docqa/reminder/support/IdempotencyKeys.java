package com.docqa.reminder.support;

import com.docqa.common.api.FieldViolation;
import com.docqa.common.error.BusinessException;
import com.docqa.common.error.ErrorCode;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 调用方幂等键的准入规则。契约把它定为全局规则（写接口必须携带，长度 8-64，
 * 且只允许 {@code [A-Za-z0-9._:-]}），创建提醒与课表导入共用同一判据，因此集中在这里，避免两处各自演进。
 */
public final class IdempotencyKeys {

    private static final int MIN_LENGTH = 8;
    private static final int MAX_LENGTH = 64;

    /**
     * 键要拼进 Redis 的幂等记录键，放过空白与控制字符会让不同请求落进同一命名空间，
     * 因此契约的 pattern 与长度同判据。
     */
    private static final Pattern ALLOWED_CHARACTERS = Pattern.compile("^[A-Za-z0-9._:-]+$");

    private static final String RULE = "must be 8-64 characters from [A-Za-z0-9._:-]";

    private IdempotencyKeys() {
    }

    public static String requireValid(String key) {
        if (key == null
                || key.isBlank()
                || key.length() < MIN_LENGTH
                || key.length() > MAX_LENGTH
                || !ALLOWED_CHARACTERS.matcher(key).matches()) {
            throw new BusinessException(
                    ErrorCode.COM_VALIDATION_001,
                    null,
                    List.of(FieldViolation.of("Idempotency-Key", RULE)));
        }
        return key;
    }
}
