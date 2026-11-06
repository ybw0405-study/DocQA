package com.docqa.reminder.dto;

import com.docqa.reminder.domain.ReminderKind;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record DeadlineReminderCreateRequest(
        @NotNull
        ReminderKind kind,

        @NotBlank
        @Size(max = 200)
        String title,

        @Size(max = 1000)
        String description,

        @NotBlank
        @Size(max = 64)
        @Pattern(regexp = "^UTC$|^[A-Za-z]+(?:/[A-Za-z0-9_+\\-]+)+$")
        String timezone,

        @NotNull
        Instant dueAt,

        @Min(0)
        @Max(10080)
        Integer leadMinutes) implements ReminderCreateRequest {
}
