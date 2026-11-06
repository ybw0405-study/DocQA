package com.docqa.document.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UploadInitRequest(
        @NotBlank @Size(max = 255) String fileName,
        @NotNull @Min(1) Long sizeBytes,
        @NotBlank String contentType,
        @NotBlank @Pattern(regexp = "^[a-f0-9]{32}$") String md5) {
}
