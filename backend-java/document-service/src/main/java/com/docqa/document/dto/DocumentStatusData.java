package com.docqa.document.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record DocumentStatusData(
        UUID documentId,
        String fileName,
        String title,
        List<String> tags,
        String status,
        int retryCount,
        int maxRetry,
        String errorCode,
        String errorMessage,
        String indexVersion,
        String pendingIndexVersion,
        long chunkCount,
        Long vectorRows,
        Instant vectorConfirmedAt,
        String vectorConsistency,
        Instant indexedAt,
        Instant createdAt,
        Instant updatedAt) {

    public DocumentStatusData {
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}
