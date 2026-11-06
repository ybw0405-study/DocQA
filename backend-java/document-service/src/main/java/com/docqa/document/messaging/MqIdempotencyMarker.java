package com.docqa.document.messaging;

import com.docqa.document.config.DocumentProperties;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class MqIdempotencyMarker {

    private static final Logger LOGGER = LoggerFactory.getLogger(MqIdempotencyMarker.class);

    private final StringRedisTemplate redisTemplate;
    private final Duration ttl;

    public MqIdempotencyMarker(StringRedisTemplate redisTemplate, DocumentProperties properties) {
        this.redisTemplate = redisTemplate;
        this.ttl = properties.getMqIdempotencyTtl();
    }

    public boolean alreadyProcessed(String topic, String messageId) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(key(topic, messageId)));
        } catch (RuntimeException ex) {
            LOGGER.warn("Unable to read MQ idempotency key: topic={}, messageId={}", topic, messageId, ex);
            return false;
        }
    }

    public void markProcessed(String topic, String messageId) {
        try {
            redisTemplate.opsForValue().set(key(topic, messageId), "1", ttl);
        } catch (RuntimeException ex) {
            LOGGER.warn("Unable to save MQ idempotency key: topic={}, messageId={}", topic, messageId, ex);
        }
    }

    private static String key(String topic, String messageId) {
        return "mq:idem:" + topic + ":" + messageId;
    }
}
