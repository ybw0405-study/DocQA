package com.docqa.agentgateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.docqa.agentgateway.config.JwtProperties;
import com.docqa.common.error.BusinessException;
import com.docqa.common.error.ErrorCode;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JwtAuthenticatorTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String ISSUER = "docqa-user-service";
    private static final String AUDIENCE = "docqa-api";
    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final SecretKey signingKey =
            Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
    private final Set<String> revokedTokenIds = new HashSet<>();

    private JwtProperties properties;
    private JwtAuthenticator authenticator;

    @BeforeEach
    void setUp() {
        properties = new JwtProperties();
        properties.setIssuer(ISSUER);
        properties.setAudience(AUDIENCE);
        properties.setSecret(SECRET);
        TokenRevocationStore revocationStore =
                (tokenId, userId, issuedAt) -> revokedTokenIds.contains(tokenId);
        authenticator = new JwtAuthenticator(properties, Clock.fixed(NOW, ZoneOffset.UTC), revocationStore);
    }

    @Test
    void authenticatesValidToken() {
        String token = token(UUID.randomUUID().toString(), USER_ID.toString(), ISSUER, AUDIENCE,
                NOW.plusSeconds(3600), signingKey);

        assertThat(authenticator.authenticate("Bearer " + token)).isEqualTo(USER_ID);
    }

    @Test
    void rejectsRevokedTokenId() {
        String jti = UUID.randomUUID().toString();
        revokedTokenIds.add(jti);
        String token = token(jti, USER_ID.toString(), ISSUER, AUDIENCE, NOW.plusSeconds(3600), signingKey);

        assertUnauthorized("Bearer " + token);
    }

    @Test
    void rejectsTokenIssuedBeforeUserWatermark() {
        TokenRevocationStore store = (tokenId, userId, issuedAt) -> issuedAt.isBefore(NOW.plusSeconds(1));
        JwtAuthenticator strict = new JwtAuthenticator(properties, Clock.fixed(NOW, ZoneOffset.UTC), store);
        String token = token(UUID.randomUUID().toString(), USER_ID.toString(), ISSUER, AUDIENCE,
                NOW.plusSeconds(3600), signingKey);

        assertThatThrownBy(() -> strict.authenticate("Bearer " + token))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.USR_AUTH_001);
    }

    @Test
    void acceptsTokenIssuedExactlyAtWatermark() {
        TokenRevocationStore store = (tokenId, userId, issuedAt) -> issuedAt.isBefore(NOW.minusSeconds(10));
        JwtAuthenticator lenient = new JwtAuthenticator(properties, Clock.fixed(NOW, ZoneOffset.UTC), store);
        String token = token(UUID.randomUUID().toString(), USER_ID.toString(), ISSUER, AUDIENCE,
                NOW.plusSeconds(3600), signingKey);

        assertThat(lenient.authenticate("Bearer " + token)).isEqualTo(USER_ID);
    }

    @Test
    void rejectsTokenWithoutJti() {
        String token = token(null, USER_ID.toString(), ISSUER, AUDIENCE, NOW.plusSeconds(3600), signingKey);

        assertUnauthorized("Bearer " + token);
    }

    @Test
    void rejectsExpiredToken() {
        String token = token(UUID.randomUUID().toString(), USER_ID.toString(), ISSUER, AUDIENCE,
                NOW.minusSeconds(1), signingKey);

        assertUnauthorized("Bearer " + token);
    }

    @Test
    void rejectsWrongIssuer() {
        String token = token(UUID.randomUUID().toString(), USER_ID.toString(), "other-issuer", AUDIENCE,
                NOW.plusSeconds(3600), signingKey);

        assertUnauthorized("Bearer " + token);
    }

    @Test
    void rejectsWrongAudience() {
        String token = token(UUID.randomUUID().toString(), USER_ID.toString(), ISSUER, "other-audience",
                NOW.plusSeconds(3600), signingKey);

        assertUnauthorized("Bearer " + token);
    }

    @Test
    void rejectsTokenSignedWithAnotherKey() {
        SecretKey otherKey = Keys.hmacShaKeyFor(
                "abcdef0123456789abcdef0123456789".getBytes(StandardCharsets.UTF_8));
        String token = token(UUID.randomUUID().toString(), USER_ID.toString(), ISSUER, AUDIENCE,
                NOW.plusSeconds(3600), otherKey);

        assertUnauthorized("Bearer " + token);
    }

    @Test
    void rejectsUnsignedToken() {
        String token = Jwts.builder()
                .subject(USER_ID.toString())
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .issuedAt(Date.from(NOW))
                .expiration(Date.from(NOW.plusSeconds(3600)))
                .compact();

        assertUnauthorized("Bearer " + token);
    }

    @Test
    void rejectsMissingHeader() {
        assertUnauthorized(null);
    }

    @Test
    void rejectsNonBearerScheme() {
        assertUnauthorized("Basic abc");
    }

    @Test
    void rejectsEmptyToken() {
        assertUnauthorized("Bearer   ");
    }

    @Test
    void rejectsSubjectThatIsNotUuid() {
        String token = token(UUID.randomUUID().toString(), "demo-user", ISSUER, AUDIENCE,
                NOW.plusSeconds(3600), signingKey);

        assertUnauthorized("Bearer " + token);
    }

    @Test
    void refusesShortSecretAtConstruction() {
        properties.setSecret("too-short");

        assertThatThrownBy(() -> new JwtAuthenticator(
                properties, Clock.fixed(NOW, ZoneOffset.UTC), (tokenId, userId, issuedAt) -> false))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void exposesAdminRoleFromClaims() {
        String token = tokenWithRoles(List.of("USER", "ADMIN"));

        AuthenticatedUser identity = authenticator.authenticateIdentity("Bearer " + token);

        assertThat(identity.userId()).isEqualTo(USER_ID);
        assertThat(identity.roles()).containsExactlyInAnyOrder("USER", "ADMIN");
        assertThat(identity.isAdmin()).isTrue();
        assertThat(authenticator.authenticate("Bearer " + token)).isEqualTo(USER_ID);
    }

    @Test
    void treatsTokenWithoutRolesAsNonAdmin() {
        String token = token(UUID.randomUUID().toString(), USER_ID.toString(), ISSUER, AUDIENCE,
                NOW.plusSeconds(3600), signingKey);

        AuthenticatedUser identity = authenticator.authenticateIdentity("Bearer " + token);

        assertThat(identity.roles()).isEmpty();
        assertThat(identity.isAdmin()).isFalse();
    }

    @Test
    void ignoresBlankAndNonStringEntriesInRolesClaim() {
        List<Object> roles = new ArrayList<>();
        roles.add("USER");
        roles.add(" ");
        roles.add(42);
        String token = tokenWithRoles(roles);

        AuthenticatedUser identity = authenticator.authenticateIdentity("Bearer " + token);

        assertThat(identity.roles()).containsExactly("USER");
        assertThat(identity.isAdmin()).isFalse();
    }

    private String tokenWithRoles(Collection<?> roles) {
        return Jwts.builder()
                .subject(USER_ID.toString())
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .issuedAt(Date.from(NOW.minusSeconds(10)))
                .expiration(Date.from(NOW.plusSeconds(3600)))
                .id(UUID.randomUUID().toString())
                .claim("roles", roles)
                .signWith(signingKey)
                .compact();
    }

    private String token(
            String jti,
            String subject,
            String issuer,
            String audience,
            Instant expiration,
            SecretKey key) {
        var builder = Jwts.builder()
                .subject(subject)
                .issuer(issuer)
                .audience().add(audience).and()
                .issuedAt(Date.from(NOW.minusSeconds(10)))
                .expiration(Date.from(expiration));
        if (jti != null) {
            builder.id(jti);
        }
        return builder.signWith(key).compact();
    }

    private void assertUnauthorized(String authorization) {
        assertThatThrownBy(() -> authenticator.authenticate(authorization))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.USR_AUTH_001);
    }
}
