package com.docqa.agentgateway.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.docqa.agentgateway.config.AuditProperties;
import com.docqa.agentgateway.domain.OutboxBacklog;
import com.docqa.agentgateway.domain.OutboxExecution;
import com.docqa.agentgateway.dto.AuditEvent;
import com.docqa.agentgateway.repository.AgentExecutionRepository;
import com.docqa.common.metrics.BusinessMetrics;
import com.docqa.common.tenant.TenantContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class AuditOutboxRelayTest {

    private static final UUID TENANT_ID = TenantContext.DEFAULT_TENANT_ID;
    private static final Instant RECORDED_AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final UUID EXECUTION_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Mock
    private AgentExecutionRepository repository;
    @Mock
    private AuditEventPublisher publisher;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private AuditOutboxRelay relay;
    private BusinessMetrics metrics;

    @BeforeEach
    void setUp() {
        metrics = new BusinessMetrics(new SimpleMeterRegistry());
        relay = new AuditOutboxRelay(repository, publisher, redisTemplate, new AuditProperties(), metrics);
        // relay 每轮都会刷新积压指标；默认给一个「已追平」的快照，避免各用例都要重复 stub。
        lenient()
                .when(repository.outboxBacklog(eq(TENANT_ID), nullable(Instant.class), nullable(UUID.class)))
                .thenReturn(new OutboxBacklog(0, null));
    }

    @Test
    void publishesEventsAndAdvancesCursor() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(AuditOutboxRelay.CURSOR_KEY)).thenReturn(null);
        AuditEvent first = event("AGENT_ASK_COMPLETED");
        AuditEvent second = event("AGENT_CACHE_HIT");
        when(repository.findOutboxBatch(TENANT_ID, null, null, 100))
                .thenReturn(List.of(new OutboxExecution(EXECUTION_ID, RECORDED_AT, List.of(first, second))));

        relay.relay();

        verify(publisher).publish(first);
        verify(publisher).publish(second);
        verify(valueOperations).set(
                AuditOutboxRelay.CURSOR_KEY, RECORDED_AT.toEpochMilli() + "|" + EXECUTION_ID);
    }

    @Test
    void resumesFromStoredCursor() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(AuditOutboxRelay.CURSOR_KEY))
                .thenReturn("1000|00000000-0000-0000-0000-000000000002");
        when(repository.findOutboxBatch(eq(TENANT_ID), any(Instant.class), any(UUID.class), anyInt()))
                .thenReturn(List.of());

        relay.relay();

        verify(repository).findOutboxBatch(
                TENANT_ID,
                Instant.ofEpochMilli(1000),
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                100);
    }

    @Test
    void discardsMalformedCursorAndReplaysFromStart() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(AuditOutboxRelay.CURSOR_KEY)).thenReturn("broken-cursor");
        when(repository.findOutboxBatch(eq(TENANT_ID), isNull(), isNull(), anyInt())).thenReturn(List.of());

        relay.relay();

        verify(redisTemplate).delete(AuditOutboxRelay.CURSOR_KEY);
        verify(repository).findOutboxBatch(TENANT_ID, null, null, 100);
    }

    @Test
    void skipsEmptyOutboxButAdvancesCursor() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(AuditOutboxRelay.CURSOR_KEY)).thenReturn(null);
        when(repository.findOutboxBatch(TENANT_ID, null, null, 100))
                .thenReturn(List.of(new OutboxExecution(EXECUTION_ID, RECORDED_AT, List.of())));

        relay.relay();

        verify(publisher, never()).publish(any());
        verify(valueOperations).set(AuditOutboxRelay.CURSOR_KEY, RECORDED_AT.toEpochMilli() + "|" + EXECUTION_ID);
    }

    @Test
    void swallowsRepositoryFailure() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(AuditOutboxRelay.CURSOR_KEY)).thenReturn(null);
        when(repository.findOutboxBatch(any(), any(), any(), anyInt()))
                .thenThrow(new IllegalStateException("db down"));

        assertThatCode(() -> relay.relay()).doesNotThrowAnyException();
        verify(publisher, never()).publish(any());
    }

    @Test
    void doesNotAdvanceCursorWhenPublishFails() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(AuditOutboxRelay.CURSOR_KEY)).thenReturn(null);
        AuditEvent failing = event("AGENT_ASK_COMPLETED");
        when(repository.findOutboxBatch(TENANT_ID, null, null, 100))
                .thenReturn(List.of(new OutboxExecution(EXECUTION_ID, RECORDED_AT, List.of(failing))));
        org.mockito.Mockito.doThrow(new IllegalStateException("mq down")).when(publisher).publish(failing);

        assertThatCode(() -> relay.relay()).doesNotThrowAnyException();

        verify(valueOperations, never()).set(eq(AuditOutboxRelay.CURSOR_KEY), anyString());
    }

    private AuditEvent event(String type) {
        return new AuditEvent(
                UUID.randomUUID(), type, "agent_gateway", "JAVA", "INFO", RECORDED_AT,
                "4bf92f3577b34da6a3ce929d0e0e4736",
                UUID.randomUUID(), UUID.randomUUID(), Map.of());
    }
}
