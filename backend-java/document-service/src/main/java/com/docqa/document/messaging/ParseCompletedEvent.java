package com.docqa.document.messaging;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ParseCompletedEvent(
        UUID eventId,
        String traceId,
        UUID documentId,
        String indexVersion,
        String status,
        List<ParsedChunk> chunks,
        Long vectorRows,
        Instant occurredAt) {
}
