package com.docqa.document.messaging;

import java.time.Instant;
import java.util.UUID;

public record ParseFailedEvent(
        UUID eventId,
        String traceId,
        UUID documentId,
        String indexVersion,
        String errorCode,
        String errorMessage,
        boolean retryable,
        Instant occurredAt) {
}
