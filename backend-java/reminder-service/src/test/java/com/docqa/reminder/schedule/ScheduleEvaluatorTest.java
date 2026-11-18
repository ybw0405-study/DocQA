package com.docqa.reminder.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.docqa.common.tenant.TenantContext;
import com.docqa.reminder.config.ReminderProperties;
import com.docqa.reminder.domain.ReminderRow;
import com.docqa.reminder.domain.ReminderStatus;
import com.docqa.reminder.domain.ScheduleImportAction;
import com.docqa.reminder.recurrence.RecurrenceCalculator;
import com.docqa.reminder.recurrence.RruleParser;
import com.docqa.reminder.repository.ReminderRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 逐事件判定。这里取证的是三件事：四态决策树的分支、触发时刻的 lead 换算方向、以及
 * 「不写库的分支必须回答库内真正会响的值」。预览与导入共用本类，所以这些结论同时属于两个操作。
 *
 * <p>{@code NOW} 固定在 2026-09-01T00:00:00Z，{@code pastGrace} 用默认 10 分钟；
 * 行号与时刻断言都按周一 08:00（Asia/Shanghai）= 00:00Z 那门课算。
 */
class ScheduleEvaluatorTest {

    private static final UUID TENANT_ID = TenantContext.DEFAULT_TENANT_ID;
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID STORED_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant MONDAY_CLASS = Instant.parse("2026-09-07T00:00:00Z");
    private static final String REQUEST_KEY = "idem-key-0001";
    private static final String MON_0800 = "linear-algebra-2026-fall-mon-0800";
    private static final String WEEKLY_MONDAY = "FREQ=WEEKLY;BYDAY=MO";

    private final ReminderRepository repository = mock(ReminderRepository.class);
    private final RruleParser rruleParser = new RruleParser();
    private final ScheduleEvaluator evaluator = new ScheduleEvaluator(
            repository, rruleParser, new RecurrenceCalculator(), new ReminderProperties(),
            Clock.fixed(NOW, ZoneId.of("UTC")));

    @Test
    void unknownUidBecomesCreateWithBothTriggersShiftedByTheLead() {
        // 数值直接对齐契约 2.2.0 预览示例：00:00Z 的课提前 10 分钟。
        ScheduleDecision decision = evaluate(10, event(MON_0800, 3, WEEKLY_MONDAY, MONDAY_CLASS)).get(0);

        assertThat(decision.action()).isEqualTo(ScheduleImportAction.CREATE);
        assertThat(decision.currentSequence()).isNull();
        assertThat(decision.nextTriggerAt()).isEqualTo(Instant.parse("2026-09-06T23:50:00Z"));
        assertThat(decision.followingTriggerAt()).isEqualTo(Instant.parse("2026-09-13T23:50:00Z"));
        assertThat(decision.reminderId()).isNotNull();
        assertThat(decision.row()).isNotNull();
        assertThat(decision.row().nextTriggerAt()).isEqualTo(decision.nextTriggerAt());
    }

    @Test
    void theRowCarriesCourseProvenanceAndAPerRowIdempotencyKey() {
        ReminderRow row = evaluate(10, event(MON_0800, 3, WEEKLY_MONDAY, MONDAY_CLASS)).get(0).row();

        assertThat(row.sourceType()).isEqualTo("COURSE");
        assertThat(row.sourceUid()).isEqualTo(MON_0800);
        assertThat(row.sourceSequence()).isEqualTo(3);
        assertThat(row.leadMinutes()).isEqualTo(10);
        assertThat(row.status()).isEqualTo(ReminderStatus.ACTIVE);
        assertThat(row.version()).isZero();
        assertThat(row.lastTriggeredAt()).isNull();
        assertThat(row.dtstart()).isEqualTo(MONDAY_CLASS);
        // 一次导入涉及多行，请求键不能直接落列，否则第二行就撞 uk_reminder_user_idempotency。
        assertThat(row.idempotencyKey()).hasSize(64).isNotEqualTo(REQUEST_KEY);
    }

    @Test
    void twoCoursesInOneBatchGetDifferentRowKeys() {
        List<ScheduleDecision> decisions = evaluate(10,
                event(MON_0800, 1, WEEKLY_MONDAY, MONDAY_CLASS),
                event("discrete-math-wed-1000", 2, "FREQ=WEEKLY;BYDAY=WE", Instant.parse("2026-09-09T02:00:00Z")));

        assertThat(decisions).hasSize(2);
        assertThat(decisions).extracting(decision -> decision.row().idempotencyKey()).doesNotHaveDuplicates();
        assertThat(decisions).extracting(decision -> decision.row().sourceUid())
                .containsExactly(MON_0800, "discrete-math-wed-1000");
    }

    @Test
    void higherFileSequenceUpdatesTheExistingRowWithoutMovingItsIdentity() {
        givenStored(stored(4, 30, Instant.parse("2026-09-06T23:30:00Z"), ReminderStatus.ACTIVE));

        ScheduleDecision decision = evaluate(10, event(MON_0800, 5, WEEKLY_MONDAY, MONDAY_CLASS)).get(0);

        assertThat(decision.action()).isEqualTo(ScheduleImportAction.UPDATE);
        assertThat(decision.currentSequence()).isEqualTo(4);
        assertThat(decision.reminderId()).isEqualTo(STORED_ID);
        assertThat(decision.row().id()).isEqualTo(STORED_ID);
        assertThat(decision.row().userId()).isEqualTo(USER_ID);
        assertThat(decision.nextTriggerAt()).isEqualTo(Instant.parse("2026-09-06T23:50:00Z"));
    }

    @Test
    void updateKeepsADeadRowDeadBecauseHigherSequenceIsNotUserConsent() {
        givenStored(stored(4, 0, null, ReminderStatus.CANCELLED));

        ScheduleDecision decision = evaluate(10, event(MON_0800, 5, WEEKLY_MONDAY, MONDAY_CLASS)).get(0);

        assertThat(decision.action()).isEqualTo(ScheduleImportAction.UPDATE);
        assertThat(decision.row().status()).isEqualTo(ReminderStatus.CANCELLED);
    }

    @Test
    void equalSequenceIsUnchangedAndLowerSequenceIsStale() {
        givenStored(stored(3, 30, Instant.parse("2026-09-06T23:30:00Z"), ReminderStatus.ACTIVE));

        assertThat(evaluate(10, event(MON_0800, 3, WEEKLY_MONDAY, MONDAY_CLASS)).get(0).action())
                .isEqualTo(ScheduleImportAction.UNCHANGED);
        assertThat(evaluate(10, event(MON_0800, 2, WEEKLY_MONDAY, MONDAY_CLASS)).get(0).action())
                .isEqualTo(ScheduleImportAction.STALE);
    }

    @Test
    void skippedDecisionsReportTheStoredScheduleRatherThanTheRequestedLead() {
        // 库内行的提前量是 30 分钟，本次请求填了 5。UNCHANGED 不写库，
        // 所以「下一次会响的时刻」仍是库内值，再下一次也必须按库内的 30 分钟求。
        givenStored(stored(3, 30, Instant.parse("2026-09-06T23:30:00Z"), ReminderStatus.ACTIVE));

        ScheduleDecision decision = evaluate(5, event(MON_0800, 3, WEEKLY_MONDAY, MONDAY_CLASS)).get(0);

        assertThat(decision.action()).isEqualTo(ScheduleImportAction.UNCHANGED);
        assertThat(decision.row()).isNull();
        assertThat(decision.reminderId()).isEqualTo(STORED_ID);
        assertThat(decision.nextTriggerAt()).isEqualTo(Instant.parse("2026-09-06T23:30:00Z"));
        assertThat(decision.followingTriggerAt()).isEqualTo(Instant.parse("2026-09-13T23:30:00Z"));
    }

    @Test
    void storedRowWithoutATriggerYieldsNoFollowingTrigger() {
        givenStored(stored(3, 10, null, ReminderStatus.COMPLETED));

        ScheduleDecision decision = evaluate(10, event(MON_0800, 3, WEEKLY_MONDAY, MONDAY_CLASS)).get(0);

        assertThat(decision.nextTriggerAt()).isNull();
        assertThat(decision.followingTriggerAt()).isNull();
    }

    @Test
    void pastOccurrencesAreSkippedButTheChosenOneIsStillRemindedEarly() {
        // dtstart 从 2026-08-03 起，NOW 之前的周一 occurrence 全部越过；选中的 09-07 仍提前 10 分钟响。
        ScheduleDecision decision = evaluate(10,
                event(MON_0800, 1, WEEKLY_MONDAY, Instant.parse("2026-08-03T00:00:00Z"))).get(0);

        assertThat(decision.nextTriggerAt()).isEqualTo(Instant.parse("2026-09-06T23:50:00Z"));
        assertThat(decision.followingTriggerAt()).isEqualTo(Instant.parse("2026-09-13T23:50:00Z"));
    }

    @Test
    void anOccurrenceWhoseReminderMomentAlreadyPassedIsNotSelected() {
        // 门槛 = now - pastGrace + lead，在 occurrence 域上比较。每天 00:30Z 的课提前 60 分钟，
        // 09-01T00:30 那次occurrence 的提醒时刻是 08-31T23:30（已经过去），因此只能选 09-02。
        ScheduleDecision decision = evaluate(60,
                event("english-daily", 1, "FREQ=DAILY", Instant.parse("2026-08-31T00:30:00Z"))).get(0);

        assertThat(decision.nextTriggerAt()).isEqualTo(Instant.parse("2026-09-01T23:30:00Z"));
        assertThat(decision.followingTriggerAt()).isEqualTo(Instant.parse("2026-09-02T23:30:00Z"));
    }

    @Test
    void anExcludedWholeDayIsSkippedForBothTheTriggerAndItsSuccessor() {
        IcsEvent event = new IcsEvent(
                MON_0800, "线性代数", null, 1, WEEKLY_MONDAY, rruleParser.parse(WEEKLY_MONDAY),
                MONDAY_CLASS, "Asia/Shanghai", List.of(), List.of(LocalDate.of(2026, 9, 7)));

        ScheduleDecision decision = evaluate(10, event).get(0);

        // 09-07 整天停课，首个可提醒的是下一周一；提前量仍然是 10 分钟。
        assertThat(decision.nextTriggerAt()).isEqualTo(Instant.parse("2026-09-13T23:50:00Z"));
        assertThat(decision.followingTriggerAt()).isEqualTo(Instant.parse("2026-09-20T23:50:00Z"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void theWholeBatchIsJudgedWithOneLookupByUid() {
        ArgumentCaptor<List<String>> uids = ArgumentCaptor.forClass(List.class);

        evaluate(10,
                event(MON_0800, 1, WEEKLY_MONDAY, MONDAY_CLASS),
                event("discrete-math-wed-1000", 2, "FREQ=WEEKLY;BYDAY=WE", Instant.parse("2026-09-09T02:00:00Z")));

        verify(repository, times(1)).findByUserAndSourceUids(
                eq(TENANT_ID), eq(USER_ID), eq("COURSE"), uids.capture());
        assertThat(uids.getValue()).containsExactly(MON_0800, "discrete-math-wed-1000");
    }

    private List<ScheduleDecision> evaluate(int leadMinutes, IcsEvent... events) {
        return evaluator.evaluate(TENANT_ID, USER_ID, REQUEST_KEY, leadMinutes, List.of(events));
    }

    private void givenStored(ReminderRow row) {
        when(repository.findByUserAndSourceUids(any(), any(), any(), anyList())).thenReturn(List.of(row));
    }

    private IcsEvent event(String uid, int sequence, String rrule, Instant dtstart) {
        return new IcsEvent(uid, "线性代数", null, sequence, rrule, rruleParser.parse(rrule),
                dtstart, "Asia/Shanghai", List.of(), List.of());
    }

    private static ReminderRow stored(int sequence, int leadMinutes, Instant nextTriggerAt, ReminderStatus status) {
        return new ReminderRow(
                STORED_ID, USER_ID, "线性代数", null, WEEKLY_MONDAY, MONDAY_CLASS, "Asia/Shanghai",
                leadMinutes, List.of(), List.of(), status, "COURSE", MON_0800, sequence, "stored-key",
                nextTriggerAt, null, 7L, NOW, NOW);
    }
}
