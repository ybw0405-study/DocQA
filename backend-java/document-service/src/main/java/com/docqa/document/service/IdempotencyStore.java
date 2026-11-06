package com.docqa.document.service;

import com.docqa.document.domain.IdempotencyOperation;
import java.util.Optional;
import java.util.UUID;

public interface IdempotencyStore {

    Optional<IdempotencyRecord> find(UUID userId, IdempotencyOperation operation, String key);

    void save(UUID userId, IdempotencyOperation operation, String key, IdempotencyRecord record);

    void delete(UUID userId, IdempotencyOperation operation, String key);
}
