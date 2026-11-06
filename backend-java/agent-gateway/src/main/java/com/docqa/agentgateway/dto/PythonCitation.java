package com.docqa.agentgateway.dto;

import java.util.UUID;

public record PythonCitation(UUID documentId, int chunkIndex, double score, String snippet) {
}
