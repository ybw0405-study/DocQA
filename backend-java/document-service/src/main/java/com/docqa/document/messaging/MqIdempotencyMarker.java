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

    /**
     * 原子占位：SET NX EX。返回 true 表示已处理/已被其它线程占用（应跳过）；
     * 返回 false 表示首次占位成功（可继续处理）。
     *
     * <p>原实现是「先 hasKey 后 set」两步，并发投递同一 messageId 时存在竞态；
     * 且 Redis 故障返回 false = 关闭幂等。此处改为原子占位 + fail-closed：
     * Redis 不可用时抛出异常，让消费端 RECONSUME_LATER 重投，而不是放行重复处理。
     * 消费端在业务异常时必须调用 {@link #release(String, String)} 释放占位。
     */
    public boolean alreadyProcessed(String topic, String messageId) {
        String k = key(topic, messageId);
        try {
            return !Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(k, "1", ttl));
        } catch (RuntimeException ex) {
            LOGGER.error("MQ idempotency store unavailable; fail-closed: topic={}, messageId={}",
                    topic, messageId, ex);
            throw ex;
        }
    }

    /** 业务失败时释放占位，允许消息重投后再次处理。 */
    public void release(String topic, String messageId) {
        try {
            redisTemplate.delete(key(topic, messageId));
        } catch (RuntimeException ex) {
            LOGGER.warn("Unable to release MQ idempotency key: topic={}, messageId={}", topic, messageId, ex);
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
