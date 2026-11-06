package com.docqa.document.messaging;

import java.time.Instant;
import java.util.UUID;

public record VectorChangeRequestedEvent(
        UUID eventId,
        String traceId,
        UUID documentId,
        UUID userId,
        String operation,
        String indexVersion,
        Instant occurredAt) {

    public static VectorChangeRequestedEvent deleteDocument(
            String traceId,
            UUID documentId,
            UUID userId,
            Instant occurredAt) {
        return new VectorChangeRequestedEvent(
                UUID.randomUUID(),
                traceId,
                documentId,
                userId,
                VectorChangeOperation.DELETE_DOCUMENT.name(),
                null,
                occurredAt);
    }

    public static VectorChangeRequestedEvent deleteVersion(
            String traceId,
            UUID documentId,
            UUID userId,
            String indexVersion,
            Instant occurredAt) {
        return new VectorChangeRequestedEvent(
                UUID.randomUUID(),
                traceId,
                documentId,
                userId,
                VectorChangeOperation.DELETE_VERSION.name(),
                indexVersion,
                occurredAt);
    }
}
