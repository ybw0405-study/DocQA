package com.docqa.reminder.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.docqa.common.trace.TraceIdContext;
import com.docqa.common.metrics.BusinessMetrics;
import com.docqa.reminder.config.ReminderProperties;
import com.docqa.reminder.domain.DueReminder;
import com.docqa.reminder.domain.ReminderRow;
import com.docqa.reminder.domain.ReminderStatus;
import com.docqa.reminder.messaging.ReminderDueEvent;
import com.docqa.reminder.messaging.ReminderDuePublisher;
import com.docqa.reminder.recurrence.RecurrenceCalculator;
import com.docqa.reminder.recurrence.RruleParser;
import com.docqa.reminder.repository.ReminderRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReminderDueServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID OTHER_TENANT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID REMINDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final Instant NOW = Instant.parse("2026-01-01T09:00:30Z");
    private static final Instant OCCURRENCE = Instant.parse("2026-01-01T09:00:00Z");
    private static final Instant NEXT = Instant.parse("2026-01-02T09:00:00Z");

    @Mock
    private ReminderRepository reminderRepository;
    @Mock
    private FiredOccurrenceStore firedOccurrenceStore;
    @Mock
    private ReminderDuePublisher reminderDuePublisher;

    private final ReminderProperties properties = new ReminderProperties();
    private ReminderDueService service;

    @BeforeEach
    void setUp() {
        service = new ReminderDueService(
                reminderRepository,
                firedOccurrenceStore,
                reminderDuePublisher,
                new RruleParser(),
                new RecurrenceCalculator(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                properties,
                new BusinessMetrics(new SimpleMeterRegistry()));
    }

    @AfterEach
    void tearDown() {
        TraceIdContext.clear();
    }

    @Test
    void publishesDueEventStampedWithRowTenantAndAdvancesReminder() {
        when(reminderRepository.findDue(NOW, properties.getScanBatchSize()))
                .thenReturn(List.of(due(TENANT_ID, row(REMINDER_ID, "FREQ=DAILY", OCCURRENCE, 5L))));
        when(firedOccurrenceStore.claim(REMINDER_ID, OCCURRENCE))
                .thenReturn(FiredOccurrenceStore.Claim.CLAIMED);
        when(reminderRepository.advance(TENANT_ID, REMINDER_ID, 5L, NEXT, OCCURRENCE)).thenReturn(1);

        service.dispatchDue();

        ArgumentCaptor<ReminderDueEvent> captor = ArgumentCaptor.forClass(ReminderDueEvent.class);
        verify(reminderDuePublisher).publish(captor.capture());
        ReminderDueEvent event = captor.getValue();
        assertThat(event.eventId()).isNotNull();
        assertThat(event.traceId()).matches("[0-9a-f]{32}");
        assertThat(event.reminderId()).isEqualTo(REMINDER_ID);
        assertThat(event.userId()).isEqualTo(USER_ID);
        assertThat(event.title()).isEqualTo("高等数学");
        assertThat(event.description()).isEqualTo("教学楼 A-203");
        assertThat(event.sourceType()).isEqualTo("CUSTOM");
        assertThat(event.rrule()).isEqualTo("FREQ=DAILY");
        assertThat(event.timezone()).isEqualTo("UTC");
        assertThat(event.occurrenceStartUtc()).isEqualTo(OCCURRENCE);
        assertThat(event.nextTriggerAt()).isEqualTo(NEXT);
        assertThat(event.occurredAt()).isEqualTo(NOW);
        assertThat(event.tenantId()).isEqualTo(TENANT_ID);

        verify(firedOccurrenceStore).markFired(REMINDER_ID, OCCURRENCE);
        verify(reminderRepository).advance(TENANT_ID, REMINDER_ID, 5L, NEXT, OCCURRENCE);
        verify(reminderRepository, never()).complete(any(), any(), anyLong(), any());
    }

    @Test
    void keepsEachRowTenantWhenScanningAcrossTenants() {
        UUID otherReminder = UUID.fromString("00000000-0000-0000-0000-000000000099");
        when(reminderRepository.findDue(NOW, properties.getScanBatchSize()))
                .thenReturn(List.of(
                        due(TENANT_ID, row(REMINDER_ID, "FREQ=DAILY", OCCURRENCE, 5L)),
                        due(OTHER_TENANT_ID, row(otherReminder, "FREQ=DAILY", OCCURRENCE, 3L))));
        when(firedOccurrenceStore.claim(any(), eq(OCCURRENCE)))
                .thenReturn(FiredOccurrenceStore.Claim.CLAIMED);
        when(reminderRepository.advance(any(), any(), anyLong(), eq(NEXT), eq(OCCURRENCE))).thenReturn(1);

        service.dispatchDue();

        ArgumentCaptor<ReminderDueEvent> captor = ArgumentCaptor.forClass(ReminderDueEvent.class);
        verify(reminderDuePublisher, org.mockito.Mockito.times(2)).publish(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(ReminderDueEvent::tenantId)
                .containsExactlyInAnyOrder(TENANT_ID, OTHER_TENANT_ID);
        verify(reminderRepository).advance(TENANT_ID, REMINDER_ID, 5L, NEXT, OCCURRENCE);
        verify(reminderRepository).advance(OTHER_TENANT_ID, otherReminder, 3L, NEXT, OCCURRENCE);
    }

    @Test
    void completesReminderWithoutFurtherOccurrences() {
        when(reminderRepository.findDue(NOW, properties.getScanBatchSize()))
                .thenReturn(List.of(due(TENANT_ID, row(REMINDER_ID, "FREQ=DAILY;COUNT=1", OCCURRENCE, 5L))));
        when(firedOccurrenceStore.claim(REMINDER_ID, OCCURRENCE))
                .thenReturn(FiredOccurrenceStore.Claim.CLAIMED);
        when(reminderRepository.complete(TENANT_ID, REMINDER_ID, 5L, OCCURRENCE)).thenReturn(1);

        service.dispatchDue();

        ArgumentCaptor<ReminderDueEvent> captor = ArgumentCaptor.forClass(ReminderDueEvent.class);
        verify(reminderDuePublisher).publish(captor.capture());
        assertThat(captor.getValue().nextTriggerAt()).isNull();
        verify(reminderRepository).complete(TENANT_ID, REMINDER_ID, 5L, OCCURRENCE);
        verify(reminderRepository, never()).advance(any(), any(), anyLong(), any(), any());
    }

    @Test
    void alreadyPublishedOccurrenceAdvancesWithoutPublishing() {
        when(reminderRepository.findDue(NOW, properties.getScanBatchSize()))
                .thenReturn(List.of(due(TENANT_ID, row(REMINDER_ID, "FREQ=DAILY", OCCURRENCE, 5L))));
        when(firedOccurrenceStore.claim(REMINDER_ID, OCCURRENCE))
                .thenReturn(FiredOccurrenceStore.Claim.FIRED);
        when(reminderRepository.advance(TENANT_ID, REMINDER_ID, 5L, NEXT, OCCURRENCE)).thenReturn(1);

        service.dispatchDue();

        verifyNoInteractions(reminderDuePublisher);
        verify(firedOccurrenceStore, never()).markFired(any(), any());
        verify(reminderRepository).advance(TENANT_ID, REMINDER_ID, 5L, NEXT, OCCURRENCE);
    }

    @Test
    void occurrenceBeingPublishedElsewhereIsSkipped() {
        when(reminderRepository.findDue(NOW, properties.getScanBatchSize()))
                .thenReturn(List.of(due(TENANT_ID, row(REMINDER_ID, "FREQ=DAILY", OCCURRENCE, 5L))));
        when(firedOccurrenceStore.claim(REMINDER_ID, OCCURRENCE))
                .thenReturn(FiredOccurrenceStore.Claim.IN_PROGRESS);

        service.dispatchDue();

        verifyNoInteractions(reminderDuePublisher);
        verify(reminderRepository, never()).advance(any(), any(), anyLong(), any(), any());
        verify(reminderRepository, never()).complete(any(), any(), anyLong(), any());
    }

    @Test
    void skipsOccurrenceBeyondPastGraceWithoutPublishing() {
        Instant stale = Instant.parse("2025-12-30T09:00:00Z");
        when(reminderRepository.findDue(NOW, properties.getScanBatchSize()))
                .thenReturn(List.of(due(TENANT_ID, row(REMINDER_ID, "FREQ=DAILY", stale, stale, 5L))));
        when(reminderRepository.advance(TENANT_ID, REMINDER_ID, 5L, OCCURRENCE, null)).thenReturn(1);

        service.dispatchDue();

        verifyNoInteractions(reminderDuePublisher);
        verifyNoInteractions(firedOccurrenceStore);
        verify(reminderRepository).advance(eq(TENANT_ID), eq(REMINDER_ID), eq(5L), eq(OCCURRENCE), isNull());
        verify(reminderRepository, never()).complete(any(), any(), anyLong(), any());
    }

    @Test
    void publishFailureReleasesClaimAndLeavesStateUntouched() {
        when(reminderRepository.findDue(NOW, properties.getScanBatchSize()))
                .thenReturn(List.of(due(TENANT_ID, row(REMINDER_ID, "FREQ=DAILY", OCCURRENCE, 5L))));
        when(firedOccurrenceStore.claim(REMINDER_ID, OCCURRENCE))
                .thenReturn(FiredOccurrenceStore.Claim.CLAIMED);
        doThrow(new IllegalStateException("mq down")).when(reminderDuePublisher).publish(any());

        service.dispatchDue();

        verify(firedOccurrenceStore).release(REMINDER_ID, OCCURRENCE);
        verify(firedOccurrenceStore, never()).markFired(any(), any());
        verify(reminderRepository, never()).advance(any(), any(), anyLong(), any(), any());
        verify(reminderRepository, never()).complete(any(), any(), anyLong(), any());
    }

    @Test
    void continuesScanningAfterOneCorruptRow() {
        UUID corruptId = UUID.fromString("00000000-0000-0000-0000-000000000003");
        when(reminderRepository.findDue(NOW, properties.getScanBatchSize()))
                .thenReturn(List.of(
                        due(TENANT_ID, row(corruptId, "FREQ=BOGUS", OCCURRENCE, 0L)),
                        due(TENANT_ID, row(REMINDER_ID, "FREQ=DAILY", OCCURRENCE, 5L))));
        when(firedOccurrenceStore.claim(REMINDER_ID, OCCURRENCE))
                .thenReturn(FiredOccurrenceStore.Claim.CLAIMED);
        when(reminderRepository.advance(TENANT_ID, REMINDER_ID, 5L, NEXT, OCCURRENCE)).thenReturn(1);

        service.dispatchDue();

        ArgumentCaptor<ReminderDueEvent> captor = ArgumentCaptor.forClass(ReminderDueEvent.class);
        verify(reminderDuePublisher).publish(captor.capture());
        assertThat(captor.getValue().reminderId()).isEqualTo(REMINDER_ID);
        verify(firedOccurrenceStore, never()).claim(eq(corruptId), any());
    }

    @Test
    void lostOptimisticLockDoesNotFailTheScan() {
        when(reminderRepository.findDue(NOW, properties.getScanBatchSize()))
                .thenReturn(List.of(due(TENANT_ID, row(REMINDER_ID, "FREQ=DAILY", OCCURRENCE, 5L))));
        when(firedOccurrenceStore.claim(REMINDER_ID, OCCURRENCE))
                .thenReturn(FiredOccurrenceStore.Claim.CLAIMED);
        when(reminderRepository.advance(TENANT_ID, REMINDER_ID, 5L, NEXT, OCCURRENCE)).thenReturn(0);

        service.dispatchDue();

        verify(reminderDuePublisher).publish(any());
        verify(reminderRepository).advance(TENANT_ID, REMINDER_ID, 5L, NEXT, OCCURRENCE);
    }

    @Test
    void usesConfiguredBatchSize() {
        properties.setScanBatchSize(7);
        when(reminderRepository.findDue(NOW, 7)).thenReturn(List.of());

        service.dispatchDue();

        verify(reminderRepository).findDue(NOW, 7);
    }

    @Test
    void keepsExistingTraceIdInPublishedEvent() {
        TraceIdContext.set("4bf92f3577b34da6a3ce929d0e0e4736");
        when(reminderRepository.findDue(NOW, properties.getScanBatchSize()))
                .thenReturn(List.of(due(TENANT_ID, row(REMINDER_ID, "FREQ=DAILY", OCCURRENCE, 5L))));
        when(firedOccurrenceStore.claim(REMINDER_ID, OCCURRENCE))
                .thenReturn(FiredOccurrenceStore.Claim.CLAIMED);
        when(reminderRepository.advance(TENANT_ID, REMINDER_ID, 5L, NEXT, OCCURRENCE)).thenReturn(1);

        service.dispatchDue();

        ArgumentCaptor<ReminderDueEvent> captor = ArgumentCaptor.forClass(ReminderDueEvent.class);
        verify(reminderDuePublisher).publish(captor.capture());
        assertThat(captor.getValue().traceId()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(TraceIdContext.current()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
    }

    private DueReminder due(UUID tenantId, ReminderRow row) {
        return new DueReminder(tenantId, row);
    }

    private ReminderRow row(UUID id, String rrule, Instant nextTriggerAt, long version) {
        return row(id, rrule, OCCURRENCE, nextTriggerAt, version);
    }

    private ReminderRow row(UUID id, String rrule, Instant dtstart, Instant nextTriggerAt, long version) {
        return new ReminderRow(
                id,
                USER_ID,
                "高等数学",
                "教学楼 A-203",
                rrule,
                dtstart,
                "UTC",
                0,
                List.of(),
                List.of(),
                ReminderStatus.ACTIVE,
                "CUSTOM",
                null,
                0,
                "idem-key-0001",
                nextTriggerAt,
                null,
                version,
                NOW,
                NOW);
    }
}
