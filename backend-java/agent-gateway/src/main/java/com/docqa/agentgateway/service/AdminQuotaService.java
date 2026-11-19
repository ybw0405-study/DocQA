package com.docqa.agentgateway.service;

import com.docqa.agentgateway.dto.AiQuota;
import com.docqa.agentgateway.dto.AiQuotaListData;
import com.docqa.agentgateway.repository.AdminAuditEntry;
import com.docqa.agentgateway.repository.AdminAuditRepository;
import com.docqa.agentgateway.repository.AiQuotaRepository;
import com.docqa.common.api.FieldViolation;
import com.docqa.common.api.KeysetCursor;
import com.docqa.common.error.BusinessException;
import com.docqa.common.error.ErrorCode;
import com.docqa.common.trace.TraceIdContext;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class AdminQuotaService {

    public static final String EVENT_QUOTA_UPDATED = "ADMIN_AI_QUOTA_UPDATED";
    public static final String EVENT_QUOTA_CLEARED = "ADMIN_AI_QUOTA_CLEARED";

    private static final Logger LOGGER = LoggerFactory.getLogger(AdminQuotaService.class);
    private static final int MIN_PAGE_SIZE = 1;
    private static final int MAX_PAGE_SIZE = 200;

    private final AiQuotaRepository quotaRepository;
    private final AdminAuditRepository auditRepository;
    private final TransactionRunner transactionRunner;
    private final Clock clock;

    public AdminQuotaService(
            AiQuotaRepository quotaRepository,
            AdminAuditRepository auditRepository,
            TransactionRunner transactionRunner,
            Clock clock) {
        this.quotaRepository = quotaRepository;
        this.auditRepository = auditRepository;
        this.transactionRunner = transactionRunner;
        this.clock = clock;
    }

    /** tenantId 必须来自已验签 JWT，不接受请求参数；配额只在租户内可见、可改。 */
    public AiQuotaListData list(UUID tenantId, String cursorToken, int limit) {
        if (limit < MIN_PAGE_SIZE || limit > MAX_PAGE_SIZE) {
            throw new BusinessException(
                    ErrorCode.COM_VALIDATION_001,
                    "limit out of range",
                    List.of(FieldViolation.of("limit", "must be between 1 and 200")));
        }
        KeysetCursor cursor = parseCursor(cursorToken);
        List<AiQuota> rows = quotaRepository.findPage(
                tenantId,
                cursor == null ? null : cursor.sortKey(),
                cursor == null ? null : cursor.id(),
                limit + 1);
        boolean hasMore = rows.size() > limit;
        List<AiQuota> page = hasMore ? rows.subList(0, limit) : rows;
        String nextCursor = hasMore
                ? KeysetCursor.of(page.get(page.size() - 1).updatedAt(), page.get(page.size() - 1).userId()).encode()
                : null;
        return new AiQuotaListData(page, nextCursor, limit);
    }

    /** tenantId 必须来自已验签 JWT；审计条目也落在同一租户，避免管理操作跨租户留痕。 */
    public AiQuota update(
            UUID tenantId,
            UUID adminId,
            UUID targetUserId,
            Long dailyQuestionLimit,
            Long dailyTokenLimit) {
        String username = quotaRepository.findUsername(tenantId, targetUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USR_NOT_FOUND_001));
        Instant now = clock.instant();
        boolean cleared = dailyQuestionLimit == null && dailyTokenLimit == null;
        String traceId = TraceIdContext.currentOrCreate();
        transactionRunner.execute(() -> {
            if (cleared) {
                quotaRepository.delete(tenantId, targetUserId);
            } else {
                quotaRepository.upsert(tenantId, targetUserId, dailyQuestionLimit, dailyTokenLimit, adminId, now);
            }
            auditRepository.insert(tenantId, new AdminAuditEntry(
                    UUID.randomUUID(),
                    traceId,
                    adminId,
                    cleared ? EVENT_QUOTA_CLEARED : EVENT_QUOTA_UPDATED,
                    "INFO",
                    Map.of("targetUserId", targetUserId.toString()),
                    now,
                    now));
        });
        // 配额数值只进结构化日志，不进审计 payload（D-160）。
        LOGGER.info(
                "Admin AI quota changed: targetUserId={}, dailyQuestionLimit={}, dailyTokenLimit={}, "
                        + "cleared={}, adminId={}, traceId={}",
                targetUserId,
                dailyQuestionLimit,
                dailyTokenLimit,
                cleared,
                adminId,
                traceId);
        return new AiQuota(targetUserId, username, dailyQuestionLimit, dailyTokenLimit, now, adminId);
    }

    private static KeysetCursor parseCursor(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        return KeysetCursor.decode(token).orElseThrow(() -> new BusinessException(
                ErrorCode.COM_VALIDATION_001,
                "invalid cursor",
                List.of(FieldViolation.of("cursor", "must come from a previous response"))));
    }
}
