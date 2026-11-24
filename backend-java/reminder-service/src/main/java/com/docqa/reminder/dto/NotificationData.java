package com.docqa.reminder.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * 一条通知等于一次已落库的触发，不是提醒规则本身：同一条提醒可以有多条通知，
 * 提醒被完成或取消后历史通知仍然保留。
 * {@code plannedTriggerAt} 是计划触发时刻（课表类即上课时刻 − 提前量），界面对 COURSE 行
 * 需用看板行的 {@code leadMinutes} 做显示层换算。
 */
public record NotificationData(
        UUID notificationId,
        UUID reminderId,
        String kind,
        String title,
        String description,
        Instant plannedTriggerAt,
        Instant nextTriggerAt,
        Instant deliveredAt,
        Instant readAt,
        Instant createdAt) {
}
