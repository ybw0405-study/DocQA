package com.docqa.user.service;

import com.docqa.user.config.AuthProperties;
import com.docqa.user.support.Fingerprints;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * 注册接口的固定窗口限流。必须跨实例共享，所以走 Redis。
 *
 * <p>key 由「解析后客户端 IP 指纹 + 用户名指纹 + 窗口起点」三段组成：所有流量经网关转发时
 * remoteAddr 会塌缩成同一个网关地址，只按 IP 会让全站共享一份配额；第二维保证任何
 * 单一维度塌缩都不会把配额退化成全局。原始地址与用户名都不进 Redis，只写 SHA-256 前缀。
 *
 * <p>INCR 与 EXPIRE 放在同一段 Lua 里：非原子的「首次 INCR 成功、随后 EXPIRE 失败」会让
 * key 永远没有 TTL，注册配额被永久耗尽。
 */
@Component
public class RegistrationRateLimiter {

    private static final Logger LOGGER = LoggerFactory.getLogger(RegistrationRateLimiter.class);
    private static final String KEY_PREFIX = "rate:ip:";
    private static final int HASH_PREFIX_LENGTH = 16;

    private static final DefaultRedisScript<Long> INCREMENT_SCRIPT = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
              redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return count
            """, Long.class);

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

    public RateLimitDecision check(String remoteAddress, String username) {
        long windowMillis = properties.getRegistrationWindow().toMillis();
        long now = clock.millis();
        long windowStart = Math.floorDiv(now, windowMillis) * windowMillis;
        String key = KEY_PREFIX + hash(remoteAddress) + ":" + hash(username) + ":" + windowStart;
        try {
            Long count = redisTemplate.execute(
                    INCREMENT_SCRIPT, List.of(key), String.valueOf(windowMillis));
            if (count == null) {
                return RateLimitDecision.permit();
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

    private String hash(String value) {
        String normalized = value == null ? "" : value.trim();
        String digest = Fingerprints.sha256(normalized);
        return digest.substring(0, Math.min(HASH_PREFIX_LENGTH, digest.length()));
    }
}
