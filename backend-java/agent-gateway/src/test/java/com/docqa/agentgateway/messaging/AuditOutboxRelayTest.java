package com.docqa.agentgateway.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.docqa.agentgateway.config.AuditProperties;
import com.docqa.agentgateway.domain.OutboxBacklog;
import com.docqa.agentgateway.domain.OutboxExecution;
import com.docqa.agentgateway.domain.RelayCursor;
import com.docqa.agentgateway.dto.AuditEvent;
import com.docqa.agentgateway.repository.AgentExecutionRepository;
import com.docqa.common.metrics.BusinessMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AuditOutboxRelayTest {

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final Instant RECORDED_AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final UUID EXECUTION_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Mock
    private AgentExecutionRepository repository;
    @Mock
    private AuditEventPublisher publisher;

    private AuditOutboxRelay relay;
    private BusinessMetrics metrics;

    @BeforeEach
    void setUp() {
        metrics = new BusinessMetrics(new SimpleMeterRegistry());
        relay = new AuditOutboxRelay(repository, publisher, new AuditProperties(), metrics);
        // relay 每轮都会刷新积压指标；默认给一个「已追平」的快照，避免各用例都要重复 stub。
        lenient()
                .when(repository.outboxBacklog(any(), any()))
                .thenReturn(new OutboxBacklog(0, null));
    }

    @Test
    void publishesEventsStampedWithOutboxTenantAndAdvancesCursor() {
        AuditEvent first = event("AGENT_ASK_COMPLETED");
        AuditEvent second = event("AGENT_CACHE_HIT");
        when(repository.findOutboxBatch(null, null, 100))
                .thenReturn(List.of(execution(List.of(first, second))));

        relay.relay();

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(publisher, org.mockito.Mockito.times(2)).publish(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(published -> {
            assertThat(published.tenantId()).isEqualTo(TENANT_ID);
            assertThat(published.eventId()).isIn(first.eventId(), second.eventId());
        });
        // 游标持久化到 DB，而不是可被 LRU 驱逐的 Redis
        verify(repository).saveRelayCursor(RECORDED_AT, EXECUTION_ID);
    }

    @Test
    void resumesFromStoredCursor() {
        UUID storedId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        when(repository.findRelayCursor()).thenReturn(new RelayCursor(Instant.ofEpochMilli(1000), storedId));
        when(repository.findOutboxBatch(any(Instant.class), any(UUID.class), anyInt()))
                .thenReturn(List.of());

        relay.relay();

        verify(repository).findOutboxBatch(Instant.ofEpochMilli(1000), storedId, 100);
    }

    @Test
    void noCursorStartsFromTheBeginning() {
        when(repository.findRelayCursor()).thenReturn(null);
        when(repository.findOutboxBatch(isNull(), isNull(), anyInt())).thenReturn(List.of());

        relay.relay();

        verify(repository).findOutboxBatch(null, null, 100);
    }

    @Test
    void skipsEmptyOutboxButAdvancesCursor() {
        when(repository.findOutboxBatch(null, null, 100))
                .thenReturn(List.of(execution(List.of())));

        relay.relay();

        verify(publisher, never()).publish(any());
        verify(repository).saveRelayCursor(RECORDED_AT, EXECUTION_ID);
    }

    @Test
    void swallowsRepositoryFailure() {
        when(repository.findOutboxBatch(any(), any(), anyInt()))
                .thenThrow(new IllegalStateException("db down"));

        assertThatCode(() -> relay.relay()).doesNotThrowAnyException();
        verify(publisher, never()).publish(any());
    }

    @Test
    void doesNotAdvanceCursorWhenPublishFails() {
        AuditEvent failing = event("AGENT_ASK_COMPLETED");
        when(repository.findOutboxBatch(null, null, 100))
                .thenReturn(List.of(execution(List.of(failing))));
        doThrow(new IllegalStateException("mq down")).when(publisher).publish(any());

        assertThatCode(() -> relay.relay()).doesNotThrowAnyException();

        verify(repository, never()).saveRelayCursor(any(), any());
    }

    private OutboxExecution execution(List<AuditEvent> events) {
        return new OutboxExecution(EXECUTION_ID, TENANT_ID, RECORDED_AT, events);
    }

    private AuditEvent event(String type) {
        // 工厂写入 outbox 时 tenantId 为 null；relay 必须按行的 tenant_id 覆盖。
        return new AuditEvent(
                UUID.randomUUID(), type, "agent_gateway", "JAVA", "INFO", RECORDED_AT,
                "4bf92f3577b34da6a3ce929d0e0e4736",
                UUID.randomUUID(), UUID.randomUUID(), Map.of(), null);
    }
}
