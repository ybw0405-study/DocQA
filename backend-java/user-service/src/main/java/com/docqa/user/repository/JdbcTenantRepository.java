package com.docqa.user.repository;

import com.docqa.user.domain.TenantRecord;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 租户控制面查询的 JDBC 实现。
 *
 * <p>刻意不带 {@code tenant_id} 谓词：tenant 表就是租户清单本身，按 slug 查它是在
 * 「确定当前租户是谁」，而不是「在某个租户内查业务数据」。TenantIsolationContractTest
 * 对本文件有显式豁免，理由见那里；其余业务仓库一律必须带 tenant_id。
 *
 * <p>status 不在 SQL 里过滤，由 {@link com.docqa.user.service.TenantResolver} 统一裁决，
 * 这样「哪些状态允许登录」是单一事实来源，便于测试与后续订阅状态联动。
 */
@Repository
public class JdbcTenantRepository implements TenantRepository {

    private static final String FIND_BY_SLUG = "SELECT id, slug, status FROM tenant WHERE slug = ?";

    private static final String INSERT_TENANT = """
            INSERT IGNORE INTO tenant (id, slug, name, tier, isolation, status, owner_user_id)
            VALUES (?, ?, ?, ?, ?, 'ACTIVE', NULL)
            """;

    private static final RowMapper<TenantRecord> ROW_MAPPER = (rs, rowNum) -> new TenantRecord(
            UUID.fromString(rs.getString("id")),
            rs.getString("slug"),
            rs.getString("status"));

    private final JdbcTemplate jdbcTemplate;

    public JdbcTenantRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<TenantRecord> findBySlug(String slug) {
        return jdbcTemplate.query(FIND_BY_SLUG, ROW_MAPPER, slug).stream().findFirst();
    }

    @Override
    public int insert(UUID id, String slug, String name, String tier, String isolation) {
        return jdbcTemplate.update(
                INSERT_TENANT, id.toString(), slug, name, tier, isolation);
    }
}
