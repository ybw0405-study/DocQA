package com.docqa.reminder.repository;

import com.docqa.reminder.domain.ReminderTriggerRow;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ReminderTriggerRepository {

    /** 唯一键 {@code uk_reminder_trigger_tenant_planned} 冲突时抛出 {@code DuplicateKeyException}，由调用方按「已处理」收敛。 */
    void insert(UUID tenantId, ReminderTriggerRow row);

    /** keyset 分页，排序为 {@code planned_trigger_at DESC, id DESC}；游标是上一页最后一行的锚点，可为 null（首页）。 */
    List<ReminderTriggerRow> findUserPage(UUID tenantId, UUID userId, boolean unreadOnly,
            Instant beforePlannedTriggerAt, UUID beforeId, int limit);

    /** 只把未读行由空变有，返回真正被写入的行数；不触碰已读行，因此重复提交返回 0。 */
    int markAllUnreadAsRead(UUID tenantId, UUID userId, Instant now);

    int markRead(UUID tenantId, UUID userId, List<UUID> notificationIds, Instant now);
}
