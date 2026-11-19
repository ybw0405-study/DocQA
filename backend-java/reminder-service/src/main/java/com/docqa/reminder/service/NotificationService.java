package com.docqa.reminder.service;

import com.docqa.common.api.FieldViolation;
import com.docqa.common.api.KeysetCursor;
import com.docqa.common.error.BusinessException;
import com.docqa.common.error.ErrorCode;
import com.docqa.common.tenant.TenantContext;
import com.docqa.reminder.domain.ReminderKind;
import com.docqa.reminder.domain.ReminderTriggerRow;
import com.docqa.reminder.dto.NotificationData;
import com.docqa.reminder.dto.NotificationListData;
import com.docqa.reminder.dto.NotificationReadData;
import com.docqa.reminder.dto.NotificationReadRequest;
import com.docqa.reminder.repository.ReminderTriggerRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 站内通知的读侧与已读收敛。列表排序键与游标编码严格同序
 * （{@code planned_trigger_at DESC, id DESC}），已读是「只由空变有」的收敛操作，因此不要求幂等键、也不计限流（D-67）。
 */
@Service
public class NotificationService {

    private static final int MIN_PAGE_SIZE = 1;
    private static final int MAX_PAGE_SIZE = 100;

    private final ReminderTriggerRepository reminderTriggerRepository;
    private final Clock clock;

    public NotificationService(ReminderTriggerRepository reminderTriggerRepository, Clock clock) {
        this.reminderTriggerRepository = reminderTriggerRepository;
        this.clock = clock;
    }

    public NotificationListData list(UUID userId, boolean unreadOnly, String cursorToken, int limit) {
        UUID tenantId = TenantContext.require();
        if (limit < MIN_PAGE_SIZE || limit > MAX_PAGE_SIZE) {
            throw new BusinessException(ErrorCode.COM_VALIDATION_001, "limit out of range",
                    List.of(FieldViolation.of("limit", "must be between 1 and 100")));
        }
        KeysetCursor cursor = parseCursor(cursorToken);
        List<ReminderTriggerRow> rows = reminderTriggerRepository.findUserPage(
                tenantId,
                userId,
                unreadOnly,
                cursor == null ? null : cursor.sortKey(),
                cursor == null ? null : cursor.id(),
                limit + 1);
        boolean hasMore = rows.size() > limit;
        List<ReminderTriggerRow> page = hasMore ? rows.subList(0, limit) : rows;
        List<NotificationData> items = page.stream()
                .map(NotificationService::toData)
                .toList();
        String nextCursor = hasMore
                ? KeysetCursor.of(page.get(page.size() - 1).plannedTriggerAt(), page.get(page.size() - 1).id()).encode()
                : null;
        return new NotificationListData(items, nextCursor, limit);
    }

    public NotificationReadData markRead(UUID userId, NotificationReadRequest request) {
        UUID tenantId = TenantContext.require();
        boolean all = Boolean.TRUE.equals(request.all());
        List<UUID> ids = request.ids();
        if (all && ids != null) {
            throw new BusinessException(ErrorCode.COM_VALIDATION_001, "all and ids are mutually exclusive",
                    List.of(FieldViolation.of("ids", "must be omitted when all is true")));
        }
        if (!all && (ids == null || ids.isEmpty())) {
            throw new BusinessException(ErrorCode.COM_VALIDATION_001, "ids is required",
                    List.of(FieldViolation.of("ids", "must contain at least one notification id when all is false")));
        }
        // 与 delivered_at 同口径截断到秒：DATETIME(3) 存毫秒，未截断时同一秒内多次操作会读出无意义的毫秒差。
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        int updated = all
                ? reminderTriggerRepository.markAllUnreadAsRead(tenantId, userId, now)
                : reminderTriggerRepository.markRead(tenantId, userId, ids, now);
        return new NotificationReadData(updated, all);
    }

    private static NotificationData toData(ReminderTriggerRow row) {
        return new NotificationData(
                row.id(),
                row.reminderId(),
                ReminderKind.fromSourceType(row.sourceType()).name(),
                row.title(),
                row.description(),
                row.plannedTriggerAt(),
                row.nextTriggerAt(),
                row.deliveredAt(),
                row.readAt(),
                row.createdAt());
    }

    private static KeysetCursor parseCursor(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        return KeysetCursor.decode(token).orElseThrow(() -> new BusinessException(
                ErrorCode.COM_VALIDATION_001, "invalid cursor",
                List.of(FieldViolation.of("cursor", "must come from a previous response"))));
    }
}
