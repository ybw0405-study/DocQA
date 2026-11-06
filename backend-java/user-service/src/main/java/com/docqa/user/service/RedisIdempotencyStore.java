package com.docqa.user.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.docqa.user.config.AuthProperties;
import com.docqa.user.domain.IdempotencyOperation;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisIdempotencyStore implements IdempotencyStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(RedisIdempotencyStore.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final AuthProperties properties;

    public RedisIdempotencyStore(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            AuthProperties properties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public Optional<IdempotencyRecord> find(String scope, IdempotencyOperation operation, String key) {
        String value = redisTemplate.opsForValue().get(redisKey(scope, operation, key));
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(value, IdempotencyRecord.class));
        } catch (JsonProcessingException ex) {
            LOGGER.warn("Discarding malformed user idempotency record");
            clear(scope, operation, key);
            return Optional.empty();
        }
    }

    @Override
    public boolean tryStart(
            String scope,
            IdempotencyOperation operation,
            String key,
            String fingerprint,
            Instant now) {
        IdempotencyRecord record = new IdempotencyRecord(IdempotencyStatus.IN_PROGRESS, fingerprint, null, now);
        Boolean created = redisTemplate.opsForValue().setIfAbsent(
                redisKey(scope, operation, key),
                serialize(record),
                properties.getIdempotencyInProgressTtl());
        return Boolean.TRUE.equals(created);
    }

    @Override
    public void complete(
            String scope,
            IdempotencyOperation operation,
            String key,
            String fingerprint,
            String responseJson,
            Instant now) {
        IdempotencyRecord record = new IdempotencyRecord(IdempotencyStatus.COMPLETED, fingerprint, responseJson, now);
        redisTemplate.opsForValue().set(
                redisKey(scope, operation, key),
                serialize(record),
                properties.getIdempotencyResultTtl());
    }

    @Override
    public void clear(String scope, IdempotencyOperation operation, String key) {
        redisTemplate.delete(redisKey(scope, operation, key));
    }

    private String redisKey(String scope, IdempotencyOperation operation, String key) {
        return "idem:" + scope + ":" + operation.name() + ":" + key;
    }

    private String serialize(IdempotencyRecord record) {
        try {
            return objectMapper.writeValueAsString(record);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize user idempotency record", ex);
        }
    }
}
