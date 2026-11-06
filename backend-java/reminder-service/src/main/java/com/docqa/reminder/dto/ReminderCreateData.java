package com.docqa.reminder.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ReminderCreateData(
        UUID reminderId,
        String status,
        String kind,
        String title,
        String rrule,
        Instant dtstart,
        String timezone,
        List<Instant> exdates,
        Instant nextTriggerAt,
        long version,
        boolean idempotentReplay,
        Instant createdAt,
        Instant updatedAt) {

    public ReminderCreateData {
        exdates = exdates == null ? List.of() : List.copyOf(exdates);
    }

    public ReminderCreateData withIdempotentReplay() {
        return new ReminderCreateData(
                reminderId,
                status,
                kind,
                title,
                rrule,
                dtstart,
                timezone,
                exdates,
                nextTriggerAt,
                version,
                true,
                createdAt,
                updatedAt);
    }
}
