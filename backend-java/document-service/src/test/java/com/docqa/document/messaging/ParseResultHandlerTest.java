package com.docqa.document.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.docqa.common.metrics.BusinessMetrics;
import com.docqa.document.config.DocumentProperties;
import com.docqa.document.domain.ChunkEntry;
import com.docqa.document.domain.DocumentRow;
import com.docqa.document.domain.DocumentStatus;
import com.docqa.document.domain.TenantDocument;
import com.docqa.document.repository.ChunkRepository;
import com.docqa.document.repository.DocumentRepository;
import com.docqa.document.repository.VectorCleanupTaskRepository;
import com.docqa.document.transaction.TransactionRunner;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class ParseResultHandlerTest {

    private static final UUID TENANT_ID = UUID.fromString("0192f3a4-a1b2-7c33-9d01-1234567890ab");
    private static final UUID DOCUMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String OBJECT_KEY = "documents/" + USER_ID + "/" + DOCUMENT_ID + "/source";
    private static final String MD5 = "d41d8cd98f00b204e9800998ecf8427e";
    private static final String REBUILD_FAILURE_MESSAGE = "重建索引失败，已保留原索引版本，可稍后再次发起重建。";
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final String MESSAGE_ID = "message-1";
    private static final String TRACE_ID = "0123456789abcdef0123456789abcdef";

    @Mock
    private DocumentRepository documentRepository;
    @Mock
    private ChunkRepository chunkRepository;
    @Mock
    private DocumentEventPublisher documentEventPublisher;
    @Mock
    private TransactionRunner transactionRunner;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private VectorCleanupTaskRepository cleanupTasks;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private ParseResultHandler handler;

    @BeforeEach
    void setUp() {
        DocumentProperties properties = new DocumentProperties();
        properties.setMqIdempotencyTtl(Duration.ofDays(7));
        handler = new ParseResultHandler(
                documentRepository,
                chunkRepository,
                documentEventPublisher,
                transactionRunner,
                new MqIdempotencyMarker(redisTemplate, properties),
                objectMapper,
                Clock.fixed(NOW, ZoneOffset.UTC),
                new BusinessMetrics(new SimpleMeterRegistry()),
                cleanupTasks);

        lenient().when(transactionRunner.execute(any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(0)).get());
        lenient().when(redisTemplate.hasKey(anyString())).thenReturn(false);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        // 原子占位默认成功（首次处理），与原 hasKey=false 语义一致
        lenient().when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);
    }

    @Test
    void completedEventWritesChunksAndMarksIndexed() throws Exception {
        givenDocument(document(DocumentStatus.PARSING, 0));
        when(documentRepository.markIndexed(TENANT_ID, DOCUMENT_ID, "v1", null, NOW)).thenReturn(1);

        handler.handleCompleted(MESSAGE_ID, bytes(completed("v1", null, chunk(0))));

        ArgumentCaptor<List<ChunkEntry>> chunks = chunksCaptor();
        verify(chunkRepository).upsertAll(eq(TENANT_ID), chunks.capture());
        assertThat(chunks.getValue()).hasSize(1);
        assertThat(chunks.getValue().get(0).chunkIndex()).isZero();
        assertThat(chunks.getValue().get(0).indexVersion()).isEqualTo("v1");
        verify(documentRepository).markIndexed(TENANT_ID, DOCUMENT_ID, "v1", null, NOW);
        verify(documentRepository, never()).promotePendingIndexVersion(eq(TENANT_ID), any(), anyString(), any(), any());
        verifyProcessedMarker("parse.completed");
    }

    @Test
    void completedEventWritesInTheTenantThatOwnsTheDocument() throws Exception {
        UUID owningTenant = UUID.fromString("0192f3a4-a1b2-7c33-9d01-1234567890ac");
        DocumentRow row = document(DocumentStatus.PARSING, 0);
        when(documentRepository.findTenantDocumentById(DOCUMENT_ID))
                .thenReturn(Optional.of(new TenantDocument(owningTenant, row)));
        when(documentRepository.findById(owningTenant, DOCUMENT_ID)).thenReturn(Optional.of(row));
        when(documentRepository.markIndexed(owningTenant, DOCUMENT_ID, "v1", null, NOW)).thenReturn(1);

        handler.handleCompleted(MESSAGE_ID, bytes(completed("v1", null, chunk(0))));

        verify(chunkRepository).upsertAll(eq(owningTenant), any());
        verify(documentRepository).markIndexed(owningTenant, DOCUMENT_ID, "v1", null, NOW);
        verify(documentRepository, never()).markIndexed(eq(TENANT_ID), any(), anyString(), any(), any());
    }

    @Test
    void completedEventStoresTheVectorRowCountReportedByPython() throws Exception {
        givenDocument(document(DocumentStatus.PARSING, 0));
        when(documentRepository.markIndexed(TENANT_ID, DOCUMENT_ID, "v1", 7L, NOW)).thenReturn(1);

        handler.handleCompleted(MESSAGE_ID, bytes(completed("v1", 7L, chunk(0))));

        verify(documentRepository).markIndexed(TENANT_ID, DOCUMENT_ID, "v1", 7L, NOW);
    }

    @Test
    void completedEventPromotesAPendingVersionAndQueuesTheOldVersionForDeletion() throws Exception {
        DocumentRow rebuilding = row(DocumentStatus.INDEXED, 0, "v1", "v2", null);
        givenDocument(rebuilding);
        when(documentRepository.promotePendingIndexVersion(TENANT_ID, DOCUMENT_ID, "v2", null, NOW)).thenReturn(1);

        handler.handleCompleted(MESSAGE_ID, bytes(completed("v2", null, chunk(0))));

        ArgumentCaptor<List<ChunkEntry>> chunks = chunksCaptor();
        verify(chunkRepository).upsertAll(eq(TENANT_ID), chunks.capture());
        assertThat(chunks.getValue().get(0).indexVersion()).isEqualTo("v2");
        verify(chunkRepository).deleteByVersion(TENANT_ID, DOCUMENT_ID, "v1");
        verify(documentRepository, never()).markIndexed(eq(TENANT_ID), any(), anyString(), any(), any());
        ArgumentCaptor<VectorChangeRequestedEvent> event =
                ArgumentCaptor.forClass(VectorChangeRequestedEvent.class);
        verify(documentEventPublisher).publishVectorChangeRequested(event.capture());
        assertThat(event.getValue().operation()).isEqualTo("DELETE_VERSION");
        assertThat(event.getValue().indexVersion()).isEqualTo("v1");
        assertThat(event.getValue().documentId()).isEqualTo(DOCUMENT_ID);
        assertThat(event.getValue().userId()).isEqualTo(USER_ID);
        // 发布前必须先把待确认删除落进 outbox，发布失败才有重投依据。
        verify(cleanupTasks).enqueue(TENANT_ID, DOCUMENT_ID, USER_ID, "v1", "DELETE_VERSION", NOW);
    }

    @Test
    void completedEventForASoftDeletedDocumentIsDroppedWithoutAnyWrite() throws Exception {
        givenDocument(row(DocumentStatus.INDEXED, 0, "v1", null, NOW));

        handler.handleCompleted(MESSAGE_ID, bytes(completed("v1", null, chunk(0))));

        verifyNoInteractions(chunkRepository);
        verify(documentRepository, never()).markIndexed(eq(TENANT_ID), any(), anyString(), any(), any());
        verify(documentRepository, never()).promotePendingIndexVersion(eq(TENANT_ID), any(), anyString(), any(), any());
        verifyProcessedMarker("parse.completed");
    }

    @Test
    void staleCompletedEventForAnAlreadySupersededVersionIsIgnored() throws Exception {
        givenDocument(row(DocumentStatus.INDEXED, 0, "v2", null, null));

        handler.handleCompleted(MESSAGE_ID, bytes(completed("v1", null, chunk(0))));

        verifyNoInteractions(chunkRepository);
        verify(documentEventPublisher, never()).publishVectorChangeRequested(any());
        verifyProcessedMarker("parse.completed");
    }

    @Test
    void invalidChunksFailANormalParseAndKeepTheDocumentUnindexed() throws Exception {
        givenDocument(document(DocumentStatus.PARSING, 0));
        when(documentRepository.markFailed(TENANT_ID, DOCUMENT_ID, "DOC-VALIDATION-004", "解析结果不完整，请重新上传。"))
                .thenReturn(1);

        handler.handleCompleted(MESSAGE_ID, bytes(completed("v1", null, chunk(0), chunk(0))));

        verifyNoInteractions(chunkRepository);
        verify(documentRepository).markFailed(TENANT_ID, DOCUMENT_ID, "DOC-VALIDATION-004", "解析结果不完整，请重新上传。");
    }

    @Test
    void invalidChunksDuringARebuildOnlyAbortThePendingVersion() throws Exception {
        givenDocument(row(DocumentStatus.INDEXED, 0, "v1", "v2", null));

        handler.handleCompleted(MESSAGE_ID, bytes(completed("v2", null, (ParsedChunk[]) null)));

        verify(documentRepository).abortReindex(TENANT_ID, 
                DOCUMENT_ID, "v2", "DOC-VALIDATION-004", "解析结果不完整，请重新上传。");
        verify(documentRepository, never()).markFailed(eq(TENANT_ID), any(), anyString(), anyString());
    }

    @Test
    void failedEventDuringARebuildKeepsTheOldIndexVersionAndDoesNotRetry() throws Exception {
        givenDocument(row(DocumentStatus.INDEXED, 0, "v1", "v2", null));

        handler.handleFailed(MESSAGE_ID, bytes(failed("v2", "DOC-PARSE-001", "embedding timeout", true)));

        verify(documentRepository).abortReindex(TENANT_ID, DOCUMENT_ID, "v2", "DOC-PARSE-001", REBUILD_FAILURE_MESSAGE);
        verify(documentRepository, never()).markFailed(eq(TENANT_ID), any(), anyString(), anyString());
        verify(documentRepository, never()).incrementRetryAndMarkParsing(eq(TENANT_ID), any(), any());
        verify(documentEventPublisher, never()).publishParseRequested(any(ParseRequestedEvent.class), anyInt());
    }

    @Test
    void retryableFailureRepublishesWithDelay() throws Exception {
        givenDocument(document(DocumentStatus.PARSING, 0));
        // applyIndexed / retry 判定读到的最新行
        when(documentRepository.findById(TENANT_ID, DOCUMENT_ID))
                .thenReturn(Optional.of(document(DocumentStatus.PARSING, 1)));
        when(documentRepository.incrementRetryAndMarkParsing(TENANT_ID, DOCUMENT_ID, NOW)).thenReturn(1);

        handler.handleFailed(MESSAGE_ID, bytes(failed("v1", "DOC-VALIDATION-003", "encrypted", true)));

        verify(documentEventPublisher).publishParseRequested(any(ParseRequestedEvent.class), eq(4));
        verifyProcessedMarker("parse.failed");
    }

    @Test
    void exhaustedRetryMarksDocumentDead() throws Exception {
        givenDocument(document(DocumentStatus.PARSING, 3));
        when(documentRepository.incrementRetryAndMarkParsing(TENANT_ID, DOCUMENT_ID, NOW)).thenReturn(0);

        handler.handleFailed(MESSAGE_ID, bytes(failed("v1", "COM-INTERNAL-001", "parser failed", true)));

        verify(documentRepository).markDead(eq(TENANT_ID), eq(DOCUMENT_ID), eq("COM-INTERNAL-001"), anyString());
        verify(documentEventPublisher, never()).publishParseRequested(any(ParseRequestedEvent.class), anyInt());
    }

    @Test
    void permanentFailureMarksFailedWithoutRetry() throws Exception {
        givenDocument(document(DocumentStatus.PARSING, 0));
        when(documentRepository.markFailed(TENANT_ID, DOCUMENT_ID, "DOC-VALIDATION-003", "encrypted")).thenReturn(1);

        handler.handleFailed(MESSAGE_ID, bytes(failed("v1", "DOC-VALIDATION-003", "encrypted", false)));

        verify(documentRepository).markFailed(TENANT_ID, DOCUMENT_ID, "DOC-VALIDATION-003", "encrypted");
        verify(documentEventPublisher, never()).publishParseRequested(any(ParseRequestedEvent.class));
    }

    @Test
    void unrecognisedErrorCodeFallsBackToTheInternalCode() throws Exception {
        givenDocument(document(DocumentStatus.PARSING, 0));
        when(documentRepository.markFailed(TENANT_ID, DOCUMENT_ID, "COM-INTERNAL-001", "解析失败，请稍后重试。")).thenReturn(1);

        handler.handleFailed(MESSAGE_ID, bytes(failed("v1", "lowercase code", null, false)));

        verify(documentRepository).markFailed(TENANT_ID, DOCUMENT_ID, "COM-INTERNAL-001", "解析失败，请稍后重试。");
    }

    @Test
    void aRedeliveredMessageIsSkippedBeforeAnyDatabaseRead() throws Exception {
        // 重投时原子占位失败（返回 false）表示已处理，应在读库前跳过
        when(valueOperations.setIfAbsent(eq("mq:idem:parse.completed:" + MESSAGE_ID),
                eq("1"), eq(Duration.ofDays(7)))).thenReturn(false);

        handler.handleCompleted(MESSAGE_ID, bytes(completed("v1", null, chunk(0))));

        // 原子占位被调用（判定为重复），但没有任何业务读库
        verify(valueOperations).setIfAbsent(eq("mq:idem:parse.completed:" + MESSAGE_ID),
                eq("1"), eq(Duration.ofDays(7)));
        verifyNoInteractions(documentRepository, chunkRepository, documentEventPublisher);
    }

    @Test
    void anUnparseablePayloadIsMarkedProcessedSoTheBrokerStopsRedelivering() {
        handler.handleCompleted(MESSAGE_ID, "{ this is not json".getBytes(StandardCharsets.UTF_8));

        verifyNoInteractions(documentRepository, chunkRepository);
        verifyProcessedMarker("parse.completed");
    }

    @Test
    void aCompletedEventWithoutDocumentIdIsDropped() throws Exception {
        ParseCompletedEvent event = new ParseCompletedEvent(
                UUID.randomUUID(), TRACE_ID, null, "v1", "INDEXED", List.of(chunk(0)), null, NOW);

        handler.handleCompleted(MESSAGE_ID, bytes(event));

        verifyNoInteractions(documentRepository, chunkRepository);
        verifyProcessedMarker("parse.completed");
    }


    private void givenDocument(DocumentRow document) {
        when(documentRepository.findTenantDocumentById(DOCUMENT_ID))
                .thenReturn(Optional.of(new TenantDocument(TENANT_ID, document)));
        // 只有走到 applyIndexed / 重试判定才会再读一次，其他路径不应因未使用而报严格桩错误
        lenient().when(documentRepository.findById(TENANT_ID, DOCUMENT_ID)).thenReturn(Optional.of(document));
    }

    private void verifyProcessedMarker(String topic) {
        verify(valueOperations).set(
                eq("mq:idem:" + topic + ":" + MESSAGE_ID), eq("1"), eq(Duration.ofDays(7)));
    }

    private byte[] bytes(Object event) throws Exception {
        return objectMapper.writeValueAsBytes(event);
    }

    private static ParseCompletedEvent completed(String indexVersion, Long vectorRows, ParsedChunk... chunks) {
        return new ParseCompletedEvent(
                UUID.randomUUID(),
                TRACE_ID,
                DOCUMENT_ID,
                indexVersion,
                "INDEXED",
                chunks == null ? null : List.of(chunks),
                vectorRows,
                NOW);
    }

    private static ParseFailedEvent failed(String indexVersion, String errorCode, String errorMessage,
            boolean retryable) {
        return new ParseFailedEvent(
                UUID.randomUUID(), TRACE_ID, DOCUMENT_ID, indexVersion, errorCode, errorMessage, retryable, NOW);
    }

    private static ParsedChunk chunk(int index) {
        return new ParsedChunk(index, UUID.randomUUID().toString(), "a".repeat(64), 120);
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<ChunkEntry>> chunksCaptor() {
        return ArgumentCaptor.forClass(List.class);
    }

    private static DocumentRow document(DocumentStatus status, int retryCount) {
        return row(status, retryCount, "v1", null, null);
    }

    private static DocumentRow row(DocumentStatus status, int retryCount, String indexVersion,
            String pendingIndexVersion, Instant deletedAt) {
        return new DocumentRow(
                DOCUMENT_ID,
                USER_ID,
                "notes.pdf",
                null,
                "application/pdf",
                1024,
                MD5,
                OBJECT_KEY,
                status,
                retryCount,
                3,
                null,
                null,
                indexVersion,
                pendingIndexVersion,
                null,
                null,
                null,
                null,
                deletedAt,
                NOW,
                NOW);
    }
}
