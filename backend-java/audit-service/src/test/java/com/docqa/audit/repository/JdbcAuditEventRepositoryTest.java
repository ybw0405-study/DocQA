package com.docqa.audit.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.docqa.audit.domain.AuditEventRow;
import java.lang.reflect.Method;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcAuditEventRepositoryTest {

    private static final UUID EVENT_ID = UUID.fromString("0192f3a4-a1b2-7c33-9d01-1234567890ab");
    private static final UUID USER_ID = UUID.fromString("0192f3a4-7b2c-7d10-9e8f-1234567890ab");
    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final Instant OCCURRED_AT = Instant.parse("2026-10-20T10:15:01.120Z");
    private static final Instant INGESTED_AT = Instant.parse("2026-10-20T10:15:02Z");

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final JdbcAuditEventRepository repository =
            new JdbcAuditEventRepository(jdbcTemplate, objectMapper);

    @Test
    void insertsEveryColumnWithAParameterizedStatement() {
        repository.insert(row());

        ArgumentCaptor<Object[]> captor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(eq(JdbcAuditEventRepository.INSERT_SQL), captor.capture());
        Object[] args = captor.getValue();
        assertThat(args).hasSize(10);
        assertThat(args[0]).isEqualTo(EVENT_ID.toString());
        assertThat(args[1]).isEqualTo(TRACE_ID);
        assertThat(args[2]).isEqualTo(USER_ID.toString());
        assertThat(args[3]).isEqualTo("RAG_RETRIEVED");
        assertThat(args[4]).isEqualTo("rag_retriever");
        assertThat(args[5]).isEqualTo("PYTHON");
        assertThat(args[6]).isEqualTo("INFO");
        assertThat(args[7]).isEqualTo("{\"chunkCount\":6,\"durationMs\":182}");
        assertThat(args[8]).isEqualTo(Timestamp.from(OCCURRED_AT));
        assertThat(args[9]).isEqualTo(Timestamp.from(INGESTED_AT));
    }

    @Test
    void queriesByTraceIdAndMapsTheStoredPayload() throws Exception {
        when(jdbcTemplate.query(
                eq(JdbcAuditEventRepository.SELECT_BY_TRACE_SQL),
                any(RowMapper.class),
                eq(TRACE_ID),
                eq(50)))
                .thenReturn(List.<AuditEventRow>of());

        List<AuditEventRow> result = repository.findByTraceId(TRACE_ID, 50);
        assertThat(result).isEmpty();

        ArgumentCaptor<RowMapper<AuditEventRow>> mapperCaptor = mapperCaptor();
        verify(jdbcTemplate).query(
                eq(JdbcAuditEventRepository.SELECT_BY_TRACE_SQL),
                mapperCaptor.capture(),
                eq(TRACE_ID),
                eq(50));
        ResultSet resultSet = resultSet();
        AuditEventRow mapped = mapperCaptor.getValue().mapRow(resultSet, 0);
        assertThat(mapped.id()).isEqualTo(EVENT_ID);
        assertThat(mapped.traceId()).isEqualTo(TRACE_ID);
        assertThat(mapped.userId()).isEqualTo(USER_ID);
        assertThat(mapped.payloadRedacted()).containsEntry("chunkCount", 6);
        assertThat(mapped.occurredAt()).isEqualTo(OCCURRED_AT);
        assertThat(mapped.ingestedAt()).isEqualTo(INGESTED_AT);
    }

    @Test
    void repositorySurfaceIsAppendOnly() {
        assertThat(AuditEventRepository.class.getDeclaredMethods())
                .extracting(Method::getName)
                .containsExactlyInAnyOrder("insert", "findByTraceId");
        assertThat(JdbcAuditEventRepository.INSERT_SQL).startsWith("INSERT INTO audit_event");
        assertThat(JdbcAuditEventRepository.SELECT_BY_TRACE_SQL).startsWith("SELECT");
        assertThat(JdbcAuditEventRepository.INSERT_SQL.toLowerCase()).doesNotContain("update", "delete");
        assertThat(JdbcAuditEventRepository.SELECT_BY_TRACE_SQL.toLowerCase()).doesNotContain("update", "delete");
    }

    private AuditEventRow row() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("chunkCount", 6L);
        payload.put("durationMs", 182L);
        return new AuditEventRow(
                EVENT_ID, TRACE_ID, USER_ID, "RAG_RETRIEVED", "rag_retriever",
                "PYTHON", "INFO", payload, OCCURRED_AT, INGESTED_AT);
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<RowMapper<AuditEventRow>> mapperCaptor() {
        return ArgumentCaptor.forClass(RowMapper.class);
    }

    private ResultSet resultSet() {
        try {
            ResultSet resultSet = mock(ResultSet.class);
            when(resultSet.getString("id")).thenReturn(EVENT_ID.toString());
            when(resultSet.getString("trace_id")).thenReturn(TRACE_ID);
            when(resultSet.getString("user_id")).thenReturn(USER_ID.toString());
            when(resultSet.getString("event_type")).thenReturn("RAG_RETRIEVED");
            when(resultSet.getString("agent_name")).thenReturn("rag_retriever");
            when(resultSet.getString("source")).thenReturn("PYTHON");
            when(resultSet.getString("severity")).thenReturn("INFO");
            when(resultSet.getString("payload_redacted")).thenReturn("{\"chunkCount\":6}");
            when(resultSet.getTimestamp("occurred_at")).thenReturn(Timestamp.from(OCCURRED_AT));
            when(resultSet.getTimestamp("ingested_at")).thenReturn(Timestamp.from(INGESTED_AT));
            return resultSet;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
