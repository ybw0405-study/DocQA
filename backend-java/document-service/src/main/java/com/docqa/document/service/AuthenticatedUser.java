package com.docqa.document.service;

import java.util.UUID;

/** 已验签令牌的身份视图；userId 与 tenantId 均来自令牌声明，不接受外部传参。 */
public record AuthenticatedUser(UUID userId, UUID tenantId) {
}
