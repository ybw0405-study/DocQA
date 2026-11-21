package com.docqa.user.service;

import com.docqa.user.config.AuthProperties;
import com.docqa.user.support.Fingerprints;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * 登录失败的固定窗口限流。必须跨实例共享，所以计数走 Redis（P2：原进程内存 Map 在多副本/
 * 重启后失效，N 实例 = N 倍爆破预算）。
 *
 * <p>双键：用户维度 + 来源 IP 维度，任一维度达到阈值即拒绝；任一维度塌缩（网关转发使
 * remoteAddr 恒为网关 IP）都不会退化为全局共享配额。原始用户名与地址都不进 Redis，
 * 只写 SHA-256 前缀。INCR 与首次 PEXPIRE 放在同一段 Lua 里，避免 key 无 TTL 永久耗尽。
 *
 * <p>三个公开方法保持原签名不变（AuthService 调用点无需改动）：{@code check} 只读不计数，
 * {@code recordFailure} 在登录失败后 +1，{@code reset} 在成功后清零。Redis 抖动时
 * fail-open（放行并告警），与注册限流同口径——限流不可用不应让全站无法登录。
 */
@Component
public class LoginRateLimiter {

    private static final Logger LOGGER = LoggerFactory.getLogger(LoginRateLimiter.class);
    private static final String KEY_PREFIX = "rate:login:";
    private static final int HASH_PREFIX_LENGTH = 16;

    private static final DefaultRedisScript<Long> RECORD_SCRIPT = new DefaultRedisScript<>("""
            local u = redis.call('INCR', KEYS[1])
            if u == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
            local i = redis.call('INCR', KEYS[2])
            if i == 1 then redis.call('PEXPIRE', KEYS[2], ARGV[1]) end
            return u
            """, Long.class);
    private static final DefaultRedisScript<Long> CHECK_SCRIPT = new DefaultRedisScript<>("""
            local u = tonumber(redis.call('GET', KEYS[1]) or '0')
            local i = tonumber(redis.call('GET', KEYS[2]) or '0')
            if u > i then return u end
            return i
            """, Long.class);
    private static final DefaultRedisScript<Long> RESET_SCRIPT = new DefaultRedisScript<>("""
            redis.call('DEL', KEYS[1], KEYS[2])
            return 1
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final int maxFailures;
    private final long windowMillis;
    private final Clock clock;

    public LoginRateLimiter(StringRedisTemplate redisTemplate, AuthProperties properties, Clock clock) {
        if (properties.getMaxLoginFailures() < 1) {
            throw new IllegalStateException("maxLoginFailures must be positive");
        }
        if (properties.getLoginFailureWindow().isNegative()
                || properties.getLoginFailureWindow().isZero()) {
            throw new IllegalStateException("loginFailureWindow must be positive");
        }
        this.redisTemplate = redisTemplate;
        this.maxFailures = properties.getMaxLoginFailures();
        this.windowMillis = properties.getLoginFailureWindow().toMillis();
        this.clock = clock;
    }

    public RateLimitDecision check(String username, String remoteAddress) {
        long now = clock.millis();
        long windowStart = Math.floorDiv(now, windowMillis) * windowMillis;
        List<String> keys = keys(username, remoteAddress, windowStart);
        try {
            Long failures = redisTemplate.execute(CHECK_SCRIPT, keys);
            if (failures == null || failures < maxFailures) {
                return RateLimitDecision.permit();
            }
            long remainingMillis = windowStart + windowMillis - now;
            return RateLimitDecision.reject(
                    Math.max(1, TimeUnit.MILLISECONDS.toSeconds(remainingMillis + 999)));
        } catch (DataAccessException ex) {
            LOGGER.warn("Failing open on login rate limit check", ex);
            return RateLimitDecision.permit();
        }
    }

    public void recordFailure(String username, String remoteAddress) {
        long now = clock.millis();
        long windowStart = Math.floorDiv(now, windowMillis) * windowMillis;
        List<String> keys = keys(username, remoteAddress, windowStart);
        try {
            redisTemplate.execute(RECORD_SCRIPT, keys, String.valueOf(windowMillis));
        } catch (DataAccessException ex) {
            LOGGER.warn("Failing open on login rate limit record", ex);
        }
    }

    public void reset(String username, String remoteAddress) {
        long now = clock.millis();
        long windowStart = Math.floorDiv(now, windowMillis) * windowMillis;
        List<String> keys = keys(username, remoteAddress, windowStart);
        try {
            redisTemplate.execute(RESET_SCRIPT, keys);
        } catch (DataAccessException ex) {
            LOGGER.warn("Failing open on login rate limit reset", ex);
        }
    }

    private List<String> keys(String username, String remoteAddress, long windowStart) {
        return List.of(
                KEY_PREFIX + "u:" + hash(username) + ":" + windowStart,
                KEY_PREFIX + "i:" + hash(remoteAddress) + ":" + windowStart);
    }

    private String hash(String value) {
        String normalized = value == null
                ? ""
                : value.trim().toLowerCase(Locale.ROOT);
        String digest = Fingerprints.sha256(normalized);
        return digest.substring(0, Math.min(HASH_PREFIX_LENGTH, digest.length()));
    }
}
