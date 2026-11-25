package com.docqa.common.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.docqa.common.db.AuditAppendOnlyAssessment.Verdict;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

class AuditAppendOnlyGuardTest {

    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
    }

    private void stubDatabase(String account, String schema, List<Map<String, Object>> rows) {
        when(jdbcTemplate.queryForObject(AuditAppendOnlyGuard.ACCOUNT_SQL, String.class)).thenReturn(account);
        when(jdbcTemplate.queryForObject(AuditAppendOnlyGuard.SCHEMA_SQL, String.class)).thenReturn(schema);
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(rows);
    }

    @Test
    @DisplayName("合法账号：run 不抛异常")
    void appendOnlyAccountPasses() {
        stubDatabase("ss_audit@localhost", "docqa", List.of(
                Map.of("SCOPE", "TABLE", "PRIVILEGE", "SELECT"),
                Map.of("SCOPE", "TABLE", "PRIVILEGE", "INSERT")));

        new AuditAppendOnlyGuard(jdbcTemplate, AuditAppendOnlyGuard.Mode.ENFORCE).run(null);
    }

    @Test
    @DisplayName("enforce 模式下违规即拒绝启动，异常信息里带上可执行的修因语句")
    void enforceModeFailsFastOnViolation() {
        stubDatabase("ss_app@localhost", "docqa", List.of(
                Map.of("SCOPE", "GLOBAL", "PRIVILEGE", "UPDATE"),
                Map.of("SCOPE", "TABLE", "PRIVILEGE", "SELECT"),
                Map.of("SCOPE", "TABLE", "PRIVILEGE", "INSERT")));

        AuditAppendOnlyGuard guard = new AuditAppendOnlyGuard(jdbcTemplate, AuditAppendOnlyGuard.Mode.ENFORCE);

        assertThatThrownBy(() -> guard.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("verdict=VIOLATION")
                .hasMessageContaining("REVOKE UPDATE ON *.* FROM 'ss_app'@'localhost';");
    }

    @Test
    @DisplayName("root 连库时给「换账号」的提示，而不是一条会自伤的 REVOKE")
    void rootAccountGetsSwitchAccountHint() {
        stubDatabase("root@localhost", "docqa", List.of(
                Map.of("SCOPE", "GLOBAL", "PRIVILEGE", "UPDATE"),
                Map.of("SCOPE", "GLOBAL", "PRIVILEGE", "DELETE"),
                Map.of("SCOPE", "TABLE", "PRIVILEGE", "SELECT"),
                Map.of("SCOPE", "TABLE", "PRIVILEGE", "INSERT")));

        AuditAppendOnlyGuard guard = new AuditAppendOnlyGuard(jdbcTemplate, AuditAppendOnlyGuard.Mode.ENFORCE);

        // 照抄「REVOKE ... ON *.* FROM 'root'@'localhost'」会把迁移与运维一起废掉
        assertThatThrownBy(() -> guard.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不要撤 root 的权限")
                .hasMessageNotContaining("REVOKE UPDATE");
    }

    @Test
    @DisplayName("warn 模式只记录，不影响启动：默认模式必须能跑在还没收敛的环境上")
    void warnModeDoesNotBlockStartup() {
        stubDatabase("root@localhost", "docqa", List.of(Map.of("SCOPE", "GLOBAL", "PRIVILEGE", "UPDATE")));

        new AuditAppendOnlyGuard(jdbcTemplate, AuditAppendOnlyGuard.Mode.WARN).run(null);
    }

    @Test
    @DisplayName("enforce 下读不到授权事实时失败关闭（证明不了合规即视为不合规）")
    void enforceModeFailsClosedWhenProbeFails() {
        when(jdbcTemplate.queryForObject(AuditAppendOnlyGuard.ACCOUNT_SQL, String.class))
                .thenThrow(new DataAccessResourceFailureException("connection refused"));

        AuditAppendOnlyGuard guard = new AuditAppendOnlyGuard(jdbcTemplate, AuditAppendOnlyGuard.Mode.ENFORCE);

        assertThatThrownBy(() -> guard.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("could not verify")
                .hasMessageContaining("connection refused");
    }

    @Test
    @DisplayName("off 模式一条 SQL 都不发")
    void offModeSkipsAllQueries() {
        new AuditAppendOnlyGuard(jdbcTemplate, AuditAppendOnlyGuard.Mode.OFF).run(null);

        verify(jdbcTemplate, org.mockito.Mockito.never()).queryForObject(anyString(), eq(String.class));
    }

    @Test
    @DisplayName("GRANTEE 绑定值是 'user'@'host'，不是 user@host：绑错就永远查不到授权")
    void granteeBindingUsesInformationSchemaLiteral() {
        stubDatabase("ss_app@localhost", "docqa", List.of(
                Map.of("SCOPE", "TABLE", "PRIVILEGE", "SELECT"),
                Map.of("SCOPE", "TABLE", "PRIVILEGE", "INSERT")));

        new AuditAppendOnlyGuard(jdbcTemplate, AuditAppendOnlyGuard.Mode.WARN).inspect();

        ArgumentCaptor<Object[]> captor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).queryForList(eq(AuditAppendOnlyGuard.PRIVILEGE_SQL), captor.capture());
        assertThat(captor.getValue()).containsExactly(
                "'ss_app'@'localhost'", "'ss_app'@'localhost'", "docqa",
                "'ss_app'@'localhost'", "docqa");
    }

    @Test
    @DisplayName("列名大小写不一致也能读到值：驱动升级不该让守卫静默判 OK")
    void columnLookupIsCaseInsensitive() {
        stubDatabase("ss_audit@localhost", "docqa", List.of(
                Map.of("scope", "table", "privilege", "select"),
                Map.of("SCOPE", "TABLE", "PRIVILEGE", "insert")));

        AuditAppendOnlyAssessment.Result result = new AuditAppendOnlyGuard(jdbcTemplate, AuditAppendOnlyGuard.Mode.WARN).inspect();

        assertThat(result.verdict()).isEqualTo(Verdict.OK);
    }

    @Test
    @DisplayName("账号或库名缺失时降级为 UNKNOWN，不擅自判成 OK")
    void missingSchemaYieldsUnknown() {
        stubDatabase("ss_audit@localhost", null, List.of());

        AuditAppendOnlyAssessment.Result result = new AuditAppendOnlyGuard(jdbcTemplate, AuditAppendOnlyGuard.Mode.WARN).inspect();

        assertThat(result.verdict()).isEqualTo(Verdict.UNKNOWN);
    }

    @Test
    @DisplayName("模式解析：拼错的值必须报错，未配置默认收紧为 enforce")
    void unknownModeIsRejected() {
        assertThat(AuditAppendOnlyGuard.Mode.parse("enforce")).isEqualTo(AuditAppendOnlyGuard.Mode.ENFORCE);
        assertThat(AuditAppendOnlyGuard.Mode.parse(" OFF ")).isEqualTo(AuditAppendOnlyGuard.Mode.OFF);
        assertThat(AuditAppendOnlyGuard.Mode.parse(null)).isEqualTo(AuditAppendOnlyGuard.Mode.ENFORCE);
        assertThat(AuditAppendOnlyGuard.Mode.parse(" ")).isEqualTo(AuditAppendOnlyGuard.Mode.ENFORCE);
        assertThatThrownBy(() -> AuditAppendOnlyGuard.Mode.parse("enfroce"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("enfroce");
    }
}
