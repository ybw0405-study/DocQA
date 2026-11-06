package com.docqa.reminder.service;

import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 与 user-service 写入方共用同一组 key：`auth:revoked:jti:{jti}` 与 `auth:revoked:user:{userId}`。
 * 读取失败一律按未撤销处理（fail-open）并告警：吊销的自然上限就是 access TTL，
 * 而 fail-closed 会把 Redis 抖动放大成全站 401。
 */
@Component
public class RedisTokenRevocationStore implements TokenRevocationStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(RedisTokenRevocationStore.class);
    private static final String TOKEN_KEY_PREFIX = "auth:revoked:jti:";
    private static final String USER_KEY_PREFIX = "auth:revoked:user:";

    private final StringRedisTemplate redisTemplate;

    public RedisTokenRevocationStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean isRevoked(String tokenId, UUID userId, Instant issuedAt) {
        if (tokenId == null || tokenId.isBlank() || userId == null || issuedAt == null) {
            return false;
        }
        try {
            if (Boolean.TRUE.equals(redisTemplate.hasKey(TOKEN_KEY_PREFIX + tokenId))) {
                return true;
            }
            String floor = redisTemplate.opsForValue().get(USER_KEY_PREFIX + userId);
            if (floor == null || floor.isBlank()) {
                return false;
            }
            return issuedAt.isBefore(Instant.ofEpochMilli(Long.parseLong(floor)));
        } catch (DataAccessException | NumberFormatException ex) {
            LOGGER.warn("Failing open on access token revocation lookup", ex);
            return false;
        }
    }
}
