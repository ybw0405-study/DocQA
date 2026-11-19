package com.docqa.agentgateway.messaging;

import com.docqa.agentgateway.config.AuditProperties;
import com.docqa.agentgateway.domain.OutboxBacklog;
import com.docqa.agentgateway.domain.OutboxExecution;
import com.docqa.agentgateway.dto.AuditEvent;
import com.docqa.agentgateway.repository.AgentExecutionRepository;
import com.docqa.common.metrics.BusinessMetrics;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AuditOutboxRelay {

    public static final String CURSOR_KEY = "audit:relay:cursor";

    private static final Logger LOGGER = LoggerFactory.getLogger(AuditOutboxRelay.class);
    private static final String CURSOR_SEPARATOR = "|";

    private final AgentExecutionRepository repository;
    private final AuditEventPublisher publisher;
    private final StringRedisTemplate redisTemplate;
    private final AuditProperties properties;
    private final BusinessMetrics metrics;

    public AuditOutboxRelay(
            AgentExecutionRepository repository,
            AuditEventPublisher publisher,
            StringRedisTemplate redisTemplate,
            AuditProperties properties,
            BusinessMetrics metrics) {
        this.repository = repository;
        this.publisher = publisher;
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.metrics = metrics;
    }

    @Scheduled(
            fixedDelayString = "${docqa.audit.relay-interval-ms:5000}",
            initialDelayString = "${docqa.audit.relay-interval-ms:5000}")
    public void relay() {
        Cursor cursor = null;
        try {
            cursor = readCursor();
            // relay 是全局定时任务：按单一游标跨租户扫描 outbox，每条行的 tenant_id 随消息下发。
            // 租户边界在写侧逐行保证，投递侧不再回落默认租户。
            List<OutboxExecution> executions = repository.findOutboxBatch(
                    cursor == null ? null : cursor.recordedAt(),
                    cursor == null ? null : cursor.id(),
                    properties.getRelayBatchSize());
            relayBatch(executions);
        } catch (RuntimeException ex) {
            LOGGER.error("Audit outbox relay failed: type={}", ex.getClass().getName());
        } finally {
            // 放在 finally：relay 失败时积压指标必须仍然刷新，否则「relay 一直抛异常」这种
            // 最需要被看见的状态，反而会让 pending 停在最后一次成功时的读数上。
            // 游标向下传递而不是重读：readCursor 对畸形游标有删除副作用，重读会做两次。
            refreshBacklog(cursor);
        }
    }

    private void refreshBacklog(Cursor cursor) {
        try {
            // 积压指标按全局游标统计（与投递扫描同一口径），不按单租户回落。
            OutboxBacklog backlog = repository.outboxBacklog(
                    cursor == null ? null : cursor.recordedAt(),
                    cursor == null ? null : cursor.id());
            Instant oldest = backlog.oldestRecordedAt();
            metrics.auditOutboxBacklog(
                    backlog.pending(),
                    oldest == null ? null : Duration.between(oldest, Instant.now()));
        } catch (RuntimeException ex) {
            // 指标刷新失败不得影响 relay 本身：降级为一条 warn，保留上一轮读数。
            LOGGER.warn("Unable to refresh audit outbox backlog metric: type={}", ex.getClass().getName());
        }
    }

    private void relayBatch(List<OutboxExecution> executions) {
        for (OutboxExecution execution : executions) {
            if (execution.events() == null || execution.events().isEmpty()) {
                LOGGER.warn("Skipping empty audit outbox: executionId={}", execution.id());
                writeCursor(execution);
                continue;
            }
            for (AuditEvent event : execution.events()) {
                // outbox 行的 tenant_id 是租户归属的权威来源；覆盖事件里可能残留的旧值（含 null）。
                publisher.publish(event.withTenant(execution.tenantId()));
            }
            writeCursor(execution);
        }
    }

    private Cursor readCursor() {
        String value = redisTemplate.opsForValue().get(CURSOR_KEY);
        if (value == null || value.isBlank()) {
            return null;
        }
        String[] parts = value.split("\\|", 2);
        if (parts.length != 2) {
            LOGGER.warn("Discarding malformed audit relay cursor");
            redisTemplate.delete(CURSOR_KEY);
            return null;
        }
        try {
            return new Cursor(Instant.ofEpochMilli(Long.parseLong(parts[0])), UUID.fromString(parts[1]));
        } catch (RuntimeException ex) {
            LOGGER.warn("Discarding malformed audit relay cursor");
            redisTemplate.delete(CURSOR_KEY);
            return null;
        }
    }

    private void writeCursor(OutboxExecution execution) {
        redisTemplate.opsForValue().set(
                CURSOR_KEY,
                execution.recordedAt().toEpochMilli() + CURSOR_SEPARATOR + execution.id());
    }

    private record Cursor(Instant recordedAt, UUID id) {
    }
}
