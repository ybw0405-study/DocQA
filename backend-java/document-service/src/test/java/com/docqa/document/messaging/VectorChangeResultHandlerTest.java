package com.docqa.document.messaging;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.docqa.common.tenant.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.docqa.document.config.DocumentProperties;
import com.docqa.document.domain.DocumentRow;
import com.docqa.document.domain.DocumentStatus;
import com.docqa.document.repository.DocumentRepository;
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

    private static final UUID TENANT_ID = TenantContext.DEFAULT_TENANT_ID;
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
                Clock.fixed(NOW, ZoneOffset.UTC));
        lenient().when(redisTemplate.hasKey(anyString())).thenReturn(false);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void aConfirmedDocumentDeletionRecordsTheRowCountReportedByPython() throws Exception {
        when(documentRepository.findById(TENANT_ID, DOCUMENT_ID))
                .thenReturn(Optional.of(document(true)));

        handler.handle(MESSAGE_ID, bytes("DELETE_DOCUMENT", null, "COMPLETED", 0L, null));

        verify(documentRepository).recordVectorConfirmation(TENANT_ID, DOCUMENT_ID, 0L, NOW);
        verify(valueOperations).set(
                anyString(), anyString(), eq(Duration.ofDays(7)));
    }

    @Test
    void aNonZeroRemainingCountIsStoredSoConsistencyReadsAsDiverged() throws Exception {
        when(documentRepository.findById(TENANT_ID, DOCUMENT_ID))
                .thenReturn(Optional.of(document(true)));

        handler.handle(MESSAGE_ID, bytes("DELETE_DOCUMENT", null, "COMPLETED", 7L, null));

        verify(documentRepository).recordVectorConfirmation(TENANT_ID, DOCUMENT_ID, 7L, NOW);
    }

    @Test
    void aFailedVectorChangeKeepsTheBusinessStateAndWritesNothing() throws Exception {
        when(documentRepository.findById(TENANT_ID, DOCUMENT_ID))
                .thenReturn(Optional.of(document(true)));

        handler.handle(MESSAGE_ID, bytes("DELETE_DOCUMENT", null, "FAILED", null, "VEC-DELETE-001"));

        verify(documentRepository, never()).recordVectorConfirmation(eq(TENANT_ID), any(), anyLong(), any());
    }

    @Test
    void aConfirmationWithoutARowCountLeavesTheReconciliationColumnsUnknown() throws Exception {
        when(documentRepository.findById(TENANT_ID, DOCUMENT_ID))
                .thenReturn(Optional.of(document(true)));

        handler.handle(MESSAGE_ID, bytes("DELETE_DOCUMENT", null, "COMPLETED", null, null));

        verify(documentRepository, never()).recordVectorConfirmation(eq(TENANT_ID), any(), anyLong(), any());
    }

    @Test
    void aConfirmationForADocumentThatIsNotDeletedIsIgnored() throws Exception {
        when(documentRepository.findById(TENANT_ID, DOCUMENT_ID))
                .thenReturn(Optional.of(document(false)));

        handler.handle(MESSAGE_ID, bytes("DELETE_DOCUMENT", null, "COMPLETED", 0L, null));

        verify(documentRepository, never()).recordVectorConfirmation(eq(TENANT_ID), any(), anyLong(), any());
    }

    @Test
    void aVersionCleanupConfirmationOnlyLogs() throws Exception {
        when(documentRepository.findById(TENANT_ID, DOCUMENT_ID))
                .thenReturn(Optional.of(document(false)));

        handler.handle(MESSAGE_ID, bytes("DELETE_VERSION", "v1", "COMPLETED", 0L, null));

        verify(documentRepository, never()).recordVectorConfirmation(eq(TENANT_ID), any(), anyLong(), any());
    }

    @Test
    void anUnknownDocumentIsSkipped() throws Exception {
        when(documentRepository.findById(TENANT_ID, DOCUMENT_ID)).thenReturn(Optional.empty());

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
        when(redisTemplate.hasKey(IDEMPOTENCY_KEY)).thenReturn(true);

        handler.handle(MESSAGE_ID, bytes("DELETE_DOCUMENT", null, "COMPLETED", 0L, null));

        verifyNoInteractions(documentRepository, valueOperations);
    }

    @Test
    void anUnparseablePayloadIsMarkedProcessedSoTheBrokerStopsRedelivering() {
        handler.handle(MESSAGE_ID, "{ not json".getBytes(StandardCharsets.UTF_8));

        verifyNoInteractions(documentRepository);
        verify(valueOperations).set(anyString(), anyString(), eq(Duration.ofDays(7)));
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
