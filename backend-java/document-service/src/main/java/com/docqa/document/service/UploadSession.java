package com.docqa.document.service;

import java.time.Instant;
import java.util.UUID;

public record UploadSession(
        UUID documentId,
        UUID userId,
        String objectKey,
        String minioUploadId,
        int partSize,
        int partCount,
        Instant expiresAt) {
}
