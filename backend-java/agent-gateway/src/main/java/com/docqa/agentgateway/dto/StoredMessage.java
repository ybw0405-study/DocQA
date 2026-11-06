package com.docqa.agentgateway.dto;

import java.time.Instant;
import java.util.UUID;

public record StoredMessage(UUID id, String role, String content, Instant createdAt) {
}
