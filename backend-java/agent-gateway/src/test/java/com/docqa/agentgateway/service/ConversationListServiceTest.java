package com.docqa.agentgateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.docqa.agentgateway.dto.ConversationListData;
import com.docqa.agentgateway.dto.ConversationListItemData;
import com.docqa.agentgateway.dto.ConversationListRow;
import com.docqa.agentgateway.repository.ConversationRepository;
import com.docqa.common.api.KeysetCursor;
import com.docqa.common.error.BusinessException;
import com.docqa.common.error.ErrorCode;
import com.docqa.common.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConversationListServiceTest {

    private static final UUID TENANT_ID = TenantContext.DEFAULT_TENANT_ID;
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID FIRST_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID SECOND_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID THIRD_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID DOCUMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000005");
    private static final Instant FIRST_ACTIVITY = Instant.parse("2026-10-20T10:20:00Z");
    private static final Instant SECOND_ACTIVITY = Instant.parse("2026-10-20T10:15:00Z");
    private static final Instant CREATED = Instant.parse("2026-10-19T08:00:00Z");

    @Mock
    private ConversationRepository conversationRepository;

    private ConversationListService service;

    @BeforeEach
    void setUp() {
        TenantContext.set(TENANT_ID);
        service = new ConversationListService(conversationRepository);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void mapsRowsIntoItemsWithScopeAndActivityTimestamps() {
        when(conversationRepository.findUserPage(eq(TENANT_ID), eq(USER_ID), isNull(), isNull(), eq(false), eq(3)))
                .thenReturn(List.of(
                        row(FIRST_ID, "线性代数", List.of(DOCUMENT_ID), FIRST_ACTIVITY),
                        row(SECOND_ID, "新会话", List.of(), null)));

        ConversationListData data = service.list(USER_ID, null, 2);

        assertThat(data.limit()).isEqualTo(2);
        assertThat(data.items()).hasSize(2);
        assertThat(data.items().get(0).conversationId()).isEqualTo(FIRST_ID);
        assertThat(data.items().get(0).title()).isEqualTo("线性代数");
        assertThat(data.items().get(0).documentScopeIds()).containsExactly(DOCUMENT_ID);
        assertThat(data.items().get(0).lastMessageAt()).isEqualTo(FIRST_ACTIVITY);
        assertThat(data.items().get(0).createdAt()).isEqualTo(CREATED);
        assertThat(data.items().get(1).documentScopeIds()).isEmpty();
        assertThat(data.items().get(1).lastMessageAt()).isNull();
        assertThat(data.nextCursor()).isNull();
    }

    @Test
    void encodesNextCursorFromTheLastReturnedRow() {
        when(conversationRepository.findUserPage(eq(TENANT_ID), eq(USER_ID), isNull(), isNull(), eq(false), eq(3)))
                .thenReturn(List.of(
                        row(FIRST_ID, "A", List.of(), FIRST_ACTIVITY),
                        row(SECOND_ID, "B", List.of(), SECOND_ACTIVITY),
                        row(THIRD_ID, "C", List.of(), null)));

        ConversationListData data = service.list(USER_ID, null, 2);

        assertThat(data.items()).extracting(ConversationListItemData::conversationId)
                .containsExactly(FIRST_ID, SECOND_ID);
        KeysetCursor cursor = KeysetCursor.decode(data.nextCursor()).orElseThrow();
        assertThat(cursor.id()).isEqualTo(SECOND_ID);
        assertThat(cursor.sortKey()).isEqualTo(SECOND_ACTIVITY);
    }

    @Test
    void resumesInsideTheNoMessageGroupWhenCursorCarriesNoSortKey() {
        String token = KeysetCursor.of(null, THIRD_ID).encode();
        when(conversationRepository.findUserPage(eq(TENANT_ID), eq(USER_ID), isNull(), eq(THIRD_ID), eq(true), eq(21)))
                .thenReturn(List.of(row(SECOND_ID, "B", List.of(), null)));

        ConversationListData data = service.list(USER_ID, token, 20);

        assertThat(data.items()).hasSize(1);
        assertThat(data.nextCursor()).isNull();
    }

    @Test
    void passesDecodedCursorDownToTheRepository() {
        String token = KeysetCursor.of(FIRST_ACTIVITY, FIRST_ID).encode();
        when(conversationRepository.findUserPage(
                eq(TENANT_ID), eq(USER_ID), eq(FIRST_ACTIVITY), eq(FIRST_ID), eq(false), eq(21)))
                .thenReturn(List.of());

        ConversationListData data = service.list(USER_ID, token, 20);

        assertThat(data.items()).isEmpty();
        assertThat(data.nextCursor()).isNull();
        assertThat(data.limit()).isEqualTo(20);
    }

    @Test
    void rejectsLimitOutsideTheContractRange() {
        assertThatThrownBy(() -> service.list(USER_ID, null, 0))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.COM_VALIDATION_001);
        assertThatThrownBy(() -> service.list(USER_ID, null, 101))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.COM_VALIDATION_001);

        verifyNoInteractions(conversationRepository);
    }

    @Test
    void rejectsMalformedCursorWithoutFallingBackToTheFirstPage() {
        assertThatThrownBy(() -> service.list(USER_ID, "not-a-cursor", 20))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.COM_VALIDATION_001);

        verifyNoInteractions(conversationRepository);
    }

    private static ConversationListRow row(UUID id, String title, List<UUID> scope, Instant lastMessageAt) {
        return new ConversationListRow(id, title, scope, lastMessageAt, CREATED);
    }
}
