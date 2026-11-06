package com.docqa.reminder.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.docqa.reminder.config.ReminderProperties;
import com.docqa.reminder.domain.IdempotencyOperation;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisIdempotencyStore implements IdempotencyStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(RedisIdempotencyStore.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final ReminderProperties properties;

    public RedisIdempotencyStore(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            ReminderProperties properties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public Optional<IdempotencyRecord> find(UUID userId, IdempotencyOperation operation, String key) {
        String value = redisTemplate.opsForValue().get(redisKey(userId, operation, key));
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(value, IdempotencyRecord.class));
        } catch (JsonProcessingException ex) {
            LOGGER.warn("Discarding malformed reminder idempotency record");
            clear(userId, operation, key);
            return Optional.empty();
        }
    }

    @Override
    public boolean tryStart(
            UUID userId,
            IdempotencyOperation operation,
            String key,
            String fingerprint,
            Instant now) {
        IdempotencyRecord record = new IdempotencyRecord(IdempotencyStatus.IN_PROGRESS, fingerprint, null, now);
        Boolean created = redisTemplate.opsForValue().setIfAbsent(
                redisKey(userId, operation, key),
                serialize(record),
                properties.getIdempotencyInProgressTtl());
        return Boolean.TRUE.equals(created);
    }

    @Override
    public void complete(
            UUID userId,
            IdempotencyOperation operation,
            String key,
            String fingerprint,
            String responseJson,
            Instant now) {
        IdempotencyRecord record = new IdempotencyRecord(IdempotencyStatus.COMPLETED, fingerprint, responseJson, now);
        redisTemplate.opsForValue().set(
                redisKey(userId, operation, key),
                serialize(record),
                properties.getIdempotencyResultTtl());
    }

    @Override
    public void clear(UUID userId, IdempotencyOperation operation, String key) {
        redisTemplate.delete(redisKey(userId, operation, key));
    }

    private String redisKey(UUID userId, IdempotencyOperation operation, String key) {
        return "idem:" + userId + ":" + operation.name() + ":" + key;
    }

    private String serialize(IdempotencyRecord record) {
        try {
            return objectMapper.writeValueAsString(record);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize reminder idempotency record", ex);
        }
    }
}
