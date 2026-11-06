package com.docqa.agentgateway.service;

import com.docqa.agentgateway.dto.AskData;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface IdempotencyStore {

    Optional<IdempotencyRecord> find(UUID userId, UUID conversationId, String key);

    boolean tryStart(UUID userId, UUID conversationId, String key, String fingerprint, Instant now);

    void complete(UUID userId, UUID conversationId, String key, String fingerprint, AskData response, Instant now);

    void fail(UUID userId, UUID conversationId, String key, String fingerprint, String errorCode, Instant now);

    void clear(UUID userId, UUID conversationId, String key);
}
