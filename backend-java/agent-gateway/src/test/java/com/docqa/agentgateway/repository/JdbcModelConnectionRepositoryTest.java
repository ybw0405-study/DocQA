package com.docqa.agentgateway.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.docqa.common.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * SQL 文本契约：租户谓词、生成列不可写、凭据两列同写、参数化而非字符串拼接。
 * 真库行为由 MigrationOnRealMysqlTest 覆盖，这里钉的是「SQL 长什么样」。
 */
class JdbcModelConnectionRepositoryTest {

    private static final UUID TENANT_ID = TenantContext.DEFAULT_TENANT_ID;
    private static final UUID CONNECTION_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final Instant NOW = Instant.parse("2026-11-22T10:15:42.010Z");

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final JdbcModelConnectionRepository repository = new JdbcModelConnectionRepository(jdbcTemplate);

    @Test
    void everyTenantScopedStatementCarriesTheTenantPredicate() {
        assertThat(JdbcModelConnectionRepository.FIND_ALL_SQL).contains("WHERE tenant_id = ?");
        assertThat(JdbcModelConnectionRepository.FIND_BY_ID_SQL).contains("WHERE tenant_id = ? AND id = ?");
        assertThat(JdbcModelConnectionRepository.FIND_BY_NAME_SQL).contains("WHERE tenant_id = ? AND name = ?");
        assertThat(JdbcModelConnectionRepository.FIND_ACTIVE_SQL).contains("WHERE tenant_id = ? AND active_slot = 1");
        assertThat(JdbcModelConnectionRepository.COUNT_SQL).contains("WHERE tenant_id = ?");
        assertThat(JdbcModelConnectionRepository.UPDATE_CREDENTIAL_SQL).contains("WHERE tenant_id = ? AND id = ?");
        assertThat(JdbcModelConnectionRepository.CLEAR_ACTIVE_SQL).contains("WHERE tenant_id = ?");
        assertThat(JdbcModelConnectionRepository.DEACTIVATE_SQL).contains("WHERE tenant_id = ? AND id = ?");
        assertThat(JdbcModelConnectionRepository.MARK_ACTIVE_SQL).contains("WHERE tenant_id = ? AND id = ?");
        assertThat(JdbcModelConnectionRepository.UPDATE_PROBE_SQL).contains("WHERE tenant_id = ? AND id = ?");
        assertThat(JdbcModelConnectionRepository.DELETE_SQL).contains("WHERE tenant_id = ? AND id = ?");
    }

    /** 投影必须逐列枚举：SELECT * 会把 credential 密文带进任何一次误用的日志或调试输出。 */
    @Test
    void projectionEnumeratesColumnsAndNeverSelectsStar() {
        assertThat(JdbcModelConnectionRepository.PROJECTION).doesNotContain("*");
        assertThat(JdbcModelConnectionRepository.PROJECTION).contains("credential, credential_key_id");
        assertThat(JdbcModelConnectionRepository.PROJECTION).contains("FROM model_connection");
    }

    /** active_slot 是 STORED 生成列，写它 MySQL 直接报 3105；is_active 也不该被 upsert 顺带改动。 */
    @Test
    void upsertNeverWritesTheGeneratedColumnOrTheActivationFlag() {
        assertThat(JdbcModelConnectionRepository.UPSERT_SQL).doesNotContain("active_slot");
        assertThat(JdbcModelConnectionRepository.UPSERT_SQL).contains("VALUES (?, ?, ?, ?, ?, ?, ?, FALSE, ?, ?)");
        String onDuplicate = JdbcModelConnectionRepository.UPSERT_SQL
                .substring(JdbcModelConnectionRepository.UPSERT_SQL.indexOf("ON DUPLICATE KEY UPDATE"));
        assertThat(onDuplicate).doesNotContain("is_active");
        assertThat(onDuplicate).contains("base_url = VALUES(base_url)");
        assertThat(onDuplicate).contains("model = VALUES(model)");
    }

    @Test
    void credentialAndKeyIdAreAlwaysWrittenTogether() {
        assertThat(JdbcModelConnectionRepository.UPDATE_CREDENTIAL_SQL)
                .contains("SET credential = ?, credential_key_id = ?");
    }

    @Test
    void passesIdentifiersAsParametersNotAsSqlText() {
        repository.upsert(TENANT_ID, CONNECTION_ID, "主连接", "deepseek", "https://api.deepseek.com/v1",
                "deepseek-chat", true, ADMIN_ID, NOW);

        verify(jdbcTemplate).update(
                JdbcModelConnectionRepository.UPSERT_SQL,
                CONNECTION_ID.toString(),
                TENANT_ID.toString(),
                "主连接",
                "deepseek",
                "https://api.deepseek.com/v1",
                "deepseek-chat",
                true,
                Timestamp.from(NOW),
                ADMIN_ID.toString());
        assertThat(JdbcModelConnectionRepository.UPSERT_SQL).doesNotContain(CONNECTION_ID.toString());
        assertThat(JdbcModelConnectionRepository.UPSERT_SQL).doesNotContain(TENANT_ID.toString());
    }

    @Test
    void deleteIsScopedToBothTenantAndConnection() {
        repository.delete(TENANT_ID, CONNECTION_ID);

        verify(jdbcTemplate).update(JdbcModelConnectionRepository.DELETE_SQL, TENANT_ID.toString(),
                CONNECTION_ID.toString());
    }

    @Test
    void deactivateTargetsOneRowWhileClearActiveTargetsTheTenant() {
        repository.deactivate(TENANT_ID, CONNECTION_ID, ADMIN_ID, NOW);
        verify(jdbcTemplate).update(eq(JdbcModelConnectionRepository.DEACTIVATE_SQL), any(Timestamp.class),
                eq(ADMIN_ID.toString()), eq(TENANT_ID.toString()), eq(CONNECTION_ID.toString()));

        repository.clearActive(TENANT_ID, ADMIN_ID, NOW);
        verify(jdbcTemplate).update(eq(JdbcModelConnectionRepository.CLEAR_ACTIVE_SQL), any(Timestamp.class),
                eq(ADMIN_ID.toString()), eq(TENANT_ID.toString()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void readsOneConnectionWithTheTenantPredicate() {
        repository.findById(TENANT_ID, CONNECTION_ID);

        verify(jdbcTemplate).query(eq(JdbcModelConnectionRepository.FIND_BY_ID_SQL), any(RowMapper.class),
                eq(TENANT_ID.toString()), eq(CONNECTION_ID.toString()));
    }

    /** 启动期重发布是有意跨租户的扫描：行自带 tenant_id，发布时逐行按租户建键。 */
    @Test
    void startupRepublishScansActiveRowsAcrossTenants() {
        repository.findAllActive();

        assertThat(JdbcModelConnectionRepository.FIND_ALL_ACTIVE_SQL).contains("WHERE active_slot = 1");
        assertThat(JdbcModelConnectionRepository.FIND_ALL_ACTIVE_SQL).contains("tenant_id");
        verify(jdbcTemplate).query(eq(JdbcModelConnectionRepository.FIND_ALL_ACTIVE_SQL), any(RowMapper.class));
    }
}
