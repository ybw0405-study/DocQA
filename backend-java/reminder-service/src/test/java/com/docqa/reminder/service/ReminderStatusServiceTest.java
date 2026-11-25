package com.docqa.reminder.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.docqa.common.error.BusinessException;
import com.docqa.common.error.ErrorCode;
import com.docqa.common.tenant.TenantContext;
import com.docqa.reminder.domain.IdempotencyOperation;
import com.docqa.reminder.domain.ReminderRow;
import com.docqa.reminder.domain.ReminderStatus;
import com.docqa.reminder.dto.ReminderStatusUpdateData;
import com.docqa.reminder.dto.ReminderStatusUpdateRequest;
import com.docqa.reminder.recurrence.RecurrenceCalculator;
import com.docqa.reminder.recurrence.RruleParser;
import com.docqa.reminder.repository.ReminderRepository;
import com.docqa.reminder.support.Fingerprints;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 判定顺序的五种结果各占一条主用例：404 → 同态 no-op → 非法边 → 恢复无未来 occurrence → CAS 0 行。
 * 顺序本身就是契约（非法边必须先于 CAS，否则带正确版本的「改回已完成」能倒退），所以每条评论都写明本用例排除了哪一层。
 */
@ExtendWith(MockitoExtension.class)
class ReminderStatusServiceTest {

    private static final UUID TENANT_ID = TenantContext.DEFAULT_TENANT_ID;
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID REMINDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final String IDEMPOTENCY_KEY = "idem-key-0001";
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant PAUSED_CURSOR = Instant.parse("2026-08-31T23:00:00Z");
    private static final Instant MONDAY_CLASS = Instant.parse("2026-09-07T00:00:00Z");
    private static final Instant RESUMED_TRIGGER = Instant.parse("2026-09-06T23:50:00Z");

    @Mock
    private ReminderRepository reminderRepository;
    @Mock
    private IdempotencyStore idempotencyStore;
    @Mock
    private RateLimiter rateLimiter;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private ReminderStatusService service;

    @BeforeEach
    void setUp() {
        service = new ReminderStatusService(
                reminderRepository,
                idempotencyStore,
                rateLimiter,
                new RruleParser(),
                new RecurrenceCalculator(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                objectMapper);
        lenient().when(idempotencyStore.find(any(), any(), any())).thenReturn(Optional.empty());
        lenient().when(idempotencyStore.tryStart(any(), any(), any(), any(), any())).thenReturn(true);
        TenantContext.set(TENANT_ID);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void pauseKeepsTheTriggerCursorAndAdvancesTheVersion() {
        given(row(ReminderStatus.ACTIVE, "FREQ=WEEKLY;BYDAY=MO", MONDAY_CLASS, 10, PAUSED_CURSOR, 3L));

        ReminderStatusUpdateData data = service.update(
                USER_ID, REMINDER_ID, IDEMPOTENCY_KEY, request(ReminderStatus.PAUSED, 3L));

        assertThat(data.reminderId()).isEqualTo(REMINDER_ID);
        assertThat(data.status()).isEqualTo("PAUSED");
        // 暂停不清空游标，「本来下次是几点」是暂停区唯一有用的信息。
        assertThat(data.nextTriggerAt()).isEqualTo(PAUSED_CURSOR);
        assertThat(data.version()).isEqualTo(4L);
        assertThat(data.idempotentReplay()).isFalse();
        verify(rateLimiter).check(USER_ID);
        verify(reminderRepository).updateStatus(TENANT_ID, REMINDER_ID, USER_ID, 3L, ReminderStatus.PAUSED, PAUSED_CURSOR, NOW);
        verify(idempotencyStore).complete(
                eq(USER_ID),
                eq(IdempotencyOperation.REMINDER_UPDATE),
                eq(IDEMPOTENCY_KEY),
                anyString(),
                anyString(),
                eq(NOW));
    }

    @Test
    void terminalTargetsClearTheCursorSoTheSchedulerStopsSeeingTheRow() {
        for (ReminderStatus terminal : List.of(ReminderStatus.COMPLETED, ReminderStatus.CANCELLED)) {
            clearInvocations(reminderRepository, idempotencyStore, rateLimiter);
            given(row(ReminderStatus.ACTIVE, "FREQ=WEEKLY;BYDAY=MO", MONDAY_CLASS, 10, PAUSED_CURSOR, 5L));

            ReminderStatusUpdateData data = service.update(
                    USER_ID, REMINDER_ID, IDEMPOTENCY_KEY + terminal, request(terminal, 5L));

            assertThat(data.status()).isEqualTo(terminal.name());
            assertThat(data.nextTriggerAt()).isNull();
            assertThat(data.version()).isEqualTo(6L);
            verify(reminderRepository).updateStatus(TENANT_ID, REMINDER_ID, USER_ID, 5L, terminal, null, NOW);
        }
    }

    @Test
    void sameStatusIsANoOpThatSkipsTheVersionCheckAndWritesNothing() {
        // 行上是版本 3、游标 X，请求带着过期读出的版本 99：no-op 既不比版本也不写库。
        given(row(ReminderStatus.ACTIVE, "FREQ=WEEKLY;BYDAY=MO", MONDAY_CLASS, 10, PAUSED_CURSOR, 3L));

        ReminderStatusUpdateData data = service.update(
                USER_ID, REMINDER_ID, IDEMPOTENCY_KEY, request(ReminderStatus.ACTIVE, 99L));

        assertThat(data.status()).isEqualTo("ACTIVE");
        assertThat(data.nextTriggerAt()).isEqualTo(PAUSED_CURSOR);
        // 响应里的 version 取当前行的真实值，客户端因此能看出「99 是过期读出」——这就是没有第二次副作用的证据。
        assertThat(data.version()).isEqualTo(3L);
        verifyNoWrite();
        verify(idempotencyStore).complete(
                eq(USER_ID),
                eq(IdempotencyOperation.REMINDER_UPDATE),
                eq(IDEMPOTENCY_KEY),
                anyString(),
                anyString(),
                eq(NOW));
    }

    @Test
    void aReminderOwnedBySomeoneElseLooksExactlyLikeItDoesNotExist() {
        when(reminderRepository.findOwned(TENANT_ID, USER_ID, REMINDER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(
                USER_ID, REMINDER_ID, IDEMPOTENCY_KEY, request(ReminderStatus.PAUSED, 0L)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.REM_NOT_FOUND_001);

        verifyNoWrite();
        verify(idempotencyStore).clear(USER_ID, IdempotencyOperation.REMINDER_UPDATE, IDEMPOTENCY_KEY);
    }

    @Test
    void aTerminalReminderCannotBeRevivedEvenWithItsCurrentVersion() {
        // 边先于 CAS：版本对得上也不能把已完成改回进行中。
        given(row(ReminderStatus.COMPLETED, "FREQ=WEEKLY;BYDAY=MO", MONDAY_CLASS, 0, null, 7L));

        assertThatThrownBy(() -> service.update(
                USER_ID, REMINDER_ID, IDEMPOTENCY_KEY, request(ReminderStatus.PAUSED, 7L)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.REM_CONFLICT_004);

        verifyNoWrite();
    }

    @Test
    void resumeRecalculatesFromTheRuleInsteadOfReusingTheStalePausedCursor() {
        given(row(ReminderStatus.PAUSED, "FREQ=WEEKLY;BYDAY=MO", MONDAY_CLASS, 10, PAUSED_CURSOR, 2L));

        ReminderStatusUpdateData data = service.update(
                USER_ID, REMINDER_ID, IDEMPOTENCY_KEY, request(ReminderStatus.ACTIVE, 2L));

        // 阈值工作在上课时刻域（2026-09-07 那节课），物化时再减回提前量：00:00 − 10 min。
        assertThat(data.nextTriggerAt()).isEqualTo(RESUMED_TRIGGER);
        assertThat(data.status()).isEqualTo("ACTIVE");
        assertThat(data.version()).isEqualTo(3L);
        verify(reminderRepository).updateStatus(
                TENANT_ID, REMINDER_ID, USER_ID, 2L, ReminderStatus.ACTIVE, RESUMED_TRIGGER, NOW);
    }

    @Test
    void resumeLeavesThePausedRowUntouchedWhenTheRuleHasNoOccurrenceLeft() {
        // 选 ③：算不出下一时刻时既不写「ACTIVE + 空游标」的永不触发行，也不替用户改成已完成。
        given(row(ReminderStatus.PAUSED, "FREQ=DAILY;COUNT=1", NOW.minusSeconds(86400), 0, PAUSED_CURSOR, 4L));

        assertThatThrownBy(() -> service.update(
                USER_ID, REMINDER_ID, IDEMPOTENCY_KEY, request(ReminderStatus.ACTIVE, 4L)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.REM_CONFLICT_004);

        verifyNoWrite();
        verify(idempotencyStore).clear(USER_ID, IdempotencyOperation.REMINDER_UPDATE, IDEMPOTENCY_KEY);
    }

    @Test
    void losingTheCompareAndSwapIsReportedAsAVersionConflict() {
        given(row(ReminderStatus.ACTIVE, "FREQ=WEEKLY;BYDAY=MO", MONDAY_CLASS, 0, PAUSED_CURSOR, 3L));
        // 后声明的精确桩覆盖 given() 里的通用成功桩：这一条要的正是 0 行。
        when(reminderRepository.updateStatus(
                TENANT_ID, REMINDER_ID, USER_ID, 3L, ReminderStatus.PAUSED, PAUSED_CURSOR, NOW)).thenReturn(0);

        assertThatThrownBy(() -> service.update(
                USER_ID, REMINDER_ID, IDEMPOTENCY_KEY, request(ReminderStatus.PAUSED, 3L)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.REM_CONFLICT_003);

        verify(idempotencyStore).clear(USER_ID, IdempotencyOperation.REMINDER_UPDATE, IDEMPOTENCY_KEY);
    }

    @Test
    void theCasPredicateUsesTheVersionFromTheRequestRatherThanTheRow() {
        // 行已被调度器推进到 9，请求仍说 2：必须拿请求的 2 去比，用行上的 9 就等于静默覆盖并发写。
        given(row(ReminderStatus.ACTIVE, "FREQ=WEEKLY;BYDAY=MO", MONDAY_CLASS, 0, PAUSED_CURSOR, 9L));

        ReminderStatusUpdateData data = service.update(
                USER_ID, REMINDER_ID, IDEMPOTENCY_KEY, request(ReminderStatus.PAUSED, 2L));

        ArgumentCaptor<Long> expected = ArgumentCaptor.forClass(Long.class);
        verify(reminderRepository).updateStatus(
                eq(TENANT_ID), eq(REMINDER_ID), eq(USER_ID), expected.capture(),
                eq(ReminderStatus.PAUSED), eq(PAUSED_CURSOR), any());
        assertThat(expected.getValue()).isEqualTo(2L);
        assertThat(data.version()).isEqualTo(3L);
    }

    @Test
    void theWriteTimestampIsTruncatedToWholeSecondsToMatchTheCheckConstraint() {
        ReminderStatusService atMillisecond = newService(Clock.fixed(NOW.plusMillis(470), ZoneOffset.UTC));
        given(row(ReminderStatus.ACTIVE, "FREQ=WEEKLY;BYDAY=MO", MONDAY_CLASS, 0, PAUSED_CURSOR, 0L));

        ReminderStatusUpdateData data = atMillisecond.update(
                USER_ID, REMINDER_ID, IDEMPOTENCY_KEY, request(ReminderStatus.PAUSED, 0L));

        assertThat(data.updatedAt()).isEqualTo(NOW);
        verify(reminderRepository).updateStatus(TENANT_ID, REMINDER_ID, USER_ID, 0L, ReminderStatus.PAUSED, PAUSED_CURSOR, NOW);
    }

    @Test
    void theFingerprintBindsReminderIdStatusAndExpectedVersion() {
        given(row(ReminderStatus.ACTIVE, "FREQ=WEEKLY;BYDAY=MO", MONDAY_CLASS, 0, PAUSED_CURSOR, 3L));

        service.update(USER_ID, REMINDER_ID, IDEMPOTENCY_KEY, request(ReminderStatus.PAUSED, 3L));

        ArgumentCaptor<String> fingerprint = ArgumentCaptor.forClass(String.class);
        verify(idempotencyStore).tryStart(
                eq(USER_ID), eq(IdempotencyOperation.REMINDER_UPDATE), eq(IDEMPOTENCY_KEY),
                fingerprint.capture(), eq(NOW));
        assertThat(fingerprint.getValue()).isEqualTo(Fingerprints.sha256(REMINDER_ID + "|PAUSED|3"));
    }

    @Test
    void replaysTheStoredResponseWithoutWritingAgain() {
        ReminderStatusUpdateData stored =
                new ReminderStatusUpdateData(REMINDER_ID, "PAUSED", PAUSED_CURSOR, 4L, NOW, false);
        when(idempotencyStore.find(any(), any(), any())).thenReturn(Optional.of(new IdempotencyRecord(
                IdempotencyStatus.COMPLETED,
                Fingerprints.sha256(REMINDER_ID + "|PAUSED|3"),
                write(stored),
                NOW)));

        ReminderStatusUpdateData data = service.update(
                USER_ID, REMINDER_ID, IDEMPOTENCY_KEY, request(ReminderStatus.PAUSED, 3L));

        assertThat(data.idempotentReplay()).isTrue();
        assertThat(data.version()).isEqualTo(4L);
        assertThat(data.nextTriggerAt()).isEqualTo(PAUSED_CURSOR);
        verifyNoInteractions(reminderRepository);
    }

    @Test
    void theSameKeyWithADifferentVersionIsRejectedAsAMismatch() {
        when(idempotencyStore.find(any(), any(), any())).thenReturn(Optional.of(new IdempotencyRecord(
                IdempotencyStatus.COMPLETED,
                Fingerprints.sha256(REMINDER_ID + "|PAUSED|2"),
                write(new ReminderStatusUpdateData(REMINDER_ID, "PAUSED", PAUSED_CURSOR, 3L, NOW, false)),
                NOW)));

        assertThatThrownBy(() -> service.update(
                USER_ID, REMINDER_ID, IDEMPOTENCY_KEY, request(ReminderStatus.PAUSED, 3L)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.REM_CONFLICT_001);

        verifyNoInteractions(reminderRepository);
    }

    @Test
    void anAttemptStillInFlightIsRejectedWithoutTouchingTheRow() {
        when(idempotencyStore.find(any(), any(), any())).thenReturn(Optional.of(
                new IdempotencyRecord(IdempotencyStatus.IN_PROGRESS, "fingerprint", null, NOW)));

        assertThatThrownBy(() -> service.update(
                USER_ID, REMINDER_ID, IDEMPOTENCY_KEY, request(ReminderStatus.PAUSED, 3L)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.REM_CONFLICT_001);

        verifyNoInteractions(reminderRepository);
    }

    @Test
    void aWinnerThatClaimedTheKeyConcurrentlyIsReportedAsStillInProgress() {
        when(idempotencyStore.find(any(), any(), any())).thenReturn(
                Optional.empty(),
                Optional.of(new IdempotencyRecord(IdempotencyStatus.IN_PROGRESS, "other", null, NOW)));
        when(idempotencyStore.tryStart(any(), any(), any(), any(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.update(
                USER_ID, REMINDER_ID, IDEMPOTENCY_KEY, request(ReminderStatus.PAUSED, 3L)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.REM_CONFLICT_001);

        verifyNoInteractions(reminderRepository);
    }

    @Test
    void anInvalidIdempotencyKeyNeverReachesRedisOrTheDatabase() {
        assertThatThrownBy(() -> service.update(
                USER_ID, REMINDER_ID, "short", request(ReminderStatus.PAUSED, 0L)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.COM_VALIDATION_001);

        verifyNoInteractions(rateLimiter, idempotencyStore, reminderRepository);
    }

    @Test
    void aFailureToRecordIdempotencyNeverUndoesASuccessfulWrite() {
        given(row(ReminderStatus.ACTIVE, "FREQ=WEEKLY;BYDAY=MO", MONDAY_CLASS, 0, PAUSED_CURSOR, 3L));
        doThrow(new IllegalStateException("redis down"))
                .when(idempotencyStore)
                .complete(any(), any(), any(), any(), any(), any());

        ReminderStatusUpdateData data = service.update(
                USER_ID, REMINDER_ID, IDEMPOTENCY_KEY, request(ReminderStatus.PAUSED, 3L));

        assertThat(data.version()).isEqualTo(4L);
        verify(idempotencyStore).clear(USER_ID, IdempotencyOperation.REMINDER_UPDATE, IDEMPOTENCY_KEY);
    }

    @Test
    void anUnreadableIdempotencyRecordBecomesAnInternalErrorRatherThanASilentRewrite() {
        when(idempotencyStore.find(any(), any(), any())).thenReturn(Optional.of(new IdempotencyRecord(
                IdempotencyStatus.COMPLETED,
                Fingerprints.sha256(REMINDER_ID + "|PAUSED|3"),
                "{not-json",
                NOW)));

        assertThatThrownBy(() -> service.update(
                USER_ID, REMINDER_ID, IDEMPOTENCY_KEY, request(ReminderStatus.PAUSED, 3L)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.COM_INTERNAL_001);

        verifyNoInteractions(reminderRepository);
    }

    private ReminderStatusService newService(Clock clock) {
        return new ReminderStatusService(
                reminderRepository,
                idempotencyStore,
                rateLimiter,
                new RruleParser(),
                new RecurrenceCalculator(),
                clock,
                objectMapper);
    }

    private void given(ReminderRow row) {
        when(reminderRepository.findOwned(TENANT_ID, USER_ID, REMINDER_ID)).thenReturn(Optional.of(row));
        // CAS 默认赢：各用例只在自己关心结果时才覆盖这条桩。
        lenient().when(reminderRepository.updateStatus(any(), any(), any(), anyLong(), any(), any(), any())).thenReturn(1);
    }

    private void verifyNoWrite() {
        verify(reminderRepository, never()).updateStatus(any(), any(), any(), anyLong(), any(), any(), any());
    }

    private ReminderStatusUpdateRequest request(ReminderStatus status, long version) {
        return new ReminderStatusUpdateRequest(status, version);
    }

    private String write(ReminderStatusUpdateData data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private ReminderRow row(
            ReminderStatus status,
            String rrule,
            Instant dtstart,
            int leadMinutes,
            Instant nextTriggerAt,
            long version) {
        return new ReminderRow(
                REMINDER_ID,
                USER_ID,
                "线性代数",
                null,
                rrule,
                dtstart,
                "Asia/Shanghai",
                leadMinutes,
                List.of(),
                List.of(),
                status,
                "COURSE",
                "linear-algebra-2026-fall-mon-0800",
                1,
                IDEMPOTENCY_KEY,
                nextTriggerAt,
                null,
                version,
                NOW,
                NOW);
    }
}
