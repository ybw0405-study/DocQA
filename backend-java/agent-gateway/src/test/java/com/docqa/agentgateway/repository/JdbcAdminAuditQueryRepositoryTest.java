package com.docqa.agentgateway.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.docqa.agentgateway.dto.AuditEventItem;
import com.docqa.common.tenant.TenantContext;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcAdminAuditQueryRepositoryTest {

    private static final UUID EVENT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID CURSOR_ID = UUID.fromString("00000000-0000-0000-0000-0000000000e2");
    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String EVENT_TYPE = "RAG_RETRIEVED";
    private static final Instant FROM = Instant.parse("2026-11-02T09:00:00Z");
    private static final Instant TO = Instant.parse("2026-11-03T09:00:00Z");
    private static final Instant OCCURRED_AT = Instant.parse("2026-11-03T10:00:01.120Z");

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final JdbcAdminAuditQueryRepository repository =
            new JdbcAdminAuditQueryRepository(jdbcTemplate, new ObjectMapper());

    @Test
    @SuppressWarnings("unchecked")
    void ordersByOccurredAtThenIdEvenWithoutFilters() {
        repository.findPage(TenantContext.DEFAULT_TENANT_ID, new AuditQueryFilter(null, null, null, null, null), null, null, 11);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sqlCaptor.capture(), argsCaptor.capture(), any(RowMapper.class));
        String sql = sqlCaptor.getValue();
        assertThat(sql).contains("FROM audit_event");
        assertThat(sql).contains("ORDER BY occurred_at DESC, id DESC LIMIT ?");
        assertThat(sql).doesNotContain("trace_id = ?");
        assertThat(sql).doesNotContain("user_id = ?");
        assertThat(sql).doesNotContain("event_type = ?");
        assertThat(sql).doesNotContain("occurred_at >=");
        assertThat(sql).doesNotContain("*");
        assertThat(argsCaptor.getValue()).containsExactly(TenantContext.DEFAULT_TENANT_ID.toString(), 11);
    }

    @Test
    @SuppressWarnings("unchecked")
    void appliesEveryFilterAndTheWindowInDeclarationOrder() {
        repository.findPage(
                TenantContext.DEFAULT_TENANT_ID,
                new AuditQueryFilter(TRACE_ID, USER_ID, EVENT_TYPE, FROM, TO), null, null, 101);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sqlCaptor.capture(), argsCaptor.capture(), any(RowMapper.class));
        String sql = sqlCaptor.getValue();
        assertThat(sql).contains("AND trace_id = ?");
        assertThat(sql).contains("AND user_id = ?");
        assertThat(sql).contains("AND event_type = ?");
        assertThat(sql).contains("AND occurred_at >= ?");
        assertThat(sql).contains("AND occurred_at <= ?");
        assertThat(sql).doesNotContain("occurred_at < ?");
        assertThat(argsCaptor.getValue()).containsExactly(
                TenantContext.DEFAULT_TENANT_ID.toString(),
                TRACE_ID,
                USER_ID.toString(),
                EVENT_TYPE,
                Timestamp.from(FROM),
                Timestamp.from(TO),
                101);
    }

    @Test
    @SuppressWarnings("unchecked")
    void cursorNarrowsStrictlyBelowTheLastVisibleRow() {
        repository.findPage(
                TenantContext.DEFAULT_TENANT_ID,
                new AuditQueryFilter(TRACE_ID, null, null, null, null), OCCURRED_AT, CURSOR_ID, 101);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sqlCaptor.capture(), argsCaptor.capture(), any(RowMapper.class));
        String sql = sqlCaptor.getValue();
        assertThat(sql).contains("AND (occurred_at < ? OR (occurred_at = ? AND id < ?))");
        assertThat(argsCaptor.getValue()).containsExactly(
                TenantContext.DEFAULT_TENANT_ID.toString(),
                TRACE_ID,
                Timestamp.from(OCCURRED_AT),
                Timestamp.from(OCCURRED_AT),
                CURSOR_ID.toString(),
                101);
        assertThat(sql).doesNotContain(CURSOR_ID.toString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void halfCursorIsIgnored() {
        repository.findPage(TenantContext.DEFAULT_TENANT_ID, new AuditQueryFilter(null, USER_ID, null, null, null), null, CURSOR_ID, 101);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sqlCaptor.capture(), any(Object[].class), any(RowMapper.class));
        assertThat(sqlCaptor.getValue()).doesNotContain("occurred_at < ?");
    }

    @Test
    @SuppressWarnings("unchecked")
    void mapsNullAgentNameAndParsesTheRedactedPayload() throws Exception {
        ArgumentCaptor<RowMapper<AuditEventItem>> mapperCaptor = ArgumentCaptor.forClass(RowMapper.class);
        repository.findPage(TenantContext.DEFAULT_TENANT_ID, new AuditQueryFilter(null, null, null, null, null), null, null, 1);
        verify(jdbcTemplate).query(any(String.class), any(Object[].class), mapperCaptor.capture());

        AuditEventItem row = mapperCaptor.getValue().mapRow(
                resultSet("{\"chunkCount\":6,\"topScore\":0.87}", null), 0);

        assertThat(row.eventId()).isEqualTo(EVENT_ID);
        assertThat(row.traceId()).isEqualTo(TRACE_ID);
        assertThat(row.userId()).isEqualTo(USER_ID);
        assertThat(row.eventType()).isEqualTo(EVENT_TYPE);
        assertThat(row.agentName()).isNull();
        assertThat(row.source()).isEqualTo("PYTHON");
        assertThat(row.severity()).isEqualTo("INFO");
        assertThat(row.payload()).isEqualTo(Map.of("chunkCount", 6, "topScore", 0.87));
        assertThat(row.occurredAt()).isEqualTo(OCCURRED_AT);
    }

    @Test
    @SuppressWarnings("unchecked")
    void keepsAgentNameWhenPresent() throws Exception {
        ArgumentCaptor<RowMapper<AuditEventItem>> mapperCaptor = ArgumentCaptor.forClass(RowMapper.class);
        repository.findPage(TenantContext.DEFAULT_TENANT_ID, new AuditQueryFilter(null, null, null, null, null), null, null, 1);
        verify(jdbcTemplate).query(any(String.class), any(Object[].class), mapperCaptor.capture());

        AuditEventItem row = mapperCaptor.getValue().mapRow(resultSet("{}", "rag_retriever"), 0);

        assertThat(row.agentName()).isEqualTo("rag_retriever");
    }

    @Test
    @SuppressWarnings("unchecked")
    void failsLoudlyWhenAStoredPayloadIsNotJson() throws Exception {
        ArgumentCaptor<RowMapper<AuditEventItem>> mapperCaptor = ArgumentCaptor.forClass(RowMapper.class);
        repository.findPage(TenantContext.DEFAULT_TENANT_ID, new AuditQueryFilter(null, null, null, null, null), null, null, 1);
        verify(jdbcTemplate).query(any(String.class), any(Object[].class), mapperCaptor.capture());

        RowMapper<AuditEventItem> mapper = mapperCaptor.getValue();
        ResultSet rs = resultSet("not-json", null);

        assertThatThrownBy(() -> mapper.mapRow(rs, 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("audit payload");
    }

    @Test
    void querySurfaceIsReadOnly() {
        assertThat(JdbcAdminAuditQueryRepository.PROJECTION)
                .startsWith("SELECT id, trace_id, user_id, event_type");
        assertThat(JdbcAdminAuditQueryRepository.PROJECTION.toLowerCase())
                .doesNotContain("update", "delete", "insert");
    }

    private static ResultSet resultSet(String payload, String agentName) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("id")).thenReturn(EVENT_ID.toString());
        when(rs.getString("trace_id")).thenReturn(TRACE_ID);
        when(rs.getString("user_id")).thenReturn(USER_ID.toString());
        when(rs.getString("event_type")).thenReturn(EVENT_TYPE);
        when(rs.getString("agent_name")).thenReturn(agentName);
        when(rs.getString("source")).thenReturn("PYTHON");
        when(rs.getString("severity")).thenReturn("INFO");
        when(rs.getString("payload_redacted")).thenReturn(payload);
        when(rs.getTimestamp("occurred_at")).thenReturn(Timestamp.from(OCCURRED_AT));
        return rs;
    }
}
