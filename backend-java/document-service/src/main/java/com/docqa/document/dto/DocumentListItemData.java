package com.docqa.document.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record DocumentListItemData(
        UUID documentId,
        String fileName,
        String title,
        List<String> tags,
        String contentType,
        long sizeBytes,
        String status,
        int retryCount,
        int maxRetry,
        String errorCode,
        String indexVersion,
        long chunkCount,
        Instant indexedAt,
        Instant createdAt,
        Instant updatedAt) {

    public DocumentListItemData {
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}
