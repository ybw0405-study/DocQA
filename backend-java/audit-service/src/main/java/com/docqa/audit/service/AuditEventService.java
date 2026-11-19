package com.docqa.audit.service;

import com.docqa.audit.config.AuditProperties;
import com.docqa.audit.domain.AuditEventRow;
import com.docqa.audit.dto.AuditEventMessage;
import com.docqa.audit.idempotency.MqIdempotencyStore;
import com.docqa.audit.repository.AuditEventRepository;
import com.docqa.common.trace.TraceIdContext;
import com.docqa.common.tenant.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class AuditEventService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AuditEventService.class);

    private final AuditEventNormalizer normalizer;
    private final AuditEventRepository repository;
    private final MqIdempotencyStore idempotencyStore;
    private final AuditProperties properties;
    private final Clock clock;

    public AuditEventService(
            AuditEventNormalizer normalizer,
            AuditEventRepository repository,
            MqIdempotencyStore idempotencyStore,
            AuditProperties properties,
            Clock clock) {
        this.normalizer = normalizer;
        this.repository = repository;
        this.idempotencyStore = idempotencyStore;
        this.properties = properties;
        this.clock = clock;
    }

    public IngestOutcome ingest(AuditEventMessage message, String traceIdProperty) {
        Instant now = clock.instant();
        AuditEventNormalizer.NormalizedAuditEvent normalized =
                normalizer.normalize(message, traceIdProperty, now);
        AuditEventRow event = normalized.event();
        if (!normalized.warnings().isEmpty()) {
            LOGGER.warn("Audit event payload trimmed: eventId={}, fields={}", event.id(), normalized.warnings());
        }
        if (idempotencyStore.isProcessed(event.id())) {
            LOGGER.debug("Duplicate audit event ignored from Redis: eventId={}", event.id());
            return IngestOutcome.DUPLICATE;
        }
        UUID tenantId = message.tenantId();
        if (tenantId == null) {
            // 部署窗口内可能还有不带 tenantId 的在途旧消息；P0 存量数据全在默认租户，
            // 按默认租户收敛并告警，新消息必须携带 tenantId。
            LOGGER.warn(
                    "Audit event missing tenantId, falling back to default tenant: eventId={}", event.id());
            tenantId = TenantContext.DEFAULT_TENANT_ID;
        }
        try {
            repository.insert(tenantId, event);
        } catch (DuplicateKeyException ex) {
            idempotencyStore.markProcessed(event.id());
            LOGGER.debug("Duplicate audit event ignored from the database: eventId={}", event.id());
            return IngestOutcome.DUPLICATE;
        }
        idempotencyStore.markProcessed(event.id());
        LOGGER.info(
                "Audit event ingested: eventId={}, traceId={}, eventType={}",
                event.id(),
                event.traceId(),
                event.eventType());
        return IngestOutcome.INGESTED;
    }

    public List<AuditEventRow> findByTraceId(String traceId, Integer limit) {
        String normalizedTraceId = traceId == null ? "" : traceId.trim().toLowerCase(Locale.ROOT);
        if (!TraceIdContext.isValid(normalizedTraceId)) {
            throw new IllegalArgumentException("traceId must be 32 lowercase hexadecimal characters");
        }
        int effectiveLimit = limit == null ? properties.getQueryDefaultLimit() : limit;
        if (effectiveLimit < 1 || effectiveLimit > properties.getQueryMaxLimit()) {
            throw new IllegalArgumentException(
                    "limit must be between 1 and " + properties.getQueryMaxLimit());
        }
        // 本查询目前没有 HTTP 入口：audit-service 是纯 MQ 消费者，管理面审计查询走
        // agent-gateway 的 AdminAuditQueryService（已按 tenantId 过滤）。这是 §5.11 记录的有意
        // 边界——待本服务暴露按 traceId 的查询端点时，再让调用方传入 tenantId 一并收口；
        // 在此之前该方法仅服务于默认租户视图，不随写入侧的租户传播改动，避免凭空变更语义。
        return repository.findByTraceId(TenantContext.DEFAULT_TENANT_ID, normalizedTraceId, effectiveLimit);
    }

    public enum IngestOutcome {
        INGESTED,
        DUPLICATE
    }
}
