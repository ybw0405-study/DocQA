package com.docqa.user.dto;

import com.docqa.user.domain.UserRole;
import com.docqa.user.domain.UserStatus;
import java.util.Set;
import java.util.UUID;

/**
 * 当前身份视图：userId 与 roles 来自已验签令牌，username 与 status 实时读自 app_user。
 */
public record CurrentUserData(
        UUID userId,
        String username,
        UserStatus status,
        Set<UserRole> roles,
        long accessTokenExpiresIn) {
}
