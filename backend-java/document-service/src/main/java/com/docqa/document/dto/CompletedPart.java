package com.docqa.document.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CompletedPart(
        @Min(1) @Max(10000) int partNumber,
        @NotBlank @Size(max = 128) String etag) {
}
