package com.docqa.agentgateway.dto;

import java.util.UUID;

public record AuthorizedDocument(UUID documentId, String indexVersion) {
}
