package com.docqa.document.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** V10 的 DDL 同时存在于迁移脚本与存储设计文档，逐字对账避免两边漂移。 */
class MigrationV10ContractTest {

    private static final String MIGRATION = "db/migration/V10__tenant_foundation.sql";
    private static final String DEFAULT_TENANT = "11111111-1111-1111-1111-111111111111";

    @Test
    void migrationMatchesStorageDoc() throws IOException {
        String migration = normalize(readClasspath(MIGRATION));
        String storage = normalize(sqlBlock(readRepoFile("docs/02-design/storage.md"), "### 2.18 tenant"));

        assertThat(migration)
                .as("V10 迁移必须与存储设计文档 §2.18 的 DDL 逐字一致")
                .isEqualTo(storage);
    }

    @Test
    void migrationAddsTenantToEveryBusinessTable() throws IOException {
        String migration = readClasspath(MIGRATION);
        for (String table : Arrays.asList(
                "app_user", "app_user_role", "refresh_token", "ai_quota", "document", "chunk",
                "document_tag", "document_tag_link", "conversation", "message", "agent_execution",
                "audit_event", "reminder", "reminder_trigger")) {
            assertThat(migration)
                    .as("%s 必须加 tenant_id", table)
                    .containsPattern("ALTER TABLE " + table + "\\s+ADD COLUMN tenant_id");
        }
    }

    @Test
    void migrationCreatesTenantTableAndTenantizesUniqueKeys() throws IOException {
        String migration = normalize(readClasspath(MIGRATION)).toUpperCase(Locale.ROOT);
        assertThat(migration).contains("CREATE TABLE TENANT");
        assertThat(migration).contains(DEFAULT_TENANT.toUpperCase(Locale.ROOT));
        // 原来全局唯一的 username 必须改为租户内唯一
        assertThat(migration).contains("UK_APP_USER_TENANT_USERNAME (TENANT_ID, USERNAME)");
        assertThat(migration).doesNotContain("UNIQUE KEY UK_APP_USER_USERNAME (USERNAME)");
        // 用户隔离最直接的冲突点：文档 md5 也必须租户化
        assertThat(migration).contains("UK_DOCUMENT_TENANT_USER_MD5 (TENANT_ID, USER_ID, MD5)");
    }

    /**
     * 迁移必须保持纯 DDL。
     *
     * <p>这不是洁癖：迁移账号 {@code ss_migrator} 只持有 schema 级 DDL 与
     * {@code flyway_schema_history} 的 DML，没有任何业务表的写权限（ops/sql/README.md）。
     * V10 初版曾在文件中间插了一行 {@code INSERT INTO tenant} 来种默认租户，结果在
     * MySQL 8.4 上直接报 {@code INSERT command denied}，且因为 DDL 不参与事务，
     * 迁移停在「tenant 表已建、其余一条没跑」的半迁移状态。这条断言把那次事故钉死。
     *
     * <p>需要种子数据时放到启动期的 Runner 里（参考 DefaultTenantInitializer），
     * 用 app 账号写。
     */
    @Test
    void migrationStaysPureDdl() throws IOException {
        String migration = normalize(readClasspath(MIGRATION)).toUpperCase(Locale.ROOT);
        assertThat(Arrays.stream(migration.split("\\R"))
                        .filter(MigrationV10ContractTest::startsWithDmlKeyword)
                        .toList())
                .as("迁移不得含 INSERT/UPDATE/DELETE/REPLACE：迁移账号没有业务表写权限")
                .isEmpty();
    }

    /** 只看语句首词，避免 ON UPDATE CURRENT_TIMESTAMP、ON DELETE CASCADE 这类误判。 */
    private static boolean startsWithDmlKeyword(String statement) {
        return statement.startsWith("INSERT ")
                || statement.startsWith("UPDATE ")
                || statement.startsWith("DELETE ")
                || statement.startsWith("REPLACE ");
    }

    @Test
    void migrationNeverDeletesDataOnlyConstraints() throws IOException {
        String migration = normalize(readClasspath(MIGRATION)).toUpperCase(Locale.ROOT);
        assertThat(Arrays.stream(migration.split("\\R"))
                        .filter(MigrationV10ContractTest::isDataDestructive)
                        .toList())
                .as("迁移可以删除旧约束，但不得删数据；所有旧约束都在本文件内以租户化版本重建")
                .isEmpty();
    }

    /** DROP INDEX / DROP FOREIGN KEY / DROP PRIMARY KEY 只删约束，不是删数据。 */
    private static boolean isDataDestructive(String statement) {
        return statement.startsWith("DELETE ")
                || statement.contains("DELETE FROM")
                || statement.startsWith("TRUNCATE ")
                || statement.contains("DROP TABLE");
    }

    private static String readClasspath(String name) throws IOException {
        return new ClassPathResource(name).getContentAsString(StandardCharsets.UTF_8);
    }

    private static String sqlBlock(String markdown, String headingPrefix) {
        int heading = markdown.indexOf(headingPrefix);
        if (heading < 0) {
            throw new IllegalStateException("文档缺少小节：" + headingPrefix);
        }
        int fence = markdown.indexOf("```sql", heading);
        if (fence < 0) {
            throw new IllegalStateException("小节内缺少 sql 代码块：" + headingPrefix);
        }
        int start = markdown.indexOf('\n', fence) + 1;
        int end = markdown.indexOf("```", start);
        if (end < 0) {
            throw new IllegalStateException("sql 代码块未闭合：" + headingPrefix);
        }
        return markdown.substring(start, end);
    }

    private static String normalize(String sql) {
        return Arrays.stream(sql.split("\\R"))
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .filter(line -> !line.startsWith("--"))
                .collect(Collectors.joining("\n"));
    }

    private static String readRepoFile(String relativePath) throws IOException {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            Path candidate = directory.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException(relativePath + " not found from " + Path.of("").toAbsolutePath());
    }
}
