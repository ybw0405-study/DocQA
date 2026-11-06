package com.docqa.document.service;

import com.docqa.document.config.DocumentProperties;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class RedisUploadSessionStore implements UploadSessionStore {

    private static final String KEY_PREFIX = "upload:session:";

    private final StringRedisTemplate redisTemplate;
    private final DocumentProperties properties;

    public RedisUploadSessionStore(StringRedisTemplate redisTemplate, DocumentProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    @Override
    public void save(UploadSession session) {
        String key = key(session.documentId());
        Map<String, String> values = Map.of(
                "documentId", session.documentId().toString(),
                "userId", session.userId().toString(),
                "objectKey", session.objectKey(),
                "minioUploadId", session.minioUploadId(),
                "partSize", Integer.toString(session.partSize()),
                "partCount", Integer.toString(session.partCount()),
                "expiresAt", Long.toString(session.expiresAt().toEpochMilli()));
        redisTemplate.opsForHash().putAll(key, values);
        redisTemplate.expire(key, properties.getSessionTtl());
    }

    @Override
    public Optional<UploadSession> find(UUID documentId) {
        String key = key(documentId);
        Map<Object, Object> values = redisTemplate.opsForHash().entries(key);
        if (values.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new UploadSession(
                    UUID.fromString(value(values, "documentId")),
                    UUID.fromString(value(values, "userId")),
                    value(values, "objectKey"),
                    value(values, "minioUploadId"),
                    Integer.parseInt(value(values, "partSize")),
                    Integer.parseInt(value(values, "partCount")),
                    Instant.ofEpochMilli(Long.parseLong(value(values, "expiresAt")))));
        } catch (IllegalArgumentException ex) {
            redisTemplate.delete(key);
            return Optional.empty();
        }
    }

    @Override
    public void delete(UUID documentId) {
        redisTemplate.delete(key(documentId));
    }

    private String value(Map<Object, Object> values, String field) {
        Object value = values.get(field);
        if (value == null) {
            throw new IllegalArgumentException("Missing upload session field: " + field);
        }
        return value.toString();
    }

    private String key(UUID documentId) {
        return KEY_PREFIX + documentId;
    }
}
