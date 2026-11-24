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

/** V13 的 DDL 同时存在于迁移脚本与存储设计文档，逐字对账避免两边漂移。 */
class MigrationV13ContractTest {

    private static final String MIGRATION = "db/migration/V13__model_connection.sql";

    @Test
    void migrationMatchesStorageDoc() throws IOException {
        String migration = normalize(readClasspath(MIGRATION));
        String storage = normalize(sqlBlock(readRepoFile("docs/02-design/storage.md"), "### 2.19 model_connection"));

        assertThat(migration)
                .as("V13 迁移必须与存储设计文档 §2.19 的 DDL 逐字一致")
                .isEqualTo(storage);
    }

    /**
     * 每租户至多一条生效连接必须是存储层约束，而不是应用层的「先查再改」。
     *
     * <p>MySQL 没有部分索引，所以靠 STORED 生成列把生效条件投影成 1/NULL 再建唯一键。
     * 少了生成列或少了唯一键，并发切换生效就能造出两条同时生效的连接，
     * 而 Redis 分发键只有一个槽位——届时哪条生效取决于最后一次写入，无从追溯。
     */
    @Test
    void migrationEnforcesSingleActiveConnectionPerTenant() throws IOException {
        String migration = normalize(readClasspath(MIGRATION)).toUpperCase(Locale.ROOT);
        assertThat(migration)
                .containsPattern("ACTIVE_SLOT\\s+TINYINT\\s+GENERATED ALWAYS AS \\(IF\\(ENABLED = 1 AND IS_ACTIVE = 1, 1, NULL\\)\\) STORED");
        assertThat(migration).contains("UNIQUE KEY UK_MODEL_CONNECTION_TENANT_ACTIVE (TENANT_ID, ACTIVE_SLOT)");
    }

    /**
     * 凭据只以密文落库，且密文与 keyId 同生同死。
     *
     * <p>「有密文却没 keyId」的行永远解不开，只会在问答时塌成上游不可用；
     * 反过来任何明文列（api_key / secret / token）都会让密钥进入备份、慢日志与 DBA 视野。
     */
    @Test
    void migrationStoresCredentialAsCiphertextOnly() throws IOException {
        String migration = normalize(readClasspath(MIGRATION)).toUpperCase(Locale.ROOT);
        assertThat(migration).containsPattern("CREDENTIAL\\s+VARBINARY\\(1024\\) NULL");
        assertThat(migration).containsPattern("CREDENTIAL_KEY_ID\\s+CHAR\\(12\\) CHARACTER SET ASCII COLLATE ASCII_BIN NULL");
        assertThat(migration).contains("(CREDENTIAL IS NULL) = (CREDENTIAL_KEY_ID IS NULL)");
        assertThat(migration).doesNotContain("API_KEY");
        assertThat(migration).doesNotContain("SECRET");
    }

    /** 创建按 (tenant_id, name) upsert，管理面因此按值幂等（不声明幂等头）。 */
    @Test
    void migrationMakesConnectionNameUniquePerTenant() throws IOException {
        String migration = normalize(readClasspath(MIGRATION)).toUpperCase(Locale.ROOT);
        assertThat(migration).contains("UNIQUE KEY UK_MODEL_CONNECTION_TENANT_NAME (TENANT_ID, NAME)");
        assertThat(migration).contains("CONSTRAINT FK_MODEL_CONNECTION_TENANT FOREIGN KEY (TENANT_ID)");
        assertThat(migration).contains("REFERENCES TENANT (ID) ON DELETE CASCADE");
    }

    /**
     * 迁移必须保持纯 DDL：迁移账号 ss_migrator 只持有 schema 级 DDL 与
     * flyway_schema_history 的 DML，没有业务表写权限（见 MigrationV10ContractTest 的事故记录）。
     */
    @Test
    void migrationStaysPureDdl() throws IOException {
        String migration = normalize(readClasspath(MIGRATION)).toUpperCase(Locale.ROOT);
        assertThat(Arrays.stream(migration.split("\\R"))
                        .filter(MigrationV13ContractTest::startsWithDmlKeyword)
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
    void migrationOnlyAddsThePlannedTable() throws IOException {
        String migration = normalize(readClasspath(MIGRATION)).toUpperCase(Locale.ROOT);
        assertThat(migration).contains("CREATE TABLE MODEL_CONNECTION");
        assertThat(Arrays.stream(migration.split("\\R"))
                        .filter(MigrationV13ContractTest::isDataDestructive)
                        .toList())
                .as("本切片只新增一张表，不改既有表、不删数据")
                .isEmpty();
    }

    private static boolean isDataDestructive(String statement) {
        return statement.startsWith("DELETE ")
                || statement.contains("DELETE FROM")
                || statement.startsWith("TRUNCATE ")
                || statement.contains("DROP TABLE")
                || statement.startsWith("ALTER TABLE");
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
