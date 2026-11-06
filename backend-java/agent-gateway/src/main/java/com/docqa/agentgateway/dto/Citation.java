package com.docqa.agentgateway.dto;

import java.util.UUID;

public record Citation(UUID documentId, int chunkIndex, double score, String snippet) {
}
