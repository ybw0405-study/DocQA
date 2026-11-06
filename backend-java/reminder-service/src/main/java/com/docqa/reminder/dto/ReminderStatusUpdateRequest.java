package com.docqa.reminder.dto;

import com.docqa.reminder.domain.ReminderStatus;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 只改状态：标题、RRULE、时区与提前量不在可改面内（契约 2.3.0）。
 * {@code version} 读起来像目标版本，实际是「我以为它还是几」——期望的当前版本。
 */
public record ReminderStatusUpdateRequest(
        @NotNull
        ReminderStatus status,

        @NotNull
        @Min(0)
        Long version) {
}
