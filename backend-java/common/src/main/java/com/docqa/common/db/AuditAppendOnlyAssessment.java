package com.docqa.common.db;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 审计表「只追加」的判据：把 {@code information_schema} 里读到的授权翻译成
 * 「这个账号能不能改写历史行」。
 *
 * <p>本类是纯函数，不做任何 IO。这样设计不是为了好看，而是因为这条判据的每一分支都必须
 * 能被单测逐条钉住：授权集合是 DB 状态，DB 状态无法在 CI 里稳定复现，而把「判定」与
 * 「读取」切开之后，判定部分就变成对一组字符串的集合运算，可以在毫秒级跑完所有边界。</p>
 *
 * <p>判定的两个方向都必须管，只查一边会留下假阴性：</p>
 * <ul>
 *   <li><b>多了</b>：出现 {@code UPDATE} / {@code DELETE}，历史行从授权层就能被改写；</li>
 *   <li><b>少了</b>：缺 {@code SELECT} 或 {@code INSERT}，服务会在运行期以 500 的形式
 *       暴露，而不是在启动时就告诉你账号配错了。</li>
 * </ul>
 */
public final class AuditAppendOnlyAssessment {

    /** 被判定为「只能追加」的那张表。 */
    public static final String AUDIT_TABLE = "audit_event";

    /** 任何作用域上出现即判违规的动作。{@code ALL PRIVILEGES} 只在防御性场景出现。 */
    public static final Set<String> FORBIDDEN_PRIVILEGES = Set.of("UPDATE", "DELETE", "ALL PRIVILEGES");

    /** 审计表上必须具备的动作。 */
    public static final Set<String> REQUIRED_PRIVILEGES = Set.of("SELECT", "INSERT");

    private static final Set<String> KNOWN_SCOPES = Set.of("GLOBAL", "SCHEMA", "TABLE");

    private AuditAppendOnlyAssessment() {
    }

    /** 一条授权事实：作用域 + 动作。表级授权的表名固定为 {@link #AUDIT_TABLE}。 */
    public record Grant(String scope, String privilege) {

        public Grant {
            scope = scope == null ? "" : scope.trim().toUpperCase(Locale.ROOT);
            privilege = privilege == null ? "" : privilege.trim().toUpperCase(Locale.ROOT);
        }

        public String describe() {
            return scope.toLowerCase(Locale.ROOT) + ":" + privilege;
        }
    }

    public enum Verdict {
        /** 恰好是「只读 + 追加」，没有多余也没有缺失。 */
        OK,
        /** 多余或缺失至少一边不成立；两者都可能同时出现。 */
        VIOLATION,
        /** 读不到判定所需的事实（例如连接串没带库名），既不能判真也不能判假。 */
        UNKNOWN
    }

    /**
     * 判定结果。{@code offending} 保留作用域信息，因为「撤库级 UPDATE」与「撤表级 UPDATE」
     * 是两条不同的 SQL，丢掉作用域就没法生成可执行的修因语句。
     *
     * <p>{@code reason} 只在 {@link Verdict#UNKNOWN} 时有值，用来解释「为什么判不了」。
     * 它不能复用 {@code missing}：那会把「账号缺 INSERT」和「连接串没带库名」这两种
     * 完全不同的事挤进同一个字段，读日志的人会按前者去修，而真正的问题是后者。</p>
     */
    public record Result(
            Verdict verdict,
            String account,
            String schema,
            List<Grant> offending,
            List<String> missing,
            String reason) {

        public boolean isViolation() {
            return verdict == Verdict.VIOLATION;
        }

        public boolean isUnknown() {
            return verdict == Verdict.UNKNOWN;
        }

        /** 单行摘要，给日志用；不含口令，也不含任何行数据。 */
        public String summary() {
            return "verdict=" + verdict
                    + " account=" + account
                    + " schema=" + schema
                    + " offending=" + offending.stream().map(Grant::describe).toList()
                    + " missing=" + missing
                    + (reason == null ? "" : " reason=" + reason);
        }

        /**
         * 生成可直接粘贴执行的修因语句。同类作用域合并成一条 REVOKE，避免刷屏；
         * 缺失的动作单独补一条 GRANT。返回空列表表示无需修因。
         *
         * <p><b>root 是特例，必须拦下</b>：对 {@code 'root'@'localhost'} 生成
         * {@code REVOKE UPDATE, DELETE ON *.*} 是把「换个账号」错说成「撤管理员权限」——
         * 照做会连迁移与运维一起废掉。这里返回一条 SQL 注释作为提示：粘进客户端是无害的
         * 空语句，但它明确指向上游动作（换连库账号），而不是给出一条会自伤的 DDL。</p>
         */
        public List<String> remediationSql() {
            if (account != null && account.startsWith("'root'@")) {
                return List.of("-- 当前用 root 连库：不要撤 root 的权限，改成让服务连 ss_app / ss_audit"
                        + "（见 ops/sql/README.md）");
            }
            List<String> statements = new ArrayList<>();
            Map<String, Set<String>> byScope = new LinkedHashMap<>();
            for (Grant grant : offending) {
                byScope.computeIfAbsent(grant.scope(), key -> new LinkedHashSet<>()).add(grant.privilege());
            }
            byScope.forEach((scope, privileges) -> statements.add(
                    "REVOKE " + String.join(", ", privileges) + " ON " + target(scope, schema)
                            + " FROM " + account + ";"));
            if (!missing.isEmpty()) {
                statements.add("GRANT " + String.join(", ", missing) + " ON `" + schema + "`.`" + AUDIT_TABLE + "`"
                        + " TO " + account + ";");
            }
            return statements;
        }

        private String target(String scope, String schema) {
            return switch (scope) {
                case "GLOBAL" -> "*.*";
                case "SCHEMA" -> "`" + schema + "`.*";
                default -> "`" + schema + "`.`" + AUDIT_TABLE + "`";
            };
        }
    }

    /** 判定不了时的统一出口：既不冒充 OK，也不冒充违规。 */
    public static Result unknown(String account, String schema, String reason) {
        String safeAccount = (account == null || account.isBlank()) ? "unknown" : account;
        String safeSchema = (schema == null || schema.isBlank()) ? "unknown" : schema;
        return new Result(Verdict.UNKNOWN, safeAccount, safeSchema, List.of(), List.of(), reason);
    }

    /**
     * 判定主入口。
     *
     * @param account 形如 {@code 'ss_audit'@'localhost'} 的账号字面量；它会**原样**进入
     *                {@link Result#remediationSql()}，所以必须是可执行 SQL 的形态而不是
     *                {@code ss_audit@localhost}
     * @param schema  当前连接的库名；为 null 或空表示连接串没带库名，判定降级为 UNKNOWN
     * @param grants  三路查询的并集：全局授权、本库授权、本库 {@code audit_event} 的表级授权
     */
    public static Result assess(String account, String schema, List<Grant> grants) {
        if (schema == null || schema.isBlank()) {
            return unknown(account, schema, "DATABASE() is null");
        }
        String safeAccount = (account == null || account.isBlank()) ? "unknown" : account;
        Set<String> effective = new LinkedHashSet<>();
        Set<Grant> offending = new LinkedHashSet<>();
        if (grants != null) {
            for (Grant grant : grants) {
                if (grant == null || grant.privilege().isEmpty()) {
                    continue;
                }
                // 只认三种作用域：未知作用域既不算有效授权也不算违规，避免把解析异常当成安全结论
                if (!KNOWN_SCOPES.contains(grant.scope())) {
                    continue;
                }
                effective.add(grant.privilege());
                if (FORBIDDEN_PRIVILEGES.contains(grant.privilege())) {
                    offending.add(grant);
                }
            }
        }
        List<String> missing = REQUIRED_PRIVILEGES.stream()
                .filter(privilege -> !effective.contains(privilege))
                .sorted()
                .toList();
        Verdict verdict = offending.isEmpty() && missing.isEmpty() ? Verdict.OK : Verdict.VIOLATION;
        return new Result(verdict, safeAccount, schema, List.copyOf(offending), missing, null);
    }
}
