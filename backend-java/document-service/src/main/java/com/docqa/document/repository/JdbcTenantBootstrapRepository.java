package com.docqa.document.repository;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@link TenantBootstrapRepository} 的 JDBC 实现。
 *
 * <p>用 {@code INSERT IGNORE} 而不是「先查再插」：多个 document-service 实例同时启动时，
 * 先查再插会撞唯一键报错；INSERT IGNORE 让后来者静默跳过，天然幂等。
 */
@Repository
public class JdbcTenantBootstrapRepository implements TenantBootstrapRepository {

    private static final String INSERT_DEFAULT_TENANT = """
            INSERT IGNORE INTO tenant (id, slug, name, tier, isolation, status, owner_user_id)
            VALUES (?, ?, ?, ?, ?, 'ACTIVE', NULL)
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcTenantBootstrapRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public int insertIfAbsent(UUID tenantId, String slug, String name, String tier, String isolation) {
        return jdbcTemplate.update(
                INSERT_DEFAULT_TENANT, tenantId.toString(), slug, name, tier, isolation);
    }
}
