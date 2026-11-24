package com.docqa.agentgateway.service;

import com.docqa.agentgateway.dto.AuditEventItem;
import com.docqa.agentgateway.dto.AuditEventListData;
import com.docqa.agentgateway.repository.AdminAuditQueryRepository;
import com.docqa.agentgateway.repository.AuditQueryFilter;
import com.docqa.common.api.FieldViolation;
import com.docqa.common.api.KeysetCursor;
import com.docqa.common.error.BusinessException;
import com.docqa.common.error.ErrorCode;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * 管理面审计查询。
 *
 * <p>过滤条件 `traceId` / `userId` / `eventType` 任意组合；三者都缺省时套用最近 24 小时的
 * 默认窗口，已有过滤条件时只按显式给出的 `from` / `to` 限窗。</p>
 */
@Service
public class AdminAuditQueryService {

    private static final int MAX_PAGE_SIZE = 500;
    private static final Pattern TRACE_ID = Pattern.compile("^[0-9a-f]{32}$");
    private static final Pattern EVENT_TYPE = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");

    private final AdminAuditQueryRepository auditRepository;
    private final Clock clock;

    public AdminAuditQueryService(AdminAuditQueryRepository auditRepository, Clock clock) {
        this.auditRepository = auditRepository;
        this.clock = clock;
    }

    /** tenantId 必须来自已验签 JWT；审计查询不允许靠请求参数切换租户。 */
    public AuditEventListData list(
            UUID tenantId,
            String traceId,
            String userId,
            String eventType,
            String from,
            String to,
            int limit,
            String cursorToken) {
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new BusinessException(
                    ErrorCode.COM_VALIDATION_001,
                    "limit out of range",
                    List.of(FieldViolation.of("limit", "must be between 1 and 500")));
        }
        String normalizedTraceId = blankToNull(traceId);
        if (normalizedTraceId != null && !TRACE_ID.matcher(normalizedTraceId).matches()) {
            throw invalid("traceId", "must be a 32-character lowercase hex string");
        }
        String normalizedEventType = blankToNull(eventType);
        if (normalizedEventType != null && !EVENT_TYPE.matcher(normalizedEventType).matches()) {
            throw invalid("eventType", "must match ^[A-Z][A-Z0-9_]{0,63}$");
        }
        UUID targetUserId = parseUserId(userId);

        boolean hasFilter = normalizedTraceId != null || targetUserId != null || normalizedEventType != null;
        AdminQueryWindow window = AdminQueryWindow.resolve(from, to, clock, !hasFilter);
        KeysetCursor cursor = parseCursor(cursorToken);

        List<AuditEventItem> rows = auditRepository.findPage(
                tenantId,
                new AuditQueryFilter(
                        normalizedTraceId,
                        targetUserId,
                        normalizedEventType,
                        window.from(),
                        window.to()),
                cursor == null ? null : cursor.sortKey(),
                cursor == null ? null : cursor.id(),
                limit + 1);
        boolean hasMore = rows.size() > limit;
        List<AuditEventItem> page = hasMore ? rows.subList(0, limit) : rows;
        String nextCursor = hasMore
                ? KeysetCursor.of(
                        page.get(page.size() - 1).occurredAt(),
                        page.get(page.size() - 1).eventId()).encode()
                : null;
        return new AuditEventListData(page, nextCursor, limit);
    }

    private static UUID parseUserId(String userId) {
        String normalized = blankToNull(userId);
        if (normalized == null) {
            return null;
        }
        try {
            return UUID.fromString(normalized);
        } catch (IllegalArgumentException ex) {
            throw invalid("userId", "must be a UUID");
        }
    }

    private static KeysetCursor parseCursor(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        return KeysetCursor.decode(token).orElseThrow(() -> invalid(
                "cursor", "must come from a previous response"));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static BusinessException invalid(String field, String message) {
        return new BusinessException(
                ErrorCode.COM_VALIDATION_001,
                "invalid audit query",
                List.of(FieldViolation.of(field, message)));
    }
}
