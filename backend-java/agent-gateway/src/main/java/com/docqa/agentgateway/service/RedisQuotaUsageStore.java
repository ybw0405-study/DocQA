package com.docqa.agentgateway.service;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class RedisQuotaUsageStore implements QuotaUsageStore {

    static final String KEY_PREFIX = "agent:quota:usage:";
    static final long TTL_SECONDS = 48L * 3600L;

    private static final String FIELD_QUESTIONS = "questions";
    private static final String FIELD_TOKENS = "tokens";

    private static final DefaultRedisScript<Long> RESERVE_SCRIPT = new DefaultRedisScript<>("""
            local questions = tonumber(redis.call('HGET', KEYS[1], 'questions') or '0')
            local tokens = tonumber(redis.call('HGET', KEYS[1], 'tokens') or '0')
            local questionLimit = tonumber(ARGV[1])
            local tokenLimit = tonumber(ARGV[2])
            if questionLimit >= 0 and questions >= questionLimit then
              return 0
            end
            if tokenLimit >= 0 and tokens >= tokenLimit then
              return 0
            end
            redis.call('HINCRBY', KEYS[1], 'questions', 1)
            redis.call('EXPIRE', KEYS[1], ARGV[3])
            return 1
            """, Long.class);

    private static final DefaultRedisScript<Long> ADD_TOKENS_SCRIPT = new DefaultRedisScript<>("""
            redis.call('HINCRBY', KEYS[1], 'tokens', ARGV[1])
            redis.call('EXPIRE', KEYS[1], ARGV[2])
            return 1
            """, Long.class);

    private final StringRedisTemplate redisTemplate;

    public RedisQuotaUsageStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean tryConsume(UUID userId, LocalDate day, Long questionLimit, Long tokenLimit) {
        Long allowed = redisTemplate.execute(
                RESERVE_SCRIPT,
                List.of(key(userId, day)),
                limitArg(questionLimit),
                limitArg(tokenLimit),
                String.valueOf(TTL_SECONDS));
        if (allowed == null) {
            throw new IllegalStateException("Quota reserve script returned no result");
        }
        return allowed == 1L;
    }

    @Override
    public void addTokens(UUID userId, LocalDate day, long tokens) {
        redisTemplate.execute(
                ADD_TOKENS_SCRIPT,
                List.of(key(userId, day)),
                String.valueOf(tokens),
                String.valueOf(TTL_SECONDS));
    }

    static String key(UUID userId, LocalDate day) {
        return KEY_PREFIX + userId + ":" + day;
    }

    private static String limitArg(Long limit) {
        return String.valueOf(limit == null ? -1L : limit);
    }
}
