package com.docqa.user.service;

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

    public String issue(UUID userId, Collection<UserRole> roles) {
        if (roles == null || roles.isEmpty()) {
            throw new IllegalArgumentException("access token requires at least one role");
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
