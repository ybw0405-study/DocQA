package com.docqa.reminder.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ReminderListItemData(
        UUID reminderId,
        String kind,
        String status,
        String title,
        String description,
        String rrule,
        Instant dtstart,
        String timezone,
        List<Instant> exdates,
        int leadMinutes,
        Instant nextTriggerAt,
        Instant lastTriggeredAt,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public ReminderListItemData {
        exdates = exdates == null ? List.of() : List.copyOf(exdates);
    }
}
