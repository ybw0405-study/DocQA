package com.docqa.document.messaging;

public record ParsedChunk(int chunkIndex, String vectorId, String contentHash, int tokenCount) {
}
