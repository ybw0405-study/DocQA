package com.docqa.user.domain;

import java.time.Instant;
import java.util.UUID;

/** 新账号写入形状：口令必须已经过 BCrypt 摘要，状态固定为 ACTIVE。 */
public record NewUser(
        UUID id,
        String username,
        String passwordHash,
        UserStatus status,
        Instant createdAt) {
}
