package com.docqa.document.messaging;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.docqa.document.config.DocumentProperties;
import com.docqa.document.domain.DocumentRow;
import com.docqa.document.domain.DocumentStatus;
import com.docqa.document.domain.TenantDocument;
import com.docqa.document.repository.DocumentRepository;
import com.docqa.document.repository.VectorCleanupTaskRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class VectorChangeResultHandlerTest {

    private static final UUID TENANT_ID = UUID.fromString("0192f3a4-a1b2-7c33-9d01-1234567890ab");
    private static final UUID OTHER_TENANT_ID = UUID.fromString("0192f3a4-a1b2-7c33-9d01-1234567890ac");
    private static final UUID DOCUMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final String MESSAGE_ID = "vector-message-1";
    private static final String IDEMPOTENCY_KEY = "mq:idem:vector-change-completed:" + MESSAGE_ID;

    @Mock
    private DocumentRepository documentRepository;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private VectorCleanupTaskRepository cleanupTasks;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private VectorChangeResultHandler handler;

    @BeforeEach
    void setUp() {
        DocumentProperties properties = new DocumentProperties();
        properties.setMqIdempotencyTtl(Duration.ofDays(7));
        handler = new VectorChangeResultHandler(
                documentRepository,
                new MqIdempotencyMarker(redisTemplate, properties),
                objectMapper,
                Clock.fixed(NOW, ZoneOffset.UTC),
                cleanupTasks);
        lenient().when(redisTemplate.hasKey(anyString())).thenReturn(false);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        // 原子占位默认成功（首次处理）
        lenient().when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);
    }

    @Test
    void aConfirmedDocumentDeletionRecordsTheRowCountReportedByPython() throws Exception {
        givenDocument(document(true));

        handler.handle(MESSAGE_ID, bytes("DELETE_DOCUMENT", null, "COMPLETED", 0L, null));

        verify(documentRepository).recordVectorConfirmation(TENANT_ID, DOCUMENT_ID, 0L, NOW);
        verify(valueOperations).set(anyString(), anyString(), eq(Duration.ofDays(7)));
    }

    @Test
    void aNonZeroRemainingCountIsStoredSoConsistencyReadsAsDiverged() throws Exception {
        givenDocument(document(true));

        handler.handle(MESSAGE_ID, bytes("DELETE_DOCUMENT", null, "COMPLETED", 7L, null));

        verify(documentRepository).recordVectorConfirmation(TENANT_ID, DOCUMENT_ID, 7L, NOW);
    }

    @Test
    void confirmationWritesInTheTenantThatOwnsTheDocument() throws Exception {
        givenDocument(OTHER_TENANT_ID, document(true));

        handler.handle(MESSAGE_ID, bytes("DELETE_DOCUMENT", null, "COMPLETED", 3L, null));

        verify(documentRepository).recordVectorConfirmation(OTHER_TENANT_ID, DOCUMENT_ID, 3L, NOW);
        verify(documentRepository, never()).recordVectorConfirmation(eq(TENANT_ID), any(), anyLong(), any());
    }

    @Test
    void aFailedVectorChangeIsNotAckedAndIsReturnedToTheBroker() throws Exception {
        givenDocument(document(true));

        assertThrows(IllegalStateException.class,
                () -> handler.handle(MESSAGE_ID, bytes("DELETE_DOCUMENT", null, "FAILED", null, "VEC-DELETE-001")));

        // 失败的删除确认不得写幂等标记，否则 MQ 永远不会再给一次机会。
        verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
        verify(documentRepository, never()).recordVectorConfirmation(eq(TENANT_ID), any(), anyLong(), any());
        verify(cleanupTasks, never()).remove(any(), any(), anyString(), anyString());
    }

    @Test
    void aConfirmationWithoutARowCountLeavesTheReconciliationColumnsUnknown() throws Exception {
        givenDocument(document(true));

        handler.handle(MESSAGE_ID, bytes("DELETE_DOCUMENT", null, "COMPLETED", null, null));

        verify(documentRepository, never()).recordVectorConfirmation(eq(TENANT_ID), any(), anyLong(), any());
    }

    @Test
    void aConfirmationForADocumentThatIsNotDeletedIsIgnored() throws Exception {
        givenDocument(document(false));

        handler.handle(MESSAGE_ID, bytes("DELETE_DOCUMENT", null, "COMPLETED", 0L, null));

        verify(documentRepository, never()).recordVectorConfirmation(eq(TENANT_ID), any(), anyLong(), any());
    }

    @Test
    void aSuccessfulVersionCleanupRemovesTheOutboxRow() throws Exception {
        givenDocument(document(false));

        handler.handle(MESSAGE_ID, bytes("DELETE_VERSION", "v1", "COMPLETED", 0L, null));

        verify(documentRepository, never()).recordVectorConfirmation(eq(TENANT_ID), any(), anyLong(), any());
        // 只有成功确认才出队，之后不再重投。
        verify(cleanupTasks).remove(TENANT_ID, DOCUMENT_ID, "v1", "DELETE_VERSION");
    }

    @Test
    void aFailedVersionCleanupKeepsTheOutboxRowForRepublishing() throws Exception {
        givenDocument(document(false));

        assertThrows(IllegalStateException.class,
                () -> handler.handle(MESSAGE_ID, bytes("DELETE_VERSION", "v1", "FAILED", null, "VEC-DELETE-001")));

        verify(cleanupTasks, never()).remove(any(), any(), anyString(), anyString());
        verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void anUnknownDocumentIsSkipped() throws Exception {
        when(documentRepository.findTenantDocumentById(DOCUMENT_ID)).thenReturn(Optional.empty());

        handler.handle(MESSAGE_ID, bytes("DELETE_DOCUMENT", null, "COMPLETED", 0L, null));

        verify(documentRepository, never()).recordVectorConfirmation(eq(TENANT_ID), any(), anyLong(), any());
    }

    @Test
    void anUnknownOperationIsDroppedBeforeAnyRead() throws Exception {
        handler.handle(MESSAGE_ID, bytes("RENAME", null, "COMPLETED", 0L, null));

        verifyNoInteractions(documentRepository);
        verify(valueOperations).set(anyString(), anyString(), eq(Duration.ofDays(7)));
    }

    @Test
    void aRedeliveredConfirmationIsSkipped() throws Exception {
        // 重投时原子占位失败（false）表示已处理
        when(valueOperations.setIfAbsent(eq(IDEMPOTENCY_KEY), eq("1"), eq(Duration.ofDays(7))))
                .thenReturn(false);

        handler.handle(MESSAGE_ID, bytes("DELETE_DOCUMENT", null, "COMPLETED", 0L, null));

        verify(valueOperations).setIfAbsent(eq(IDEMPOTENCY_KEY), eq("1"), eq(Duration.ofDays(7)));
        verifyNoInteractions(documentRepository);
    }

    @Test
    void anUnparseablePayloadIsMarkedProcessedSoTheBrokerStopsRedelivering() {
        handler.handle(MESSAGE_ID, "{ not json".getBytes(StandardCharsets.UTF_8));

        verifyNoInteractions(documentRepository);
        verify(valueOperations).set(anyString(), anyString(), eq(Duration.ofDays(7)));
    }

    private void givenDocument(DocumentRow document) {
        givenDocument(TENANT_ID, document);
    }

    private void givenDocument(UUID tenantId, DocumentRow document) {
        when(documentRepository.findTenantDocumentById(DOCUMENT_ID))
                .thenReturn(Optional.of(new TenantDocument(tenantId, document)));
    }

    private byte[] bytes(String operation, String indexVersion, String status, Long remainingRows,
            String errorCode) throws Exception {
        return objectMapper.writeValueAsBytes(new VectorChangeCompletedEvent(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "0123456789abcdef0123456789abcdef",
                DOCUMENT_ID,
                operation,
                indexVersion,
                status,
                remainingRows,
                errorCode,
                null,
                NOW));
    }

    private static DocumentRow document(boolean deleted) {
        return new DocumentRow(
                DOCUMENT_ID,
                USER_ID,
                "notes.pdf",
                null,
                "application/pdf",
                1024,
                "d41d8cd98f00b204e9800998ecf8427e",
                "documents/" + USER_ID + "/" + DOCUMENT_ID + "/source",
                DocumentStatus.INDEXED,
                0,
                3,
                null,
                null,
                "v1",
                null,
                null,
                null,
                null,
                NOW,
                deleted ? NOW.minus(Duration.ofMinutes(30)) : null,
                NOW,
                NOW);
    }
}
