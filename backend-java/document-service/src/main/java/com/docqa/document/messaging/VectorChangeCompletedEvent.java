package com.docqa.document.messaging;

import java.time.Instant;
import java.util.UUID;

public record VectorChangeCompletedEvent(
        UUID eventId,
        UUID requestEventId,
        String traceId,
        UUID documentId,
        String operation,
        String indexVersion,
        String status,
        Long remainingRows,
        String errorCode,
        String errorMessage,
        Instant occurredAt) {

    public boolean completed() {
        return "COMPLETED".equals(status);
    }
}
