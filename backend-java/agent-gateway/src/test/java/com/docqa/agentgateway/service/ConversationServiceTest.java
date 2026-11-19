package com.docqa.agentgateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.docqa.agentgateway.dto.ConversationData;
import com.docqa.agentgateway.dto.CreateConversationRequest;
import com.docqa.agentgateway.repository.ConversationRepository;
import com.docqa.common.error.BusinessException;
import com.docqa.common.error.ErrorCode;
import com.docqa.common.tenant.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConversationServiceTest {

    private static final UUID TENANT_ID = TenantContext.DEFAULT_TENANT_ID;
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID CONVERSATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID DOCUMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final String KEY = "conversation-key-01";
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Mock
    private ConversationRepository conversationRepository;
    @Mock
    private ConversationCreationStore creationStore;
    @Mock
    private DocumentScopeService documentScopeService;
    @Mock
    private RateLimiter rateLimiter;

    private ConversationService service;

    @BeforeEach
    void setUp() {
        TenantContext.set(TENANT_ID);
        service = new ConversationService(
                Clock.fixed(NOW, ZoneOffset.UTC),
                conversationRepository,
                creationStore,
                documentScopeService,
                rateLimiter);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void createsNewConversation() {
        when(creationStore.tryStart(eq(USER_ID), eq(KEY), anyString(), eq(NOW))).thenReturn(true);
        when(conversationRepository.createOwned(any(), any(), eq(USER_ID), eq("线性代数"), eq(List.of(DOCUMENT_ID)), eq(NOW)))
                .thenReturn(true);

        ConversationData data = service.create(
                USER_ID, KEY, new CreateConversationRequest("线性代数", List.of(DOCUMENT_ID)));

        assertThat(data.conversationId()).isNotNull();
        assertThat(data.title()).isEqualTo("线性代数");
        assertThat(data.documentScopeIds()).containsExactly(DOCUMENT_ID);
        assertThat(data.idempotentReplay()).isFalse();
        assertThat(data.createdAt()).isEqualTo(NOW);
        verify(documentScopeService).assertOwned(USER_ID, List.of(DOCUMENT_ID));
        verify(creationStore).complete(eq(USER_ID), eq(KEY), anyString(), eq(data), eq(NOW));
    }

    @Test
    void usesDefaultTitleWhenTitleIsAbsent() {
        when(creationStore.tryStart(any(), anyString(), anyString(), any())).thenReturn(true);
        when(conversationRepository.createOwned(any(), any(), eq(USER_ID), eq(ConversationService.DEFAULT_TITLE), eq(List.of()), any()))
                .thenReturn(true);

        ConversationData data = service.create(USER_ID, KEY, new CreateConversationRequest(null, null));

        assertThat(data.title()).isEqualTo(ConversationService.DEFAULT_TITLE);
        assertThat(data.documentScopeIds()).isEmpty();
        verify(documentScopeService, never()).assertOwned(any(), any());
    }

    @Test
    void rejectsBlankTitle() {
        assertThatThrownBy(() -> service.create(
                USER_ID, KEY, new CreateConversationRequest("   ", null)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.AGT_VALIDATION_001);
        verify(creationStore, never()).tryStart(any(), anyString(), anyString(), any());
    }

    @Test
    void rejectsTooManyScopeDocuments() {
        List<UUID> tooMany = java.util.stream.IntStream.range(0, 21)
                .mapToObj(index -> UUID.randomUUID())
                .toList();

        assertThatThrownBy(() -> service.create(
                USER_ID, KEY, new CreateConversationRequest("title", tooMany)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.AGT_VALIDATION_001);
    }

    @Test
    void rejectsDuplicateScopeDocuments() {
        assertThatThrownBy(() -> service.create(
                USER_ID, KEY, new CreateConversationRequest("title", List.of(DOCUMENT_ID, DOCUMENT_ID))))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.AGT_VALIDATION_001);
    }

    @Test
    void rejectsForeignScopeDocument() {
        doThrowNotFound();

        assertThatThrownBy(() -> service.create(
                USER_ID, KEY, new CreateConversationRequest("title", List.of(DOCUMENT_ID))))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.DOC_NOT_FOUND_001);
        verify(creationStore, never()).tryStart(any(), anyString(), anyString(), any());
    }

    @Test
    void replaysCompletedRequestWithSameFingerprint() {
        ConversationData original =
                new ConversationData(CONVERSATION_ID, "线性代数", List.of(DOCUMENT_ID), false, NOW);
        when(creationStore.find(USER_ID, KEY)).thenReturn(Optional.of(new ConversationCreationRecord(
                IdempotencyStatus.COMPLETED, fingerprint("线性代数", List.of(DOCUMENT_ID)), original, NOW)));

        ConversationData replay = service.create(
                USER_ID, KEY, new CreateConversationRequest("线性代数", List.of(DOCUMENT_ID)));

        assertThat(replay.conversationId()).isEqualTo(CONVERSATION_ID);
        assertThat(replay.idempotentReplay()).isTrue();
        verify(conversationRepository, never()).createOwned(any(), any(), any(), anyString(), any(), any());
    }

    @Test
    void rejectsCompletedRequestWithDifferentFingerprint() {
        ConversationData original =
                new ConversationData(CONVERSATION_ID, "旧标题", List.of(), false, NOW);
        when(creationStore.find(USER_ID, KEY)).thenReturn(Optional.of(new ConversationCreationRecord(
                IdempotencyStatus.COMPLETED, "different-fingerprint", original, NOW)));

        assertThatThrownBy(() -> service.create(
                USER_ID, KEY, new CreateConversationRequest("新标题", List.of())))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.AGT_CONFLICT_002);
    }

    @Test
    void rejectsInProgressRequest() {
        when(creationStore.find(USER_ID, KEY)).thenReturn(Optional.of(new ConversationCreationRecord(
                IdempotencyStatus.IN_PROGRESS, "fingerprint", null, NOW)));

        assertThatThrownBy(() -> service.create(
                USER_ID, KEY, new CreateConversationRequest("title", List.of())))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.AGT_CONFLICT_002);
    }

    @Test
    void clearsIdempotencyRecordWhenInsertFails() {
        when(creationStore.tryStart(any(), anyString(), anyString(), any())).thenReturn(true);
        when(conversationRepository.createOwned(any(), any(), any(), anyString(), any(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.create(
                USER_ID, KEY, new CreateConversationRequest("title", List.of())))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.COM_INTERNAL_001);
        verify(creationStore).clear(USER_ID, KEY);
    }

    @Test
    void appliesRateLimitBeforeIdempotencyLookup() {
        org.mockito.Mockito.doThrow(new RateLimitExceededException(42)).when(rateLimiter).check(USER_ID);

        assertThatThrownBy(() -> service.create(
                USER_ID, KEY, new CreateConversationRequest("title", List.of())))
                .isInstanceOf(RateLimitExceededException.class);
        verify(creationStore, never()).find(any(), anyString());
    }

    @Test
    void ensureOwnedDoesNothingForOwnedConversation() {
        when(conversationRepository.existsByIdAndUser(any(), eq(CONVERSATION_ID), eq(USER_ID))).thenReturn(true);

        service.ensureOwned(CONVERSATION_ID, USER_ID, List.of(DOCUMENT_ID));

        verify(conversationRepository, never()).createOwned(any(), any(), any(), anyString(), any(), any());
    }

    @Test
    void ensureOwnedCreatesMissingConversation() {
        when(conversationRepository.existsByIdAndUser(any(), eq(CONVERSATION_ID), eq(USER_ID))).thenReturn(false);
        when(conversationRepository.createOwned(
                any(), eq(CONVERSATION_ID), eq(USER_ID), eq(ConversationService.DEFAULT_TITLE),
                eq(List.of(DOCUMENT_ID)), eq(NOW)))
                .thenReturn(true);

        service.ensureOwned(CONVERSATION_ID, USER_ID, List.of(DOCUMENT_ID));

        ArgumentCaptor<String> title = ArgumentCaptor.forClass(String.class);
        verify(conversationRepository).createOwned(
                any(), eq(CONVERSATION_ID), eq(USER_ID), title.capture(), eq(List.of(DOCUMENT_ID)), eq(NOW));
        assertThat(title.getValue()).isEqualTo(ConversationService.DEFAULT_TITLE);
    }

    @Test
    void ensureOwnedToleratesConcurrentCreation() {
        when(conversationRepository.existsByIdAndUser(any(), eq(CONVERSATION_ID), eq(USER_ID)))
                .thenReturn(false, true);
        when(conversationRepository.createOwned(any(), any(), any(), anyString(), any(), any())).thenReturn(false);

        service.ensureOwned(CONVERSATION_ID, USER_ID, List.of(DOCUMENT_ID));
    }

    @Test
    void ensureOwnedRejectsForeignConversation() {
        when(conversationRepository.existsByIdAndUser(any(), eq(CONVERSATION_ID), eq(USER_ID))).thenReturn(false);
        when(conversationRepository.createOwned(any(), any(), any(), anyString(), any(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.ensureOwned(CONVERSATION_ID, USER_ID, List.of(DOCUMENT_ID)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.AGT_NOT_FOUND_001);
    }

    @Test
    void requireAccessibleAllowsMissingConversation() {
        when(conversationRepository.existsByIdAndUser(any(), eq(CONVERSATION_ID), eq(USER_ID))).thenReturn(false);
        when(conversationRepository.existsById(any(), eq(CONVERSATION_ID))).thenReturn(false);

        service.requireAccessible(CONVERSATION_ID, USER_ID);
    }

    @Test
    void requireAccessibleRejectsForeignConversation() {
        when(conversationRepository.existsByIdAndUser(any(), eq(CONVERSATION_ID), eq(USER_ID))).thenReturn(false);
        when(conversationRepository.existsById(any(), eq(CONVERSATION_ID))).thenReturn(true);

        assertThatThrownBy(() -> service.requireAccessible(CONVERSATION_ID, USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.AGT_NOT_FOUND_001);
    }

    private void doThrowNotFound() {
        org.mockito.Mockito.doThrow(new BusinessException(ErrorCode.DOC_NOT_FOUND_001))
                .when(documentScopeService).assertOwned(eq(USER_ID), any());
    }

    private String fingerprint(String title, List<UUID> scope) {
        String scopeValue = scope.stream().map(UUID::toString).sorted().collect(Collectors.joining(","));
        return Hashing.sha256(USER_ID + "|CREATE_CONVERSATION|" + title + "|" + scopeValue);
    }
}
