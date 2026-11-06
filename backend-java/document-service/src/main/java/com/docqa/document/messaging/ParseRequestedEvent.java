package com.docqa.document.messaging;

import java.time.Instant;
import java.util.UUID;

public record ParseRequestedEvent(
        UUID eventId,
        String traceId,
        UUID documentId,
        UUID userId,
        String indexVersion,
        String objectKey,
        String fileName,
        String contentType,
        long sizeBytes,
        String md5,
        int retryCount,
        Instant occurredAt) {
}
