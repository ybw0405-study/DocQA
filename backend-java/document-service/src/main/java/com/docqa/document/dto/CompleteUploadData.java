package com.docqa.document.dto;

import java.time.Instant;
import java.util.UUID;

public record CompleteUploadData(
        UUID documentId,
        UUID uploadId,
        String status,
        Instant parseRequestedAt,
        int retryCount,
        boolean idempotentReplay) {

    public CompleteUploadData withIdempotentReplay() {
        return new CompleteUploadData(
                documentId,
                uploadId,
                status,
                parseRequestedAt,
                retryCount,
                true);
    }
}
