package com.docqa.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.docqa.common.tenant.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.docqa.common.error.BusinessException;
import com.docqa.common.error.ErrorCode;
import com.docqa.document.config.DocumentProperties;
import com.docqa.document.config.MinioProperties;
import com.docqa.document.domain.DocumentRow;
import com.docqa.document.domain.DocumentStatus;
import com.docqa.document.domain.IdempotencyOperation;
import com.docqa.document.domain.TagName;
import com.docqa.document.dto.CompleteUploadRequest;
import com.docqa.document.dto.CompletedPart;
import com.docqa.document.dto.DocumentRenameRequest;
import com.docqa.document.dto.DocumentStatusData;
import com.docqa.document.dto.DocumentTagsUpdateRequest;
import com.docqa.document.dto.UploadInitData;
import com.docqa.document.dto.UploadInitRequest;
import com.docqa.document.lock.DistributedLock;
import com.docqa.document.messaging.DocumentEventPublisher;
import com.docqa.document.messaging.ParseRequestedEvent;
import com.docqa.document.messaging.VectorChangeRequestedEvent;
import com.docqa.document.repository.ChunkRepository;
import com.docqa.document.repository.DocumentRepository;
import com.docqa.document.repository.DocumentTagRepository;
import com.docqa.document.storage.ObjectStorage;
import com.docqa.document.support.TagNormalizer;
import com.docqa.document.transaction.TransactionRunner;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

    private static final UUID TENANT_ID = TenantContext.DEFAULT_TENANT_ID;
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID DOCUMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final String MD5 = "d41d8cd98f00b204e9800998ecf8427e";
    private static final String OBJECT_KEY = "documents/" + USER_ID + "/" + DOCUMENT_ID + "/source";
    private static final String IDEMPOTENCY_KEY = "idem-key-0001";
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final int PART_SIZE = 5 * 1024 * 1024;

    @Mock
    private DocumentRepository documentRepository;
    @Mock
    private ChunkRepository chunkRepository;
    @Mock
    private DocumentTagRepository tagRepository;
    @Mock
    private ObjectStorage objectStorage;
    @Mock
    private UploadSessionStore uploadSessionStore;
    @Mock
    private IdempotencyStore idempotencyStore;
    @Mock
    private DocumentEventPublisher documentEventPublisher;
    @Mock
    private DistributedLock distributedLock;
    @Mock
    private TransactionRunner transactionRunner;

    private DocumentService documentService;

    @BeforeEach
    void setUp() {
        TenantContext.set(TENANT_ID);
        DocumentProperties documentProperties = new DocumentProperties();
        documentProperties.setSessionTtl(Duration.ofMinutes(45));
        documentProperties.setDefaultMaxRetry(3);

        MinioProperties minioProperties = new MinioProperties();
        minioProperties.setPartSize(PART_SIZE);
        minioProperties.setPresignExpiry(Duration.ofMinutes(15));

        documentService = new DocumentService(
                documentRepository,
                chunkRepository,
                tagRepository,
                objectStorage,
                uploadSessionStore,
                idempotencyStore,
                documentEventPublisher,
                distributedLock,
                transactionRunner,
                Clock.fixed(NOW, ZoneOffset.UTC),
                new ObjectMapper().findAndRegisterModules(),
                documentProperties,
                minioProperties);

        lenient().when(distributedLock.withLock(anyString(), any(Duration.class), any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(2)).get());
        lenient().when(transactionRunner.execute(any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(0)).get());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void initCreatesDocumentAndPresignedParts() {
        when(idempotencyStore.find(USER_ID, IdempotencyOperation.UPLOAD_INIT, IDEMPOTENCY_KEY))
                .thenReturn(Optional.empty());
        when(documentRepository.findByUserAndMd5(TENANT_ID, USER_ID, MD5)).thenReturn(Optional.empty());
        when(objectStorage.createMultipartUpload(anyString())).thenReturn("minio-upload-1");
        when(objectStorage.presignPart(anyString(), eq("minio-upload-1"), anyInt(), any()))
                .thenReturn("https://minio.example/part");

        UploadInitResult result = documentService.init(
                USER_ID,
                IDEMPOTENCY_KEY,
                new UploadInitRequest("notes.pdf", (long) PART_SIZE * 2, "application/pdf", MD5));

        assertThat(result.created()).isTrue();
        UploadInitData data = result.data();
        assertThat(data.status()).isEqualTo("UPLOADED");
        assertThat(data.instantUpload()).isFalse();
        assertThat(data.partSize()).isEqualTo(PART_SIZE);
        assertThat(data.partCount()).isEqualTo(2);
        assertThat(data.parts()).hasSize(2);
        assertThat(data.parts().get(0).partNumber()).isEqualTo(1);
        assertThat(data.parts().get(1).partNumber()).isEqualTo(2);
        assertThat(data.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(45)));

        ArgumentCaptor<DocumentRow> documentCaptor = ArgumentCaptor.forClass(DocumentRow.class);
        verify(documentRepository).insert(eq(TENANT_ID), documentCaptor.capture());
        DocumentRow inserted = documentCaptor.getValue();
        assertThat(data.documentId()).isEqualTo(inserted.id());
        assertThat(inserted.objectKey())
                .isEqualTo("documents/" + TENANT_ID + "/" + USER_ID + "/" + inserted.id() + "/source");
        assertThat(inserted.status()).isEqualTo(DocumentStatus.UPLOADED);
        assertThat(inserted.title()).isNull();
        verify(uploadSessionStore).save(any(UploadSession.class));
        verify(idempotencyStore).save(
                eq(USER_ID),
                eq(IdempotencyOperation.UPLOAD_INIT),
                eq(IDEMPOTENCY_KEY),
                any(IdempotencyRecord.class));
    }

    @Test
    void initReturnsInstantUploadForIndexedDocument() {
        DocumentRow indexed = document(DocumentStatus.INDEXED);
        when(idempotencyStore.find(USER_ID, IdempotencyOperation.UPLOAD_INIT, IDEMPOTENCY_KEY))
                .thenReturn(Optional.empty());
        when(documentRepository.findByUserAndMd5(TENANT_ID, USER_ID, MD5)).thenReturn(Optional.of(indexed));

        UploadInitResult result = documentService.init(
                USER_ID,
                IDEMPOTENCY_KEY,
                new UploadInitRequest("notes.pdf", (long) PART_SIZE, "application/pdf", MD5));

        assertThat(result.created()).isFalse();
        assertThat(result.data().instantUpload()).isTrue();
        assertThat(result.data().status()).isEqualTo("INDEXED");
        assertThat(result.data().partCount()).isZero();
        assertThat(result.data().parts()).isEmpty();
        verify(objectStorage, never()).createMultipartUpload(anyString());
    }

    @Test
    void initRejectsInstantUploadForASoftDeletedDocument() {
        when(idempotencyStore.find(USER_ID, IdempotencyOperation.UPLOAD_INIT, IDEMPOTENCY_KEY))
                .thenReturn(Optional.empty());
        when(documentRepository.findByUserAndMd5(TENANT_ID, USER_ID, MD5))
                .thenReturn(Optional.of(softDeleted(DocumentStatus.INDEXED)));

        assertThatThrownBy(() -> documentService.init(
                        USER_ID,
                        IDEMPOTENCY_KEY,
                        new UploadInitRequest("notes.pdf", (long) PART_SIZE, "application/pdf", MD5)))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.DOC_CONFLICT_001);
                    assertThat(ex.getMessage()).contains("请重新上传完整文件");
                });
        verify(documentRepository, never()).insert(eq(TENANT_ID), any());
    }

    @Test
    void initRejectsDocumentThatIsAlreadyParsing() {
        when(idempotencyStore.find(USER_ID, IdempotencyOperation.UPLOAD_INIT, IDEMPOTENCY_KEY))
                .thenReturn(Optional.empty());
        when(documentRepository.findByUserAndMd5(TENANT_ID, USER_ID, MD5))
                .thenReturn(Optional.of(document(DocumentStatus.PARSING)));

        assertThatThrownBy(() -> documentService.init(
                        USER_ID,
                        IDEMPOTENCY_KEY,
                        new UploadInitRequest("notes.pdf", (long) PART_SIZE, "application/pdf", MD5)))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.DOC_CONFLICT_001));
    }

    @Test
    void completeMarksDocumentParsingAndPublishesEvent() {
        DocumentRow uploaded = document(DocumentStatus.UPLOADED);
        UploadSession session = session();
        when(idempotencyStore.find(USER_ID, IdempotencyOperation.UPLOAD_COMPLETE, IDEMPOTENCY_KEY))
                .thenReturn(Optional.empty());
        when(documentRepository.findByIdAndUser(TENANT_ID, DOCUMENT_ID, USER_ID)).thenReturn(Optional.of(uploaded));
        when(uploadSessionStore.find(DOCUMENT_ID)).thenReturn(Optional.of(session));
        when(documentRepository.markParsing(TENANT_ID, DOCUMENT_ID, USER_ID, NOW)).thenReturn(1);
        when(objectStorage.size(OBJECT_KEY)).thenReturn((long) PART_SIZE);
        when(objectStorage.md5(OBJECT_KEY)).thenReturn(MD5);

        CompleteUploadResult result = documentService.complete(
                USER_ID,
                DOCUMENT_ID,
                IDEMPOTENCY_KEY,
                new CompleteUploadRequest(List.of(new CompletedPart(1, "etag-1"))));

        assertThat(result.accepted()).isTrue();
        assertThat(result.data().status()).isEqualTo("PARSING");
        assertThat(result.data().documentId()).isEqualTo(DOCUMENT_ID);
        verify(objectStorage).completeMultipartUpload(eq(OBJECT_KEY), eq("minio-upload-1"), any());
        verify(uploadSessionStore).delete(DOCUMENT_ID);
        verify(documentEventPublisher).publishParseRequested(any());
    }

    @Test
    void completeRejectsMismatchedObjectAndCleansUp() {
        DocumentRow uploaded = document(DocumentStatus.UPLOADED);
        when(idempotencyStore.find(USER_ID, IdempotencyOperation.UPLOAD_COMPLETE, IDEMPOTENCY_KEY))
                .thenReturn(Optional.empty());
        when(documentRepository.findByIdAndUser(TENANT_ID, DOCUMENT_ID, USER_ID)).thenReturn(Optional.of(uploaded));
        when(uploadSessionStore.find(DOCUMENT_ID)).thenReturn(Optional.of(session()));
        when(objectStorage.size(OBJECT_KEY)).thenReturn((long) PART_SIZE);
        when(objectStorage.md5(OBJECT_KEY)).thenReturn("ffffffffffffffffffffffffffffffff");

        assertThatThrownBy(() -> documentService.complete(
                        USER_ID,
                        DOCUMENT_ID,
                        IDEMPOTENCY_KEY,
                        new CompleteUploadRequest(List.of(new CompletedPart(1, "etag-1")))))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.DOC_VALIDATION_004));

        verify(objectStorage).remove(OBJECT_KEY);
        verify(uploadSessionStore).delete(DOCUMENT_ID);
        verify(documentRepository, never()).markParsing(eq(TENANT_ID), any(), any(), any());
        verify(documentEventPublisher, never()).publishParseRequested(any());
    }

    @Test
    void statusReportsChunkCountVectorConfirmationAndTags() {
        DocumentRow indexed = row(DocumentStatus.INDEXED, "v2", null, 3L);
        when(documentRepository.findByIdAndUser(TENANT_ID, DOCUMENT_ID, USER_ID)).thenReturn(Optional.of(indexed));
        when(chunkRepository.count(TENANT_ID, DOCUMENT_ID, "v2")).thenReturn(3L);
        when(tagRepository.findNames(TENANT_ID, DOCUMENT_ID)).thenReturn(List.of("考研数学"));

        DocumentStatusData data = documentService.status(USER_ID, DOCUMENT_ID);

        assertThat(data.status()).isEqualTo("INDEXED");
        assertThat(data.chunkCount()).isEqualTo(3);
        assertThat(data.indexVersion()).isEqualTo("v2");
        assertThat(data.pendingIndexVersion()).isNull();
        assertThat(data.vectorRows()).isEqualTo(3L);
        assertThat(data.vectorConsistency()).isEqualTo("MATCHED");
        assertThat(data.tags()).containsExactly("考研数学");
    }

    @Test
    void renameWritesOnlyTheTitleAndLeavesTheFileNameAlone() {
        DocumentRow indexed = document(DocumentStatus.INDEXED);
        DocumentRow renamed = withTitle(indexed, "高等数学·第三章笔记");
        when(idempotencyStore.find(USER_ID, IdempotencyOperation.DOCUMENT_RENAME, IDEMPOTENCY_KEY))
                .thenReturn(Optional.empty());
        when(documentRepository.findByIdAndUser(TENANT_ID, DOCUMENT_ID, USER_ID))
                .thenReturn(Optional.of(indexed), Optional.of(renamed));
        when(documentRepository.updateTitle(TENANT_ID, DOCUMENT_ID, USER_ID, "高等数学·第三章笔记")).thenReturn(1);

        DocumentStatusData data = documentService.rename(
                USER_ID, DOCUMENT_ID, IDEMPOTENCY_KEY, new DocumentRenameRequest("  高等数学·第三章笔记 "));

        assertThat(data.title()).isEqualTo("高等数学·第三章笔记");
        assertThat(data.fileName()).isEqualTo("notes.pdf");
        verify(documentRepository).updateTitle(TENANT_ID, DOCUMENT_ID, USER_ID, "高等数学·第三章笔记");
    }

    @Test
    void renameRejectsBlankOrOverlongTitlesBeforeTouchingTheDatabase() {
        assertThatThrownBy(() -> documentService.rename(
                        USER_ID, DOCUMENT_ID, IDEMPOTENCY_KEY, new DocumentRenameRequest("   ")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.COM_VALIDATION_001);
        assertThatThrownBy(() -> documentService.rename(
                        USER_ID, DOCUMENT_ID, IDEMPOTENCY_KEY, new DocumentRenameRequest("字".repeat(121))))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.COM_VALIDATION_001);
        verifyNoDocumentLookup();
    }

    @Test
    void renameOfAForeignOrSoftDeletedDocumentIsNotFound() {
        when(idempotencyStore.find(USER_ID, IdempotencyOperation.DOCUMENT_RENAME, IDEMPOTENCY_KEY))
                .thenReturn(Optional.empty());
        when(documentRepository.findByIdAndUser(TENANT_ID, DOCUMENT_ID, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> documentService.rename(
                        USER_ID, DOCUMENT_ID, IDEMPOTENCY_KEY, new DocumentRenameRequest("新标题")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.DOC_NOT_FOUND_001);
        verify(documentRepository, never()).updateTitle(eq(TENANT_ID), any(), any(), any());
    }

    @Test
    void replaceTagsResolvesNamesAndRewritesLinksInsideOneTransaction() {
        DocumentRow indexed = document(DocumentStatus.INDEXED);
        UUID firstId = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
        UUID secondId = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
        List<TagName> tags = List.of(TagNormalizer.of("考研 数学"), TagNormalizer.of("错题本"));
        Map<String, UUID> resolved = new LinkedHashMap<>();
        resolved.put(tags.get(0).normalized(), firstId);
        resolved.put(tags.get(1).normalized(), secondId);
        when(idempotencyStore.find(USER_ID, IdempotencyOperation.DOCUMENT_TAG_UPDATE, IDEMPOTENCY_KEY))
                .thenReturn(Optional.empty());
        when(documentRepository.findByIdAndUser(TENANT_ID, DOCUMENT_ID, USER_ID)).thenReturn(Optional.of(indexed));
        when(tagRepository.resolveOrCreate(TENANT_ID, USER_ID, tags, NOW)).thenReturn(resolved);
        when(tagRepository.findNames(TENANT_ID, DOCUMENT_ID)).thenReturn(List.of("考研 数学", "错题本"));

        DocumentStatusData data = documentService.replaceTags(
                USER_ID,
                DOCUMENT_ID,
                IDEMPOTENCY_KEY,
                new DocumentTagsUpdateRequest(List.of(" 考研   数学 ", "错题本")));

        verify(tagRepository).replaceLinks(TENANT_ID, DOCUMENT_ID, List.of(firstId, secondId), NOW);
        assertThat(data.tags()).containsExactly("考研 数学", "错题本");
    }

    @Test
    void replaceTagsWithAnEmptyListClearsEveryLink() {
        DocumentRow indexed = document(DocumentStatus.INDEXED);
        when(idempotencyStore.find(USER_ID, IdempotencyOperation.DOCUMENT_TAG_UPDATE, IDEMPOTENCY_KEY))
                .thenReturn(Optional.empty());
        when(documentRepository.findByIdAndUser(TENANT_ID, DOCUMENT_ID, USER_ID)).thenReturn(Optional.of(indexed));
        when(tagRepository.resolveOrCreate(TENANT_ID, USER_ID, List.of(), NOW)).thenReturn(Map.of());

        DocumentStatusData data = documentService.replaceTags(
                USER_ID, DOCUMENT_ID, IDEMPOTENCY_KEY, new DocumentTagsUpdateRequest(List.of()));

        verify(tagRepository).replaceLinks(TENANT_ID, DOCUMENT_ID, List.of(), NOW);
        assertThat(data.tags()).isEmpty();
    }

    @Test
    void replaceTagsRejectsMalformedTagListsBeforeTouchingTheDatabase() {
        assertThatThrownBy(() -> documentService.replaceTags(
                        USER_ID, DOCUMENT_ID, IDEMPOTENCY_KEY, new DocumentTagsUpdateRequest(null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.COM_VALIDATION_001);
        assertThatThrownBy(() -> documentService.replaceTags(
                        USER_ID, DOCUMENT_ID, IDEMPOTENCY_KEY, new DocumentTagsUpdateRequest(List.of("  "))))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.COM_VALIDATION_001);
        assertThatThrownBy(() -> documentService.replaceTags(
                        USER_ID, DOCUMENT_ID, IDEMPOTENCY_KEY, new DocumentTagsUpdateRequest(List.of("错题", "错题"))))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.COM_VALIDATION_001);
        assertThatThrownBy(() -> documentService.replaceTags(
                        USER_ID,
                        DOCUMENT_ID,
                        IDEMPOTENCY_KEY,
                        new DocumentTagsUpdateRequest(List.of("标".repeat(25)))))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.COM_VALIDATION_001);
        assertThatThrownBy(() -> documentService.replaceTags(
                        USER_ID,
                        DOCUMENT_ID,
                        IDEMPOTENCY_KEY,
                        new DocumentTagsUpdateRequest(IntStream.range(0, 21)
                                .mapToObj(index -> "标签" + index)
                                .toList())))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.COM_VALIDATION_001);
        verifyNoDocumentLookup();
    }

    @Test
    void deleteSoftDeletesLocallyAndAsksTheVectorSideToRemoveTheContent() {
        DocumentRow indexed = document(DocumentStatus.INDEXED);
        when(idempotencyStore.find(USER_ID, IdempotencyOperation.DOCUMENT_DELETE, IDEMPOTENCY_KEY))
                .thenReturn(Optional.empty());
        when(documentRepository.findByIdAndUser(TENANT_ID, DOCUMENT_ID, USER_ID)).thenReturn(Optional.of(indexed));
        when(documentRepository.softDelete(TENANT_ID, DOCUMENT_ID, USER_ID, NOW)).thenReturn(1);

        documentService.delete(USER_ID, DOCUMENT_ID, IDEMPOTENCY_KEY);

        verify(chunkRepository).deleteByDocument(TENANT_ID, DOCUMENT_ID);
        verify(documentRepository).softDelete(TENANT_ID, DOCUMENT_ID, USER_ID, NOW);
        ArgumentCaptor<VectorChangeRequestedEvent> captor =
                ArgumentCaptor.forClass(VectorChangeRequestedEvent.class);
        verify(documentEventPublisher).publishVectorChangeRequested(captor.capture());
        VectorChangeRequestedEvent event = captor.getValue();
        assertThat(event.operation()).isEqualTo("DELETE_DOCUMENT");
        assertThat(event.documentId()).isEqualTo(DOCUMENT_ID);
        assertThat(event.userId()).isEqualTo(USER_ID);
        assertThat(event.indexVersion()).isNull();
        assertThat(event.occurredAt()).isEqualTo(NOW);
    }

    @Test
    void deleteStillCommitsWhenTheVectorPublishFails() {
        DocumentRow indexed = document(DocumentStatus.INDEXED);
        when(idempotencyStore.find(USER_ID, IdempotencyOperation.DOCUMENT_DELETE, IDEMPOTENCY_KEY))
                .thenReturn(Optional.empty());
        when(documentRepository.findByIdAndUser(TENANT_ID, DOCUMENT_ID, USER_ID)).thenReturn(Optional.of(indexed));
        when(documentRepository.softDelete(TENANT_ID, DOCUMENT_ID, USER_ID, NOW)).thenReturn(1);
        doThrow(new IllegalStateException("broker down"))
                .when(documentEventPublisher).publishVectorChangeRequested(any());

        documentService.delete(USER_ID, DOCUMENT_ID, IDEMPOTENCY_KEY);

        verify(documentRepository).softDelete(TENANT_ID, DOCUMENT_ID, USER_ID, NOW);
        verify(idempotencyStore).save(
                eq(USER_ID), eq(IdempotencyOperation.DOCUMENT_DELETE), eq(IDEMPOTENCY_KEY),
                any(IdempotencyRecord.class));
    }

    @Test
    void deleteRejectsADocumentThatIsStillParsing() {
        when(idempotencyStore.find(USER_ID, IdempotencyOperation.DOCUMENT_DELETE, IDEMPOTENCY_KEY))
                .thenReturn(Optional.empty());
        when(documentRepository.findByIdAndUser(TENANT_ID, DOCUMENT_ID, USER_ID))
                .thenReturn(Optional.of(document(DocumentStatus.PARSING)));

        assertThatThrownBy(() -> documentService.delete(USER_ID, DOCUMENT_ID, IDEMPOTENCY_KEY))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.DOC_CONFLICT_001);
        verify(documentRepository, never()).softDelete(eq(TENANT_ID), any(), any(), any());
        verify(documentEventPublisher, never()).publishVectorChangeRequested(any());
        verify(idempotencyStore, never()).save(any(), any(), any(), any());
    }

    @Test
    void repeatedDeleteIsReplayedWithoutADatabaseRoundTrip() {
        DocumentRow indexed = document(DocumentStatus.INDEXED);
        when(idempotencyStore.find(USER_ID, IdempotencyOperation.DOCUMENT_DELETE, IDEMPOTENCY_KEY))
                .thenReturn(Optional.empty());
        when(documentRepository.findByIdAndUser(TENANT_ID, DOCUMENT_ID, USER_ID)).thenReturn(Optional.of(indexed));
        when(documentRepository.softDelete(TENANT_ID, DOCUMENT_ID, USER_ID, NOW)).thenReturn(1);

        documentService.delete(USER_ID, DOCUMENT_ID, IDEMPOTENCY_KEY);
        ArgumentCaptor<IdempotencyRecord> recordCaptor = ArgumentCaptor.forClass(IdempotencyRecord.class);
        verify(idempotencyStore).save(
                eq(USER_ID), eq(IdempotencyOperation.DOCUMENT_DELETE), eq(IDEMPOTENCY_KEY), recordCaptor.capture());

        reset(documentRepository, chunkRepository, documentEventPublisher, idempotencyStore);
        when(idempotencyStore.find(USER_ID, IdempotencyOperation.DOCUMENT_DELETE, IDEMPOTENCY_KEY))
                .thenReturn(Optional.of(recordCaptor.getValue()));

        documentService.delete(USER_ID, DOCUMENT_ID, IDEMPOTENCY_KEY);

        verifyNoInteractions(documentRepository, chunkRepository, documentEventPublisher);
    }

    @Test
    void reusingAnIdempotencyKeyForAnotherDocumentIsRejected() {
        DocumentRow indexed = document(DocumentStatus.INDEXED);
        when(idempotencyStore.find(USER_ID, IdempotencyOperation.DOCUMENT_DELETE, IDEMPOTENCY_KEY))
                .thenReturn(Optional.empty());
        when(documentRepository.findByIdAndUser(TENANT_ID, DOCUMENT_ID, USER_ID)).thenReturn(Optional.of(indexed));
        when(documentRepository.softDelete(TENANT_ID, DOCUMENT_ID, USER_ID, NOW)).thenReturn(1);

        documentService.delete(USER_ID, DOCUMENT_ID, IDEMPOTENCY_KEY);
        ArgumentCaptor<IdempotencyRecord> recordCaptor = ArgumentCaptor.forClass(IdempotencyRecord.class);
        verify(idempotencyStore).save(
                eq(USER_ID), eq(IdempotencyOperation.DOCUMENT_DELETE), eq(IDEMPOTENCY_KEY), recordCaptor.capture());

        UUID otherDocument = UUID.fromString("00000000-0000-0000-0000-000000000009");
        when(idempotencyStore.find(USER_ID, IdempotencyOperation.DOCUMENT_DELETE, IDEMPOTENCY_KEY))
                .thenReturn(Optional.of(new IdempotencyRecord(
                        "other-fingerprint", recordCaptor.getValue().responseJson(), NOW)));

        assertThatThrownBy(() -> documentService.delete(USER_ID, otherDocument, IDEMPOTENCY_KEY))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.DOC_CONFLICT_001);
    }

    @Test
    void retryParseReopensAFailedDocumentAndResetsTheRetryCounter() {
        DocumentRow failed = document(DocumentStatus.FAILED);
        DocumentRow parsing = withRetry(failed, DocumentStatus.PARSING, 0);
        when(idempotencyStore.find(USER_ID, IdempotencyOperation.DOCUMENT_RETRY, IDEMPOTENCY_KEY))
                .thenReturn(Optional.empty());
        when(documentRepository.findByIdAndUser(TENANT_ID, DOCUMENT_ID, USER_ID))
                .thenReturn(Optional.of(failed), Optional.of(parsing));
        when(documentRepository.resetRetryAndMarkParsing(TENANT_ID, DOCUMENT_ID, USER_ID, NOW)).thenReturn(1);

        DocumentCommandResult result = documentService.retryParse(USER_ID, DOCUMENT_ID, IDEMPOTENCY_KEY);

        assertThat(result.accepted()).isTrue();
        assertThat(result.data().status()).isEqualTo("PARSING");
        assertThat(result.data().retryCount()).isZero();
        ArgumentCaptor<ParseRequestedEvent> captor = ArgumentCaptor.forClass(ParseRequestedEvent.class);
        verify(documentEventPublisher).publishParseRequested(captor.capture());
        assertThat(captor.getValue().indexVersion()).isEqualTo("v1");
        assertThat(captor.getValue().retryCount()).isZero();
    }

    @Test
    void retryParseIsRefusedForDocumentsThatDidNotFail() {
        for (DocumentStatus status : List.of(DocumentStatus.UPLOADED, DocumentStatus.PARSING,
                DocumentStatus.INDEXED)) {
            reset(idempotencyStore, documentRepository, documentEventPublisher);
            when(idempotencyStore.find(USER_ID, IdempotencyOperation.DOCUMENT_RETRY, IDEMPOTENCY_KEY))
                    .thenReturn(Optional.empty());
            when(documentRepository.findByIdAndUser(TENANT_ID, DOCUMENT_ID, USER_ID))
                    .thenReturn(Optional.of(document(status)));

            assertThatThrownBy(() -> documentService.retryParse(USER_ID, DOCUMENT_ID, IDEMPOTENCY_KEY))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.DOC_CONFLICT_001);
            verify(documentRepository, never()).resetRetryAndMarkParsing(eq(TENANT_ID), any(), any(), any());
            verify(documentEventPublisher, never()).publishParseRequested(any());
        }
    }

    @Test
    void deadDocumentsCanAlsoBeRetriedManually() {
        DocumentRow dead = document(DocumentStatus.DEAD);
        when(idempotencyStore.find(USER_ID, IdempotencyOperation.DOCUMENT_RETRY, IDEMPOTENCY_KEY))
                .thenReturn(Optional.empty());
        when(documentRepository.findByIdAndUser(TENANT_ID, DOCUMENT_ID, USER_ID)).thenReturn(Optional.of(dead));
        when(documentRepository.resetRetryAndMarkParsing(TENANT_ID, DOCUMENT_ID, USER_ID, NOW)).thenReturn(1);

        DocumentCommandResult result = documentService.retryParse(USER_ID, DOCUMENT_ID, IDEMPOTENCY_KEY);

        assertThat(result.accepted()).isTrue();
        verify(documentRepository).resetRetryAndMarkParsing(TENANT_ID, DOCUMENT_ID, USER_ID, NOW);
    }

    @Test
    void reindexKeepsTheDocumentIndexedAndOpensAPendingVersion() {
        DocumentRow indexed = document(DocumentStatus.INDEXED);
        DocumentRow rebuilding = withPending(indexed, "v2");
        when(idempotencyStore.find(USER_ID, IdempotencyOperation.DOCUMENT_REINDEX, IDEMPOTENCY_KEY))
                .thenReturn(Optional.empty());
        when(documentRepository.findByIdAndUser(TENANT_ID, DOCUMENT_ID, USER_ID))
                .thenReturn(Optional.of(indexed), Optional.of(rebuilding));
        when(documentRepository.beginReindex(TENANT_ID, DOCUMENT_ID, USER_ID, "v2", NOW)).thenReturn(1);

        DocumentCommandResult result = documentService.reindex(USER_ID, DOCUMENT_ID, IDEMPOTENCY_KEY);

        assertThat(result.accepted()).isTrue();
        assertThat(result.data().status()).isEqualTo("INDEXED");
        assertThat(result.data().indexVersion()).isEqualTo("v1");
        assertThat(result.data().pendingIndexVersion()).isEqualTo("v2");
        ArgumentCaptor<ParseRequestedEvent> captor = ArgumentCaptor.forClass(ParseRequestedEvent.class);
        verify(documentEventPublisher).publishParseRequested(captor.capture());
        assertThat(captor.getValue().indexVersion()).isEqualTo("v2");
        assertThat(captor.getValue().retryCount()).isZero();
    }

    @Test
    void reindexRefusesNonIndexedDocumentsAndConcurrentRebuilds() {
        when(idempotencyStore.find(USER_ID, IdempotencyOperation.DOCUMENT_REINDEX, IDEMPOTENCY_KEY))
                .thenReturn(Optional.empty());
        when(documentRepository.findByIdAndUser(TENANT_ID, DOCUMENT_ID, USER_ID))
                .thenReturn(Optional.of(document(DocumentStatus.FAILED)));

        assertThatThrownBy(() -> documentService.reindex(USER_ID, DOCUMENT_ID, IDEMPOTENCY_KEY))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.DOC_CONFLICT_001);

        reset(idempotencyStore, documentRepository);
        when(idempotencyStore.find(USER_ID, IdempotencyOperation.DOCUMENT_REINDEX, IDEMPOTENCY_KEY))
                .thenReturn(Optional.empty());
        when(documentRepository.findByIdAndUser(TENANT_ID, DOCUMENT_ID, USER_ID))
                .thenReturn(Optional.of(withPending(document(DocumentStatus.INDEXED), "v2")));

        assertThatThrownBy(() -> documentService.reindex(USER_ID, DOCUMENT_ID, IDEMPOTENCY_KEY))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.DOC_CONFLICT_001);
                    assertThat(ex.getMessage()).contains("已有重建任务在途");
                });
        verify(documentRepository, never()).beginReindex(eq(TENANT_ID), any(), any(), any(), any());
    }

    @Test
    void reindexRefusesToInferANewVersionFromAnUnknownIndexVersionLabel() {
        DocumentRow indexed = row(DocumentStatus.INDEXED, "prod-2026-10", null, null);
        when(idempotencyStore.find(USER_ID, IdempotencyOperation.DOCUMENT_REINDEX, IDEMPOTENCY_KEY))
                .thenReturn(Optional.empty());
        when(documentRepository.findByIdAndUser(TENANT_ID, DOCUMENT_ID, USER_ID)).thenReturn(Optional.of(indexed));

        assertThatThrownBy(() -> documentService.reindex(USER_ID, DOCUMENT_ID, IDEMPOTENCY_KEY))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.DOC_CONFLICT_001);
        verify(documentRepository, never()).beginReindex(eq(TENANT_ID), any(), any(), any(), any());
        verify(documentEventPublisher, never()).publishParseRequested(any());
    }

    @Test
    void replayedCommandsReportAcceptedFalseWithoutRePublishing() {
        DocumentRow indexed = document(DocumentStatus.INDEXED);
        when(idempotencyStore.find(USER_ID, IdempotencyOperation.DOCUMENT_REINDEX, IDEMPOTENCY_KEY))
                .thenReturn(Optional.empty());
        when(documentRepository.findByIdAndUser(TENANT_ID, DOCUMENT_ID, USER_ID)).thenReturn(Optional.of(indexed));
        when(documentRepository.beginReindex(TENANT_ID, DOCUMENT_ID, USER_ID, "v2", NOW)).thenReturn(1);

        DocumentCommandResult first = documentService.reindex(USER_ID, DOCUMENT_ID, IDEMPOTENCY_KEY);
        ArgumentCaptor<IdempotencyRecord> recordCaptor = ArgumentCaptor.forClass(IdempotencyRecord.class);
        verify(idempotencyStore).save(
                eq(USER_ID), eq(IdempotencyOperation.DOCUMENT_REINDEX), eq(IDEMPOTENCY_KEY), recordCaptor.capture());

        reset(documentRepository, documentEventPublisher, idempotencyStore);
        when(idempotencyStore.find(USER_ID, IdempotencyOperation.DOCUMENT_REINDEX, IDEMPOTENCY_KEY))
                .thenReturn(Optional.of(recordCaptor.getValue()));

        DocumentCommandResult replay = documentService.reindex(USER_ID, DOCUMENT_ID, IDEMPOTENCY_KEY);

        assertThat(first.accepted()).isTrue();
        assertThat(replay.accepted()).isFalse();
        assertThat(replay.data().documentId()).isEqualTo(DOCUMENT_ID);
        verifyNoInteractions(documentRepository, documentEventPublisher);
    }

    @Test
    void allCommandsRejectMalformedIdempotencyKeys() {
        String badKey = "short";
        assertThatThrownBy(() -> documentService.rename(
                        USER_ID, DOCUMENT_ID, badKey, new DocumentRenameRequest("新标题")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.COM_VALIDATION_001);
        assertThatThrownBy(() -> documentService.replaceTags(
                        USER_ID, DOCUMENT_ID, badKey, new DocumentTagsUpdateRequest(List.of())))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.COM_VALIDATION_001);
        assertThatThrownBy(() -> documentService.delete(USER_ID, DOCUMENT_ID, badKey))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.COM_VALIDATION_001);
        assertThatThrownBy(() -> documentService.retryParse(USER_ID, DOCUMENT_ID, badKey))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.COM_VALIDATION_001);
        assertThatThrownBy(() -> documentService.reindex(USER_ID, DOCUMENT_ID, badKey))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.COM_VALIDATION_001);
        verifyNoDocumentLookup();
    }

    private void verifyNoDocumentLookup() {
        verify(documentRepository, never()).findByIdAndUser(eq(TENANT_ID), any(), any());
        verify(documentRepository, never()).updateTitle(eq(TENANT_ID), any(), any(), any());
        verify(documentRepository, never()).softDelete(eq(TENANT_ID), any(), any(), any());
    }

    private UploadSession session() {
        return new UploadSession(
                DOCUMENT_ID,
                USER_ID,
                OBJECT_KEY,
                "minio-upload-1",
                PART_SIZE,
                1,
                NOW.plus(Duration.ofMinutes(30)));
    }

    private static DocumentRow document(DocumentStatus status) {
        return row(status, "v1", null, null);
    }

    private static DocumentRow row(DocumentStatus status, String indexVersion, String pendingIndexVersion,
            Long vectorRows) {
        return new DocumentRow(
                DOCUMENT_ID,
                USER_ID,
                "notes.pdf",
                null,
                "application/pdf",
                PART_SIZE,
                MD5,
                OBJECT_KEY,
                status,
                0,
                3,
                null,
                null,
                indexVersion,
                pendingIndexVersion,
                vectorRows,
                vectorRows == null ? null : NOW,
                null,
                vectorRows == null ? null : NOW,
                null,
                NOW,
                NOW);
    }

    private static DocumentRow softDeleted(DocumentStatus status) {
        DocumentRow source = row(status, "v1", null, 3L);
        return new DocumentRow(
                source.id(),
                source.userId(),
                source.fileName(),
                source.title(),
                source.contentType(),
                source.sizeBytes(),
                source.md5(),
                source.objectKey(),
                source.status(),
                source.retryCount(),
                source.maxRetry(),
                source.errorCode(),
                source.errorMessage(),
                source.indexVersion(),
                source.pendingIndexVersion(),
                source.vectorRows(),
                source.vectorConfirmedAt(),
                source.parsingStartedAt(),
                source.indexedAt(),
                NOW,
                source.createdAt(),
                source.updatedAt());
    }

    private static DocumentRow withTitle(DocumentRow source, String title) {
        return new DocumentRow(
                source.id(),
                source.userId(),
                source.fileName(),
                title,
                source.contentType(),
                source.sizeBytes(),
                source.md5(),
                source.objectKey(),
                source.status(),
                source.retryCount(),
                source.maxRetry(),
                source.errorCode(),
                source.errorMessage(),
                source.indexVersion(),
                source.pendingIndexVersion(),
                source.vectorRows(),
                source.vectorConfirmedAt(),
                source.parsingStartedAt(),
                source.indexedAt(),
                source.deletedAt(),
                source.createdAt(),
                source.updatedAt());
    }

    private static DocumentRow withPending(DocumentRow source, String pendingIndexVersion) {
        return new DocumentRow(
                source.id(),
                source.userId(),
                source.fileName(),
                source.title(),
                source.contentType(),
                source.sizeBytes(),
                source.md5(),
                source.objectKey(),
                source.status(),
                source.retryCount(),
                source.maxRetry(),
                source.errorCode(),
                source.errorMessage(),
                source.indexVersion(),
                pendingIndexVersion,
                source.vectorRows(),
                source.vectorConfirmedAt(),
                NOW,
                source.indexedAt(),
                source.deletedAt(),
                source.createdAt(),
                source.updatedAt());
    }

    private static DocumentRow withRetry(DocumentRow source, DocumentStatus status, int retryCount) {
        return new DocumentRow(
                source.id(),
                source.userId(),
                source.fileName(),
                source.title(),
                source.contentType(),
                source.sizeBytes(),
                source.md5(),
                source.objectKey(),
                status,
                retryCount,
                source.maxRetry(),
                source.errorCode(),
                source.errorMessage(),
                source.indexVersion(),
                source.pendingIndexVersion(),
                source.vectorRows(),
                source.vectorConfirmedAt(),
                NOW,
                source.indexedAt(),
                source.deletedAt(),
                source.createdAt(),
                source.updatedAt());
    }
}
