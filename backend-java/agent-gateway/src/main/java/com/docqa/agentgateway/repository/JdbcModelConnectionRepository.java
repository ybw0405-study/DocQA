package com.docqa.agentgateway.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcModelConnectionRepository implements ModelConnectionRepository {

    /** active_slot 是 STORED 生成列，任何写入都不得列出它，否则 MySQL 直接报 3105。 */
    static final String PROJECTION = """
            SELECT id, tenant_id, name, preset_id, base_url, model, enabled, is_active,
                   credential, credential_key_id, probe_status, last_probed_at,
                   created_at, updated_at, updated_by
            FROM model_connection
            """;

    static final String FIND_ALL_SQL = PROJECTION + " WHERE tenant_id = ? ORDER BY created_at, id";

    static final String FIND_BY_ID_SQL = PROJECTION + " WHERE tenant_id = ? AND id = ?";

    static final String FIND_BY_NAME_SQL = PROJECTION + " WHERE tenant_id = ? AND name = ?";

    static final String FIND_ACTIVE_SQL = PROJECTION + " WHERE tenant_id = ? AND active_slot = 1";

    static final String COUNT_SQL = "SELECT COUNT(*) FROM model_connection WHERE tenant_id = ?";

    static final String UPSERT_SQL = """
            INSERT INTO model_connection
              (id, tenant_id, name, preset_id, base_url, model, enabled, is_active, updated_at, updated_by)
            VALUES (?, ?, ?, ?, ?, ?, ?, FALSE, ?, ?)
            ON DUPLICATE KEY UPDATE
              preset_id = VALUES(preset_id),
              base_url = VALUES(base_url),
              model = VALUES(model),
              enabled = VALUES(enabled),
              updated_at = VALUES(updated_at),
              updated_by = VALUES(updated_by)
            """;

    static final String UPDATE_CREDENTIAL_SQL = """
            UPDATE model_connection
               SET credential = ?, credential_key_id = ?, updated_at = ?, updated_by = ?
             WHERE tenant_id = ? AND id = ?
            """;

    static final String CLEAR_ACTIVE_SQL = """
            UPDATE model_connection SET is_active = FALSE, updated_at = ?, updated_by = ?
             WHERE tenant_id = ? AND is_active = TRUE
            """;

    static final String DEACTIVATE_SQL = """
            UPDATE model_connection SET is_active = FALSE, updated_at = ?, updated_by = ?
             WHERE tenant_id = ? AND id = ?
            """;

    static final String MARK_ACTIVE_SQL = """
            UPDATE model_connection SET is_active = TRUE, updated_at = ?, updated_by = ?
             WHERE tenant_id = ? AND id = ?
            """;

    static final String UPDATE_PROBE_SQL = """
            UPDATE model_connection SET probe_status = ?, last_probed_at = ?, updated_at = ?
             WHERE tenant_id = ? AND id = ?
            """;

    static final String DELETE_SQL = "DELETE FROM model_connection WHERE tenant_id = ? AND id = ?";

    static final String FIND_ALL_ACTIVE_SQL = PROJECTION + " WHERE active_slot = 1 ORDER BY tenant_id, id";

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<ModelConnectionRow> rowMapper = this::mapRow;

    public JdbcModelConnectionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<ModelConnectionRow> findAll(UUID tenantId) {
        return jdbcTemplate.query(FIND_ALL_SQL, rowMapper, tenantId.toString());
    }

    @Override
    public Optional<ModelConnectionRow> findById(UUID tenantId, UUID connectionId) {
        return first(jdbcTemplate.query(FIND_BY_ID_SQL, rowMapper, tenantId.toString(), connectionId.toString()));
    }

    @Override
    public Optional<ModelConnectionRow> findByName(UUID tenantId, String name) {
        return first(jdbcTemplate.query(FIND_BY_NAME_SQL, rowMapper, tenantId.toString(), name));
    }

    @Override
    public Optional<ModelConnectionRow> findActive(UUID tenantId) {
        return first(jdbcTemplate.query(FIND_ACTIVE_SQL, rowMapper, tenantId.toString()));
    }

    @Override
    public int count(UUID tenantId) {
        Integer count = jdbcTemplate.queryForObject(COUNT_SQL, Integer.class, tenantId.toString());
        return count == null ? 0 : count;
    }

    @Override
    public void upsert(UUID tenantId, UUID connectionId, String name, String presetId, String baseUrl, String model,
            boolean enabled, UUID updatedBy, Instant now) {
        jdbcTemplate.update(
                UPSERT_SQL,
                connectionId.toString(),
                tenantId.toString(),
                name,
                presetId,
                baseUrl,
                model,
                enabled,
                Timestamp.from(now),
                updatedBy == null ? null : updatedBy.toString());
    }

    @Override
    public void updateCredential(UUID tenantId, UUID connectionId, byte[] credential, String credentialKeyId,
            UUID updatedBy, Instant now) {
        jdbcTemplate.update(
                UPDATE_CREDENTIAL_SQL,
                credential,
                credentialKeyId,
                Timestamp.from(now),
                updatedBy == null ? null : updatedBy.toString(),
                tenantId.toString(),
                connectionId.toString());
    }

    @Override
    public void clearActive(UUID tenantId, UUID updatedBy, Instant now) {
        jdbcTemplate.update(
                CLEAR_ACTIVE_SQL,
                Timestamp.from(now),
                updatedBy == null ? null : updatedBy.toString(),
                tenantId.toString());
    }

    @Override
    public void deactivate(UUID tenantId, UUID connectionId, UUID updatedBy, Instant now) {
        jdbcTemplate.update(
                DEACTIVATE_SQL,
                Timestamp.from(now),
                updatedBy == null ? null : updatedBy.toString(),
                tenantId.toString(),
                connectionId.toString());
    }

    @Override
    public void markActive(UUID tenantId, UUID connectionId, UUID updatedBy, Instant now) {
        jdbcTemplate.update(
                MARK_ACTIVE_SQL,
                Timestamp.from(now),
                updatedBy == null ? null : updatedBy.toString(),
                tenantId.toString(),
                connectionId.toString());
    }

    @Override
    public void updateProbeStatus(UUID tenantId, UUID connectionId, String probeStatus, Instant probedAt, Instant now) {
        jdbcTemplate.update(
                UPDATE_PROBE_SQL,
                probeStatus,
                probedAt == null ? null : Timestamp.from(probedAt),
                Timestamp.from(now),
                tenantId.toString(),
                connectionId.toString());
    }

    @Override
    public void delete(UUID tenantId, UUID connectionId) {
        jdbcTemplate.update(DELETE_SQL, tenantId.toString(), connectionId.toString());
    }

    @Override
    public List<ModelConnectionRow> findAllActive() {
        return jdbcTemplate.query(FIND_ALL_ACTIVE_SQL, rowMapper);
    }

    private static Optional<ModelConnectionRow> first(List<ModelConnectionRow> rows) {
        return rows.stream().findFirst();
    }

    private ModelConnectionRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        String presetId = rs.getString("preset_id");
        String credentialKeyId = rs.getString("credential_key_id");
        String updatedBy = rs.getString("updated_by");
        return new ModelConnectionRow(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("tenant_id")),
                rs.getString("name"),
                presetId,
                rs.getString("base_url"),
                rs.getString("model"),
                rs.getBoolean("enabled"),
                rs.getBoolean("is_active"),
                rs.getBytes("credential"),
                credentialKeyId,
                rs.getString("probe_status"),
                toInstant(rs.getTimestamp("last_probed_at")),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")),
                updatedBy == null ? null : UUID.fromString(updatedBy));
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
