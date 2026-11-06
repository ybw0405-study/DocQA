package com.docqa.audit.idempotency;

import com.docqa.audit.config.AuditProperties;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class RedisMqIdempotencyStore implements MqIdempotencyStore {

    static final String KEY_PREFIX = "mq:idem:audit-event:";

    private static final Logger LOGGER = LoggerFactory.getLogger(RedisMqIdempotencyStore.class);

    private final StringRedisTemplate redisTemplate;
    private final AuditProperties properties;

    public RedisMqIdempotencyStore(StringRedisTemplate redisTemplate, AuditProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    @Override
    public boolean isProcessed(UUID eventId) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(key(eventId)));
        } catch (RuntimeException ex) {
            LOGGER.warn("Unable to read audit MQ idempotency key; falling back to the database", ex);
            return false;
        }
    }

    @Override
    public void markProcessed(UUID eventId) {
        try {
            redisTemplate.opsForValue().set(key(eventId), "1", properties.getMqIdempotencyTtl());
        } catch (RuntimeException ex) {
            LOGGER.warn("Unable to save audit MQ idempotency key; the database primary key remains authoritative", ex);
        }
    }

    private String key(UUID eventId) {
        return KEY_PREFIX + eventId;
    }
}
