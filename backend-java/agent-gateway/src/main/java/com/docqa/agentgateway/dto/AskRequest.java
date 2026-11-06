package com.docqa.agentgateway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record AskRequest(
        @NotBlank(message = "must not be blank")
        @Size(max = 4000, message = "must be at most 4000 characters")
        String question,
        @NotNull(message = "must not be null")
        @Size(min = 1, max = 20, message = "must contain 1 to 20 documents")
        List<@NotNull(message = "must not contain null") UUID> documentIds) {
}
