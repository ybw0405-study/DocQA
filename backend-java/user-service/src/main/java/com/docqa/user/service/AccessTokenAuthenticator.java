package com.docqa.user.service;

import com.docqa.common.error.BusinessException;
import com.docqa.common.error.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 访问令牌的唯一入口：只信自己验签通过的令牌，userId 取 `sub`、角色取 `roles`，
 * 任何 Header 或 Body 传入的身份与角色都不参与判定；此外还要过撤销表与用户级水位。
 */
@Component
public class AccessTokenAuthenticator {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final RevocationStore revocationStore;

    public AccessTokenAuthenticator(JwtService jwtService, RevocationStore revocationStore) {
        this.jwtService = jwtService;
        this.revocationStore = revocationStore;
    }

    public JwtIdentity authenticate(String authorizationHeader) {
        return tryAuthenticate(authorizationHeader).orElseThrow(() -> new BusinessException(ErrorCode.USR_AUTH_001));
    }

    /** 登出时访问令牌可能已经过期，此时仍然要能完成登出，所以这条路径不把失败当错误。 */
    public Optional<JwtIdentity> tryAuthenticate(String authorizationHeader) {
        Optional<String> token = bearerToken(authorizationHeader);
        if (token.isEmpty()) {
            return Optional.empty();
        }
        JwtIdentity identity;
        try {
            identity = jwtService.verify(token.get());
        } catch (InvalidJwtException ex) {
            return Optional.empty();
        }
        if (revocationStore.isAccessTokenRevoked(identity.tokenId())) {
            return Optional.empty();
        }
        Instant floor = revocationStore.issuedAtFloor(identity.userId()).orElse(null);
        if (floor != null && identity.issuedAt().isBefore(floor)) {
            return Optional.empty();
        }
        return Optional.of(identity);
    }

    public void revoke(JwtIdentity identity, Instant now) {
        revocationStore.revokeAccessToken(identity.tokenId(), Duration.between(now, identity.expiresAt()));
    }

    /**
     * 把该用户的签发时刻下界推到 now，使其全部在途 access 失效（改密、停用）。
     * TTL 取 access TTL：任何仍在有效期的令牌剩余寿命都不会超过它。
     */
    public void revokeAllIssuedBefore(UUID userId, Instant watermark) {
        revocationStore.revokeUser(userId, watermark, jwtService.accessTtl());
    }

    private Optional<String> bearerToken(String authorizationHeader) {
        if (authorizationHeader == null) {
            return Optional.empty();
        }
        String value = authorizationHeader.trim();
        if (!value.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return Optional.empty();
        }
        String token = value.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty() || token.indexOf(' ') >= 0 || token.indexOf('\t') >= 0) {
            return Optional.empty();
        }
        return Optional.of(token);
    }
}
