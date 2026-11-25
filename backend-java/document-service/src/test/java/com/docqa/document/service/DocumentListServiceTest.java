package com.docqa.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.docqa.common.tenant.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.docqa.common.api.KeysetCursor;
import com.docqa.common.error.BusinessException;
import com.docqa.common.error.ErrorCode;
import com.docqa.document.config.DocumentProperties;
import com.docqa.document.config.MinioProperties;
import com.docqa.document.domain.DocumentRow;
import com.docqa.document.domain.DocumentStatus;
import com.docqa.document.dto.DocumentListData;
import com.docqa.document.dto.DocumentListItemData;
import com.docqa.document.lock.DistributedLock;
import com.docqa.document.messaging.DocumentEventPublisher;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DocumentListServiceTest {

    private static final UUID TENANT_ID = TenantContext.DEFAULT_TENANT_ID;
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID FIRST_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID SECOND_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID LOOKAHEAD_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final Instant CREATED_AT = Instant.parse("2026-10-20T10:15:50.123Z");

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
        minioProperties.setPartSize(5 * 1024 * 1024);
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
                Clock.fixed(CREATED_AT, ZoneOffset.UTC),
                new ObjectMapper().findAndRegisterModules(),
                documentProperties,
                minioProperties);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void returnsItemsWithBatchedChunkCountsAndTags() {
        when(documentRepository.findUserPage(eq(TENANT_ID), eq(USER_ID), isNull(), isNull(), isNull(), isNull(), isNull(), eq(3)))
                .thenReturn(List.of(row(FIRST_ID, DocumentStatus.INDEXED), row(SECOND_ID, DocumentStatus.UPLOADED)));
        when(chunkRepository.countByDocuments(eq(TENANT_ID), any())).thenReturn(Map.of(FIRST_ID, 42L));
        when(tagRepository.findNamesByDocuments(eq(TENANT_ID), any())).thenReturn(Map.of(FIRST_ID, List.of("考研数学")));

        DocumentListData data = documentService.list(USER_ID, null, null, null, null, 2);

        assertThat(data.limit()).isEqualTo(2);
        assertThat(data.nextCursor()).isNull();
        assertThat(data.items()).hasSize(2);
        DocumentListItemData first = data.items().get(0);
        assertThat(first.status()).isEqualTo("INDEXED");
        assertThat(first.title()).isEqualTo("高等数学·第三章笔记");
        assertThat(first.tags()).containsExactly("考研数学");
        assertThat(first.chunkCount()).isEqualTo(42L);
        assertThat(first.errorCode()).isNull();
        assertThat(data.items().get(1).errorCode()).isEqualTo("DOC-PARSE-001");
        assertThat(data.items().get(1).tags()).isEmpty();
        assertThat(data.items().get(1).chunkCount()).isZero();
        verify(chunkRepository).countByDocuments(TENANT_ID, List.of(FIRST_ID, SECOND_ID));
        verify(tagRepository).findNamesByDocuments(TENANT_ID, List.of(FIRST_ID, SECOND_ID));
    }

    @Test
    void exposesNextCursorFromTheLastReturnedRowNotTheLookaheadRow() {
        when(documentRepository.findUserPage(eq(TENANT_ID), eq(USER_ID), isNull(), isNull(), isNull(), isNull(), isNull(), eq(3)))
                .thenReturn(List.of(
                        row(FIRST_ID, DocumentStatus.INDEXED),
                        row(SECOND_ID, DocumentStatus.PARSING),
                        row(LOOKAHEAD_ID, DocumentStatus.FAILED)));
        when(chunkRepository.countByDocuments(eq(TENANT_ID), any())).thenReturn(Map.of());
        when(tagRepository.findNamesByDocuments(eq(TENANT_ID), any())).thenReturn(Map.of());

        DocumentListData data = documentService.list(USER_ID, null, null, null, null, 2);

        assertThat(data.items()).hasSize(2);
        KeysetCursor cursor = KeysetCursor.decode(data.nextCursor()).orElseThrow();
        assertThat(cursor.id()).isEqualTo(SECOND_ID);
        assertThat(cursor.sortKey()).isEqualTo(CREATED_AT);
        verify(chunkRepository).countByDocuments(TENANT_ID, List.of(FIRST_ID, SECOND_ID));
        verify(tagRepository).findNamesByDocuments(TENANT_ID, List.of(FIRST_ID, SECOND_ID));
    }

    @Test
    void passesStatusSearchTagAndCursorDownToTheRepository() {
        String token = KeysetCursor.of(CREATED_AT.minusSeconds(60), USER_ID).encode();
        when(documentRepository.findUserPage(eq(TENANT_ID), eq(USER_ID), eq(DocumentStatus.FAILED), eq("高数"), eq("考研数学"),
                eq(CREATED_AT.minusSeconds(60)), eq(USER_ID), eq(21)))
                .thenReturn(List.of());

        DocumentListData data = documentService.list(USER_ID, "FAILED", "高数", "考研数学", token, 20);

        assertThat(data.items()).isEmpty();
        assertThat(data.nextCursor()).isNull();
    }

    @Test
    void blankSearchAndTagFiltersBehaveAsAbsent() {
        when(documentRepository.findUserPage(eq(TENANT_ID), eq(USER_ID), isNull(), isNull(), isNull(), isNull(), isNull(), eq(21)))
                .thenReturn(List.of());

        documentService.list(USER_ID, null, "   ", "  ", null, 20);

        verify(documentRepository).findUserPage(eq(TENANT_ID), 
                eq(USER_ID), isNull(), isNull(), isNull(), isNull(), isNull(), eq(21));
    }

    @Test
    void tagFilterIsNormalizedBeforeItReachesTheRepository() {
        String normalized = TagNormalizer.of("  考研   数学 ").normalized();
        when(documentRepository.findUserPage(eq(TENANT_ID), eq(USER_ID), isNull(), isNull(), eq(normalized),
                isNull(), isNull(), eq(21)))
                .thenReturn(List.of());

        documentService.list(USER_ID, null, null, "  考研   数学 ", null, 20);

        verify(documentRepository).findUserPage(eq(TENANT_ID), 
                eq(USER_ID), isNull(), isNull(), eq(normalized), isNull(), isNull(), eq(21));
    }

    @Test
    void rejectsLimitOutsideTheContractRange() {
        assertThatThrownBy(() -> documentService.list(USER_ID, null, null, null, null, 0))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.COM_VALIDATION_001);
        assertThatThrownBy(() -> documentService.list(USER_ID, null, null, null, null, 101))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.COM_VALIDATION_001);
        verifyNoInteractions(documentRepository);
    }

    @Test
    void rejectsUnknownStatusFilter() {
        assertThatThrownBy(() -> documentService.list(USER_ID, "SOMETHING", null, null, null, 20))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.COM_VALIDATION_001);
        verifyNoInteractions(documentRepository);
    }

    @Test
    void rejectsMalformedOrForeignCursor() {
        assertThatThrownBy(() -> documentService.list(USER_ID, null, null, null, "not-a-cursor", 20))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.COM_VALIDATION_001);

        String reminderStyleToken = KeysetCursor.of(null, USER_ID).encode();
        assertThatThrownBy(() -> documentService.list(USER_ID, null, null, null, reminderStyleToken, 20))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.COM_VALIDATION_001);
        verifyNoInteractions(documentRepository);
    }

    @Test
    void rejectsOverlongSearchAndTagFilters() {
        assertThatThrownBy(() -> documentService.list(USER_ID, null, "字".repeat(121), null, null, 20))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.COM_VALIDATION_001);
        assertThatThrownBy(() -> documentService.list(USER_ID, null, null, "签".repeat(25), null, 20))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.COM_VALIDATION_001);
        verifyNoInteractions(documentRepository);
    }

    private static DocumentRow row(UUID id, DocumentStatus status) {
        return new DocumentRow(
                id,
                USER_ID,
                "calculus-notes.pdf",
                id.equals(FIRST_ID) ? "高等数学·第三章笔记" : null,
                "application/pdf",
                10_485_760L,
                "0123456789abcdef0123456789abcdef",
                "documents/" + USER_ID + "/" + id + "/source",
                status,
                0,
                3,
                status == DocumentStatus.INDEXED ? null : "DOC-PARSE-001",
                null,
                "v1",
                null,
                status == DocumentStatus.INDEXED ? 42L : null,
                status == DocumentStatus.INDEXED ? CREATED_AT.plusSeconds(60) : null,
                null,
                status == DocumentStatus.INDEXED ? CREATED_AT.plusSeconds(60) : null,
                null,
                CREATED_AT,
                CREATED_AT.plusSeconds(60));
    }
}
