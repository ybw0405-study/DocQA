package com.docqa.reminder.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 六条合法边的判据来自契约 2.3.0 与 D-58。这里只回答「哪些边合法」：
 * 同态提交故意返回 false，因为服务层在本判定**之前**就把它短路成 200 no-op（D-60），
 * 把同态也算成非法边会让连点两次「暂停」得到 409。
 */
class ReminderStatusTest {

    @Test
    void activeLeavesExactlyThreeEdges() {
        assertThat(ReminderStatus.ACTIVE.canTransitionTo(ReminderStatus.PAUSED)).isTrue();
        assertThat(ReminderStatus.ACTIVE.canTransitionTo(ReminderStatus.COMPLETED)).isTrue();
        assertThat(ReminderStatus.ACTIVE.canTransitionTo(ReminderStatus.CANCELLED)).isTrue();
    }

    @Test
    void pausedLeavesExactlyThreeEdgesAndResumeIsOneOfThem() {
        assertThat(ReminderStatus.PAUSED.canTransitionTo(ReminderStatus.ACTIVE)).isTrue();
        assertThat(ReminderStatus.PAUSED.canTransitionTo(ReminderStatus.COMPLETED)).isTrue();
        assertThat(ReminderStatus.PAUSED.canTransitionTo(ReminderStatus.CANCELLED)).isTrue();
    }

    @Test
    void terminalStatesHaveNoOutgoingEdgeEvenToThemselves() {
        for (ReminderStatus terminal : List.of(ReminderStatus.COMPLETED, ReminderStatus.CANCELLED)) {
            for (ReminderStatus target : ReminderStatus.values()) {
                assertThat(terminal.canTransitionTo(target))
                        .as("%s -> %s", terminal, target)
                        .isFalse();
            }
        }
    }

    @Test
    void selfTransitionIsHandledByTheNoOpBranchRatherThanByThisCheck() {
        for (ReminderStatus status : ReminderStatus.values()) {
            assertThat(status.canTransitionTo(status))
                    .as("%s -> %s", status, status)
                    .isFalse();
        }
    }
}
