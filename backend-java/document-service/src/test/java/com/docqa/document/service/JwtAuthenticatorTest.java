package com.docqa.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.docqa.common.error.BusinessException;
import com.docqa.common.error.ErrorCode;
import com.docqa.document.config.JwtProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JwtAuthenticatorTest {

    private static final String SECRET = "test-jwt-secret-with-at-least-32-bytes-1234567890";
    private static final String ISSUER = "docqa-user-service";
    private static final String AUDIENCE = "docqa-api";
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
    private final Set<String> revokedTokenIds = new HashSet<>();
    private JwtProperties properties;
    private JwtAuthenticator authenticator;

    @BeforeEach
    void setUp() {
        properties = new JwtProperties();
        properties.setIssuer(ISSUER);
        properties.setAudience(AUDIENCE);
        properties.setSecret(SECRET);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        TokenRevocationStore revocationStore =
                (tokenId, userId, issuedAt) -> revokedTokenIds.contains(tokenId);
        authenticator = new JwtAuthenticator(properties, clock, revocationStore);
    }

    @Test
    void acceptsValidToken() {
        String token = token(UUID.randomUUID().toString(), USER_ID.toString(), ISSUER, AUDIENCE, NOW.plusSeconds(60));

        assertThat(authenticator.authenticate("Bearer " + token)).isEqualTo(USER_ID);
    }

    @Test
    void rejectsRevokedTokenId() {
        String jti = UUID.randomUUID().toString();
        revokedTokenIds.add(jti);
        String token = token(jti, USER_ID.toString(), ISSUER, AUDIENCE, NOW.plusSeconds(60));

        assertUnauthorized("Bearer " + token);
    }

    @Test
    void rejectsTokenIssuedBeforeUserWatermark() {
        TokenRevocationStore store = (tokenId, userId, issuedAt) -> issuedAt.isBefore(NOW.minusSeconds(1));
        JwtAuthenticator strict = new JwtAuthenticator(properties, Clock.fixed(NOW, ZoneOffset.UTC), store);
        String token = token(UUID.randomUUID().toString(), USER_ID.toString(), ISSUER, AUDIENCE, NOW.plusSeconds(60));

        assertUnauthorized(strict, "Bearer " + token);
    }

    @Test
    void rejectsTokenWithoutJti() {
        String token = token(null, USER_ID.toString(), ISSUER, AUDIENCE, NOW.plusSeconds(60));

        assertUnauthorized("Bearer " + token);
    }

    @Test
    void rejectsMissingOrMalformedAuthorizationHeader() {
        assertUnauthorized(null);
        assertUnauthorized("");
        assertUnauthorized("Basic abc");
        assertUnauthorized("Bearer");
        assertUnauthorized("Bearer two tokens");
    }

    @Test
    void rejectsWrongIssuer() {
        String token = token(UUID.randomUUID().toString(), USER_ID.toString(), "other-issuer", AUDIENCE,
                NOW.plusSeconds(60));

        assertUnauthorized("Bearer " + token);
    }

    @Test
    void rejectsWrongAudience() {
        String token = token(UUID.randomUUID().toString(), USER_ID.toString(), ISSUER, "other-audience",
                NOW.plusSeconds(60));

        assertUnauthorized("Bearer " + token);
    }

    @Test
    void rejectsExpiredToken() {
        String token = token(UUID.randomUUID().toString(), USER_ID.toString(), ISSUER, AUDIENCE, NOW.minusSeconds(60));

        assertUnauthorized("Bearer " + token);
    }

    @Test
    void rejectsNonUuidSubject() {
        String token = token(UUID.randomUUID().toString(), "not-a-uuid", ISSUER, AUDIENCE, NOW.plusSeconds(60));

        assertUnauthorized("Bearer " + token);
    }

    private void assertUnauthorized(String header) {
        assertUnauthorized(authenticator, header);
    }

    private void assertUnauthorized(JwtAuthenticator target, String header) {
        assertThatThrownBy(() -> target.authenticate(header))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.USR_AUTH_001);
    }

    private String token(String jti, String subject, String issuer, String audience, Instant expiration) {
        var builder = Jwts.builder()
                .subject(subject)
                .issuer(issuer)
                .audience().add(audience).and()
                .issuedAt(Date.from(NOW.minusSeconds(5)))
                .expiration(Date.from(expiration));
        if (jti != null) {
            builder.id(jti);
        }
        return builder.signWith(key, Jwts.SIG.HS256).compact();
    }
}
