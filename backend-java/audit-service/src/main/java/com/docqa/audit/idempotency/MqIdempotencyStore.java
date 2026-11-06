package com.docqa.audit.idempotency;

import java.util.UUID;

public interface MqIdempotencyStore {

    boolean isProcessed(UUID eventId);

    void markProcessed(UUID eventId);
}
