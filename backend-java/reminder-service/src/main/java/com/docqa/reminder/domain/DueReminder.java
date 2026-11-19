package com.docqa.reminder.domain;

import java.util.UUID;

/**
 * 到期扫描的一行结果：携带该行自己的 {@code tenantId}，供投放侧 stamp 到消息上。
 *
 * <p>定时任务没有 HTTP 行上下文，扫描改为跨租户后进行；租户边界由每行自带的 tenant_id
 * 随消息下发，而不是在扫描时按单一租户过滤。
 */
public record DueReminder(UUID tenantId, ReminderRow reminder) {
}
