package com.docqa.reminder.service;

import com.docqa.reminder.config.ReminderProperties;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class RateLimiter {

    private static final DefaultRedisScript<Long> INCREMENT_SCRIPT = new DefaultRedisScript<>("""
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
              redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return current
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final ReminderProperties properties;
    private final Clock clock;

    public RateLimiter(StringRedisTemplate redisTemplate, ReminderProperties properties, Clock clock) {
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
        Long count = redisTemplate.execute(
                INCREMENT_SCRIPT,
                List.of(key),
                String.valueOf(windowSeconds));
        if (count != null && count > properties.getRateLimitMaxRequests()) {
            long retryAfter = windowSeconds - (now % windowSeconds);
            throw new RateLimitExceededException(retryAfter);
        }
    }
}
