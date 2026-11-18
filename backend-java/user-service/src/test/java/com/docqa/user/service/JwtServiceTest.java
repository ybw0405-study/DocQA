package com.docqa.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.docqa.common.tenant.TenantContext;
import com.docqa.user.config.JwtProperties;
import com.docqa.user.domain.UserRole;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SecureDigestAlgorithm;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    private static final String ISSUER = "test-issuer";
    private static final String AUDIENCE = "test-audience";
    private static final String SECRET = "test-jwt-secret-with-at-least-32-bytes-1234567890";
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private MutableClock clock;
    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(NOW);
        jwtService = service(ISSUER, AUDIENCE, SECRET, clock);
    }

    @Test
    void issuesAndVerifiesTokenWithRoles() {
        UUID userId = UUID.randomUUID();

        String token = jwtService.issue(userId, Set.of(UserRole.USER, UserRole.ADMIN));
        JwtIdentity identity = jwtService.verify(token);

        assertThat(token.split("\\.")).hasSize(3);
        assertThat(identity.userId()).isEqualTo(userId);
        assertThat(identity.tenantId()).isEqualTo(TenantContext.DEFAULT_TENANT_ID);
        assertThat(identity.roles()).containsExactlyInAnyOrder(UserRole.USER, UserRole.ADMIN);
        assertThat(identity.issuedAt()).isEqualTo(NOW);
        assertThat(identity.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(2)));
        assertThat(identity.tokenId()).isNotBlank();
        assertThat(jwtService.expiresInSeconds()).isEqualTo(7200);
        assertThat(jwtService.accessTtl()).isEqualTo(Duration.ofHours(2));
    }

    @Test
    void rejectsExpiredToken() {
        String token = jwtService.issue(UUID.randomUUID(), Set.of(UserRole.USER));
        clock.advance(Duration.ofHours(2).plusSeconds(1));

        assertThatThrownBy(() -> jwtService.verify(token))
                .isInstanceOf(InvalidJwtException.class);
    }

    @Test
    void rejectsWrongSignature() {
        JwtService other = service(ISSUER, AUDIENCE, "other-jwt-secret-with-at-least-32-bytes-123456", clock);
        String token = other.issue(UUID.randomUUID(), Set.of(UserRole.USER));

        assertThatThrownBy(() -> jwtService.verify(token))
                .isInstanceOf(InvalidJwtException.class);
    }

    @Test
    void rejectsWrongIssuer() {
        JwtService other = service("other-issuer", AUDIENCE, SECRET, clock);
        String token = other.issue(UUID.randomUUID(), Set.of(UserRole.USER));

        assertThatThrownBy(() -> jwtService.verify(token))
                .isInstanceOf(InvalidJwtException.class);
    }

    @Test
    void rejectsWrongAudience() {
        JwtService other = service(ISSUER, "other-audience", SECRET, clock);
        String token = other.issue(UUID.randomUUID(), Set.of(UserRole.USER));

        assertThatThrownBy(() -> jwtService.verify(token))
                .isInstanceOf(InvalidJwtException.class);
    }

    @Test
    void rejectsUnsignedToken() {
        String token = "{\"alg\":\"none\",\"typ\":\"JWT\"}";
        String header = base64Url(token);
        String payload = base64Url(claimsJson(UUID.randomUUID(), NOW, NOW.plusSeconds(60)));
        String unsigned = header + "." + payload + ".";

        assertThatThrownBy(() -> jwtService.verify(unsigned))
                .isInstanceOf(InvalidJwtException.class);
    }

    @Test
    void rejectsDifferentHmacAlgorithm() {
        String token = signedToken(UUID.randomUUID().toString(), ISSUER, List.of("USER"), Jwts.SIG.HS384);

        assertThatThrownBy(() -> jwtService.verify(token))
                .isInstanceOf(InvalidJwtException.class);
    }

    @Test
    void rejectsMissingSubject() {
        String token = signedToken(null, ISSUER, List.of("USER"), Jwts.SIG.HS256);

        assertThatThrownBy(() -> jwtService.verify(token))
                .isInstanceOf(InvalidJwtException.class);
    }

    @Test
    void rejectsNonUuidSubject() {
        String token = signedToken("not-a-uuid", ISSUER, List.of("USER"), Jwts.SIG.HS256);

        assertThatThrownBy(() -> jwtService.verify(token))
                .isInstanceOf(InvalidJwtException.class);
    }

    @Test
    void rejectsMissingRolesClaim() {
        String token = signedToken(UUID.randomUUID().toString(), ISSUER, null, Jwts.SIG.HS256);

        assertThatThrownBy(() -> jwtService.verify(token))
                .isInstanceOf(InvalidJwtException.class);
    }

    @Test
    void rejectsEmptyRolesClaim() {
        String token = signedToken(UUID.randomUUID().toString(), ISSUER, List.of(), Jwts.SIG.HS256);

        assertThatThrownBy(() -> jwtService.verify(token))
                .isInstanceOf(InvalidJwtException.class);
    }

    @Test
    void rejectsUnknownRoleInClaim() {
        String token = signedToken(UUID.randomUUID().toString(), ISSUER, List.of("SUPERUSER"), Jwts.SIG.HS256);

        assertThatThrownBy(() -> jwtService.verify(token))
                .isInstanceOf(InvalidJwtException.class);
    }

    @Test
    void rejectsRolesClaimThatIsNotAList() {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        String token = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .issuedAt(Date.from(NOW))
                .expiration(Date.from(NOW.plusSeconds(60)))
                .claim("roles", "USER")
                .signWith(key, Jwts.SIG.HS256)
                .compact();

        assertThatThrownBy(() -> jwtService.verify(token))
                .isInstanceOf(InvalidJwtException.class);
    }

    @Test
    void issuesTokenWithExplicitTenantId() {
        UUID tenantId = UUID.randomUUID();
        String token = jwtService.issue(UUID.randomUUID(), tenantId, Set.of(UserRole.USER));

        assertThat(jwtService.verify(token).tenantId()).isEqualTo(tenantId);
    }

    @Test
    void rejectsMissingTenantClaim() {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        String token = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .issuedAt(Date.from(NOW))
                .expiration(Date.from(NOW.plusSeconds(60)))
                .claim("roles", List.of("USER"))
                .signWith(key, Jwts.SIG.HS256)
                .compact();

        assertThatThrownBy(() -> jwtService.verify(token))
                .isInstanceOf(InvalidJwtException.class);
    }

    @Test
    void rejectsNonUuidTenantClaim() {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        String token = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .issuedAt(Date.from(NOW))
                .expiration(Date.from(NOW.plusSeconds(60)))
                .claim("roles", List.of("USER"))
                .claim(TenantContext.CLAIM_NAME, "not-a-uuid")
                .signWith(key, Jwts.SIG.HS256)
                .compact();

        assertThatThrownBy(() -> jwtService.verify(token))
                .isInstanceOf(InvalidJwtException.class);
    }

    @Test
    void refusesToIssueTokenWithoutRoles() {
        assertThatThrownBy(() -> jwtService.issue(UUID.randomUUID(), Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> jwtService.issue(UUID.randomUUID(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMissingToken() {
        assertThatThrownBy(() -> jwtService.verify(" "))
                .isInstanceOf(InvalidJwtException.class);
    }

    @Test
    void rejectsShortSecret() {
        assertThatThrownBy(() -> service(ISSUER, AUDIENCE, "short-secret", clock))
                .isInstanceOf(IllegalStateException.class);
    }

    private JwtService service(String issuer, String audience, String secret, MutableClock mutableClock) {
        JwtProperties properties = new JwtProperties();
        properties.setIssuer(issuer);
        properties.setAudience(audience);
        properties.setTtl(Duration.ofHours(2));
        properties.setSecret(secret);
        return new JwtService(properties, mutableClock);
    }

    private String signedToken(
            String subject,
            String issuer,
            List<String> roles,
            SecureDigestAlgorithm<? super SecretKey, ?> algorithm) {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject(subject)
                .issuer(issuer)
                .audience().add(AUDIENCE).and()
                .issuedAt(Date.from(NOW))
                .expiration(Date.from(NOW.plusSeconds(60)))
                .claim("roles", roles)
                .claim(TenantContext.CLAIM_NAME, TenantContext.DEFAULT_TENANT_ID.toString())
                .signWith(key, algorithm)
                .compact();
    }

    private String claimsJson(UUID userId, Instant issuedAt, Instant expiresAt) {
        return "{\"sub\":\"" + userId + "\",\"iss\":\"" + ISSUER + "\",\"aud\":\"" + AUDIENCE
                + "\",\"iat\":" + issuedAt.getEpochSecond() + ",\"exp\":" + expiresAt.getEpochSecond()
                + ",\"roles\":[\"USER\"],\"tenant_id\":\"" + TenantContext.DEFAULT_TENANT_ID + "\"}";
    }

    private String base64Url(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
