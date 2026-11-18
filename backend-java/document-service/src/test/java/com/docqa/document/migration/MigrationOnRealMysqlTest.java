package com.docqa.document.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.docqa.common.tenant.TenantContext;
import com.docqa.document.repository.JdbcTenantBootstrapRepository;
import com.docqa.document.service.DefaultTenantInitializer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;

/**
 * 真库迁移端到端：起一个 MySQL 容器，用与生产一致的最小权限账号把 V1→V10 跑一遍。
 *
 * <p><b>为什么需要它。</b>V10 初版有五道契约测试全绿、1218 个单测全过，却在真库上连续失败两次：
 * 一次是迁移账号没有业务表 DML 权限（{@code INSERT command denied}），一次是同名外键重建
 * 被 MySQL 拒绝（{@code ERROR 1826}）。契约测试比对的是**文本**，比对不了 SQL 语义与权限模型。
 * 这个类补的就是那一层：断言的是「MySQL 真的接受了这份迁移，且真的挡住了跨租户写入」。
 *
 * <p><b>为什么用低权限账号跑迁移。</b>{@code ops/sql/README.md} 的约定是 {@code ss_migrator}
 * 只持有 schema 级 DDL 加 {@code flyway_schema_history} 的 DML，业务表写权限为零。这里把这份
 * 授权逐字复刻进容器（见 {@link #createMigratorAccount()}），于是任何把 DML 塞回迁移的改动都会
 * 让本类以真实的 1142 错误变红，而不是靠一条正则去猜。
 *
 * <p><b>为什么先 baseline 再 migrate。</b>生产上 flyway 账本表由 DBA 预置，测试里没有这一步；
 * 而 MySQL 不允许对不存在的表做表级 GRANT（{@code ERROR 1146}），所以先由 root 用
 * {@code baselineVersion("0")} 让 Flyway 把账本表建出来（写入一条 version=0 的 BASELINE 记录，
 * 不占用 V1），再给 migrator 授账本表的 DML，最后才以 migrator 身份跑 V1→V10。
 *
 * <p><b>门控。</b>没有可用的 Docker 时整类跳过（{@link Assumptions#assumeTrue}），不会把无容器
 * 环境染成红色；需要强制关闭时设 {@code DOCQA_DB_IT=off}。镜像固定 {@code mysql:8.4}，与生产
 * 同版本——8.0 与 8.4 在 instant DDL 与 CHECK 约束上行为一致，但版本漂移没有理由。
 */
class MigrationOnRealMysqlTest {

    static {
        // Docker Desktop 的 Windows 管道代理对老版本 API 路径（如 /v1.32/info）直接回
        // 400 Bad Request，而 testcontainers 内置的 docker-java 默认从 API 1.32 起步，
        // 于是策略探测永远失败、整个类被静默跳过。这里把 API 版本抬高到 1.44（需要
        // Docker Engine 25.0+，2023 年起的版本都满足）。已显式指定（API_VERSION 环境
        // 变量或 api.version 系统属性）时尊重原值，不覆盖。
        if (System.getProperty("api.version") == null && System.getenv("API_VERSION") == null) {
            System.setProperty("api.version", "1.44");
        }
    }

    private static final String MYSQL_IMAGE = "mysql:8.4";
    private static final String DATABASE = "docqa";
    private static final int MYSQL_PORT = 3306;

    private static final String OWNER_USER = "container_owner";
    private static final String OWNER_PASSWORD = "owner-password";
    private static final String ROOT_USER = "root";
    // 注意：MySQLContainer.configure() 会把 MYSQL_ROOT_PASSWORD 覆盖成 withPassword 的值，
    // 所以 root 的密码就是 OWNER_PASSWORD，单独再设 MYSQL_ROOT_PASSWORD 不会生效。
    private static final String ROOT_PASSWORD = OWNER_PASSWORD;
    private static final String MIGRATOR_USER = "ss_migrator";
    private static final String MIGRATOR_PASSWORD = "migrator-password";
    private static final String APP_USER = "ss_app";
    private static final String APP_PASSWORD = "app-password";

    private static final String DEFAULT_TENANT = TenantContext.DEFAULT_TENANT_ID.toString();
    private static final String SECOND_TENANT = "22222222-2222-2222-2222-222222222222";

    private static final String SKIP_REASON =
            "未检测到可用 Docker，跳过真库迁移测试（需要 Docker 与 " + MYSQL_IMAGE
                    + " 镜像；显式关闭请设 DOCQA_DB_IT=off）";

    /** V10 必须覆盖的业务表全集。少一张就是一条跨租户泄漏路径。 */
    private static final List<String> TENANT_TABLES = List.of(
            "agent_execution",
            "ai_quota",
            "app_user",
            "app_user_role",
            "audit_event",
            "chunk",
            "conversation",
            "document",
            "document_tag",
            "document_tag_link",
            "message",
            "refresh_token",
            "reminder",
            "reminder_trigger");

    /** V1–V9 共有 7 个单列外键，V10 应把它们全部升级为带租户维度的复合外键。 */
    private static final List<String> COMPOSITE_FOREIGN_KEYS = List.of(
            "fk_ai_quota_tenant_user",
            "fk_app_user_role_tenant_user",
            "fk_chunk_tenant_document",
            "fk_document_tag_link_tenant_document",
            "fk_document_tag_link_tenant_tag",
            "fk_message_tenant_conversation",
            "fk_refresh_token_tenant_user");

    private static final List<String> LEGACY_FOREIGN_KEYS = List.of(
            "fk_ai_quota_user",
            "fk_app_user_role_user",
            "fk_chunk_document",
            "fk_document_tag_link_document",
            "fk_document_tag_link_tag",
            "fk_message_conversation",
            "fk_refresh_token_user");

    private static MySQLContainer<?> mysql;

    /** 迁移期间实际执行了几条脚本，在 {@code @BeforeAll} 里捕获，避免断言依赖测试执行顺序。 */
    private static int appliedMigrations;
    /** 迁移刚结束时 tenant 表的行数：必须为 0，证明 V10 没有种数据。 */
    private static long tenantRowsRightAfterMigration;

    @BeforeAll
    static void migrateOnRealMysql() throws SQLException {
        Assumptions.assumeTrue(integrationEnabled(), SKIP_REASON);

        mysql = new MySQLContainer<>(MYSQL_IMAGE)
                .withDatabaseName(DATABASE)
                .withUsername(OWNER_USER)
                .withPassword(OWNER_PASSWORD)
                // 1.20.4 不再替我们设 MYSQL_ROOT_HOST，缺了它 root 只授权给容器内的
                // localhost，宿主机 JDBC 会报 Access denied for 'root'@'172.17.0.1'。
                .withEnv("MYSQL_ROOT_HOST", "%")
                .withEnv("MYSQL_ROOT_PASSWORD", ROOT_PASSWORD);
        mysql.start();

        createMigratorAccount();
        baselineHistoryTableAsRoot();
        grantHistoryTableDmlToMigrator();

        MigrateResult result = Flyway.configure()
                .dataSource(jdbcUrl(), MIGRATOR_USER, MIGRATOR_PASSWORD)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        appliedMigrations = result.migrationsExecuted;
        tenantRowsRightAfterMigration = countRowsAsRoot("tenant");

        createAppAccount();
    }

    @AfterAll
    static void stopMysql() {
        if (mysql != null) {
            mysql.stop();
        }
    }

    @Test
    @DisplayName("V1→V10 在真库上全量执行，账本无失败记录")
    void appliesAllMigrationsOnRealMysql() throws SQLException {
        assertThat(appliedMigrations)
                .as("V1–V10 共 10 份脚本，执行数不等于 10 说明 Flyway 跳过了或重复执行了某些脚本")
                .isEqualTo(10);
        assertThat(queryAsRoot("SELECT version FROM flyway_schema_history WHERE success = 0"))
                .as("账本里出现 success=0 就是半迁移态，必须人工介入，绝不能被测试放过")
                .isEmpty();
        assertThat(queryAsRoot("SELECT version FROM flyway_schema_history "
                + "WHERE type = 'SQL' ORDER BY installed_rank"))
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10");
    }

    @Test
    @DisplayName("14 张业务表都加了 tenant_id：NOT NULL 且默认值指向默认租户")
    void everyBusinessTableCarriesTenantId() throws SQLException {
        assertThat(queryAsRoot("SELECT table_name FROM information_schema.columns "
                + "WHERE table_schema = '" + DATABASE + "' AND column_name = 'tenant_id' "
                + "ORDER BY table_name"))
                .containsExactlyInAnyOrderElementsOf(TENANT_TABLES);

        for (String table : TENANT_TABLES) {
            String where = "WHERE table_schema = '" + DATABASE + "' AND table_name = '" + table
                    + "' AND column_name = 'tenant_id'";
            assertThat(single(queryAsRoot("SELECT column_default FROM information_schema.columns " + where)))
                    .as("%s.tenant_id 的默认值必须是 %s：存量行靠它原地归属默认租户，"
                            + "没有默认值就得逐行 UPDATE，那是另一种迁移风险", table, DEFAULT_TENANT)
                    .isEqualTo(DEFAULT_TENANT);
            assertThat(single(queryAsRoot("SELECT is_nullable FROM information_schema.columns " + where)))
                    .as("%s.tenant_id 允许 NULL 就等于允许一条无归属的行", table)
                    .isEqualTo("NO");
        }
    }

    @Test
    @DisplayName("迁移没种任何数据：V10 是纯 DDL，migrator 也真的写不了业务表")
    void migrationStaysPureDdlOnRealMysql() throws SQLException {
        assertThat(tenantRowsRightAfterMigration)
                .as("默认租户行必须由 DefaultTenantInitializer 在启动期写入，不能回到迁移里")
                .isZero();

        assertThatThrownBy(() -> executeAs(MIGRATOR_USER, MIGRATOR_PASSWORD,
                "INSERT INTO app_user (id, username, password_hash) VALUES ("
                        + "'00000000-0000-0000-0000-0000000000ff', 'probe', '" + bcryptLike("probe") + "')"))
                .as("迁移账号一旦能写业务表，纯 DDL 的约定就失去约束力——这条断言就是那个约束")
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("denied");
    }

    @Test
    @DisplayName("唯一键与复合外键按租户重建，旧的全局唯一与单列外键全部消失")
    void rebuildsKeysAndForeignKeysPerTenant() throws SQLException {
        List<String> indexes = queryAsRoot(
                "SELECT DISTINCT index_name FROM information_schema.statistics "
                        + "WHERE table_schema = '" + DATABASE + "'");
        assertThat(indexes).contains(
                "uk_app_user_tenant_username",
                "uk_app_user_tenant_id",
                "uk_document_tenant_user_md5",
                "uk_document_tenant_id",
                "uk_document_tag_tenant_user_normalized",
                "uk_conversation_tenant_id",
                "uk_message_tenant_client_request",
                "uk_reminder_tenant_user_idempotency",
                "uk_reminder_tenant_user_source",
                "uk_reminder_trigger_tenant_planned");
        assertThat(indexes).doesNotContain(
                "uk_app_user_username",
                "uk_document_user_md5",
                "uk_document_tag_user_normalized",
                "uk_message_client_request",
                "uk_reminder_user_idempotency",
                "uk_reminder_user_source",
                "uk_reminder_trigger_planned");

        List<String> foreignKeys = queryAsRoot(
                "SELECT constraint_name FROM information_schema.table_constraints "
                        + "WHERE table_schema = '" + DATABASE + "' AND constraint_type = 'FOREIGN KEY'");
        assertThat(foreignKeys)
                .as("V1–V9 的 7 个单列外键必须全部升级；漏一个就意味着一张表的 tenant_id 可以被写错")
                .containsExactlyInAnyOrderElementsOf(COMPOSITE_FOREIGN_KEYS);
        assertThat(foreignKeys).doesNotContainAnyElementsOf(LEGACY_FOREIGN_KEYS);
    }

    @Test
    @DisplayName("默认租户行由启动期初始化器幂等写入，重复执行不报错也不重复")
    void seedsDefaultTenantIdempotently() throws SQLException {
        DefaultTenantInitializer initializer = new DefaultTenantInitializer(
                new JdbcTenantBootstrapRepository(new JdbcTemplate(
                        new DriverManagerDataSource(jdbcUrl(), APP_USER, APP_PASSWORD))));

        initializer.run(new DefaultApplicationArguments());
        // 用 id 过滤而不是全局 COUNT：别的用例（跨租户外键）会往 tenant 表里造第二个租户，
        // 让断言依赖测试执行顺序是自找的脆弱。
        assertThat(single(queryAsRoot("SELECT COUNT(*) FROM tenant WHERE id = '" + DEFAULT_TENANT + "'")))
                .isEqualTo("1");
        assertThat(queryAsRoot("SELECT CONCAT(slug, '|', name, '|', tier, '|', isolation, '|', status) "
                + "FROM tenant WHERE id = '" + DEFAULT_TENANT + "'"))
                .containsExactly("default|Default Workspace|ENTERPRISE|SHARED|ACTIVE");

        // 多实例同时启动时就是这个形状：第二个实例必须静默跳过，而不是抛唯一键冲突。
        initializer.run(new DefaultApplicationArguments());
        assertThat(single(queryAsRoot("SELECT COUNT(*) FROM tenant WHERE id = '" + DEFAULT_TENANT + "'")))
                .isEqualTo("1");
    }

    @Test
    @DisplayName("复合外键拒绝跨租户引用：租户 B 挂不到租户 A 的文档与用户名下")
    void rejectsCrossTenantReferences() throws SQLException {
        String userInDefaultTenant = "aaaaaaaa-0000-0000-0000-000000000001";
        String userInSecondTenant = "aaaaaaaa-0000-0000-0000-000000000002";
        String documentInDefaultTenant = "bbbbbbbb-0000-0000-0000-000000000001";

        insertTenant(SECOND_TENANT, "acme");
        insertUser(userInDefaultTenant, DEFAULT_TENANT, "alice");
        insertUser(userInSecondTenant, SECOND_TENANT, "alice");
        insertDocument(documentInDefaultTenant, DEFAULT_TENANT, userInDefaultTenant);

        assertThatThrownBy(() -> executeAs(APP_USER, APP_PASSWORD,
                "INSERT INTO chunk (id, tenant_id, document_id, chunk_index, vector_id, "
                        + "content_hash, token_count, index_version) VALUES ("
                        + "'cccccccc-0000-0000-0000-000000000001', '" + SECOND_TENANT + "', '"
                        + documentInDefaultTenant + "', 0, 'dddddddd-0000-0000-0000-000000000001', '"
                        + "0".repeat(64) + "', 10, 'v1')"))
                .as("租户 B 的 chunk 指向租户 A 的 document：复合外键必须拦住")
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("fk_chunk_tenant_document");

        assertThatThrownBy(() -> executeAs(APP_USER, APP_PASSWORD,
                "INSERT INTO app_user_role (tenant_id, user_id, role) VALUES ('"
                        + SECOND_TENANT + "', '" + userInDefaultTenant + "', 'USER')"))
                .as("租户 B 的角色行挂在租户 A 的用户上：这就是提权的入口")
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("fk_app_user_role_tenant_user");

        // 同租户内必须照常通过，否则说明约束写宽了，正常写入也被挡住。
        executeAs(APP_USER, APP_PASSWORD,
                "INSERT INTO chunk (id, tenant_id, document_id, chunk_index, vector_id, "
                        + "content_hash, token_count, index_version) VALUES ("
                        + "'cccccccc-0000-0000-0000-000000000002', '" + DEFAULT_TENANT + "', '"
                        + documentInDefaultTenant + "', 1, 'dddddddd-0000-0000-0000-000000000002', '"
                        + "1".repeat(64) + "', 10, 'v1')",
                "INSERT INTO app_user_role (tenant_id, user_id, role) VALUES ('"
                        + DEFAULT_TENANT + "', '" + userInDefaultTenant + "', 'USER')");
        assertThat(countRowsAsRoot("chunk")).isEqualTo(1);
        assertThat(countRowsAsRoot("app_user_role")).isEqualTo(1);

        assertThatThrownBy(() -> insertUser(
                "aaaaaaaa-0000-0000-0000-000000000003", DEFAULT_TENANT, "alice"))
                .as("同一租户内 username 仍必须唯一，租户化不等于放弃唯一性")
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("uk_app_user_tenant_username");
    }

    // ---------------------------------------------------------------- 账号与权限

    /**
     * 逐字复刻 {@code ops/sql/least-privilege.sql.tmpl} 里 ss_migrator 的授权：
     * schema 级 DDL 加账本表 DML，业务表零写权限。
     */
    private static void createMigratorAccount() throws SQLException {
        executeAsRoot(
                "CREATE USER '" + MIGRATOR_USER + "'@'%' IDENTIFIED BY '" + MIGRATOR_PASSWORD + "'",
                "GRANT CREATE, ALTER, DROP, INDEX, REFERENCES, CREATE VIEW, CREATE ROUTINE, "
                        + "ALTER ROUTINE, EXECUTE, TRIGGER ON `" + DATABASE + "`.* TO '"
                        + MIGRATOR_USER + "'@'%'");
    }

    /**
     * 让 Flyway 自己把账本表建出来。用 baselineVersion("0")：写入一条 version=0 的 BASELINE 记录，
     * V1 起的所有脚本仍然要执行，不会因为 baseline 而跳过 V1。
     */
    private static void baselineHistoryTableAsRoot() {
        Flyway.configure()
                .dataSource(jdbcUrl(), ROOT_USER, ROOT_PASSWORD)
                .baselineVersion("0")
                .load()
                .baseline();
    }

    private static void grantHistoryTableDmlToMigrator() throws SQLException {
        executeAsRoot("GRANT SELECT, INSERT, UPDATE, DELETE ON `" + DATABASE
                + "`.`flyway_schema_history` TO '" + MIGRATOR_USER + "'@'%'");
    }

    /**
     * ss_app 的授权同样复刻模板：业务表四类 DML，但 {@code tenant} 故意不给 DELETE
     * ——注销租户走 {@code status='DELETED'} 软删，硬删会打断所有存量行的归属关系。
     */
    private static void createAppAccount() throws SQLException {
        List<String> statements = new ArrayList<>();
        statements.add("CREATE USER '" + APP_USER + "'@'%' IDENTIFIED BY '" + APP_PASSWORD + "'");
        for (String table : List.of("app_user", "app_user_role", "document", "chunk")) {
            statements.add("GRANT SELECT, INSERT, UPDATE, DELETE ON `" + DATABASE + "`.`" + table
                    + "` TO '" + APP_USER + "'@'%'");
        }
        statements.add("GRANT SELECT, INSERT, UPDATE ON `" + DATABASE + "`.`tenant` TO '"
                + APP_USER + "'@'%'");
        executeAsRoot(statements.toArray(new String[0]));
    }

    // ---------------------------------------------------------------- 数据构造

    private static void insertTenant(String tenantId, String slug) throws SQLException {
        executeAs(APP_USER, APP_PASSWORD,
                "INSERT INTO tenant (id, slug, name, tier, isolation, status) VALUES ('"
                        + tenantId + "', '" + slug + "', '" + slug + "', 'PRO', 'SHARED', 'ACTIVE')");
    }

    private static void insertUser(String userId, String tenantId, String username) throws SQLException {
        executeAs(APP_USER, APP_PASSWORD,
                "INSERT INTO app_user (id, tenant_id, username, password_hash, status) VALUES ('"
                        + userId + "', '" + tenantId + "', '" + username + "', '"
                        + bcryptLike(username) + "', 'ACTIVE')");
    }

    private static void insertDocument(String documentId, String tenantId, String userId) throws SQLException {
        executeAs(APP_USER, APP_PASSWORD,
                "INSERT INTO document (id, tenant_id, user_id, file_name, content_type, size_bytes, "
                        + "md5, object_key) VALUES ('" + documentId + "', '" + tenantId + "', '"
                        + userId + "', 'a.pdf', 'application/pdf', 100, '"
                        + "0".repeat(32) + "', 'objects/" + documentId + "')");
    }

    /** app_user.password_hash 上有 CHECK(CHAR_LENGTH = 60)，这里造一个同长度的占位串。 */
    private static String bcryptLike(String seed) {
        String prefix = "$2a$10$" + Math.abs(seed.hashCode());
        return prefix + "x".repeat(Math.max(0, 60 - prefix.length()));
    }

    // ---------------------------------------------------------------- JDBC 小工具

    private static boolean integrationEnabled() {
        String mode = System.getenv("DOCQA_DB_IT");
        if ("off".equalsIgnoreCase(mode) || "false".equalsIgnoreCase(mode)) {
            return false;
        }
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static String jdbcUrl() {
        return "jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(MYSQL_PORT)
                + "/" + DATABASE
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
    }

    private static Connection connect(String user, String password) throws SQLException {
        return DriverManager.getConnection(jdbcUrl(), user, password);
    }

    private static void executeAsRoot(String... statements) throws SQLException {
        executeAs(ROOT_USER, ROOT_PASSWORD, statements);
    }

    private static void executeAs(String user, String password, String... statements) throws SQLException {
        try (Connection connection = connect(user, password);
                Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    private static List<String> queryAsRoot(String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Connection connection = connect(ROOT_USER, ROOT_PASSWORD);
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            while (resultSet.next()) {
                values.add(resultSet.getString(1));
            }
        }
        return values;
    }

    private static long countRowsAsRoot(String table) throws SQLException {
        return Long.parseLong(single(queryAsRoot("SELECT COUNT(*) FROM `" + table + "`")));
    }

    private static String single(List<String> values) {
        assertThat(values).as("期望恰好一行一列").hasSize(1);
        return values.get(0);
    }
}
