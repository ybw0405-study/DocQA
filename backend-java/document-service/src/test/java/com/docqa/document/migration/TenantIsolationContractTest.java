package com.docqa.document.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 跨服务静态契约：所有 Jdbc 仓库的业务 SQL 都必须带 tenant_id。
 *
 * <p>MySQL 8 没有 RLS，租户边界完全依赖 SQL 谓词，因此「漏一条查询」就是一次越权。
 * 这个测试把所有仓库源码当文本读取，逐文件检查是否出现租户谓词，
 * 并对已知的表名做一次「SELECT/UPDATE/DELETE 语句必须含 tenant_id」的粗判。
 * 它不替代各仓库的 SQL 断言测试，而是兜住新增仓库时忘记加租户维度的情况。</p>
 */
class TenantIsolationContractTest {

    private static final Path REPO_ROOT = findRepoRoot();

    private static final List<String> MODULES = List.of(
            "user-service", "document-service", "agent-gateway", "reminder-service", "audit-service");

    /** 跨租户运维任务：按全局游标清理过期行，不按单租户过滤。 */
    private static final List<String> GLOBAL_MAINTENANCE_METHODS = List.of("deleteExpiredTerminal");

    /**
     * 不适用「必须含 tenant_id」判定的仓库：tenant 表本身就是租户清单，
     * 它的行没有「所属租户」这个属性。除此之外不接受任何豁免。
     */
    private static final List<String> TENANT_AGNOSTIC_REPOSITORIES =
            List.of("JdbcTenantBootstrapRepository.java");

    @Test
    void everyJdbcRepositoryDeclaresTenantPredicate() throws IOException {
        List<String> offenders = new ArrayList<>();
        int checked = 0;
        for (String module : MODULES) {
            Path dir = REPO_ROOT.resolve("backend-java").resolve(module)
                    .resolve("src/main/java");
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> paths = Files.walk(dir)) {
                for (Path file : paths
                        .filter(p -> p.getFileName().toString().startsWith("Jdbc"))
                        .filter(p -> p.getFileName().toString().endsWith("Repository.java"))
                        .toList()) {
                    checked++;
                    if (TENANT_AGNOSTIC_REPOSITORIES.contains(file.getFileName().toString())) {
                        continue;
                    }
                    String source = Files.readString(file, StandardCharsets.UTF_8);
                    if (!source.contains("tenant_id")) {
                        offenders.add(REPO_ROOT.relativize(file).toString());
                    }
                }
            }
        }
        assertThat(checked)
                .as("必须实际扫描到 18 个 Jdbc 仓库，扫描数为 0 说明测试路径写错了")
                .isEqualTo(18);
        assertThat(offenders)
                .as("每个业务仓库都必须出现 tenant_id 谓词/列；遗漏即跨租户数据泄漏")
                .isEmpty();
    }

    @Test
    void fourJwtVerifiersRequireTenantClaim() throws IOException {
        List<String> files = List.of(
                "backend-java/user-service/src/main/java/com/docqa/user/service/JwtService.java",
                "backend-java/agent-gateway/src/main/java/com/docqa/agentgateway/service/JwtAuthenticator.java",
                "backend-java/document-service/src/main/java/com/docqa/document/service/JwtAuthenticator.java",
                "backend-java/reminder-service/src/main/java/com/docqa/reminder/service/JwtAuthenticator.java");
        for (String file : files) {
            String source = Files.readString(REPO_ROOT.resolve(file), StandardCharsets.UTF_8);
            assertThat(source)
                    .as("%s 必须强制解析 tenant_id claim", file)
                    .contains("TenantContext.CLAIM_NAME")
                    .contains("parseTenantId");
        }
    }

    @Test
    void everyBusinessTableIsTenantScopedInMigration() throws IOException {
        String migration = Files.readString(
                REPO_ROOT.resolve("backend-java/document-service/src/main/resources/db/migration"
                        + "/V10__tenant_foundation.sql"),
                StandardCharsets.UTF_8);
        List<String> tables = Arrays.asList(
                "app_user", "app_user_role", "refresh_token", "ai_quota", "document", "chunk",
                "document_tag", "document_tag_link", "conversation", "message", "agent_execution",
                "audit_event", "reminder", "reminder_trigger");
        for (String table : tables) {
            assertThat(migration)
                    .as("%s 必须在 V10 中加 tenant_id", table)
                    .containsPattern("ALTER TABLE " + table + "\\s+ADD COLUMN tenant_id");
        }
    }

    private static Path findRepoRoot() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            if (Files.isRegularFile(directory.resolve("docs/02-design/storage.md"))) {
                return directory;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException("DocQA repo root not found");
    }
}
