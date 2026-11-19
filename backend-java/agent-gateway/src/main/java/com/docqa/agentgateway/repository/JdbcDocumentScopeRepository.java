package com.docqa.agentgateway.repository;

import com.docqa.agentgateway.domain.DocumentRecord;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcDocumentScopeRepository implements DocumentScopeRepository {

    private static final RowMapper<DocumentRecord> ROW_MAPPER = JdbcDocumentScopeRepository::mapRow;

    private final JdbcTemplate jdbcTemplate;

    public JdbcDocumentScopeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<DocumentRecord> findByUserAndIds(UUID tenantId, UUID userId, List<UUID> documentIds) {
        String placeholders = documentIds.stream().map(id -> "?").collect(Collectors.joining(", "));
        List<Object> arguments = new ArrayList<>();
        arguments.add(tenantId.toString());
        arguments.add(userId.toString());
        documentIds.forEach(id -> arguments.add(id.toString()));
        return jdbcTemplate.query(
                "SELECT id, status, index_version FROM document"
                        + " WHERE tenant_id = ? AND deleted_at IS NULL AND user_id = ? AND id IN ("
                        + placeholders + ")",
                ROW_MAPPER,
                arguments.toArray());
    }

    private static DocumentRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new DocumentRecord(
                UUID.fromString(rs.getString("id")),
                rs.getString("status"),
                rs.getString("index_version"));
    }
}
