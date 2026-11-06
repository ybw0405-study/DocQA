package com.docqa.document.domain;

import java.time.Instant;
import java.util.UUID;

public record DocumentRow(
        UUID id,
        UUID userId,
        String fileName,
        String title,
        String contentType,
        long sizeBytes,
        String md5,
        String objectKey,
        DocumentStatus status,
        int retryCount,
        int maxRetry,
        String errorCode,
        String errorMessage,
        String indexVersion,
        String pendingIndexVersion,
        Long vectorRows,
        Instant vectorConfirmedAt,
        Instant parsingStartedAt,
        Instant indexedAt,
        Instant deletedAt,
        Instant createdAt,
        Instant updatedAt) {

    public boolean deleted() {
        return deletedAt != null;
    }

    public boolean rebuilding() {
        return pendingIndexVersion != null;
    }
}
