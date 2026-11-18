package com.docqa.agentgateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.docqa.agentgateway.dto.MessageHistoryData;
import com.docqa.agentgateway.dto.MessageHistoryItemData;
import com.docqa.agentgateway.dto.MessageHistoryRow;
import com.docqa.agentgateway.repository.ConversationRepository;
import com.docqa.agentgateway.repository.MessageRepository;
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
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConversationHistoryServiceTest {

    private static final UUID TENANT_ID = TenantContext.DEFAULT_TENANT_ID;
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID CONVERSATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID FIRST_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID SECOND_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID THIRD_ID = UUID.fromString("00000000-0000-0000-0000-000000000005");
    private static final Instant FIRST_CREATED = Instant.parse("2026-10-20T10:20:00Z");
    private static final Instant SECOND_CREATED = Instant.parse("2026-10-20T10:15:00Z");

    @Mock
    private ConversationRepository conversationRepository;
    @Mock
    private MessageRepository messageRepository;

    private ConversationHistoryService service;

    @BeforeEach
    void setUp() {
        TenantContext.set(TENANT_ID);
        service = new ConversationHistoryService(conversationRepository, messageRepository, new PiiMasker());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void masksStoredContentWhenReadingHistoryBack() {
        when(conversationRepository.existsByIdAndUser(TENANT_ID, CONVERSATION_ID, USER_ID)).thenReturn(true);
        when(messageRepository.findPage(eq(TENANT_ID), eq(CONVERSATION_ID), isNull(), isNull(), eq(3)))
                .thenReturn(List.of(
                        row(FIRST_ID, "user", "请联系 zhangwei@example.com 或 13800138000", FIRST_CREATED),
                        row(SECOND_ID, "assistant", "已在笔记中定位到该公式", SECOND_CREATED)));

        MessageHistoryData data = service.list(USER_ID, CONVERSATION_ID, null, 2);

        assertThat(data.conversationId()).isEqualTo(CONVERSATION_ID);
        assertThat(data.limit()).isEqualTo(2);
        assertThat(data.items()).hasSize(2);
        assertThat(data.items().get(0).content()).isEqualTo("请联系 [EMAIL] 或 [PHONE]");
        assertThat(data.items().get(0).role()).isEqualTo("user");
        assertThat(data.items().get(0).traceId()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(data.items().get(0).tokenCount()).isEqualTo(24);
        assertThat(data.items().get(1).content()).doesNotContain("[EMAIL]");
    }

    @Test
    void checksOwnershipBeforeTouchingTheMessageTable() {
        when(conversationRepository.existsByIdAndUser(TENANT_ID, CONVERSATION_ID, USER_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.list(USER_ID, CONVERSATION_ID, null, 20))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.AGT_NOT_FOUND_001);
        verifyNoInteractions(messageRepository);
    }

    @Test
    void readsOwnershipThenPagesMessagesInThatOrder() {
        when(conversationRepository.existsByIdAndUser(TENANT_ID, CONVERSATION_ID, USER_ID)).thenReturn(true);
        when(messageRepository.findPage(eq(TENANT_ID), eq(CONVERSATION_ID), isNull(), isNull(), eq(21)))
                .thenReturn(List.of());

        MessageHistoryData data = service.list(USER_ID, CONVERSATION_ID, null, 20);

        assertThat(data.items()).isEmpty();
        assertThat(data.nextCursor()).isNull();
        InOrder inOrder = inOrder(conversationRepository, messageRepository);
        inOrder.verify(conversationRepository).existsByIdAndUser(TENANT_ID, CONVERSATION_ID, USER_ID);
        inOrder.verify(messageRepository).findPage(TENANT_ID, CONVERSATION_ID, null, null, 21);
    }

    @Test
    void encodesNextCursorFromTheLastReturnedRow() {
        when(conversationRepository.existsByIdAndUser(TENANT_ID, CONVERSATION_ID, USER_ID)).thenReturn(true);
        when(messageRepository.findPage(eq(TENANT_ID), eq(CONVERSATION_ID), isNull(), isNull(), eq(3)))
                .thenReturn(List.of(
                        row(FIRST_ID, "user", "第一问", FIRST_CREATED),
                        row(SECOND_ID, "assistant", "第一答", SECOND_CREATED),
                        row(THIRD_ID, "user", "不应返回", SECOND_CREATED.minusSeconds(60))));

        MessageHistoryData data = service.list(USER_ID, CONVERSATION_ID, null, 2);

        assertThat(data.items()).hasSize(2);
        assertThat(data.items()).extracting(MessageHistoryItemData::messageId).containsExactly(FIRST_ID, SECOND_ID);
        KeysetCursor cursor = KeysetCursor.decode(data.nextCursor()).orElseThrow();
        assertThat(cursor.id()).isEqualTo(SECOND_ID);
        assertThat(cursor.sortKey()).isEqualTo(SECOND_CREATED);
    }

    @Test
    void passesDecodedCursorDownToTheRepository() {
        String token = KeysetCursor.of(SECOND_CREATED, SECOND_ID).encode();
        when(conversationRepository.existsByIdAndUser(TENANT_ID, CONVERSATION_ID, USER_ID)).thenReturn(true);
        when(messageRepository.findPage(TENANT_ID, CONVERSATION_ID, SECOND_CREATED, SECOND_ID, 11))
                .thenReturn(List.of());

        assertThat(service.list(USER_ID, CONVERSATION_ID, token, 10).items()).isEmpty();
    }

    @Test
    void rejectsLimitOutsideTheContractRange() {
        assertThatThrownBy(() -> service.list(USER_ID, CONVERSATION_ID, null, 0))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.COM_VALIDATION_001);
        assertThatThrownBy(() -> service.list(USER_ID, CONVERSATION_ID, null, 101))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.COM_VALIDATION_001);
        verifyNoInteractions(conversationRepository);
        verifyNoInteractions(messageRepository);
    }

    @Test
    void rejectsMalformedOrForeignCursor() {
        assertThatThrownBy(() -> service.list(USER_ID, CONVERSATION_ID, "not-a-cursor", 20))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.COM_VALIDATION_001);

        String reminderStyleToken = KeysetCursor.of(null, SECOND_ID).encode();
        assertThatThrownBy(() -> service.list(USER_ID, CONVERSATION_ID, reminderStyleToken, 20))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.COM_VALIDATION_001);
        verifyNoInteractions(messageRepository);
    }

    private static MessageHistoryRow row(UUID id, String role, String content, Instant createdAt) {
        return new MessageHistoryRow(id, role, content, 24, "4bf92f3577b34da6a3ce929d0e0e4736", createdAt);
    }
}
