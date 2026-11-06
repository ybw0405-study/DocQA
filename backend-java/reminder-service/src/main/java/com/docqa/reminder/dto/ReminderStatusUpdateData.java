package com.docqa.reminder.dto;

import java.time.Instant;
import java.util.UUID;

public record ReminderStatusUpdateData(
        UUID reminderId,
        String status,
        Instant nextTriggerAt,
        long version,
        Instant updatedAt,
        boolean idempotentReplay) {

    public ReminderStatusUpdateData withIdempotentReplay() {
        return new ReminderStatusUpdateData(reminderId, status, nextTriggerAt, version, updatedAt, true);
    }
}
