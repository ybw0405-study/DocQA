package com.docqa.agentgateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.docqa.agentgateway.dto.AiQuota;
import com.docqa.agentgateway.dto.AiQuotaListData;
import com.docqa.agentgateway.repository.AdminAuditEntry;
import com.docqa.agentgateway.repository.AdminAuditRepository;
import com.docqa.agentgateway.repository.AiQuotaRepository;
import com.docqa.common.api.KeysetCursor;
import com.docqa.common.error.BusinessException;
import com.docqa.common.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdminQuotaServiceTest {

    private static final UUID ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID TARGET_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID OTHER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final Instant NOW = Instant.parse("2026-11-03T10:15:42.010Z");
    private static final Instant OLDER = Instant.parse("2026-11-02T16:41:55.271Z");

    @Mock
    private AiQuotaRepository quotaRepository;
    @Mock
    private AdminAuditRepository auditRepository;
    @Mock
    private TransactionRunner transactionRunner;

    private AdminQuotaService service;

    @BeforeEach
    void setUp() {
        service = new AdminQuotaService(
                quotaRepository, auditRepository, transactionRunner, Clock.fixed(NOW, ZoneOffset.UTC));
        lenient().doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(transactionRunner).execute(any(Runnable.class));
    }

    @Test
    void listsTheFirstPageWithoutACursor() {
        when(quotaRepository.findPage(null, null, 51))
                .thenReturn(List.of(quota(TARGET_ID, NOW), quota(OTHER_ID, OLDER)));

        AiQuotaListData page = service.list(null, 50);

        assertThat(page.items()).hasSize(2);
        assertThat(page.nextCursor()).isNull();
        assertThat(page.limit()).isEqualTo(50);
    }

    @Test
    void returnsACursorWhenAnExtraRowComesBack() {
        when(quotaRepository.findPage(null, null, 3))
                .thenReturn(List.of(quota(TARGET_ID, NOW), quota(OTHER_ID, OLDER), quota(ADMIN_ID, OLDER)));

        AiQuotaListData page = service.list(null, 2);

        assertThat(page.items()).hasSize(2);
        KeysetCursor cursor = KeysetCursor.decode(page.nextCursor()).orElseThrow();
        assertThat(cursor.sortKey()).isEqualTo(OLDER);
        assertThat(cursor.id()).isEqualTo(OTHER_ID);
    }

    @Test
    void resumesFromTheDecodedCursor() {
        String token = KeysetCursor.of(OLDER, OTHER_ID).encode();
        when(quotaRepository.findPage(OLDER, OTHER_ID, 2)).thenReturn(List.of(quota(TARGET_ID, NOW)));

        service.list(token, 1);

        verify(quotaRepository).findPage(OLDER, OTHER_ID, 2);
    }

    @Test
    void rejectsLimitsOutsideTheQuotaRange() {
        assertThatThrownBy(() -> service.list(null, 0))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.COM_VALIDATION_001);
        assertThatThrownBy(() -> service.list(null, 201))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.COM_VALIDATION_001);
        verify(quotaRepository, never()).findPage(any(), any(), anyInt());
    }

    @Test
    void rejectsMalformedCursors() {
        assertThatThrownBy(() -> service.list("not-a-cursor", 50))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.COM_VALIDATION_001);
    }

    @Test
    void replacesTheQuotaAndAuditsItInTheSameTransaction() {
        when(quotaRepository.findUsername(TARGET_ID)).thenReturn(Optional.of("zhang.wei"));

        AiQuota result = service.update(ADMIN_ID, TARGET_ID, 30L, 60_000L);

        verify(quotaRepository).upsert(TARGET_ID, 30L, 60_000L, ADMIN_ID, NOW);
        verify(quotaRepository, never()).delete(any());
        AdminAuditEntry entry = captureAudit();
        assertThat(entry.userId()).isEqualTo(ADMIN_ID);
        assertThat(entry.eventType()).isEqualTo(AdminQuotaService.EVENT_QUOTA_UPDATED);
        assertThat(entry.severity()).isEqualTo("INFO");
        assertThat(entry.payload()).isEqualTo(Map.of("targetUserId", TARGET_ID.toString()));
        assertThat(entry.traceId()).matches("[0-9a-f]{32}");
        assertThat(entry.occurredAt()).isEqualTo(NOW);
        assertThat(entry.ingestedAt()).isEqualTo(NOW);
        assertThat(result).isEqualTo(new AiQuota(TARGET_ID, "zhang.wei", 30L, 60_000L, NOW, ADMIN_ID));
    }

    @Test
    void clearingBothLimitsDeletesTheRowAndAuditsTheClearedState() {
        when(quotaRepository.findUsername(TARGET_ID)).thenReturn(Optional.of("zhang.wei"));

        AiQuota result = service.update(ADMIN_ID, TARGET_ID, null, null);

        verify(quotaRepository).delete(TARGET_ID);
        verify(quotaRepository, never()).upsert(any(), any(), any(), any(), any());
        assertThat(captureAudit().eventType()).isEqualTo(AdminQuotaService.EVENT_QUOTA_CLEARED);
        assertThat(result.dailyQuestionLimit()).isNull();
        assertThat(result.dailyTokenLimit()).isNull();
        assertThat(result.updatedAt()).isEqualTo(NOW);
        assertThat(result.updatedBy()).isEqualTo(ADMIN_ID);
    }

    @Test
    void keepsAOneSidedLimitAsAnOverride() {
        when(quotaRepository.findUsername(TARGET_ID)).thenReturn(Optional.of("zhang.wei"));

        service.update(ADMIN_ID, TARGET_ID, null, 60_000L);

        verify(quotaRepository).upsert(TARGET_ID, null, 60_000L, ADMIN_ID, NOW);
        verify(quotaRepository, never()).delete(any());
    }

    @Test
    void rejectsUnknownTargetUsersBeforeTouchingTheQuotaTable() {
        when(quotaRepository.findUsername(TARGET_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(ADMIN_ID, TARGET_ID, 30L, null))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.USR_NOT_FOUND_001);

        verify(quotaRepository, never()).upsert(any(), any(), any(), any(), any());
        verify(quotaRepository, never()).delete(any());
        verify(auditRepository, never()).insert(any());
        verify(transactionRunner, never()).execute(any(Runnable.class));
    }

    @Test
    void propagatesAuditFailuresSoTheWriteAndTheAuditRollBackTogether() {
        when(quotaRepository.findUsername(TARGET_ID)).thenReturn(Optional.of("zhang.wei"));
        org.mockito.Mockito.doThrow(new IllegalStateException("audit insert failed"))
                .when(auditRepository).insert(any(AdminAuditEntry.class));

        assertThatThrownBy(() -> service.update(ADMIN_ID, TARGET_ID, 30L, null))
                .isInstanceOf(IllegalStateException.class);

        verify(quotaRepository).upsert(TARGET_ID, 30L, null, ADMIN_ID, NOW);
    }

    private AdminAuditEntry captureAudit() {
        ArgumentCaptor<AdminAuditEntry> captor = ArgumentCaptor.forClass(AdminAuditEntry.class);
        verify(auditRepository).insert(captor.capture());
        return captor.getValue();
    }

    private static AiQuota quota(UUID userId, Instant updatedAt) {
        return new AiQuota(userId, "user-" + userId, 30L, 60_000L, updatedAt, ADMIN_ID);
    }
}
