package com.docqa.reminder.service;

import com.docqa.reminder.domain.IdempotencyOperation;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface IdempotencyStore {

    Optional<IdempotencyRecord> find(UUID userId, IdempotencyOperation operation, String key);

    boolean tryStart(UUID userId, IdempotencyOperation operation, String key, String fingerprint, Instant now);

    void complete(UUID userId, IdempotencyOperation operation, String key, String fingerprint, String responseJson, Instant now);

    void clear(UUID userId, IdempotencyOperation operation, String key);
}
