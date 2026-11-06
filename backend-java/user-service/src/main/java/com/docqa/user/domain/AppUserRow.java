package com.docqa.user.domain;

import java.time.Instant;
import java.util.UUID;

/** 用户控制面视图，故意不含 passwordHash；管理面和 /auth/me 只能拿到这个形状。 */
public record AppUserRow(
        UUID id,
        String username,
        UserStatus status,
        Instant lastLoginAt,
        Instant createdAt) {
}
