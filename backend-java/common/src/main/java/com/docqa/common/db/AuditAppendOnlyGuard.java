package com.docqa.common.db;

import com.docqa.common.db.AuditAppendOnlyAssessment.Grant;
import com.docqa.common.db.AuditAppendOnlyAssessment.Result;
import com.docqa.common.db.AuditAppendOnlyAssessment.Verdict;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 启动自检：证明「本进程连库所用的账号写不动审计历史」。
 *
 * <p>为什么要有它：把权限收敛成 SQL 脚本只解决了「一次性做对」，解决不了「以后被改回去」。
 * 授权是活的——一次救火的 {@code GRANT ALL} 就能把 S11-6-M1 悄悄复发，而症状（审计行被改）
 * 在日志里根本不会出现。把判据搬到进程启动路径上，漂移就从「某天被审计发现」变成
 * 「下一次重启就红」。</p>
 *
 * <p>为什么读 {@code information_schema} 而不是「试着 UPDATE 一下看报不报错」：
 * 行为探测更接近事实，但它要捕获 {@code java.sql.SQLException} 才能区分「权限不足」与
 * 「其它故障」，而 common 模块的架构红线禁止依赖 {@code java.sql}（
 * {@code CommonArchitectureTest#common_must_not_depend_on_infrastructure}）。
 * 行为探测放在运维脚本里做（{@code ops/sql/verify-least-privilege.ps1}），它不受这条约束且
 * 能覆盖 DDL；进程内只做声明式核对，两者互补而非重复。</p>
 *
 * <p>三种模式：{@code off} 关闭；{@code warn} 只记日志；{@code enforce}（默认）判违规即
 * 拒绝启动。默认收紧为 {@code enforce}（LOG-2）：审计只追加是核心宣称，默认不设防等于
 * 宣称落空。尚未收敛的环境须显式设 {@code DOCQA_AUDIT_APPEND_ONLY_GUARD=warn|off}
 * 才能放宽——本地启动脚本已显式写入最小值权限账号，生产须先跑授权脚本。</p>
 */
public class AuditAppendOnlyGuard implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(AuditAppendOnlyGuard.class);

    static final String ACCOUNT_SQL = "SELECT CURRENT_USER()";
    static final String SCHEMA_SQL = "SELECT DATABASE()";

    /**
     * 三路并集：全局授权（对一切库生效）、本库授权、本库审计表的表级授权。
     * 参数按 (grantee, grantee, schema, grantee, schema) 顺序绑定。
     */
    static final String PRIVILEGE_SQL = """
            SELECT 'GLOBAL' AS SCOPE, PRIVILEGE_TYPE AS PRIVILEGE
              FROM information_schema.USER_PRIVILEGES
             WHERE GRANTEE = ?
            UNION ALL
            SELECT 'SCHEMA', PRIVILEGE_TYPE
              FROM information_schema.SCHEMA_PRIVILEGES
             WHERE GRANTEE = ? AND TABLE_SCHEMA = ?
            UNION ALL
            SELECT 'TABLE', PRIVILEGE_TYPE
              FROM information_schema.TABLE_PRIVILEGES
             WHERE GRANTEE = ? AND TABLE_SCHEMA = ? AND TABLE_NAME = 'audit_event'
            """;

    private final JdbcTemplate jdbcTemplate;
    private final Mode mode;

    public AuditAppendOnlyGuard(JdbcTemplate jdbcTemplate, Mode mode) {
        this.jdbcTemplate = jdbcTemplate;
        this.mode = mode;
    }

    public enum Mode {
        OFF,
        WARN,
        ENFORCE;

        /**
         * 解析配置值。**未知值直接抛异常而不是退回 WARN**：把 {@code enforce} 打成
         * {@code enfroce} 却静默降级，等于安全开关坏了还显示绿灯。
         */
        public static Mode parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return ENFORCE;
            }
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "off", "false", "none", "disabled" -> OFF;
                case "warn", "audit", "log" -> WARN;
                case "enforce", "strict", "true", "fail" -> ENFORCE;
                default -> throw new IllegalArgumentException(
                        "Unknown append-only-guard mode: '" + raw + "'; expected off | warn | enforce");
            };
        }
    }

    public Mode mode() {
        return mode;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (mode == Mode.OFF) {
            LOGGER.info("audit append-only guard: skipped mode=off");
            return;
        }

        Result result;
        try {
            result = inspect();
        } catch (DataAccessException ex) {
            // 读不到 ≠ 违规，但 enforce 下必须按违规处理：证明不了合规就等同于不合规。
            String reason = describe(ex);
            if (mode == Mode.ENFORCE) {
                throw new IllegalStateException("audit append-only guard could not verify the account: " + reason, ex);
            }
            LOGGER.warn("audit append-only guard: unverified mode=warn reason={}", reason);
            return;
        }

        if (result.verdict() == Verdict.OK) {
            LOGGER.info("audit append-only guard: {} mode={}", result.summary(), mode.name().toLowerCase(Locale.ROOT));
            return;
        }

        String message = "audit append-only guard: " + result.summary() + " remediation=" + result.remediationSql();
        if (mode == Mode.ENFORCE) {
            throw new IllegalStateException(message);
        }
        LOGGER.warn("{} mode=warn; set DOCQA_AUDIT_APPEND_ONLY_GUARD=enforce to fail fast", message);
    }

    Result inspect() {
        String account = jdbcTemplate.queryForObject(ACCOUNT_SQL, String.class);
        String schema = jdbcTemplate.queryForObject(SCHEMA_SQL, String.class);
        if (account == null || account.isBlank()) {
            return AuditAppendOnlyAssessment.unknown(null, schema, "CURRENT_USER() returned nothing");
        }
        // CURRENT_USER() 给的是 user@host，而 information_schema.GRANTEE 的值是带引号的
        // 'user'@'host'。按**最后一个** @ 切分而不是 replace 全部：MySQL 允许账号名里出现 @
        // （要用引号包起来），全量替换会把这种账号拼成一个查不到任何授权的字面量，
        // 于是守卫会把「账号很怪」误报成「一个字都没授」。
        int at = account.lastIndexOf('@');
        if (at <= 0 || at == account.length() - 1) {
            return AuditAppendOnlyAssessment.unknown(account, schema, "CURRENT_USER() has no host part: " + account);
        }
        String grantee = "'" + account.substring(0, at) + "'@'" + account.substring(at + 1) + "'";
        if (schema == null || schema.isBlank()) {
            return AuditAppendOnlyAssessment.unknown(grantee, schema, "DATABASE() is null");
        }
        // grantee 作为**绑定参数**传，而不是拼进 SQL 文本：既不用处理引号转义，
        // 也不会因为账号里出现特殊字符而拼出一个恰好能执行、但比较不到任何行的字面量。
        List<Map<String, Object>> rows =
                jdbcTemplate.queryForList(PRIVILEGE_SQL, grantee, grantee, schema, grantee, schema);
        List<Grant> grants = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            grants.add(new Grant(text(row, "SCOPE"), text(row, "PRIVILEGE")));
        }
        return AuditAppendOnlyAssessment.assess(grantee, schema, grants);
    }

    /**
     * 列名按大小写不敏感取：{@code information_schema} 的列标签大小写由服务端返
     * 回，不同驱动版本在同一别名上的表现并不稳定；用大小写敏感取值会让守卫在升级
     * 驱动后静默读不到数据，从而把违规判成 OK。
     */
    private static String text(Map<String, Object> row, String key) {
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue() == null ? "" : String.valueOf(entry.getValue());
            }
        }
        return "";
    }

    private static String describe(DataAccessException ex) {
        Throwable root = ex.getMostSpecificCause();
        String detail = root == null || root.getMessage() == null ? "" : root.getMessage().trim();
        if (detail.length() > 200) {
            detail = detail.substring(0, 200) + "…";
        }
        return detail.isEmpty() ? ex.getClass().getSimpleName() : detail;
    }
}
