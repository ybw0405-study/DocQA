package com.docqa.user.domain;

import java.time.Instant;
import java.util.UUID;

/** refresh_token 表的一行；tokenHash 是 SHA-256 十六进制摘要，明文不落库。 */
public record RefreshTokenRow(
        UUID id,
        UUID userId,
        UUID familyId,
        String tokenHash,
        RefreshTokenStatus status,
        Instant expiresAt,
        Instant createdAt) {
}
