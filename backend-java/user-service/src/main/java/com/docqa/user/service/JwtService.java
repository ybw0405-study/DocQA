package com.docqa.user.service;

import com.docqa.common.tenant.TenantContext;
import com.docqa.user.config.JwtProperties;
import com.docqa.user.domain.UserRole;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwsHeader;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.Set;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    private static final String ALGORITHM = "HS256";
    private static final String ROLES_CLAIM = "roles";
    private static final int MIN_SECRET_BYTES = 32;

    private final JwtProperties properties;
    private final Clock clock;
    private final SecretKey signingKey;

    public JwtService(JwtProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        byte[] secretBytes = properties.getSecret().getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("JWT secret must be at least 32 bytes");
        }
        this.signingKey = Keys.hmacShaKeyFor(secretBytes);
    }

    /**
     * 签发带租户声明的访问令牌。P1 起 tenantId 必须由调用方显式传入：登录 / 注册取
     * TenantResolver.resolve(...) 的解析结果，刷新取 refresh token 家族携带的 tenantId。
     * 刻意不提供省略 tenantId 的重载——那会静默把用户签进默认租户、破坏多租户隔离，
     * 而编译器无法拦截这类误用。验签侧强制校验 tenant_id claim，无需再改。
     */
    public String issue(UUID userId, UUID tenantId, Collection<UserRole> roles) {
        if (roles == null || roles.isEmpty()) {
            throw new IllegalArgumentException("access token requires at least one role");
        }
        if (tenantId == null) {
            throw new IllegalArgumentException("access token requires a tenantId");
        }
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(properties.getTtl());
        return Jwts.builder()
                .subject(userId.toString())
                .issuer(properties.getIssuer())
                .audience().add(properties.getAudience()).and()
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .id(UUID.randomUUID().toString())
                .claim(ROLES_CLAIM, Arrays.asList(UserRole.claimValues(roles)))
                .claim(TenantContext.CLAIM_NAME, tenantId.toString())
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    public JwtIdentity verify(String token) {
        if (token == null || token.isBlank()) {
            throw new InvalidJwtException("JWT is missing");
        }
        try {
            Jws<Claims> parsed = Jwts.parser()
                    .verifyWith(signingKey)
                    .requireIssuer(properties.getIssuer())
                    .requireAudience(properties.getAudience())
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token);
            JwsHeader header = parsed.getHeader();
            if (!ALGORITHM.equals(header.getAlgorithm())) {
                throw new InvalidJwtException("JWT algorithm is not allowed");
            }
            Claims claims = parsed.getPayload();
            UUID userId = parseSubject(claims.getSubject());
            if (claims.getIssuedAt() == null || claims.getExpiration() == null) {
                throw new InvalidJwtException("JWT time claims are missing");
            }
            return new JwtIdentity(
                    userId,
                    parseTenantId(claims),
                    parseRoles(claims),
                    claims.getId(),
                    claims.getIssuedAt().toInstant(),
                    claims.getExpiration().toInstant());
        } catch (JwtException | IllegalArgumentException | IllegalStateException ex) {
            throw new InvalidJwtException("JWT is invalid", ex);
        }
    }

    public long expiresInSeconds() {
        return properties.getTtl().toSeconds();
    }

    public Duration accessTtl() {
        return properties.getTtl();
    }

    private UUID parseTenantId(Claims claims) {
        Object raw = claims.get(TenantContext.CLAIM_NAME);
        if (!(raw instanceof String value) || value.isBlank()) {
            throw new InvalidJwtException("JWT tenant_id claim is missing");
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            throw new InvalidJwtException("JWT tenant_id claim is not a UUID", ex);
        }
    }

    private Set<UserRole> parseRoles(Claims claims) {
        Object raw = claims.get(ROLES_CLAIM);
        if (!(raw instanceof Collection<?> values)) {
            throw new InvalidJwtException("JWT roles claim is missing");
        }
        try {
            return UserRole.fromClaim(values);
        } catch (IllegalStateException ex) {
            throw new InvalidJwtException("JWT roles claim is invalid", ex);
        }
    }

    private UUID parseSubject(String subject) {
        if (subject == null || subject.isBlank()) {
            throw new InvalidJwtException("JWT subject is missing");
        }
        try {
            return UUID.fromString(subject);
        } catch (IllegalArgumentException ex) {
            throw new InvalidJwtException("JWT subject is not a UUID", ex);
        }
    }
}
