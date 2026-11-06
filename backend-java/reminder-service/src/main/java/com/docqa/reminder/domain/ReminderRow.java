package com.docqa.reminder.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ReminderRow(
        UUID id,
        UUID userId,
        String title,
        String description,
        String rrule,
        Instant dtstart,
        String timezone,
        int leadMinutes,
        List<Instant> exdates,
        List<LocalDate> exdateDates,
        ReminderStatus status,
        String sourceType,
        String sourceUid,
        int sourceSequence,
        String idempotencyKey,
        Instant nextTriggerAt,
        Instant lastTriggeredAt,
        long version,
        Instant createdAt,
        Instant updatedAt) {
}
