package com.docqa.reminder.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.docqa.common.tenant.TenantContext;
import com.docqa.reminder.config.RocketMqProperties;
import com.docqa.reminder.domain.ReminderTriggerRow;
import com.docqa.reminder.messaging.MqIdempotencyMarker;
import com.docqa.reminder.messaging.ReminderDueEvent;
import com.docqa.reminder.messaging.ReminderDueRejectedException;
import com.docqa.reminder.repository.ReminderTriggerRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

/**
 * 落库侧只认 {@code (reminderId, plannedTriggerAt)} 这个业务事实判重：{@code eventId} 与 MQ {@code msgId}
 * 在同一次 occurrence 重发时都是新值，按它们判重会让用户为同一节课收到两条通知（D-62）。
 */
@ExtendWith(MockitoExtension.class)
class ReminderTriggerIngestServiceTest {

    private static final UUID TENANT_ID = TenantContext.DEFAULT_TENANT_ID;
    private static final UUID USER_ID = UUID.fromString("0192f3a4-7b2c-7d10-9e8f-1234567890ab");
    private static final UUID REMINDER_ID = UUID.fromString("0192f3a4-7b2c-7d10-9e8f-1234567890ac");
    private static final UUID EVENT_ID = UUID.fromString("0192f3a4-7b2c-7d10-9e8f-1234567890ad");
    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String MESSAGE_ID = "0A1B2C3D4E5F";
    private static final Instant OCCURRENCE = Instant.parse("2026-08-31T00:50:00Z");
    private static final Instant NEXT_TRIGGER = Instant.parse("2026-09-07T00:50:00Z");
    private static final Instant OCCURRED_AT = Instant.parse("2026-08-31T00:50:03Z");
    private static final Instant NOW = Instant.parse("2026-08-31T00:50:04Z");

    @Mock
    private ReminderTriggerRepository reminderTriggerRepository;
    @Mock
    private MqIdempotencyMarker idempotencyMarker;

    private final RocketMqProperties properties = new RocketMqProperties();

    @BeforeEach
    void setUp() {
        properties.setDueTopic("reminder-due");
        lenient().when(idempotencyMarker.alreadyProcessed(anyString(), anyString())).thenReturn(false);
    }

    @Test
    void storesOneRowPerDueEventAndTakesThePlannedTriggerFromThePayload() {
        ReminderTriggerRow stored = ingest(new Draft());

        assertThat(stored.plannedTriggerAt()).isEqualTo(OCCURRENCE);
        assertThat(stored.nextTriggerAt()).isEqualTo(NEXT_TRIGGER);
        assertThat(stored.reminderId()).isEqualTo(REMINDER_ID);
        assertThat(stored.userId()).isEqualTo(USER_ID);
        // 载荷是触发当时的事实快照：提醒行后来被改名或删掉都不能改写已经发出的通知。
        assertThat(stored.title()).isEqualTo("线性代数");
        assertThat(stored.description()).isEqualTo("第三章");
        assertThat(stored.sourceType()).isEqualTo("COURSE");
        assertThat(stored.traceId()).isEqualTo(TRACE_ID);
        assertThat(stored.id()).isNotEqualTo(REMINDER_ID);
    }

    @Test
    void deliveryTimestampIsTruncatedToWholeSecondsAndSharedByTheRowTimestamps() {
        ReminderTriggerRow stored = ingest(new Draft(), NOW.plusMillis(470), MESSAGE_ID);

        // delivered_at == created_at == updated_at 且都是整秒：chk_reminder_trigger_read 比较的两列同口径。
        assertThat(stored.deliveredAt()).isEqualTo(NOW);
        assertThat(stored.createdAt()).isEqualTo(NOW);
        assertThat(stored.updatedAt()).isEqualTo(NOW);
        assertThat(stored.readAt()).isNull();
    }

    @Test
    void duplicateUniqueKeyHitIsAcknowledgedWithoutRewritingTheFirstDelivery() {
        doThrow(new DuplicateKeyException("uk_reminder_trigger_tenant_planned"))
                .when(reminderTriggerRepository).insert(any(), any());

        ReminderTriggerIngestService service = service(NOW);
        assertThatCode(() -> service.ingest(new Draft().event(), MESSAGE_ID)).doesNotThrowAnyException();

        // 不刷新 delivered_at，也不再写加速标记：第二次起的重投由唯一键收敛，标记只是加速层。
        verify(idempotencyMarker, never()).markProcessed(anyString(), anyString());
    }

    @Test
    void markerHitShortCircuitsBeforeTheDatabase() {
        when(idempotencyMarker.alreadyProcessed("reminder-due", MESSAGE_ID)).thenReturn(true);

        ReminderTriggerIngestService service = service(NOW);
        assertThatCode(() -> service.ingest(new Draft().event(), MESSAGE_ID)).doesNotThrowAnyException();

        verifyNoInteractions(reminderTriggerRepository);
        verify(idempotencyMarker, never()).markProcessed(anyString(), anyString());
    }

    @Test
    void successfulInsertIsWhatEarnsTheAccelerationMarker() {
        ingest(new Draft(), NOW, MESSAGE_ID);

        verify(idempotencyMarker).alreadyProcessed("reminder-due", MESSAGE_ID);
        verify(idempotencyMarker).markProcessed("reminder-due", MESSAGE_ID);
    }

    @Test
    void blankMessageIdSkipsTheRedisRoundTripButNeverSkipsTheRow() {
        ingest(new Draft(), NOW, null);

        verifyNoInteractions(idempotencyMarker);
        verify(reminderTriggerRepository).insert(any(), any());
    }

    @Test
    void everyRequiredFieldIsRejectedRatherThanStoredAsAnEmptyColumn() {
        Draft eventId = new Draft();
        eventId.eventId = null;
        Draft reminderId = new Draft();
        reminderId.reminderId = null;
        Draft userId = new Draft();
        userId.userId = null;
        Draft rrule = new Draft();
        rrule.rrule = "  ";
        Draft timezone = new Draft();
        timezone.timezone = null;
        Draft occurrence = new Draft();
        occurrence.occurrenceStartUtc = null;
        Draft occurredAt = new Draft();
        occurredAt.occurredAt = null;
        Draft title = new Draft();
        title.title = "   ";

        assertRejected(eventId.event(), "eventId");
        assertRejected(reminderId.event(), "reminderId");
        assertRejected(userId.event(), "userId");
        assertRejected(rrule.event(), "rrule");
        assertRejected(timezone.event(), "timezone");
        assertRejected(occurrence.event(), "occurrenceStartUtc");
        assertRejected(occurredAt.event(), "occurredAt");
        assertRejected(title.event(), "title");

        verifyNoInteractions(reminderTriggerRepository);
    }

    @Test
    void sourceTypeOutsideTheReadSideDomainIsRejectedRatherThanMappedToANullKind() {
        // ReminderKind 只有三值；第四个值进表会让通知列表的 kind 变成 null 或在映射时抛异常。
        Draft slack = new Draft();
        slack.sourceType = "SLACK";
        assertRejected(slack.event(), "sourceType");

        verifyNoInteractions(reminderTriggerRepository);
    }

    @Test
    void titleAndDescriptionLengthBoundsMatchTheColumnDefinitions() {
        Draft atLimit = new Draft();
        atLimit.title = "课".repeat(200);
        atLimit.description = "说".repeat(1000);
        assertThat(ingest(atLimit).title()).hasSize(200);

        clearInvocations(reminderTriggerRepository, idempotencyMarker);
        Draft overTitle = new Draft();
        overTitle.title = "课".repeat(201);
        assertRejected(overTitle.event(), "title length");

        Draft overDescription = new Draft();
        overDescription.description = "说".repeat(1001);
        assertRejected(overDescription.event(), "description length");
    }

    @Test
    void invalidTraceIdIsRejectedRatherThanLaunderedIntoAFreshOne() {
        Draft garbage = new Draft();
        garbage.traceId = "not-a-trace";
        Draft allZero = new Draft();
        allZero.traceId = "00000000000000000000000000000000";
        Draft missing = new Draft();
        missing.traceId = null;

        assertRejected(garbage.event(), "traceId");
        assertRejected(allZero.event(), "traceId");
        assertRejected(missing.event(), "traceId");

        // 生成新 trace 顶替会把「这条通知追回不到任何调度扫描」洗成看起来正常的行。
        verifyNoInteractions(reminderTriggerRepository);
    }

    @Test
    void nullEventBodyIsRejectedWithoutANullPointerException() {
        ReminderTriggerIngestService service = service(NOW);

        assertThatThrownBy(() -> service.ingest(null, MESSAGE_ID))
                .isInstanceOf(ReminderDueRejectedException.class);

        verifyNoInteractions(reminderTriggerRepository, idempotencyMarker);
    }

    private ReminderTriggerRow ingest(Draft draft) {
        return ingest(draft, NOW, MESSAGE_ID);
    }

    private ReminderTriggerRow ingest(Draft draft, Instant instant, String messageId) {
        service(instant).ingest(draft.event(), messageId);
        ArgumentCaptor<ReminderTriggerRow> captor = ArgumentCaptor.forClass(ReminderTriggerRow.class);
        verify(reminderTriggerRepository).insert(eq(TENANT_ID), captor.capture());
        return captor.getValue();
    }

    private ReminderTriggerIngestService service(Instant instant) {
        return new ReminderTriggerIngestService(
                reminderTriggerRepository,
                idempotencyMarker,
                properties,
                Clock.fixed(instant, ZoneOffset.UTC));
    }

    private void assertRejected(ReminderDueEvent event, String field) {
        assertThatThrownBy(() -> service(NOW).ingest(event, MESSAGE_ID))
                .isInstanceOf(ReminderDueRejectedException.class)
                .hasMessageContaining(field);
    }

    /** 一份合法事件的草稿：逐字段测试只改一处，避免重载签名彼此遮蔽。 */
    private static final class Draft {
        private UUID eventId = EVENT_ID;
        private String traceId = TRACE_ID;
        private UUID reminderId = REMINDER_ID;
        private UUID userId = USER_ID;
        private String title = "线性代数";
        private String description = "第三章";
        private String sourceType = "COURSE";
        private String rrule = "FREQ=WEEKLY;BYDAY=MO";
        private String timezone = "Asia/Shanghai";
        private Instant occurrenceStartUtc = OCCURRENCE;
        private Instant nextTriggerAt = NEXT_TRIGGER;
        private Instant occurredAt = OCCURRED_AT;

        private ReminderDueEvent event() {
            return new ReminderDueEvent(
                    eventId, traceId, reminderId, userId, title, description, sourceType, rrule, timezone,
                    occurrenceStartUtc, nextTriggerAt, occurredAt);
        }
    }
}
