package com.docqa.reminder.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.docqa.common.api.FieldViolation;
import com.docqa.common.error.BusinessException;
import com.docqa.common.error.ErrorCode;
import com.docqa.reminder.config.ReminderProperties;
import com.docqa.reminder.domain.IdempotencyOperation;
import com.docqa.reminder.domain.ReminderKind;
import com.docqa.reminder.domain.ReminderRow;
import com.docqa.reminder.domain.ReminderStatus;
import com.docqa.reminder.dto.CustomReminderCreateRequest;
import com.docqa.reminder.dto.DeadlineReminderCreateRequest;
import com.docqa.reminder.recurrence.RecurrenceCalculator;
import com.docqa.reminder.recurrence.RruleParser;
import com.docqa.reminder.repository.ReminderRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

@ExtendWith(MockitoExtension.class)
class ReminderServiceTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String IDEMPOTENCY_KEY = "idem-key-0001";
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    @Mock
    private ReminderRepository reminderRepository;
    @Mock
    private IdempotencyStore idempotencyStore;
    @Mock
    private RateLimiter rateLimiter;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final ReminderProperties properties = new ReminderProperties();
    private ReminderService service;

    @BeforeEach
    void setUp() {
        service = new ReminderService(
                reminderRepository,
                idempotencyStore,
                rateLimiter,
                new RruleParser(),
                new RecurrenceCalculator(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                objectMapper,
                properties);
        lenient().when(idempotencyStore.find(any(), any(), any())).thenReturn(Optional.empty());
        lenient().when(idempotencyStore.tryStart(any(), any(), any(), any(), any())).thenReturn(true);
    }

    @Test
    void createsDeadlineReminderWithMaterializedTrigger() {
        CreateReminderResult result = service.create(
                USER_ID,
                IDEMPOTENCY_KEY,
                deadline(NOW.plusSeconds(7200), 120));

        ArgumentCaptor<ReminderRow> captor = ArgumentCaptor.forClass(ReminderRow.class);
        verify(reminderRepository).insert(captor.capture());
        ReminderRow row = captor.getValue();
        assertThat(row.id()).isNotNull();
        assertThat(row.userId()).isEqualTo(USER_ID);
        assertThat(row.title()).isEqualTo("作业");
        assertThat(row.description()).isEqualTo("说明");
        assertThat(row.rrule()).isEqualTo("FREQ=DAILY;COUNT=1");
        assertThat(row.dtstart()).isEqualTo(NOW);
        assertThat(row.timezone()).isEqualTo("Asia/Shanghai");
        assertThat(row.exdates()).isEmpty();
        assertThat(row.status()).isEqualTo(ReminderStatus.ACTIVE);
        assertThat(row.sourceType()).isEqualTo("DEADLINE");
        assertThat(row.sourceUid()).isNull();
        assertThat(row.sourceSequence()).isZero();
        assertThat(row.idempotencyKey()).isEqualTo(IDEMPOTENCY_KEY);
        assertThat(row.nextTriggerAt()).isEqualTo(NOW);
        assertThat(row.lastTriggeredAt()).isNull();
        assertThat(row.version()).isZero();
        assertThat(row.createdAt()).isEqualTo(NOW);
        assertThat(row.updatedAt()).isEqualTo(NOW);

        assertThat(result.created()).isTrue();
        assertThat(result.data().reminderId()).isEqualTo(row.id());
        assertThat(result.data().status()).isEqualTo("ACTIVE");
        assertThat(result.data().kind()).isEqualTo("DEADLINE");
        assertThat(result.data().nextTriggerAt()).isEqualTo(NOW);
        assertThat(result.data().idempotentReplay()).isFalse();

        verify(rateLimiter).check(USER_ID);
        verify(idempotencyStore).complete(
                eq(USER_ID),
                eq(IdempotencyOperation.REMINDER_CREATE),
                eq(IDEMPOTENCY_KEY),
                anyString(),
                anyString(),
                eq(NOW));
    }

    @Test
    void createsCustomReminderAndNormalizesExdates() {
        CustomReminderCreateRequest request = new CustomReminderCreateRequest(
                ReminderKind.CUSTOM,
                "高等数学",
                "   ",
                "Asia/Shanghai",
                "FREQ=WEEKLY;BYDAY=MO",
                Instant.parse("2026-09-07T00:00:00Z"),
                List.of(
                        Instant.parse("2026-09-14T00:00:00.900Z"),
                        Instant.parse("2026-09-14T00:00:00Z")));

        CreateReminderResult result = service.create(USER_ID, IDEMPOTENCY_KEY, request);

        ArgumentCaptor<ReminderRow> captor = ArgumentCaptor.forClass(ReminderRow.class);
        verify(reminderRepository).insert(captor.capture());
        ReminderRow row = captor.getValue();
        assertThat(row.description()).isNull();
        assertThat(row.rrule()).isEqualTo("FREQ=WEEKLY;BYDAY=MO");
        assertThat(row.dtstart()).isEqualTo(Instant.parse("2026-09-07T00:00:00Z"));
        assertThat(row.exdates()).containsExactly(Instant.parse("2026-09-14T00:00:00Z"));
        assertThat(row.nextTriggerAt()).isEqualTo(Instant.parse("2026-09-07T00:00:00Z"));
        assertThat(row.sourceType()).isEqualTo("CUSTOM");
        assertThat(result.data().kind()).isEqualTo("CUSTOM");
        assertThat(result.data().exdates()).containsExactly(Instant.parse("2026-09-14T00:00:00Z"));
    }

    @Test
    void skipsExcludedFirstOccurrences() {
        CustomReminderCreateRequest request = new CustomReminderCreateRequest(
                ReminderKind.CUSTOM,
                "高等数学",
                null,
                "Asia/Shanghai",
                "FREQ=WEEKLY;BYDAY=MO",
                Instant.parse("2026-09-07T00:00:00Z"),
                List.of(
                        Instant.parse("2026-09-07T00:00:00Z"),
                        Instant.parse("2026-09-14T00:00:00Z")));

        service.create(USER_ID, IDEMPOTENCY_KEY, request);

        ArgumentCaptor<ReminderRow> captor = ArgumentCaptor.forClass(ReminderRow.class);
        verify(reminderRepository).insert(captor.capture());
        assertThat(captor.getValue().nextTriggerAt())
                .isEqualTo(Instant.parse("2026-09-21T00:00:00Z"));
    }

    @Test
    void rejectsDeadlineThatIsTooFarInThePast() {
        assertValidation(
                () -> service.create(
                        USER_ID,
                        IDEMPOTENCY_KEY,
                        deadline(NOW.minusSeconds(11 * 60), 0)),
                "dueAt");

        verify(reminderRepository, never()).insert(any());
        verify(idempotencyStore).clear(USER_ID, IdempotencyOperation.REMINDER_CREATE, IDEMPOTENCY_KEY);
    }

    @Test
    void rejectsUnknownTimezone() {
        DeadlineReminderCreateRequest request = new DeadlineReminderCreateRequest(
                ReminderKind.DEADLINE,
                "作业",
                null,
                "Mars/Olympus",
                NOW.plusSeconds(3600),
                0);

        assertValidation(() -> service.create(USER_ID, IDEMPOTENCY_KEY, request), "timezone");
        verify(reminderRepository, never()).insert(any());
    }

    @Test
    void rejectsUtcOffsetTimezone() {
        DeadlineReminderCreateRequest request = new DeadlineReminderCreateRequest(
                ReminderKind.DEADLINE,
                "作业",
                null,
                "+08:00",
                NOW.plusSeconds(3600),
                0);

        assertValidation(() -> service.create(USER_ID, IDEMPOTENCY_KEY, request), "timezone");
    }

    @Test
    void rejectsUnsupportedRrule() {
        CustomReminderCreateRequest request = custom("FREQ=HOURLY", NOW.plusSeconds(3600), List.of());

        assertValidation(() -> service.create(USER_ID, IDEMPOTENCY_KEY, request), "rrule");
        verify(reminderRepository, never()).insert(any());
    }

    @Test
    void rejectsRuleWithoutFutureOccurrence() {
        CustomReminderCreateRequest request = custom(
                "FREQ=DAILY;COUNT=1",
                NOW.minusSeconds(86400),
                List.of());

        assertValidation(() -> service.create(USER_ID, IDEMPOTENCY_KEY, request), "rrule");
    }

    @Test
    void rejectsTooManyExdates() {
        properties.setMaxExdates(1);
        CustomReminderCreateRequest request = custom(
                "FREQ=DAILY",
                NOW.plusSeconds(3600),
                List.of(NOW.plusSeconds(7200), NOW.plusSeconds(10800)));

        assertValidation(() -> service.create(USER_ID, IDEMPOTENCY_KEY, request), "exdates");
    }

    @Test
    void rejectsInvalidIdempotencyKeyBeforeTouchingRedisOrDatabase() {
        assertThatThrownBy(() -> service.create(USER_ID, "short", deadline(NOW.plusSeconds(3600), 0)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.COM_VALIDATION_001);
        assertThatThrownBy(() -> service.create(
                USER_ID,
                "k".repeat(65),
                deadline(NOW.plusSeconds(3600), 0)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.COM_VALIDATION_001);
        assertThatThrownBy(() -> service.create(
                USER_ID,
                " ".repeat(8),
                deadline(NOW.plusSeconds(3600), 0)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.COM_VALIDATION_001);

        verifyNoInteractions(rateLimiter, idempotencyStore, reminderRepository);
    }

    @Test
    void replaysCompletedIdempotencyRecord() {
        DeadlineReminderCreateRequest request = deadline(NOW.plusSeconds(7200), 120);
        CreateReminderResult first = service.create(USER_ID, IDEMPOTENCY_KEY, request);

        ArgumentCaptor<String> fingerprint = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> response = ArgumentCaptor.forClass(String.class);
        verify(idempotencyStore).complete(
                eq(USER_ID),
                eq(IdempotencyOperation.REMINDER_CREATE),
                eq(IDEMPOTENCY_KEY),
                fingerprint.capture(),
                response.capture(),
                eq(NOW));
        when(idempotencyStore.find(any(), any(), any())).thenReturn(Optional.of(new IdempotencyRecord(
                IdempotencyStatus.COMPLETED,
                fingerprint.getValue(),
                response.getValue(),
                NOW)));

        CreateReminderResult replay = service.create(USER_ID, IDEMPOTENCY_KEY, request);

        assertThat(replay.created()).isFalse();
        assertThat(replay.data().idempotentReplay()).isTrue();
        assertThat(replay.data().reminderId()).isEqualTo(first.data().reminderId());
        verify(reminderRepository, times(1)).insert(any());
    }

    @Test
    void rejectsSameKeyWithDifferentRequest() {
        DeadlineReminderCreateRequest request = deadline(NOW.plusSeconds(7200), 120);
        service.create(USER_ID, IDEMPOTENCY_KEY, request);

        ArgumentCaptor<String> fingerprint = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> response = ArgumentCaptor.forClass(String.class);
        verify(idempotencyStore).complete(
                eq(USER_ID),
                eq(IdempotencyOperation.REMINDER_CREATE),
                eq(IDEMPOTENCY_KEY),
                fingerprint.capture(),
                response.capture(),
                eq(NOW));
        when(idempotencyStore.find(any(), any(), any())).thenReturn(Optional.of(new IdempotencyRecord(
                IdempotencyStatus.COMPLETED,
                fingerprint.getValue(),
                response.getValue(),
                NOW)));

        assertThatThrownBy(() -> service.create(
                USER_ID,
                IDEMPOTENCY_KEY,
                deadline(NOW.plusSeconds(9000), 120)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.REM_CONFLICT_001);
    }

    @Test
    void rejectsKeyThatIsStillInProgress() {
        when(idempotencyStore.find(any(), any(), any())).thenReturn(Optional.of(new IdempotencyRecord(
                IdempotencyStatus.IN_PROGRESS,
                "fingerprint",
                null,
                NOW)));

        assertThatThrownBy(() -> service.create(
                USER_ID,
                IDEMPOTENCY_KEY,
                deadline(NOW.plusSeconds(3600), 0)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.REM_CONFLICT_001);

        verify(reminderRepository, never()).insert(any());
    }

    @Test
    void rejectsWhenAnotherRequestClaimsTheKey() {
        when(idempotencyStore.find(any(), any(), any())).thenReturn(
                Optional.empty(),
                Optional.of(new IdempotencyRecord(IdempotencyStatus.IN_PROGRESS, "other", null, NOW)));
        when(idempotencyStore.tryStart(any(), any(), any(), any(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.create(
                USER_ID,
                IDEMPOTENCY_KEY,
                deadline(NOW.plusSeconds(3600), 0)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.REM_CONFLICT_001);

        verify(reminderRepository, never()).insert(any());
    }

    @Test
    void fallsBackToDatabaseUniqueKeyForReplay() {
        DeadlineReminderCreateRequest request = deadline(NOW.plusSeconds(7200), 120);
        ReminderRow existing = row(Instant.parse("2026-09-01T02:00:00Z"));
        doThrow(new DuplicateKeyException("duplicate")).when(reminderRepository).insert(any());
        when(reminderRepository.findByUserAndIdempotencyKey(USER_ID, IDEMPOTENCY_KEY))
                .thenReturn(Optional.of(existing));

        CreateReminderResult result = service.create(USER_ID, IDEMPOTENCY_KEY, request);

        assertThat(result.created()).isFalse();
        assertThat(result.data().reminderId()).isEqualTo(existing.id());
        assertThat(result.data().idempotentReplay()).isTrue();
        verify(idempotencyStore).complete(
                eq(USER_ID),
                eq(IdempotencyOperation.REMINDER_CREATE),
                eq(IDEMPOTENCY_KEY),
                anyString(),
                anyString(),
                eq(NOW));
    }

    private DeadlineReminderCreateRequest deadline(Instant dueAt, int leadMinutes) {
        return new DeadlineReminderCreateRequest(
                ReminderKind.DEADLINE,
                " 作业 ",
                " 说明 ",
                "Asia/Shanghai",
                dueAt,
                leadMinutes);
    }

    private CustomReminderCreateRequest custom(String rrule, Instant dtstart, List<Instant> exdates) {
        return new CustomReminderCreateRequest(
                ReminderKind.CUSTOM,
                "高等数学",
                null,
                "Asia/Shanghai",
                rrule,
                dtstart,
                exdates);
    }

    private ReminderRow row(Instant nextTriggerAt) {
        return new ReminderRow(
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                USER_ID,
                "作业",
                "说明",
                "FREQ=DAILY;COUNT=1",
                nextTriggerAt,
                "Asia/Shanghai",
                0,
                List.of(),
                List.of(),
                ReminderStatus.ACTIVE,
                "DEADLINE",
                null,
                0,
                IDEMPOTENCY_KEY,
                nextTriggerAt,
                null,
                0L,
                NOW,
                NOW);
    }

    private void assertValidation(Runnable action, String field) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException exception = (BusinessException) ex;
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.REM_VALIDATION_001);
                    assertThat(exception.getFieldViolations())
                            .extracting(FieldViolation::field)
                            .containsExactly(field);
                });
    }
}
