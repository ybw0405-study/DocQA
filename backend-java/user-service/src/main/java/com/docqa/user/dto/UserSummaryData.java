package com.docqa.user.dto;

import com.docqa.user.domain.UserRole;
import com.docqa.user.domain.UserStatus;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** 管理面用户视图；刻意不含 password_hash，管理员需要的是定位账号而不是接管账号。 */
public record UserSummaryData(
        UUID userId,
        String username,
        UserStatus status,
        Set<UserRole> roles,
        Instant createdAt,
        Instant lastLoginAt) {
}
