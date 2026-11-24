package com.docqa.reminder.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.docqa.common.api.KeysetCursor;
import com.docqa.common.error.BusinessException;
import com.docqa.common.error.ErrorCode;
import com.docqa.common.tenant.TenantContext;
import com.docqa.reminder.config.ReminderProperties;
import com.docqa.reminder.domain.ReminderRow;
import com.docqa.reminder.domain.ReminderStatus;
import com.docqa.reminder.dto.ReminderListData;
import com.docqa.reminder.dto.ReminderListItemData;
import com.docqa.reminder.recurrence.RecurrenceCalculator;
import com.docqa.reminder.recurrence.RruleParser;
import com.docqa.reminder.repository.ReminderRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReminderListServiceTest {

    private static final UUID TENANT_ID = TenantContext.DEFAULT_TENANT_ID;
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID FIRST_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID SECOND_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final Instant NOW = Instant.parse("2026-10-20T10:00:00Z");
    private static final Instant FIRST_TRIGGER = Instant.parse("2026-10-25T13:59:59Z");
    private static final Instant SECOND_TRIGGER = Instant.parse("2026-10-26T13:59:59Z");

    @Mock
    private ReminderRepository reminderRepository;
    @Mock
    private IdempotencyStore idempotencyStore;
    @Mock
    private RateLimiter rateLimiter;

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
                new ObjectMapper().findAndRegisterModules(),
                new ReminderProperties());
        TenantContext.set(TENANT_ID);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void mapsRowsIntoDashboardItemsWithKindAndExdates() {
        when(reminderRepository.findUserPage(eq(TENANT_ID), eq(USER_ID), isNull(), isNull(), isNull(), eq(false), eq(21)))
                .thenReturn(List.of(row(FIRST_ID, ReminderStatus.ACTIVE, FIRST_TRIGGER, "DEADLINE", List.of(), 0)));

        ReminderListData data = service.list(USER_ID, null, null, 20);

        assertThat(data.items()).hasSize(1);
        ReminderListItemData item = data.items().get(0);
        assertThat(item.reminderId()).isEqualTo(FIRST_ID);
        assertThat(item.kind()).isEqualTo("DEADLINE");
        assertThat(item.status()).isEqualTo("ACTIVE");
        assertThat(item.nextTriggerAt()).isEqualTo(FIRST_TRIGGER);
        assertThat(item.rrule()).isEqualTo("FREQ=DAILY;COUNT=1");
        assertThat(item.exdates()).isEmpty();
        assertThat(item.leadMinutes()).isZero();
        assertThat(data.nextCursor()).isNull();
    }

    /**
     * 契约把 `leadMinutes` 定为看板行必填，且只有课表行携带真实提前量（的承接项）；
     * 早先的记录里根本没有这个分量，响应会整键缺失——严格客户端因此读不到「提前 N 分钟」。
     */
    @Test
    void projectsTheCourseLeadMinutesOntoTheDashboardItem() {
        when(reminderRepository.findUserPage(eq(TENANT_ID), eq(USER_ID), isNull(), isNull(), isNull(), eq(false), eq(21)))
                .thenReturn(List.of(row(FIRST_ID, ReminderStatus.ACTIVE, FIRST_TRIGGER, "COURSE", List.of(), 30)));

        ReminderListData data = service.list(USER_ID, null, null, 20);

        ReminderListItemData item = data.items().get(0);
        assertThat(item.kind()).isEqualTo("COURSE");
        assertThat(item.leadMinutes()).isEqualTo(30);
    }

    @Test
    void encodesNextCursorFromTheLastReturnedRow() {
        UUID lookaheadId = UUID.fromString("00000000-0000-0000-0000-000000000004");
        when(reminderRepository.findUserPage(eq(TENANT_ID), eq(USER_ID), isNull(), isNull(), isNull(), eq(false), eq(3)))
                .thenReturn(List.of(
                        row(FIRST_ID, ReminderStatus.ACTIVE, FIRST_TRIGGER, "CUSTOM", List.of(), 0),
                        row(SECOND_ID, ReminderStatus.ACTIVE, SECOND_TRIGGER, "CUSTOM", List.of(), 0),
                        row(lookaheadId, ReminderStatus.ACTIVE, SECOND_TRIGGER.plusSeconds(60), "CUSTOM", List.of(), 0)));

        ReminderListData data = service.list(USER_ID, null, null, 2);

        assertThat(data.items()).hasSize(2);
        KeysetCursor cursor = KeysetCursor.decode(data.nextCursor()).orElseThrow();
        assertThat(cursor.id()).isEqualTo(SECOND_ID);
        assertThat(cursor.sortKey()).isEqualTo(SECOND_TRIGGER);
    }

    @Test
    void passesStatusFilterAndNonNullCursorDownToTheRepository() {
        String token = KeysetCursor.of(FIRST_TRIGGER, FIRST_ID).encode();
        when(reminderRepository.findUserPage(eq(TENANT_ID), eq(USER_ID), eq(ReminderStatus.PAUSED),
                eq(FIRST_TRIGGER), eq(FIRST_ID), eq(false), eq(11)))
                .thenReturn(List.of());

        ReminderListData data = service.list(USER_ID, "PAUSED", token, 10);

        assertThat(data.items()).isEmpty();
        assertThat(data.limit()).isEqualTo(10);
    }

    @Test
    void flagsTheNoNextTriggerGroupToTheRepository() {
        String token = KeysetCursor.of(null, SECOND_ID).encode();
        when(reminderRepository.findUserPage(eq(TENANT_ID), eq(USER_ID), isNull(), isNull(), eq(SECOND_ID), eq(true), eq(21)))
                .thenReturn(List.of(row(SECOND_ID, ReminderStatus.COMPLETED, null, "DEADLINE", List.of(), 0)));

        ReminderListData data = service.list(USER_ID, null, token, 20);

        assertThat(data.items()).hasSize(1);
        assertThat(data.items().get(0).nextTriggerAt()).isNull();
        assertThat(data.items().get(0).kind()).isEqualTo("DEADLINE");
    }

    @Test
    void rejectsLimitOutsideTheContractRange() {
        assertThatThrownBy(() -> service.list(USER_ID, null, null, 0))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.COM_VALIDATION_001);
        assertThatThrownBy(() -> service.list(USER_ID, null, null, 101))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.COM_VALIDATION_001);
        verifyNoInteractions(reminderRepository);
    }

    @Test
    void rejectsUnknownStatusAndMalformedCursor() {
        assertThatThrownBy(() -> service.list(USER_ID, "DONE", null, 20))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.COM_VALIDATION_001);
        assertThatThrownBy(() -> service.list(USER_ID, null, "not-a-cursor", 20))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.COM_VALIDATION_001);
        verifyNoInteractions(reminderRepository);
    }

    private static ReminderRow row(UUID id, ReminderStatus status, Instant nextTriggerAt, String sourceType,
            List<Instant> exdates, int leadMinutes) {
        return new ReminderRow(
                id,
                USER_ID,
                "提交线性代数作业",
                "第三章习题",
                "FREQ=DAILY;COUNT=1",
                FIRST_TRIGGER,
                "Asia/Shanghai",
                leadMinutes,
                exdates,
                List.of(),
                status,
                sourceType,
                null,
                0,
                "reminder-key-0001",
                nextTriggerAt,
                null,
                0L,
                NOW,
                NOW);
    }
}
