package com.docqa.user.service;

import com.docqa.common.error.BusinessException;
import com.docqa.common.error.ErrorCode;
import com.docqa.user.config.AuthProperties;
import com.docqa.user.domain.RefreshTokenRow;
import com.docqa.user.domain.RefreshTokenStatus;
import com.docqa.common.tenant.TenantContext;
import com.docqa.user.repository.RefreshTokenRepository;
import com.docqa.user.support.Fingerprints;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 刷新令牌的一次使用即轮换。库里只存 SHA-256 摘要，明文只在 Set-Cookie 里出现一次。
 */
@Service
public class RefreshTokenService {

    private static final Logger LOGGER = LoggerFactory.getLogger(RefreshTokenService.class);
    private static final int RANDOM_BYTES = 32;

    private final RefreshTokenRepository repository;
    private final AuthProperties properties;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    public RefreshTokenService(RefreshTokenRepository repository, AuthProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    /** 登录/注册成功后建立一个新的轮换家族。 */
    @Transactional
    public IssuedRefreshToken startFamily(UUID userId) {
        UUID familyId = UUID.randomUUID();
        return insert(userId, familyId);
    }

    /**
     * 续期：占用旧行 -> 插入同家族新行。占用失败或命中终态一律按复用处理并吊销整家族，
     * 服务端无法区分「并发双击」与「被盗复用」，宁可让两端都重新登录。
     */
    @Transactional
    public IssuedRefreshToken rotate(String rawToken) {
        Instant now = clock.instant();
        RefreshTokenRow row = repository.findByHash(tenantId(), Fingerprints.sha256(rawToken))
                .orElseThrow(RefreshTokenService::rejected);
        if (row.status() != RefreshTokenStatus.ACTIVE) {
            revokeFamilyAndReject(row);
        }
        if (!row.expiresAt().isAfter(now)) {
            throw rejected();
        }
        if (repository.consumeActive(tenantId(), row.id(), now) != 1) {
            revokeFamilyAndReject(row);
        }
        return insert(row.userId(), row.familyId());
    }

    public void revokeFamily(UUID familyId) {
        int revoked = repository.revokeFamily(tenantId(), familyId, clock.instant());
        LOGGER.info("Revoked refresh token family: familyId={} count={}", familyId, revoked);
    }

    public void revokeAllForUser(UUID userId) {
        int revoked = repository.revokeAllActiveForUser(tenantId(), userId, clock.instant());
        LOGGER.info("Revoked all refresh tokens for user: count={}", revoked);
    }

    public Optional<UUID> familyOf(String rawToken) {
        return repository.findByHash(tenantId(), Fingerprints.sha256(rawToken)).map(RefreshTokenRow::familyId);
    }

    public long cookieMaxAgeSeconds() {
        return properties.getRefreshTtl().toSeconds();
    }

    private void revokeFamilyAndReject(RefreshTokenRow row) {
        revokeFamily(row.familyId());
        throw rejected();
    }

    private IssuedRefreshToken insert(UUID userId, UUID familyId) {
        Instant now = clock.instant();
        String raw = nextRawToken();
        RefreshTokenRow row = new RefreshTokenRow(
                UUID.randomUUID(),
                userId,
                familyId,
                Fingerprints.sha256(raw),
                RefreshTokenStatus.ACTIVE,
                now.plus(properties.getRefreshTtl()),
                now);
        repository.insert(tenantId(), row);
        return new IssuedRefreshToken(raw, row.userId(), familyId, row.expiresAt());
    }

    private String nextRawToken() {
        byte[] bytes = new byte[RANDOM_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static BusinessException rejected() {
        return new BusinessException(ErrorCode.USR_REFRESH_001);
    }

    public record IssuedRefreshToken(String value, UUID userId, UUID familyId, Instant expiresAt) {
    }

    /** P0：刷新令牌路径尚无租户上下文，统一归属默认租户；P1 由登录域名解析后替换。 */
    private UUID tenantId() {
        return TenantContext.DEFAULT_TENANT_ID;
    }
}
