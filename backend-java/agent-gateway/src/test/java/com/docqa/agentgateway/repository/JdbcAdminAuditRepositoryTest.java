package com.docqa.agentgateway.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.docqa.common.tenant.TenantContext;
import java.lang.reflect.Method;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcAdminAuditRepositoryTest {

    private static final UUID EVENT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    private static final UUID ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final Instant OCCURRED_AT = Instant.parse("2026-10-20T10:15:01.120Z");

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final JdbcAdminAuditRepository repository =
            new JdbcAdminAuditRepository(jdbcTemplate, new ObjectMapper().findAndRegisterModules());

    @Test
    void insertsEveryColumnWithAParameterizedStatement() {
        repository.insert(TenantContext.DEFAULT_TENANT_ID, entry());

        ArgumentCaptor<Object[]> captor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(eq(JdbcAdminAuditRepository.INSERT_SQL), captor.capture());
        Object[] args = captor.getValue();
        assertThat(args).hasSize(11);
        assertThat(args[0]).isEqualTo(TenantContext.DEFAULT_TENANT_ID.toString());
        assertThat(args[1]).isEqualTo(EVENT_ID.toString());
        assertThat(args[2]).isEqualTo(TRACE_ID);
        assertThat(args[3]).isEqualTo(ADMIN_ID.toString());
        assertThat(args[4]).isEqualTo("ADMIN_AI_KILL_SWITCH_ENABLED");
        assertThat(args[5]).isEqualTo(JdbcAdminAuditRepository.AGENT_NAME);
        assertThat(args[6]).isEqualTo(JdbcAdminAuditRepository.SOURCE);
        assertThat(args[7]).isEqualTo("WARN");
        assertThat(args[8]).isEqualTo("{\"killSwitchActive\":true}");
        assertThat(args[9]).isEqualTo(Timestamp.from(OCCURRED_AT));
        assertThat(args[10]).isEqualTo(Timestamp.from(OCCURRED_AT));
    }

    @Test
    void writesTheAdminGatewayAsTheProducer() {
        assertThat(JdbcAdminAuditRepository.AGENT_NAME).isEqualTo("admin_gateway");
        assertThat(JdbcAdminAuditRepository.SOURCE).isEqualTo("JAVA");
    }

    @Test
    void repositorySurfaceIsAppendOnly() {
        assertThat(AdminAuditRepository.class.getDeclaredMethods())
                .extracting(Method::getName)
                .containsExactly("insert");
        String sql = JdbcAdminAuditRepository.INSERT_SQL;
        assertThat(sql).startsWith("INSERT INTO audit_event");
        assertThat(sql).contains(
                "tenant_id", "id", "trace_id", "user_id", "event_type", "agent_name",
                "source", "severity", "payload_redacted", "occurred_at", "ingested_at");
        assertThat(sql.toLowerCase()).doesNotContain("update", "delete");
    }

    private AdminAuditEntry entry() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("killSwitchActive", true);
        return new AdminAuditEntry(
                EVENT_ID,
                TRACE_ID,
                ADMIN_ID,
                "ADMIN_AI_KILL_SWITCH_ENABLED",
                "WARN",
                payload,
                OCCURRED_AT,
                OCCURRED_AT);
    }
}
