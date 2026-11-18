package com.docqa.document.service;

import com.docqa.common.error.BusinessException;
import com.docqa.common.error.ErrorCode;
import com.docqa.common.tenant.TenantContext;
import com.docqa.document.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwsHeader;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

@Service
public class JwtAuthenticator {

    private static final String ALGORITHM = "HS256";
    private static final int MIN_SECRET_BYTES = 32;
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtProperties properties;
    private final Clock clock;
    private final SecretKey signingKey;
    private final TokenRevocationStore revocationStore;

    public JwtAuthenticator(JwtProperties properties, Clock clock, TokenRevocationStore revocationStore) {
        this.properties = properties;
        this.clock = clock;
        this.revocationStore = revocationStore;
        byte[] secretBytes = properties.getSecret().getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("JWT secret must be at least 32 bytes");
        }
        this.signingKey = Keys.hmacShaKeyFor(secretBytes);
    }

    public UUID authenticate(String authorizationHeader) {
        return authenticateIdentity(authorizationHeader).userId();
    }

    public AuthenticatedUser authenticateIdentity(String authorizationHeader) {
        String token = bearerToken(authorizationHeader);
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
                throw new BusinessException(ErrorCode.USR_AUTH_001);
            }
            Claims claims = parsed.getPayload();
            UUID userId = parseSubject(claims.getSubject());
            String tokenId = claims.getId();
            Date issuedAt = claims.getIssuedAt();
            if (tokenId == null || tokenId.isBlank() || issuedAt == null) {
                throw new BusinessException(ErrorCode.USR_AUTH_001);
            }
            if (revocationStore.isRevoked(tokenId, userId, issuedAt.toInstant())) {
                throw new BusinessException(ErrorCode.USR_AUTH_001);
            }
            return new AuthenticatedUser(userId, parseTenantId(claims));
        } catch (JwtException | IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.USR_AUTH_001);
        }
    }

    private UUID parseTenantId(Claims claims) {
        Object raw = claims.get(TenantContext.CLAIM_NAME);
        if (!(raw instanceof String value) || value.isBlank()) {
            throw new BusinessException(ErrorCode.USR_AUTH_001);
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.USR_AUTH_001);
        }
    }

    private String bearerToken(String authorizationHeader) {
        if (authorizationHeader == null) {
            throw new BusinessException(ErrorCode.USR_AUTH_001);
        }
        String value = authorizationHeader.trim();
        if (!value.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            throw new BusinessException(ErrorCode.USR_AUTH_001);
        }
        String token = value.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty() || token.indexOf(' ') >= 0 || token.indexOf('\t') >= 0) {
            throw new BusinessException(ErrorCode.USR_AUTH_001);
        }
        return token;
    }

    private UUID parseSubject(String subject) {
        if (subject == null || subject.isBlank()) {
            throw new BusinessException(ErrorCode.USR_AUTH_001);
        }
        try {
            return UUID.fromString(subject);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.USR_AUTH_001);
        }
    }
}
