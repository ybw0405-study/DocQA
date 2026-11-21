package com.docqa.agentgateway.service;

import com.docqa.agentgateway.config.AgentProperties;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class RateLimiter {

    private static final Logger LOGGER = LoggerFactory.getLogger(RateLimiter.class);

    private static final DefaultRedisScript<Long> INCREMENT_SCRIPT = new DefaultRedisScript<>("""
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
              redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return current
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final AgentProperties properties;
    private final Clock clock;

    public RateLimiter(StringRedisTemplate redisTemplate, AgentProperties properties, Clock clock) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.clock = clock;
    }

    public void check(UUID userId) {
        Duration window = properties.getRateLimitWindow();
        long windowSeconds = Math.max(1, window.toSeconds());
        long now = clock.instant().getEpochSecond();
        long windowStart = now - (now % windowSeconds);
        String key = "rate:user:" + userId + ":" + windowStart;
        Long count;
        try {
            count = redisTemplate.execute(
                    INCREMENT_SCRIPT,
                    List.of(key),
                    String.valueOf(windowSeconds));
        } catch (DataAccessException ex) {
            // 突发限流是业务闸：Redis 故障时按契约上抛 500（由全局异常处理器映射），
            // 不在这里放行。与登录失败计数器的 fail-open 是不同取舍——后者有 TTL 上限
            // 兜底且放行只影响爆破防护，这里放行会让突发流量直接打穿到模型计费。
            LOGGER.error("Fail-closed on rate limit check: Redis unavailable userId={}", userId, ex);
            throw ex;
        }
        if (count != null && count > properties.getRateLimitMaxRequests()) {
            long retryAfter = windowSeconds - (now % windowSeconds);
            throw new RateLimitExceededException(retryAfter);
        }
    }
}
