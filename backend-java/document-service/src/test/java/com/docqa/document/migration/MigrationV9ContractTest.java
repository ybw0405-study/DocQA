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

/**
 * V9 的 DDL 同时存在于迁移脚本、设计文档与存储文档三处。任何一处单独改动都会让它们互相矛盾，
 * 所以这里把脚本与两份文档的 SQL 规范化后逐字比较，而不是靠人工记忆同步。
 */
class MigrationV9ContractTest {

    private static final String MIGRATION = "db/migration/V9__ai_quota_and_admin_indexes.sql";

    @Test
    void migrationMatchesDesignAndStorageDocs() throws IOException {
        String migration = normalize(readClasspath(MIGRATION));
        String design =
                normalize(sqlBlock(readRepoFile("docs/06-expansion/s9-governance-ops.md"), "### 5.2 MySQL DDL"));
        String storage = normalize(sqlBlock(readRepoFile("docs/02-design/storage.md"), "### 2.17 ai_quota"));

        assertThat(migration)
                .as("V9 迁移必须与设计文档 §5.2 的 DDL 逐字一致")
                .isEqualTo(design);
        assertThat(design)
                .as("设计文档 §5.2 必须原样包含 storage.md §2.17 的建表语句")
                .startsWith(storage);
    }

    @Test
    void migrationOnlyAddsTheThreePlannedObjects() throws IOException {
        String migration = normalize(readClasspath(MIGRATION)).toUpperCase(Locale.ROOT);
        assertThat(migration).contains("CREATE TABLE AI_QUOTA");
        assertThat(migration).contains("ADD KEY IDX_AGENT_EXECUTION_STARTED (STARTED_AT)");
        assertThat(migration).contains("ADD KEY IDX_AUDIT_OCCURRED (OCCURRED_AT, ID)");
        assertThat(Arrays.stream(migration.split("\\R"))
                        .filter(MigrationV9ContractTest::isDestructiveStatement)
                        .toList())
                .as("迁移只做新增；删列/删表/清空数据必须是另一个需要裁决的切片")
                .isEmpty();
    }

    /** 外键的 ON DELETE CASCADE 是引用动作而非删数据，只有语句级 DROP / TRUNCATE / DELETE 才算破坏。 */
    private static boolean isDestructiveStatement(String statement) {
        return statement.startsWith("DELETE ")
                || statement.contains("DELETE FROM")
                || statement.contains("DROP ")
                || statement.contains("TRUNCATE ");
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
