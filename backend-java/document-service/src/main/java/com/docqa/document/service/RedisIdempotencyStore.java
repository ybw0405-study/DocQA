package com.docqa.document.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.docqa.document.config.DocumentProperties;
import com.docqa.document.domain.IdempotencyOperation;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class RedisIdempotencyStore implements IdempotencyStore {

    private static final String KEY_PREFIX = "idem:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final DocumentProperties properties;

    public RedisIdempotencyStore(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            DocumentProperties properties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public Optional<IdempotencyRecord> find(UUID userId, IdempotencyOperation operation, String key) {
        String redisKey = key(userId, operation, key);
        String value = redisTemplate.opsForValue().get(redisKey);
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(value, IdempotencyRecord.class));
        } catch (JsonProcessingException ex) {
            redisTemplate.delete(redisKey);
            return Optional.empty();
        }
    }

    @Override
    public void save(UUID userId, IdempotencyOperation operation, String key, IdempotencyRecord record) {
        try {
            String value = objectMapper.writeValueAsString(record);
            redisTemplate.opsForValue().set(
                    key(userId, operation, key),
                    value,
                    properties.getIdempotencyTtl());
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize idempotency record", ex);
        }
    }

    @Override
    public void delete(UUID userId, IdempotencyOperation operation, String key) {
        redisTemplate.delete(key(userId, operation, key));
    }

    private String key(UUID userId, IdempotencyOperation operation, String key) {
        return KEY_PREFIX + userId + ":" + operation.name() + ":" + key;
    }
}
