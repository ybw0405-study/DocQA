package com.docqa.agentgateway.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.docqa.agentgateway.dto.UsageDayPoint;
import com.docqa.agentgateway.dto.UsageStatusCount;
import com.docqa.agentgateway.dto.UsageTopUser;
import com.docqa.agentgateway.dto.UsageTotals;
import com.docqa.common.tenant.TenantContext;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcAiUsageRepositoryTest {

    private static final UUID TENANT_ID = TenantContext.DEFAULT_TENANT_ID;
    private static final Instant FROM = Instant.parse("2026-11-02T09:00:00Z");
    private static final Instant TO = Instant.parse("2026-11-03T09:00:00Z");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final JdbcAiUsageRepository repository = new JdbcAiUsageRepository(jdbcTemplate);

    @Test
    @SuppressWarnings("unchecked")
    void totalsCountSuccessBudgetAndFailureSeparately() {
        repository.totals(TENANT_ID, FROM, TO);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(
                sqlCaptor.capture(),
                any(RowMapper.class),
                eq(TENANT_ID.toString()),
                eq(Timestamp.from(FROM)),
                eq(Timestamp.from(TO)));
        String sql = sqlCaptor.getValue();
        assertThat(sql).contains("FROM agent_execution");
        assertThat(sql).contains("WHERE tenant_id = ? AND started_at >= ? AND started_at <= ?");
        assertThat(sql).contains("status = 'SUCCESS'");
        assertThat(sql).contains("status = 'BUDGET_EXHAUSTED'");
        assertThat(sql).contains("status = 'FAILED'");
        assertThat(sql).contains("SUM(token_used)");
        assertThat(sql).contains("AVG(latency_ms)");
        assertThat(sql).doesNotContain("SELECT *");
    }

    @Test
    @SuppressWarnings("unchecked")
    void mapsTotalsIncludingANullAverage() throws Exception {
        ArgumentCaptor<RowMapper<UsageTotals>> mapperCaptor = ArgumentCaptor.forClass(RowMapper.class);
        repository.totals(TENANT_ID, FROM, TO);
        verify(jdbcTemplate).query(any(String.class), mapperCaptor.capture(),
                any(String.class), any(Timestamp.class), any(Timestamp.class));

        UsageTotals totals = mapperCaptor.getValue().mapRow(totalsRow(null), 0);

        assertThat(totals.questions()).isEqualTo(128);
        assertThat(totals.success()).isEqualTo(113);
        assertThat(totals.budgetExhausted()).isEqualTo(6);
        assertThat(totals.failed()).isEqualTo(9);
        assertThat(totals.tokens()).isEqualTo(412_882);
        assertThat(totals.toolCalls()).isEqualTo(74);
        assertThat(totals.rounds()).isEqualTo(301);
        assertThat(totals.cacheHits()).isEqualTo(17);
        assertThat(totals.avgLatencyMs()).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void mapsTheAverageLatencyAsANumber() throws Exception {
        ArgumentCaptor<RowMapper<UsageTotals>> mapperCaptor = ArgumentCaptor.forClass(RowMapper.class);
        repository.totals(TENANT_ID, FROM, TO);
        verify(jdbcTemplate).query(any(String.class), mapperCaptor.capture(),
                any(String.class), any(Timestamp.class), any(Timestamp.class));

        UsageTotals totals = mapperCaptor.getValue().mapRow(totalsRow(new BigDecimal("2140.5")), 0);

        assertThat(totals.avgLatencyMs()).isEqualTo(2140.5);
    }

    @Test
    void returnsZeroTotalsWhenTheAggregateProducesNoRow() {
        when(jdbcTemplate.query(any(String.class), any(RowMapper.class), any(), any(), any()))
                .thenReturn(List.of());

        UsageTotals totals = repository.totals(TENANT_ID, FROM, TO);

        assertThat(totals.questions()).isZero();
        assertThat(totals.avgLatencyMs()).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void groupsDaysByTheUtcDateFormattedFromStartedAt() {
        repository.byDay(TENANT_ID, FROM, TO);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sqlCaptor.capture(), any(RowMapper.class),
                eq(TENANT_ID.toString()), eq(Timestamp.from(FROM)), eq(Timestamp.from(TO)));
        String sql = sqlCaptor.getValue();
        assertThat(sql).contains("DATE_FORMAT(started_at, '%Y-%m-%d') AS usage_day");
        assertThat(sql).contains("GROUP BY usage_day");
        assertThat(sql).contains("ORDER BY usage_day");
        assertThat(sql).contains("status = 'FAILED'");
    }

    @Test
    @SuppressWarnings("unchecked")
    void mapsADayPoint() throws Exception {
        ArgumentCaptor<RowMapper<UsageDayPoint>> mapperCaptor = ArgumentCaptor.forClass(RowMapper.class);
        repository.byDay(TENANT_ID, FROM, TO);
        verify(jdbcTemplate).query(any(String.class), mapperCaptor.capture(),
                any(String.class), any(Timestamp.class), any(Timestamp.class));

        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("usage_day")).thenReturn("2026-11-02");
        when(rs.getObject("questions")).thenReturn(61L);
        when(rs.getObject("tokens")).thenReturn(new BigDecimal("198402"));
        when(rs.getObject("failures")).thenReturn(5);

        UsageDayPoint point = mapperCaptor.getValue().mapRow(rs, 0);

        assertThat(point.date()).isEqualTo("2026-11-02");
        assertThat(point.questions()).isEqualTo(61);
        assertThat(point.tokens()).isEqualTo(198_402);
        assertThat(point.failed()).isEqualTo(5);
    }

    @Test
    @SuppressWarnings("unchecked")
    void mapsStatusCounts() throws Exception {
        ArgumentCaptor<RowMapper<UsageStatusCount>> mapperCaptor = ArgumentCaptor.forClass(RowMapper.class);
        repository.byStatus(TENANT_ID, FROM, TO);
        verify(jdbcTemplate).query(any(String.class), mapperCaptor.capture(),
                any(String.class), any(Timestamp.class), any(Timestamp.class));

        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("status")).thenReturn("SUCCESS");
        when(rs.getObject("executions")).thenReturn(113L);

        UsageStatusCount count = mapperCaptor.getValue().mapRow(rs, 0);

        assertThat(count.status()).isEqualTo("SUCCESS");
        assertThat(count.count()).isEqualTo(113);
    }

    @Test
    @SuppressWarnings("unchecked")
    void topUsersJoinTheIdentityTableAndTieBreakOnUserId() {
        repository.topUsers(TENANT_ID, FROM, TO, 10);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sqlCaptor.capture(), any(RowMapper.class),
                eq(TENANT_ID.toString()), eq(Timestamp.from(FROM)), eq(Timestamp.from(TO)), eq(10));
        String sql = sqlCaptor.getValue();
        assertThat(sql).contains("LEFT JOIN app_user u ON u.id = e.user_id AND u.tenant_id = e.tenant_id");
        assertThat(sql).contains("GROUP BY e.user_id, u.username");
        assertThat(sql).contains("ORDER BY questions DESC, tokens DESC, e.user_id ASC");
        assertThat(sql).contains("LIMIT ?");
    }

    @Test
    @SuppressWarnings("unchecked")
    void mapsATopUserWithAMissingUsername() throws Exception {
        ArgumentCaptor<RowMapper<UsageTopUser>> mapperCaptor = ArgumentCaptor.forClass(RowMapper.class);
        repository.topUsers(TENANT_ID, FROM, TO, 10);
        verify(jdbcTemplate).query(any(String.class), mapperCaptor.capture(),
                any(String.class), any(Timestamp.class), any(Timestamp.class), eq(10));

        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("user_id")).thenReturn(USER_ID.toString());
        when(rs.getString("username")).thenReturn(null);
        when(rs.getObject("questions")).thenReturn(44L);
        when(rs.getObject("tokens")).thenReturn(142_731L);

        UsageTopUser user = mapperCaptor.getValue().mapRow(rs, 0);

        assertThat(user.userId()).isEqualTo(USER_ID);
        assertThat(user.username()).isNull();
        assertThat(user.questions()).isEqualTo(44);
        assertThat(user.tokens()).isEqualTo(142_731);
    }

    private static ResultSet totalsRow(BigDecimal avgLatency) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getObject("questions")).thenReturn(128L);
        when(rs.getObject("successes")).thenReturn(113L);
        when(rs.getObject("budget_exhausted")).thenReturn(6L);
        when(rs.getObject("failures")).thenReturn(9L);
        when(rs.getObject("tokens")).thenReturn(new BigDecimal("412882"));
        when(rs.getObject("tool_calls")).thenReturn(74L);
        when(rs.getObject("rounds")).thenReturn(301L);
        when(rs.getObject("cache_hits")).thenReturn(17L);
        when(rs.getBigDecimal("avg_latency_ms")).thenReturn(avgLatency);
        return rs;
    }
}
