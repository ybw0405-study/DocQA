package com.docqa.reminder.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.docqa.reminder.domain.ReminderTriggerRow;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * 通知表的两条写入形态是契约的一部分：列表按 {@code planned_trigger_at DESC, id DESC} 排，
 * 已读只允许「由空变有」。两者都由 SQL 文本与绑定位置直接断言，不依赖数据库。
 */
class JdbcReminderTriggerRepositoryTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID REMINDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID NOTIFICATION_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID CURSOR_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final Instant PLANNED = Instant.parse("2026-08-31T00:50:00Z");
    private static final Instant NEXT = Instant.parse("2026-09-07T00:50:00Z");
    private static final Instant DELIVERED = Instant.parse("2026-08-31T00:50:01Z");
    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final JdbcReminderTriggerRepository repository = new JdbcReminderTriggerRepository(jdbcTemplate);

    @Test
    void insertWritesEveryColumnExplicitlyAndLeavesReadAtToAnNullBinding() {
        repository.insert(row());

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(),
                eq(NOTIFICATION_ID.toString()), eq(USER_ID.toString()), eq(REMINDER_ID.toString()),
                eq("线性代数"), eq("第三章"), eq("COURSE"),
                eq(Timestamp.from(PLANNED)), eq(Timestamp.from(NEXT)), eq(TRACE_ID),
                eq(Timestamp.from(DELIVERED)), isNull(),
                eq(Timestamp.from(DELIVERED)), eq(Timestamp.from(DELIVERED)));
        String statement = sql.getValue();
        assertThat(placeholders(statement)).isEqualTo(13);
        assertThat(statement).contains("INSERT INTO reminder_trigger");
        // read_at 必须显式写 NULL：靠列默认值会让「未读」与「这一列没被赋值」在语句上分不开。
        assertThat(statement).contains("read_at");
    }

    @Test
    void firstPageBindsOnlyTheOwnerAndOrdersByTheBusinessTriggerTimeDescending() {
        repository.findUserPage(USER_ID, false, null, null, 21);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), eq(USER_ID.toString()), eq(21));
        String statement = sql.getValue();
        assertThat(statement).contains("FROM reminder_trigger WHERE user_id = ?");
        // D-64：排序键是业务时刻而不是落库时刻，投递堆积时用户看到的仍是事件顺序。
        assertThat(statement).contains("ORDER BY planned_trigger_at DESC, id DESC LIMIT ?");
        assertThat(statement).doesNotContain("read_at IS NULL");
        assertThat(statement).doesNotContain("planned_trigger_at < ?");
        assertThat(statement).doesNotContain(USER_ID.toString());
    }

    @Test
    void unreadOnlyAddsOnlyANullPredicateAndKeepsTheSameSortKey() {
        repository.findUserPage(USER_ID, true, null, null, 21);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), eq(USER_ID.toString()), eq(21));
        assertThat(sql.getValue()).contains("AND read_at IS NULL");
        assertThat(sql.getValue()).contains("ORDER BY planned_trigger_at DESC, id DESC LIMIT ?");
    }

    @Test
    void resumingComparesTheSecondColumnOnlyOnTiesAndBindsTheAnchorTwice() {
        repository.findUserPage(USER_ID, false, PLANNED, CURSOR_ID, 21);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class),
                eq(USER_ID.toString()),
                eq(Timestamp.from(PLANNED)),
                eq(Timestamp.from(PLANNED)),
                eq(CURSOR_ID.toString()),
                eq(21));
        String statement = sql.getValue();
        assertThat(statement).contains("AND (planned_trigger_at < ? OR (planned_trigger_at = ? AND id < ?))");
        // 五个绑定位：user_id、锚点时刻（严格小于与同刻比较各一次）、锚点 id、LIMIT。
        assertThat(placeholders(statement)).isEqualTo(5);
        // 游标里的时刻与 id 都来自上一次响应，属于外部输入：只能进占位符。
        assertThat(statement).doesNotContain(PLANNED.toString());
        assertThat(statement).doesNotContain(CURSOR_ID.toString());
    }

    @Test
    void markAllUnreadAsReadOnlyEverFlipsNullReadAtInsideTheOwner() {
        repository.markAllUnreadAsRead(USER_ID, DELIVERED);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), eq(Timestamp.from(DELIVERED)), eq(USER_ID.toString()));
        String statement = sql.getValue();
        assertThat(statement).isEqualTo(
                "UPDATE reminder_trigger SET read_at = ? WHERE user_id = ? AND read_at IS NULL");
        // 两个谓词缺一不可：user_id 是越权边界，read_at IS NULL 是「真正由空变有」的行数来源。
        assertThat(statement).contains("user_id = ?");
        assertThat(statement).contains("read_at IS NULL");
        assertThat(statement).doesNotContain("updated_at");
        // 提醒被完成或取消后历史通知仍然保留，因此已读收敛不看 reminder 状态（storage.md §2.16）。
        assertThat(statement).doesNotContain("status");
    }

    @Test
    void markReadBindsOnePlaceholderPerIdAndNeverInlinesThem() {
        List<UUID> ids = List.of(NOTIFICATION_ID, CURSOR_ID);

        repository.markRead(USER_ID, ids, DELIVERED);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(),
                eq(Timestamp.from(DELIVERED)),
                eq(USER_ID.toString()),
                eq(NOTIFICATION_ID.toString()),
                eq(CURSOR_ID.toString()));
        String statement = sql.getValue();
        assertThat(statement).contains("SET read_at = ? WHERE user_id = ? AND read_at IS NULL AND id IN (?, ?)");
        assertThat(placeholders(statement)).isEqualTo(4);
        assertThat(statement).doesNotContain(NOTIFICATION_ID.toString());
    }

    private static long placeholders(String sql) {
        return sql.chars().filter(c -> c == '?').count();
    }

    private ReminderTriggerRow row() {
        return new ReminderTriggerRow(
                NOTIFICATION_ID, USER_ID, REMINDER_ID, "线性代数", "第三章", "COURSE", PLANNED, NEXT,
                TRACE_ID, DELIVERED, null, DELIVERED, DELIVERED);
    }
}
