package com.docqa.agentgateway.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.docqa.agentgateway.config.AgentProperties;
import com.docqa.agentgateway.dto.AskData;
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
    private final AgentProperties properties;

    public RedisIdempotencyStore(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            AgentProperties properties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public Optional<IdempotencyRecord> find(UUID userId, UUID conversationId, String key) {
        String value = redisTemplate.opsForValue().get(redisKey(userId, conversationId, key));
        if (value == null) {
            return Optional.empty();
        }
        try {
            StoredRecord stored = objectMapper.readValue(value, StoredRecord.class);
            return Optional.of(new IdempotencyRecord(
                    stored.status(),
                    stored.fingerprint(),
                    stored.response(),
                    stored.errorCode(),
                    stored.updatedAt()));
        } catch (JsonProcessingException ex) {
            LOGGER.warn("Discarding malformed idempotency record: traceId={}", com.docqa.common.trace.TraceIdContext.current());
            clear(userId, conversationId, key);
            return Optional.empty();
        }
    }

    @Override
    public boolean tryStart(UUID userId, UUID conversationId, String key, String fingerprint, Instant now) {
        StoredRecord record = new StoredRecord(IdempotencyStatus.IN_PROGRESS, fingerprint, null, null, now);
        Boolean created = redisTemplate.opsForValue().setIfAbsent(
                redisKey(userId, conversationId, key),
                serialize(record),
                properties.getIdempotencyInProgressTtl());
        return Boolean.TRUE.equals(created);
    }

    @Override
    public void complete(
            UUID userId,
            UUID conversationId,
            String key,
            String fingerprint,
            AskData response,
            Instant now) {
        StoredRecord record = new StoredRecord(IdempotencyStatus.COMPLETED, fingerprint, response, null, now);
        redisTemplate.opsForValue().set(
                redisKey(userId, conversationId, key),
                serialize(record),
                properties.getIdempotencyResultTtl());
    }

    @Override
    public void fail(
            UUID userId,
            UUID conversationId,
            String key,
            String fingerprint,
            String errorCode,
            Instant now) {
        StoredRecord record = new StoredRecord(IdempotencyStatus.FAILED, fingerprint, null, errorCode, now);
        redisTemplate.opsForValue().set(
                redisKey(userId, conversationId, key),
                serialize(record),
                properties.getIdempotencyResultTtl());
    }

    @Override
    public void clear(UUID userId, UUID conversationId, String key) {
        redisTemplate.delete(redisKey(userId, conversationId, key));
    }

    private String redisKey(UUID userId, UUID conversationId, String key) {
        return "idem:" + userId + ":ASK:" + conversationId + ":" + key;
    }

    private String serialize(StoredRecord record) {
        try {
            return objectMapper.writeValueAsString(record);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize idempotency record", ex);
        }
    }

    private record StoredRecord(
            IdempotencyStatus status,
            String fingerprint,
            AskData response,
            String errorCode,
            Instant updatedAt) {
    }
}
