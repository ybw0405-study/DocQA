package com.docqa.document.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record UploadInitData(
        UUID documentId,
        UUID uploadId,
        String status,
        boolean instantUpload,
        int partSize,
        int partCount,
        Instant expiresAt,
        List<PresignedPart> parts,
        boolean idempotentReplay) {

    public UploadInitData withIdempotentReplay() {
        return new UploadInitData(
                documentId,
                uploadId,
                status,
                instantUpload,
                partSize,
                partCount,
                expiresAt,
                parts,
                true);
    }
}
