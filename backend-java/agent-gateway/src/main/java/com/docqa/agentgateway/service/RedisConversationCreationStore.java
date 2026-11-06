package com.docqa.agentgateway.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.docqa.agentgateway.config.AgentProperties;
import com.docqa.agentgateway.dto.ConversationData;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisConversationCreationStore implements ConversationCreationStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(RedisConversationCreationStore.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final AgentProperties properties;

    public RedisConversationCreationStore(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            AgentProperties properties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public Optional<ConversationCreationRecord> find(UUID userId, String key) {
        String value = redisTemplate.opsForValue().get(redisKey(userId, key));
        if (value == null) {
            return Optional.empty();
        }
        try {
            StoredRecord stored = objectMapper.readValue(value, StoredRecord.class);
            return Optional.of(new ConversationCreationRecord(
                    stored.status(),
                    stored.fingerprint(),
                    stored.response(),
                    stored.updatedAt()));
        } catch (JsonProcessingException ex) {
            LOGGER.warn("Discarding malformed conversation creation record");
            clear(userId, key);
            return Optional.empty();
        }
    }

    @Override
    public boolean tryStart(UUID userId, String key, String fingerprint, Instant now) {
        StoredRecord record = new StoredRecord(IdempotencyStatus.IN_PROGRESS, fingerprint, null, now);
        Boolean created = redisTemplate.opsForValue().setIfAbsent(
                redisKey(userId, key),
                serialize(record),
                properties.getIdempotencyInProgressTtl());
        return Boolean.TRUE.equals(created);
    }

    @Override
    public void complete(
            UUID userId,
            String key,
            String fingerprint,
            ConversationData response,
            Instant now) {
        StoredRecord record = new StoredRecord(IdempotencyStatus.COMPLETED, fingerprint, response, now);
        redisTemplate.opsForValue().set(
                redisKey(userId, key),
                serialize(record),
                properties.getIdempotencyResultTtl());
    }

    @Override
    public void clear(UUID userId, String key) {
        redisTemplate.delete(redisKey(userId, key));
    }

    private String redisKey(UUID userId, String key) {
        return "idem:" + userId + ":CREATE_CONVERSATION:" + key;
    }

    private String serialize(StoredRecord record) {
        try {
            return objectMapper.writeValueAsString(record);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize conversation creation record", ex);
        }
    }

    private record StoredRecord(
            IdempotencyStatus status,
            String fingerprint,
            ConversationData response,
            Instant updatedAt) {
    }
}
