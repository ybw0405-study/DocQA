package com.docqa.user.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisRevocationStore implements RevocationStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(RedisRevocationStore.class);

    private static final String TOKEN_KEY_PREFIX = "auth:revoked:jti:";
    private static final String USER_KEY_PREFIX = "auth:revoked:user:";
    private static final String REVOKED_MARKER = "1";

    private final StringRedisTemplate redisTemplate;

    public RedisRevocationStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void revokeAccessToken(String tokenId, Duration remainingLifetime) {
        if (tokenId == null || tokenId.isBlank() || remainingLifetime == null || remainingLifetime.isNegative()
                || remainingLifetime.isZero()) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(TOKEN_KEY_PREFIX + tokenId, REVOKED_MARKER, remainingLifetime);
        } catch (DataAccessException ex) {
            LOGGER.warn("Access token revocation unavailable; database revocation still authoritative", ex);
        }
    }

    @Override
    public void revokeUser(UUID userId, Instant watermark, Duration ttl) {
        if (userId == null || watermark == null || ttl == null || ttl.isNegative() || ttl.isZero()) {
            return;
        }
        try {
            redisTemplate.opsForValue()
                    .set(USER_KEY_PREFIX + userId, Long.toString(watermark.toEpochMilli()), ttl);
        } catch (DataAccessException ex) {
            LOGGER.warn("User-wide access revocation unavailable; database revocation still authoritative", ex);
        }
    }

    @Override
    public boolean isAccessTokenRevoked(String tokenId) {
        if (tokenId == null || tokenId.isBlank()) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(TOKEN_KEY_PREFIX + tokenId));
        } catch (DataAccessException ex) {
            LOGGER.warn("Failing open on access token revocation lookup", ex);
            return false;
        }
    }

    @Override
    public Optional<Instant> issuedAtFloor(UUID userId) {
        if (userId == null) {
            return Optional.empty();
        }
        try {
            String value = redisTemplate.opsForValue().get(USER_KEY_PREFIX + userId);
            if (value == null || value.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(Instant.ofEpochMilli(Long.parseLong(value)));
        } catch (DataAccessException | NumberFormatException ex) {
            LOGGER.warn("Failing open on user revocation floor lookup", ex);
            return Optional.empty();
        }
    }
}
