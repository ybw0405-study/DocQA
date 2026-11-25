package com.docqa.common.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.docqa.common.db.AuditAppendOnlyAssessment.Grant;
import com.docqa.common.db.AuditAppendOnlyAssessment.Result;
import com.docqa.common.db.AuditAppendOnlyAssessment.Verdict;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AuditAppendOnlyAssessmentTest {

    private static final String ACCOUNT = "'ss_app'@'localhost'";
    private static final String SCHEMA = "docqa";

    @Test
    @DisplayName("表级 SELECT+INSERT 是合法形态：只追加成立")
    void appendOnlyGrantsAreAccepted() {
        Result result = AuditAppendOnlyAssessment.assess(ACCOUNT, SCHEMA, List.of(
                new Grant("schema", "USAGE"),
                new Grant("table", "SELECT"),
                new Grant("table", "INSERT")));

        assertThat(result.verdict()).isEqualTo(Verdict.OK);
        assertThat(result.offending()).isEmpty();
        assertThat(result.missing()).isEmpty();
        assertThat(result.remediationSql()).isEmpty();
    }

    @Test
    @DisplayName("全局 ALL PRIVILEGES 被展开成 UPDATE/DELETE 时按全局作用域判违规")
    void globalPrivilegesAreRejectedWithGlobalScope() {
        // information_schema.USER_PRIVILEGES 会把 GRANT ALL ON *.* 展开成逐项动作，
        // 所以永远不会看到字面量 "ALL PRIVILEGES"；判据必须靠 UPDATE/DELETE 这两行识别。
        Result result = AuditAppendOnlyAssessment.assess(ACCOUNT, SCHEMA, List.of(
                new Grant("global", "SELECT"),
                new Grant("global", "INSERT"),
                new Grant("global", "UPDATE"),
                new Grant("global", "DELETE")));

        assertThat(result.verdict()).isEqualTo(Verdict.VIOLATION);
        assertThat(result.offending()).extracting(Grant::describe)
                .containsExactly("global:UPDATE", "global:DELETE");
        assertThat(result.remediationSql())
                .containsExactly("REVOKE UPDATE, DELETE ON *.* FROM " + ACCOUNT + ";");
    }

    @Test
    @DisplayName("库级 UPDATE 的修因语句必须落回库级，而不是只撤表级")
    void schemaLevelViolationIsRemediatedAtSchemaScope() {
        Result result = AuditAppendOnlyAssessment.assess(ACCOUNT, SCHEMA, List.of(
                new Grant("SCHEMA", "UPDATE"),
                new Grant("TABLE", "SELECT"),
                new Grant("TABLE", "INSERT")));

        assertThat(result.isViolation()).isTrue();
        assertThat(result.remediationSql())
                .containsExactly("REVOKE UPDATE ON `docqa`.* FROM " + ACCOUNT + ";");
    }

    @Test
    @DisplayName("表级 UPDATE 的修因语句限定到审计表本身")
    void tableLevelViolationIsRemediatedOnAuditTable() {
        Result result = AuditAppendOnlyAssessment.assess(ACCOUNT, SCHEMA, List.of(
                new Grant("TABLE", "UPDATE"),
                new Grant("TABLE", "SELECT"),
                new Grant("TABLE", "INSERT")));

        assertThat(result.remediationSql())
                .containsExactly("REVOKE UPDATE ON `docqa`.`audit_event` FROM " + ACCOUNT + ";");
    }

    @Test
    @DisplayName("缺 INSERT 也算违规：否则服务会在运行期以 500 暴露，而不是启动即报错")
    void missingPrivilegesAreViolations() {
        Result result = AuditAppendOnlyAssessment.assess(ACCOUNT, SCHEMA, List.of(new Grant("TABLE", "SELECT")));

        assertThat(result.verdict()).isEqualTo(Verdict.VIOLATION);
        assertThat(result.offending()).isEmpty();
        assertThat(result.missing()).containsExactly("INSERT");
        assertThat(result.remediationSql())
                .containsExactly("GRANT INSERT ON `docqa`.`audit_event` TO " + ACCOUNT + ";");
    }

    @Test
    @DisplayName("一个授权都没有时，缺的两项按字典序给出")
    void emptyGrantsReportBothMissing() {
        Result result = AuditAppendOnlyAssessment.assess(ACCOUNT, SCHEMA, List.of());

        assertThat(result.missing()).containsExactly("INSERT", "SELECT");
        assertThat(result.summary()).contains("verdict=VIOLATION").contains("offending=[]");
    }

    @Test
    @DisplayName("连接串没带库名时判 UNKNOWN，不猜成 OK 也不猜成违规")
    void nullSchemaIsUnknown() {
        Result result = AuditAppendOnlyAssessment.assess(ACCOUNT, null, List.of(new Grant("TABLE", "SELECT")));

        assertThat(result.verdict()).isEqualTo(Verdict.UNKNOWN);
        assertThat(result.isUnknown()).isTrue();
        assertThat(result.isViolation()).isFalse();
        // 「判不了」的原因放在 reason 而不是 missing：两者的修因动作完全不同
        assertThat(result.reason()).isEqualTo("DATABASE() is null");
        assertThat(result.missing()).isEmpty();
        assertThat(result.remediationSql()).isEmpty();
    }

    @Test
    @DisplayName("未知作用域既不计入有效授权也不计入违规：解析异常不能变成安全结论")
    void unknownScopeIsIgnored() {
        Result result = AuditAppendOnlyAssessment.assess(ACCOUNT, SCHEMA, List.of(
                new Grant("ROLE", "UPDATE"),
                new Grant("TABLE", "SELECT"),
                new Grant("TABLE", "INSERT")));

        assertThat(result.verdict()).isEqualTo(Verdict.OK);
    }

    @Test
    @DisplayName("动作大小写与空白由 Grant 归一化，避免驱动返回大小写不一致时判错")
    void privilegesAreNormalized() {
        Result result = AuditAppendOnlyAssessment.assess(ACCOUNT, SCHEMA, List.of(
                new Grant(" table ", " select "),
                new Grant("TABLE", "insert")));

        assertThat(result.verdict()).isEqualTo(Verdict.OK);
    }

    @Test
    @DisplayName("多个违规动作按作用域合并成一条 REVOKE，不刷屏")
    void remediationGroupsByScope() {
        Result result = AuditAppendOnlyAssessment.assess(ACCOUNT, SCHEMA, List.of(
                new Grant("SCHEMA", "DELETE"),
                new Grant("SCHEMA", "UPDATE"),
                new Grant("TABLE", "DELETE"),
                new Grant("TABLE", "SELECT"),
                new Grant("TABLE", "INSERT")));

        assertThat(result.remediationSql()).containsExactly(
                "REVOKE DELETE, UPDATE ON `docqa`.* FROM " + ACCOUNT + ";",
                "REVOKE DELETE ON `docqa`.`audit_event` FROM " + ACCOUNT + ";");
    }

    @Test
    @DisplayName("root 的修因不是 REVOKE，而是「换连库账号」：撤 root 会把迁移与运维一起废掉")
    void rootRemediationDoesNotRevokeAdminPrivileges() {
        Result result = AuditAppendOnlyAssessment.assess("'root'@'localhost'", SCHEMA, List.of(
                new Grant("GLOBAL", "UPDATE"),
                new Grant("GLOBAL", "DELETE"),
                new Grant("TABLE", "SELECT"),
                new Grant("TABLE", "INSERT")));

        assertThat(result.verdict()).isEqualTo(Verdict.VIOLATION);
        assertThat(result.remediationSql()).hasSize(1);
        assertThat(result.remediationSql().get(0)).startsWith("--").contains("root").doesNotContain("REVOKE");
    }

    @Test
    @DisplayName("账号缺失时摘要里出现 unknown 而不是抛空指针")
    void blankAccountIsTolerated() {
        Result result = AuditAppendOnlyAssessment.assess(" ", SCHEMA, List.of());

        assertThat(result.account()).isEqualTo("unknown");
        assertThat(result.verdict()).isEqualTo(Verdict.VIOLATION);
    }
}
