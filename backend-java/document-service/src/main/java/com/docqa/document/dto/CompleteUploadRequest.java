package com.docqa.document.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CompleteUploadRequest(
        @NotEmpty @Size(max = 10) List<@Valid CompletedPart> parts) {
}
