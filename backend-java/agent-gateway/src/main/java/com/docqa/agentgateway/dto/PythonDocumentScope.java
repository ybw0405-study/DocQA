package com.docqa.agentgateway.dto;

import java.util.UUID;

public record PythonDocumentScope(UUID documentId, String indexVersion) {
}
