package com.docqa.reminder.dto;

import com.docqa.reminder.domain.ReminderKind;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public record CustomReminderCreateRequest(
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

        @NotBlank
        @Size(min = 6, max = 1024)
        String rrule,

        @NotNull
        Instant dtstart,

        @Size(max = 365)
        List<@NotNull Instant> exdates) implements ReminderCreateRequest {
}
