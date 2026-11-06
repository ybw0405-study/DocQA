package com.docqa.document.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record DocumentTagsUpdateRequest(
        @NotNull @Size(max = 20) List<@NotNull String> tags) {
}
