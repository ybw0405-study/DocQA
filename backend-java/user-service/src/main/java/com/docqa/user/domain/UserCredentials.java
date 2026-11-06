package com.docqa.user.domain;

import java.util.UUID;

/** 登录校验专用的凭据视图；passwordHash 只允许在 service 内部与明文比对后立即丢弃。 */
public record UserCredentials(
        UUID id,
        String username,
        String passwordHash,
        UserStatus status) {
}
