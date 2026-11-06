package com.docqa.document.domain;

import java.util.UUID;

public record ChunkEntry(
        UUID id,
        UUID documentId,
        int chunkIndex,
        String vectorId,
        String contentHash,
        int tokenCount,
        String indexVersion) {
}
