package com.docqa.reminder.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.docqa.common.api.FieldViolation;
import com.docqa.common.api.KeysetCursor;
import com.docqa.common.error.BusinessException;
import com.docqa.common.error.ErrorCode;
import com.docqa.common.tenant.TenantContext;
import com.docqa.reminder.domain.ReminderTriggerRow;
import com.docqa.reminder.dto.NotificationData;
import com.docqa.reminder.dto.NotificationListData;
import com.docqa.reminder.dto.NotificationReadData;
import com.docqa.reminder.dto.NotificationReadRequest;
import com.docqa.reminder.repository.ReminderTriggerRepository;
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

/**
 * 通知读侧的两条硬口径：排序键与游标必须同序（{@code planned_trigger_at DESC, id DESC}），
 * 已读是「只由空变有」的收敛操作，因此 0 行也是成功。
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    private static final UUID TENANT_ID = TenantContext.DEFAULT_TENANT_ID;
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID REMINDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID FIRST_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID SECOND_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final UUID THIRD_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a3");
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant LATEST = Instant.parse("2026-08-31T09:00:00Z");
    private static final Instant MIDDLE = Instant.parse("2026-08-31T08:00:00Z");
    private static final Instant OLDEST = Instant.parse("2026-08-30T08:00:00Z");
    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    @Mock
    private ReminderTriggerRepository reminderTriggerRepository;

    @BeforeEach
    void setUp() {
        TenantContext.set(TENANT_ID);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void firstPageFetchesOneExtraRowAndHandsBackTheCursorOfTheLastKeptItem() {
        NotificationService service = service(NOW);
        when(reminderTriggerRepository.findUserPage(eq(TENANT_ID), eq(USER_ID), eq(false), isNull(), isNull(), eq(3)))
                .thenReturn(List.of(row(FIRST_ID, LATEST), row(SECOND_ID, MIDDLE), row(THIRD_ID, OLDEST)));

        NotificationListData page = service.list(USER_ID, false, null, 2);

        assertThat(page.limit()).isEqualTo(2);
        assertThat(page.items())
                .extracting(NotificationData::notificationId)
                .containsExactly(FIRST_ID, SECOND_ID);
        // 游标锚在**留下的一条**上，不是多取的那一条，否则下一页会跳过中间那条通知。
        assertThat(page.nextCursor()).isEqualTo(KeysetCursor.of(MIDDLE, SECOND_ID).encode());
    }

    @Test
    void aPageThatIsExactlyAsLongAsTheLimitHasNoNextCursor() {
        NotificationService service = service(NOW);
        when(reminderTriggerRepository.findUserPage(eq(TENANT_ID), eq(USER_ID), eq(false), isNull(), isNull(), eq(3)))
                .thenReturn(List.of(row(FIRST_ID, LATEST), row(SECOND_ID, MIDDLE)));

        NotificationListData page = service.list(USER_ID, false, null, 2);

        assertThat(page.items()).hasSize(2);
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void unreadOnlyIsForwardedVerbatimRatherThanInverted() {
        NotificationService service = service(NOW);
        when(reminderTriggerRepository.findUserPage(eq(TENANT_ID), eq(USER_ID), eq(true), isNull(), isNull(), eq(2)))
                .thenReturn(List.of());

        service.list(USER_ID, true, null, 1);

        verify(reminderTriggerRepository).findUserPage(TENANT_ID, USER_ID, true, null, null, 2);
    }

    @Test
    void limitBoundsStopTheRequestBeforeItReachesTheDatabase() {
        NotificationService service = service(NOW);

        for (int invalid : List.of(0, -1, 101)) {
            assertThatThrownBy(() -> service.list(USER_ID, false, null, invalid))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> {
                        BusinessException exception = (BusinessException) ex;
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.COM_VALIDATION_001);
                        assertThat(exception.getFieldViolations())
                                .extracting(FieldViolation::field)
                                .containsExactly("limit");
                    });
        }

        verifyNoInteractions(reminderTriggerRepository);
    }

    @Test
    void aCursorDecodesIntoBothSortComponentsInTheSameOrderAsTheSortKey() {
        NotificationService service = service(NOW);
        String token = KeysetCursor.of(MIDDLE, SECOND_ID).encode();
        when(reminderTriggerRepository.findUserPage(eq(TENANT_ID), eq(USER_ID), eq(false), eq(MIDDLE), eq(SECOND_ID), eq(21)))
                .thenReturn(List.of());

        assertThat(service.list(USER_ID, false, token, 20).items()).isEmpty();
    }

    @Test
    void anUndecodableCursorIsABadRequestRatherThanAFirstPage() {
        NotificationService service = service(NOW);

        assertThatThrownBy(() -> service.list(USER_ID, false, "###not-a-cursor###", 20))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException exception = (BusinessException) ex;
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.COM_VALIDATION_001);
                    assertThat(exception.getFieldViolations())
                            .extracting(FieldViolation::field)
                            .containsExactly("cursor");
                });

        verifyNoInteractions(reminderTriggerRepository);
    }

    @Test
    void sourceTypeMapsOntoTheReadSideKindForAllThreeValues() {
        NotificationService service = service(NOW);
        when(reminderTriggerRepository.findUserPage(eq(TENANT_ID), eq(USER_ID), eq(false), isNull(), isNull(), eq(4)))
                .thenReturn(List.of(
                        row(FIRST_ID, LATEST, "COURSE"),
                        row(SECOND_ID, MIDDLE, "DEADLINE"),
                        row(THIRD_ID, OLDEST, "CUSTOM")));

        assertThat(service.list(USER_ID, false, null, 3).items())
                .extracting(NotificationData::kind)
                .containsExactly("COURSE", "DEADLINE", "CUSTOM");
    }

    @Test
    void aReadNotificationKeepsItsFirstReadTimestampInThePayload() {
        NotificationService service = service(NOW);
        ReminderTriggerRow read = new ReminderTriggerRow(
                FIRST_ID, USER_ID, REMINDER_ID, "线性代数", null, "COURSE", LATEST,
                NOW.plusSeconds(3600), TRACE_ID, LATEST.minusSeconds(1), LATEST,
                LATEST.minusSeconds(1), LATEST);
        when(reminderTriggerRepository.findUserPage(eq(TENANT_ID), eq(USER_ID), eq(false), isNull(), isNull(), eq(2)))
                .thenReturn(List.of(read));

        NotificationData item = service.list(USER_ID, false, null, 1).items().get(0);

        assertThat(item.readAt()).isEqualTo(LATEST);
        assertThat(item.deliveredAt()).isBefore(item.readAt());
    }

    @Test
    void allTrueCoversRowsTheClientHasNotEvenLoaded() {
        NotificationService service = service(NOW);
        when(reminderTriggerRepository.markAllUnreadAsRead(TENANT_ID, USER_ID, NOW)).thenReturn(3);

        NotificationReadData data = service.markRead(USER_ID, new NotificationReadRequest(true, null));

        assertThat(data.updated()).isEqualTo(3);
        assertThat(data.all()).isTrue();
        verify(reminderTriggerRepository, never()).markRead(any(), any(), any(), any());
    }

    @Test
    void theIdsPathReturnsTheAffectedRowCountAndZeroIsLegallyASuccess() {
        NotificationService service = service(NOW);
        // 已读过的 id 撞上 read_at IS NULL 就是 0 行：不报错、不逐项说明，红点照常消失（的代价）。
        when(reminderTriggerRepository.markRead(TENANT_ID, USER_ID, List.of(FIRST_ID, SECOND_ID), NOW)).thenReturn(0);

        NotificationReadData data = service.markRead(
                USER_ID, new NotificationReadRequest(false, List.of(FIRST_ID, SECOND_ID)));

        assertThat(data.updated()).isZero();
        assertThat(data.all()).isFalse();
        verify(reminderTriggerRepository, never()).markAllUnreadAsRead(any(), any(), any());
    }

    @Test
    void allAndIdsAreMutuallyExclusiveRatherThanSilentlyIgnoringOneOfThem() {
        NotificationService service = service(NOW);

        assertThatThrownBy(() -> service.markRead(USER_ID, new NotificationReadRequest(true, List.of(FIRST_ID))))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException exception = (BusinessException) ex;
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.COM_VALIDATION_001);
                    assertThat(exception.getFieldViolations())
                            .extracting(FieldViolation::field)
                            .containsExactly("ids");
                });

        verifyNoInteractions(reminderTriggerRepository);
    }

    @Test
    void idsAreRequiredAsAtLeastOneWhenAllIsFalse() {
        NotificationService service = service(NOW);

        assertThatThrownBy(() -> service.markRead(USER_ID, new NotificationReadRequest(false, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getFieldViolations().get(0).field())
                .isEqualTo("ids");
        assertThatThrownBy(() -> service.markRead(USER_ID, new NotificationReadRequest(false, List.of())))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getFieldViolations().get(0).field())
                .isEqualTo("ids");

        verifyNoInteractions(reminderTriggerRepository);
    }

    @Test
    void anAbsentAllFlagNeverReadsAsMarkEverything() {
        NotificationService service = service(NOW);

        // all 缺失时不能靠「null 即 false」把请求变成按 ids 更新，也不能变成全量收敛：契约把 all 定为必填。
        assertThatThrownBy(() -> service.markRead(USER_ID, new NotificationReadRequest(null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.COM_VALIDATION_001);

        verifyNoInteractions(reminderTriggerRepository);
    }

    @Test
    void theReadTimestampIsTruncatedToWholeSecondsJustLikeDeliveredAt() {
        NotificationService service = service(NOW.plusMillis(470));
        when(reminderTriggerRepository.markAllUnreadAsRead(TENANT_ID, USER_ID, NOW)).thenReturn(1);

        assertThat(service.markRead(USER_ID, new NotificationReadRequest(true, null)).updated()).isEqualTo(1);
    }

    private NotificationService service(Instant instant) {
        return new NotificationService(reminderTriggerRepository, Clock.fixed(instant, ZoneOffset.UTC));
    }

    private ReminderTriggerRow row(UUID id, Instant plannedTriggerAt) {
        return row(id, plannedTriggerAt, "COURSE");
    }

    private ReminderTriggerRow row(UUID id, Instant plannedTriggerAt, String sourceType) {
        return new ReminderTriggerRow(
                id, USER_ID, REMINDER_ID, "线性代数", "第三章", sourceType, plannedTriggerAt,
                plannedTriggerAt.plusSeconds(3600), TRACE_ID, plannedTriggerAt, null,
                plannedTriggerAt, plannedTriggerAt);
    }
}
