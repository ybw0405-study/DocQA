package com.docqa.reminder.repository;

import com.docqa.reminder.domain.DueReminder;
import com.docqa.reminder.domain.ReminderRow;
import com.docqa.reminder.domain.ReminderStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReminderRepository {

    void insert(UUID tenantId, ReminderRow reminder);

    Optional<ReminderRow> findByUserAndIdempotencyKey(UUID tenantId, UUID userId, String idempotencyKey);

    /** 归属条件与主键同在一个谓词里：别人的提醒一律表现为「不存在」，不区分 403 与 404。 */
    Optional<ReminderRow> findOwned(UUID tenantId, UUID userId, UUID id);

    /**
     * 状态迁移的唯一写入路径：以 {@code version} 做 CAS，同时写回状态与下一次触发时刻。
     * 谓词<strong>不</strong>含 {@code status}——并发改动同样会推进 {@code version}，CAS 已足以拒斥。
     */
    int updateStatus(UUID tenantId, UUID id, UUID userId, long expectedVersion, ReminderStatus target,
            Instant nextTriggerAt, Instant updatedAt);

    /**
     * 跨租户扫描到期行。定时任务无 HTTP 行上下文，租户边界由每行自带的 tenant_id 随消息下发；
     * 调用方必须用返回的 {@link DueReminder#tenantId()} 做后续写入与消息 stamp。
     */
    List<DueReminder> findDue(Instant now, int limit);

    int advance(UUID tenantId, UUID id, long expectedVersion, Instant nextTriggerAt, Instant lastTriggeredAt);

    int complete(UUID tenantId, UUID id, long expectedVersion, Instant lastTriggeredAt);

    List<ReminderRow> findUserPage(UUID tenantId, UUID userId, ReminderStatus status, Instant afterNextTriggerAt,
            UUID afterId, boolean afterNullGroup, int limit);

    List<ReminderRow> findByUserAndSourceUids(UUID tenantId, UUID userId, String sourceType, List<String> sourceUids);

    int updateFromImport(UUID tenantId, ReminderRow reminder);
}
