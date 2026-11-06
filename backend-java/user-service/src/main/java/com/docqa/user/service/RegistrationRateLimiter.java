package com.docqa.user.service;

import com.docqa.user.config.AuthProperties;
import com.docqa.user.support.Fingerprints;
import java.time.Clock;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 注册接口的来源 IP 固定窗口限流。必须跨实例共享，所以走 Redis 而不是登录限流那样的进程内计数；
 * key 里只写 IP 的 SHA-256 前缀，原始地址不进 Redis。
 */
@Component
public class RegistrationRateLimiter {

    private static final Logger LOGGER = LoggerFactory.getLogger(RegistrationRateLimiter.class);
    private static final String KEY_PREFIX = "rate:ip:";
    private static final int HASH_PREFIX_LENGTH = 16;

    private final StringRedisTemplate redisTemplate;
    private final AuthProperties properties;
    private final Clock clock;

    public RegistrationRateLimiter(StringRedisTemplate redisTemplate, AuthProperties properties, Clock clock) {
        if (properties.getMaxRegistrationsPerWindow() < 1) {
            throw new IllegalStateException("maxRegistrationsPerWindow must be positive");
        }
        if (properties.getRegistrationWindow().isNegative() || properties.getRegistrationWindow().isZero()) {
            throw new IllegalStateException("registrationWindow must be positive");
        }
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.clock = clock;
    }

    public RateLimitDecision check(String remoteAddress) {
        long windowMillis = properties.getRegistrationWindow().toMillis();
        long now = clock.millis();
        long windowStart = Math.floorDiv(now, windowMillis) * windowMillis;
        String key = KEY_PREFIX + hash(remoteAddress) + ":" + windowStart;
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count == null) {
                return RateLimitDecision.permit();
            }
            if (count == 1L) {
                redisTemplate.expire(key, windowMillis, TimeUnit.MILLISECONDS);
            }
            if (count <= properties.getMaxRegistrationsPerWindow()) {
                return RateLimitDecision.permit();
            }
            long remainingMillis = windowStart + windowMillis - now;
            return RateLimitDecision.reject(Math.max(1, TimeUnit.MILLISECONDS.toSeconds(remainingMillis + 999)));
        } catch (DataAccessException ex) {
            LOGGER.warn("Failing open on registration rate limit", ex);
            return RateLimitDecision.permit();
        }
    }

    private String hash(String remoteAddress) {
        String value = remoteAddress == null ? "" : remoteAddress.trim();
        String digest = Fingerprints.sha256(value);
        return digest.substring(0, Math.min(HASH_PREFIX_LENGTH, digest.length()));
    }
}
