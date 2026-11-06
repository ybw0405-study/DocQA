package com.docqa.reminder.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.docqa.reminder.domain.ReminderRow;
import com.docqa.reminder.domain.ReminderStatus;
import com.docqa.reminder.domain.ScheduleImportAction;
import com.docqa.reminder.recurrence.Rrule;
import com.docqa.reminder.repository.ReminderRepository;
import com.docqa.reminder.schedule.IcsEvent;
import com.docqa.reminder.schedule.ScheduleDecision;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.dao.DuplicateKeyException;

/**
 * 课表导入写入器的分支覆盖：四种判定各自要不要产生 SQL、顺序如何、异常如何外逃。
 *
 * <p>事务本身不在这里取证：{@code @Transactional} 由 Spring 代理实现，Mockito 直接调用
 * {@code persist} 不经过代理，回滚行为无法在本测试里观测。这里能证明的是「异常一定外逃」——
 * 这是代理回滚的前置条件；一旦写入器把 SQL 异常吞掉，整批事务就会提交半套课表。
 */
class ScheduleImportWriterTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID REMINDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final Instant MONDAY = Instant.parse("2026-09-07T00:00:00Z");

    private final ReminderRepository reminderRepository = mock(ReminderRepository.class);
    private final ScheduleImportWriter writer = new ScheduleImportWriter(reminderRepository);

    @Test
    void createDecisionInsertsThePendingRow() {
        ReminderRow row = row(1);

        writer.persist(List.of(decision(ScheduleImportAction.CREATE, row)));

        verify(reminderRepository).insert(row);
        verify(reminderRepository, never()).updateFromImport(any());
    }

    @Test
    void updateDecisionWritesThroughTheImportUpdateNotAnInsert() {
        ReminderRow row = row(5);

        writer.persist(List.of(decision(ScheduleImportAction.UPDATE, row)));

        verify(reminderRepository).updateFromImport(row);
        verify(reminderRepository, never()).insert(any());
    }

    @Test
    void unchangedAndStaleDecisionsEmitNoSqlAtAll() {
        // UNCHANGED / STALE 的 row 恒为 null：给它们补一次 insert 就会把「文件比库里更旧」写成覆盖，
        // 这是判定的意义所在，因此断言的是完全没有任何交互，而不是只断言 updateFromImport 没被调。
        writer.persist(List.of(
                decision(ScheduleImportAction.UNCHANGED, null),
                decision(ScheduleImportAction.STALE, null)));

        verifyNoInteractions(reminderRepository);
    }

    @Test
    void decisionsAreWrittenInTheirBatchOrder() {
        ReminderRow first = row(1);
        ReminderRow second = row(2);

        writer.persist(List.of(
                decision(ScheduleImportAction.CREATE, first),
                decision(ScheduleImportAction.UNCHANGED, null),
                decision(ScheduleImportAction.UPDATE, second)));

        InOrder inOrder = Mockito.inOrder(reminderRepository);
        inOrder.verify(reminderRepository).insert(first);
        inOrder.verify(reminderRepository).updateFromImport(second);
        inOrder.verifyNoMoreInteractions();
    }

    @Test
    void emptyBatchIsANoOp() {
        writer.persist(List.of());

        verifyNoInteractions(reminderRepository);
    }

    @Test
    void sqlFailurePropagatesSoTheTransactionCanRollBack() {
        ReminderRow failing = row(1);
        DuplicateKeyException duplicate = new DuplicateKeyException("Duplicate entry for key 'uk_reminder_user_source'");
        Mockito.doThrow(duplicate).when(reminderRepository).insert(failing);

        assertThatThrownBy(() -> writer.persist(List.of(
                decision(ScheduleImportAction.CREATE, failing),
                decision(ScheduleImportAction.UPDATE, row(2)))))
                .isSameAs(duplicate);

        // 第一行抛错后循环立即结束：第二行不能再被写。整批回滚由代理完成，这里只保证不再追加 SQL。
        verify(reminderRepository, never()).updateFromImport(any());
    }

    private static ScheduleDecision decision(ScheduleImportAction action, ReminderRow row) {
        return new ScheduleDecision(
                new IcsEvent("linear-algebra-2026-fall-mon-0800", "线性代数", null, row == null ? 1 : row.sourceSequence(),
                        "FREQ=WEEKLY;BYDAY=MO", Rrule.dailyOnce(), MONDAY, "Asia/Shanghai", List.of(), List.of()),
                action,
                action == ScheduleImportAction.CREATE ? null : 0,
                REMINDER_ID,
                MONDAY.minusSeconds(600),
                MONDAY.plusSeconds(60 * 86400).minusSeconds(600),
                row);
    }

    private static ReminderRow row(int sourceSequence) {
        return new ReminderRow(
                REMINDER_ID, USER_ID, "线性代数", null, "FREQ=WEEKLY;BYDAY=MO", MONDAY, "Asia/Shanghai",
                10, List.of(), List.of(), ReminderStatus.ACTIVE, "COURSE",
                "linear-algebra-2026-fall-mon-0800", sourceSequence, "0123456789abcdef0123456789abcdef",
                MONDAY.minusSeconds(600), null, 1L, MONDAY, MONDAY);
    }
}
