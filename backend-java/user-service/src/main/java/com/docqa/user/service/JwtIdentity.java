package com.docqa.user.service;

import com.docqa.user.domain.UserRole;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record JwtIdentity(
        UUID userId,
        UUID tenantId,
        Set<UserRole> roles,
        String tokenId,
        Instant issuedAt,
        Instant expiresAt) {

    public JwtIdentity {
        roles = Set.copyOf(roles);
    }

    public boolean hasRole(UserRole role) {
        return roles.contains(role);
    }
}
