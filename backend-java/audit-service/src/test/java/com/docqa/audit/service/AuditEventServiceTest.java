package com.docqa.audit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.docqa.audit.config.AuditProperties;
import com.docqa.audit.domain.AuditEventRow;
import com.docqa.audit.dto.AuditEventMessage;
import com.docqa.audit.idempotency.MqIdempotencyStore;
import com.docqa.audit.repository.AuditEventRepository;
import com.docqa.common.tenant.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

@ExtendWith(MockitoExtension.class)
class AuditEventServiceTest {

    private static final UUID TENANT_ID = TenantContext.DEFAULT_TENANT_ID;
    private static final UUID EVENT_ID = UUID.fromString("0192f3a4-a1b2-7c33-9d01-1234567890ab");
    private static final UUID USER_ID = UUID.fromString("0192f3a4-7b2c-7d10-9e8f-1234567890ab");
    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final Instant NOW = Instant.parse("2026-10-20T10:00:00Z");

    @Mock
    private AuditEventRepository repository;
    @Mock
    private MqIdempotencyStore idempotencyStore;

    private final AuditProperties properties = new AuditProperties();
    private AuditEventService service;

    @BeforeEach
    void setUp() {
        service = new AuditEventService(
                new AuditEventNormalizer(new AuditPayloadSanitizer(), properties),
                repository,
                idempotencyStore,
                properties,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void insertsAnAuditEventAndMarksItProcessed() {
        AuditEventService.IngestOutcome outcome = service.ingest(message(), null);

        ArgumentCaptor<AuditEventRow> captor = ArgumentCaptor.forClass(AuditEventRow.class);
        verify(repository).insert(eq(TENANT_ID), captor.capture());
        AuditEventRow row = captor.getValue();
        assertThat(row.id()).isEqualTo(EVENT_ID);
        assertThat(row.traceId()).isEqualTo(TRACE_ID);
        assertThat(row.userId()).isEqualTo(USER_ID);
        assertThat(row.eventType()).isEqualTo("RAG_RETRIEVED");
        assertThat(row.source()).isEqualTo("PYTHON");
        assertThat(row.severity()).isEqualTo("INFO");
        assertThat(row.payloadRedacted()).containsEntry("chunkCount", 6L);
        assertThat(row.ingestedAt()).isEqualTo(NOW);
        assertThat(outcome).isEqualTo(AuditEventService.IngestOutcome.INGESTED);
        verify(idempotencyStore).markProcessed(EVENT_ID);
    }

    @Test
    void skipsTheDatabaseWhenRedisAlreadyKnowsTheEvent() {
        when(idempotencyStore.isProcessed(EVENT_ID)).thenReturn(true);

        AuditEventService.IngestOutcome outcome = service.ingest(message(), null);

        assertThat(outcome).isEqualTo(AuditEventService.IngestOutcome.DUPLICATE);
        verify(repository, never()).insert(any(), any());
    }

    @Test
    void treatsADatabaseDuplicateAsSuccessAndRefreshesTheRedisMarker() {
        when(idempotencyStore.isProcessed(EVENT_ID)).thenReturn(false);
        doThrow(new DuplicateKeyException("duplicate")).when(repository).insert(any(), any());

        AuditEventService.IngestOutcome outcome = service.ingest(message(), null);

        assertThat(outcome).isEqualTo(AuditEventService.IngestOutcome.DUPLICATE);
        verify(idempotencyStore).markProcessed(EVENT_ID);
    }

    @Test
    void rejectsInvalidCoreFieldsBeforeTouchingStorage() {
        AuditEventMessage invalid = new AuditEventMessage(
                null, "RAG_RETRIEVED", "rag", "PYTHON", "INFO",
                NOW, TRACE_ID, USER_ID, null, Map.of());

        assertThatThrownBy(() -> service.ingest(invalid, null))
                .isInstanceOf(AuditEventRejectedException.class)
                .hasMessage("missing eventId");
        verifyNoInteractions(repository, idempotencyStore);
    }

    @Test
    void propagatesStorageFailuresSoTheConsumerCanRetry() {
        when(idempotencyStore.isProcessed(EVENT_ID)).thenReturn(false);
        doThrow(new IllegalStateException("database down")).when(repository).insert(any(), any());

        assertThatThrownBy(() -> service.ingest(message(), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database down");
    }

    @Test
    void queriesByNormalizedTraceIdWithTheDefaultLimit() {
        when(repository.findByTraceId(TENANT_ID, TRACE_ID, properties.getQueryDefaultLimit())).thenReturn(List.of());

        List<AuditEventRow> result = service.findByTraceId(TRACE_ID.toUpperCase(), null);

        assertThat(result).isEmpty();
        verify(repository).findByTraceId(TENANT_ID, TRACE_ID, properties.getQueryDefaultLimit());
    }

    @Test
    void queriesByTraceIdWithAnExplicitLimit() {
        when(repository.findByTraceId(eq(TENANT_ID), eq(TRACE_ID), eq(25))).thenReturn(List.of());

        service.findByTraceId(TRACE_ID, 25);

        verify(repository).findByTraceId(TENANT_ID, TRACE_ID, 25);
    }

    @Test
    void rejectsInvalidQueryParameters() {
        assertThatThrownBy(() -> service.findByTraceId("not-a-trace", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.findByTraceId(null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.findByTraceId(TRACE_ID, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.findByTraceId(TRACE_ID, properties.getQueryMaxLimit() + 1))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).findByTraceId(any(), any(), anyInt());
    }

    private AuditEventMessage message() {
        return new AuditEventMessage(
                EVENT_ID,
                "RAG_RETRIEVED",
                "rag_retriever",
                "PYTHON",
                "INFO",
                NOW.minusSeconds(1),
                TRACE_ID,
                USER_ID,
                UUID.fromString("0192f3a4-8c3d-7e21-8f90-abcdef123456"),
                Map.of("chunkCount", 6, "durationMs", 182));
    }
}
