package com.docqa.reminder.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 一行等于「一次已落库的触发」；站内通知就是这行的 {@code readAt} 属性，因此不另建通知表。
 * {@code plannedTriggerAt} 承载事件的计划触发时刻（课表类即上课时刻 − 提前量，见）。
 */
public record ReminderTriggerRow(
        UUID id,
        UUID userId,
        UUID reminderId,
        String title,
        String description,
        String sourceType,
        Instant plannedTriggerAt,
        Instant nextTriggerAt,
        String traceId,
        Instant deliveredAt,
        Instant readAt,
        Instant createdAt,
        Instant updatedAt) {
}
