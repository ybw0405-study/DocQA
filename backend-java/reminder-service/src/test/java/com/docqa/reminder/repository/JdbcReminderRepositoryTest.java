package com.docqa.reminder.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.docqa.reminder.domain.ReminderRow;
import com.docqa.reminder.domain.ReminderStatus;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcReminderRepositoryTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID CURSOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID REMINDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final Instant NEXT_TRIGGER = Instant.parse("2026-10-25T13:59:59Z");
    private static final Instant DTSTART = Instant.parse("2026-09-07T00:00:00Z");
    private static final Instant EXDATE = Instant.parse("2026-10-05T00:00:00Z");
    private static final LocalDate EXDATE_DATE = LocalDate.parse("2026-10-12");
    private static final String SOURCE_UID = "linear-algebra-2026-fall-mon-0800";

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final JdbcReminderRepository repository =
            new JdbcReminderRepository(jdbcTemplate, new ObjectMapper().findAndRegisterModules());

    @Test
    void firstPageOrdersTheNoTriggerGroupLastAndBindsOnlyTheUser() {
        repository.findUserPage(USER_ID, null, null, null, false, 21);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sqlCaptor.capture(), any(RowMapper.class), eq(USER_ID.toString()), eq(21));
        String sql = sqlCaptor.getValue();
        assertThat(sql).contains("FROM reminder WHERE user_id = ?");
        assertThat(sql).contains("ORDER BY (next_trigger_at IS NULL) ASC, next_trigger_at ASC, id ASC LIMIT ?");
        assertThat(sql).doesNotContain("next_trigger_at > ?");
        assertThat(sql).doesNotContain("IS NULL AND id > ?");
        assertThat(sql).doesNotContain(USER_ID.toString());
    }

    @Test
    void resumingInsideTheScheduledGroupStillYieldsTheNoTriggerRowsAfterwards() {
        repository.findUserPage(USER_ID, ReminderStatus.ACTIVE, NEXT_TRIGGER, CURSOR_ID, false, 21);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(
                sqlCaptor.capture(),
                any(RowMapper.class),
                eq(USER_ID.toString()),
                eq("ACTIVE"),
                eq(Timestamp.from(NEXT_TRIGGER)),
                eq(Timestamp.from(NEXT_TRIGGER)),
                eq(CURSOR_ID.toString()),
                eq(21));
        String sql = sqlCaptor.getValue();
        assertThat(sql).contains("AND status = ?");
        assertThat(sql).contains("AND ((next_trigger_at IS NOT NULL AND (next_trigger_at > ?");
        assertThat(sql).contains("OR (next_trigger_at = ? AND id > ?))) OR next_trigger_at IS NULL)");
        assertThat(sql).doesNotContain(NEXT_TRIGGER.toString());
        assertThat(sql).doesNotContain(CURSOR_ID.toString());
    }

    @Test
    void resumingInsideTheNoTriggerGroupComparesOnlyTheId() {
        repository.findUserPage(USER_ID, null, null, CURSOR_ID, true, 21);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(
                sqlCaptor.capture(),
                any(RowMapper.class),
                eq(USER_ID.toString()),
                eq(CURSOR_ID.toString()),
                eq(21));
        String sql = sqlCaptor.getValue();
        assertThat(sql).contains("AND (next_trigger_at IS NULL AND id > ?)");
        assertThat(sql).doesNotContain("next_trigger_at > ?");
    }

    @Test
    void insertBindsEveryColumnPositionallyIncludingLeadMinutes() {
        repository.insert(row(10, List.of(EXDATE), List.of(EXDATE_DATE)));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        // Mockito 在 varargs 位置上把一个 captor 只当作一个元素，所以逐个绑定只能用 eq：
        // 位置对齐本身就是断言，错位（把提前量写进时区那一列）会直接让 verify 失败。
        verify(jdbcTemplate).update(sql.capture(),
                eq(REMINDER_ID.toString()), eq(USER_ID.toString()), eq("线性代数"), eq(null),
                eq("FREQ=WEEKLY;BYDAY=MO"), eq(Timestamp.from(DTSTART)), eq("Asia/Shanghai"), eq(10),
                eq("[\"2026-10-05T00:00:00Z\"]"), eq("[\"2026-10-12\"]"),
                eq("ACTIVE"), eq("COURSE"), eq(SOURCE_UID), eq(3), eq("idem-key-0001"),
                eq(Timestamp.from(NEXT_TRIGGER)), eq(null), eq(0L),
                eq(Timestamp.from(NEXT_TRIGGER)), eq(Timestamp.from(NEXT_TRIGGER)));
        assertThat(placeholders(sql.getValue())).isEqualTo(20);
    }

    @Test
    void anImportWithoutExdatesBindsNullRatherThanAnEmptyJsonArray() {
        repository.insert(row(10, List.of(), List.of()));

        // 两列写 NULL 而不是 "[]"：读取侧把空串与 NULL 都还原成空列表，但 "[]" 让每行多存两字节的噪声，
        // 也让「从来没有过排除」与「排除过又清空了」在库里长得一样。
        verify(jdbcTemplate).update(any(String.class),
                any(), any(), any(), any(), any(), any(), any(), any(),
                isNull(), isNull(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void importUpdateRewritesRulesAndLeadButNeverStatusOrTriggerCursor() {
        repository.updateFromImport(row(10, List.of(EXDATE), List.of(EXDATE_DATE)));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(),
                eq("线性代数"), eq(null), eq("FREQ=WEEKLY;BYDAY=MO"), eq(Timestamp.from(DTSTART)),
                eq("Asia/Shanghai"), eq(10),
                eq("[\"2026-10-05T00:00:00Z\"]"), eq("[\"2026-10-12\"]"), eq(3),
                eq(Timestamp.from(NEXT_TRIGGER)), eq(Timestamp.from(NEXT_TRIGGER)),
                eq(REMINDER_ID.toString()), eq(USER_ID.toString()));
        String statement = sql.getValue();
        assertThat(placeholders(statement)).isEqualTo(13);
        assertThat(statement).contains("lead_minutes = ?");
        assertThat(statement).contains("exdates = ?, exdate_dates = ?, source_sequence = ?");
        assertThat(statement).contains("version = version + 1");
        // UPDATE 不复活已终止的提醒，也不越过调度器去动触发游标：这两列一旦出现就能把 CANCELLED 改回 ACTIVE。
        assertThat(statement).doesNotContain("status");
        assertThat(statement).doesNotContain("last_triggered_at");
        // 归属由 user_id 兜住：WHERE 少这一个条件，就能拿别人的 id 覆盖别人的课表。
        assertThat(statement).contains("WHERE id = ? AND user_id = ?");
    }

    @Test
    void sourceUidLookupUsesOnePlaceholderPerUidAndBindsEveryValue() {
        repository.findByUserAndSourceUids(USER_ID, "COURSE", List.of(SOURCE_UID, "离散数学-uid"));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class),
                eq(USER_ID.toString()), eq("COURSE"), eq(SOURCE_UID), eq("离散数学-uid"));
        assertThat(placeholders(sql.getValue())).isEqualTo(4);
        // UID 来自上传文件，属于外部输入：只能进占位符，不能拼进 SQL 文本。
        assertThat(sql.getValue()).doesNotContain(SOURCE_UID);
    }

    @Test
    void emptyUidListNeverReachesTheDatabase() {
        repository.findByUserAndSourceUids(USER_ID, "COURSE", List.of());

        // IN () 是非法 SQL；空批在仓库层短路，调用方不必自己判空。
        verifyNoInteractions(jdbcTemplate);
    }

    private static long placeholders(String sql) {
        return sql.chars().filter(c -> c == '?').count();
    }

    private static ReminderRow row(int leadMinutes, List<Instant> exdates, List<LocalDate> exdateDates) {
        return new ReminderRow(
                REMINDER_ID, USER_ID, "线性代数", null, "FREQ=WEEKLY;BYDAY=MO", DTSTART, "Asia/Shanghai",
                leadMinutes, exdates, exdateDates, ReminderStatus.ACTIVE, "COURSE", SOURCE_UID, 3,
                "idem-key-0001", NEXT_TRIGGER, null, 0L, NEXT_TRIGGER, NEXT_TRIGGER);
    }
}
