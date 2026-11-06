package com.docqa.reminder.messaging;

import java.time.Instant;
import java.util.UUID;

public record ReminderDueEvent(
        UUID eventId,
        String traceId,
        UUID reminderId,
        UUID userId,
        String title,
        String description,
        String sourceType,
        String rrule,
        String timezone,
        Instant occurrenceStartUtc,
        Instant nextTriggerAt,
        Instant occurredAt) {
}
