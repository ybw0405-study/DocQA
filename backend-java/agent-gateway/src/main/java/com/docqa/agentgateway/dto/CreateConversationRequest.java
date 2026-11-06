package com.docqa.agentgateway.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record CreateConversationRequest(
        @Size(min = 1, max = 200, message = "must be between 1 and 200 characters")
        String title,
        @Size(max = 20, message = "must contain at most 20 documents")
        List<@NotNull(message = "must not contain null") UUID> documentScopeIds) {
}
