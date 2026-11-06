package com.docqa.document.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DocumentRenameRequest(
        @NotBlank @Size(min = 1, max = 120) String title) {
}
