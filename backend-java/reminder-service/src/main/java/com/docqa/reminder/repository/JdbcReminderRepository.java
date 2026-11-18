package com.docqa.reminder.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.docqa.reminder.domain.ReminderRow;
import com.docqa.reminder.domain.ReminderStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcReminderRepository implements ReminderRepository {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private static final String COLUMNS = """
            id, user_id, title, description, rrule, dtstart, timezone, lead_minutes, exdates, exdate_dates, status,
            source_type, source_uid, source_sequence, idempotency_key, next_trigger_at,
            last_triggered_at, version, created_at, updated_at
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RowMapper<ReminderRow> rowMapper;

    public JdbcReminderRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.rowMapper = this::mapRow;
    }

    @Override
    public void insert(UUID tenantId, ReminderRow reminder) {
        jdbcTemplate.update("""
                        INSERT INTO reminder (
                            tenant_id, id, user_id, title, description, rrule, dtstart, timezone, lead_minutes,
                            exdates, exdate_dates,
                            status, source_type, source_uid, source_sequence, idempotency_key,
                            next_trigger_at, last_triggered_at, version, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                tenantId.toString(),
                reminder.id().toString(),
                reminder.userId().toString(),
                reminder.title(),
                reminder.description(),
                reminder.rrule(),
                Timestamp.from(reminder.dtstart()),
                reminder.timezone(),
                reminder.leadMinutes(),
                serializeExdates(reminder.exdates()),
                serializeExdateDates(reminder.exdateDates()),
                reminder.status().name(),
                reminder.sourceType(),
                reminder.sourceUid(),
                reminder.sourceSequence(),
                reminder.idempotencyKey(),
                toTimestamp(reminder.nextTriggerAt()),
                toTimestamp(reminder.lastTriggeredAt()),
                reminder.version(),
                Timestamp.from(reminder.createdAt()),
                Timestamp.from(reminder.updatedAt()));
    }

    @Override
    public Optional<ReminderRow> findByUserAndIdempotencyKey(UUID tenantId, UUID userId, String idempotencyKey) {
        return jdbcTemplate.query(
                        "SELECT " + COLUMNS + " FROM reminder WHERE tenant_id = ? AND user_id = ?"
                                + " AND idempotency_key = ?",
                        rowMapper,
                        tenantId.toString(),
                        userId.toString(),
                        idempotencyKey)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<ReminderRow> findOwned(UUID tenantId, UUID userId, UUID id) {
        return jdbcTemplate.query(
                        "SELECT " + COLUMNS + " FROM reminder WHERE tenant_id = ? AND id = ? AND user_id = ?",
                        rowMapper,
                        tenantId.toString(),
                        id.toString(),
                        userId.toString())
                .stream()
                .findFirst();
    }

    @Override
    public int updateStatus(UUID tenantId, UUID id, UUID userId, long expectedVersion, ReminderStatus target,
            Instant nextTriggerAt, Instant updatedAt) {
        return jdbcTemplate.update("""
                        UPDATE reminder
                        SET status = ?, next_trigger_at = ?, version = version + 1, updated_at = ?
                        WHERE tenant_id = ? AND id = ? AND user_id = ? AND version = ?
                        """,
                target.name(),
                toTimestamp(nextTriggerAt),
                Timestamp.from(updatedAt),
                tenantId.toString(),
                id.toString(),
                userId.toString(),
                expectedVersion);
    }

    @Override
    public List<ReminderRow> findDue(UUID tenantId, Instant now, int limit) {
        return jdbcTemplate.query("""
                        SELECT %s
                        FROM reminder
                        WHERE tenant_id = ? AND status = 'ACTIVE' AND next_trigger_at IS NOT NULL
                          AND next_trigger_at <= ?
                        ORDER BY next_trigger_at
                        LIMIT ?
                        """.formatted(COLUMNS),
                rowMapper,
                tenantId.toString(),
                Timestamp.from(now),
                limit);
    }

    @Override
    public int advance(UUID tenantId, UUID id, long expectedVersion, Instant nextTriggerAt,
            Instant lastTriggeredAt) {
        return jdbcTemplate.update("""
                        UPDATE reminder
                        SET next_trigger_at = ?,
                            last_triggered_at = COALESCE(?, last_triggered_at),
                            version = version + 1
                        WHERE tenant_id = ? AND id = ? AND version = ? AND status = 'ACTIVE'
                        """,
                Timestamp.from(nextTriggerAt),
                lastTriggeredAt == null ? null : Timestamp.from(lastTriggeredAt),
                tenantId.toString(),
                id.toString(),
                expectedVersion);
    }

    @Override
    public int complete(UUID tenantId, UUID id, long expectedVersion, Instant lastTriggeredAt) {
        return jdbcTemplate.update("""
                        UPDATE reminder
                        SET status = 'COMPLETED',
                            next_trigger_at = NULL,
                            last_triggered_at = COALESCE(?, last_triggered_at),
                            version = version + 1
                        WHERE tenant_id = ? AND id = ? AND version = ? AND status = 'ACTIVE'
                        """,
                lastTriggeredAt == null ? null : Timestamp.from(lastTriggeredAt),
                tenantId.toString(),
                id.toString(),
                expectedVersion);
    }

    @Override
    public List<ReminderRow> findUserPage(UUID tenantId, UUID userId, ReminderStatus status,
            Instant afterNextTriggerAt, UUID afterId, boolean afterNullGroup, int limit) {
        StringBuilder sql = new StringBuilder("SELECT ").append(COLUMNS)
                .append(" FROM reminder WHERE tenant_id = ? AND user_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantId.toString());
        args.add(userId.toString());
        if (status != null) {
            sql.append(" AND status = ?");
            args.add(status.name());
        }
        if (afterNullGroup) {
            sql.append(" AND (next_trigger_at IS NULL AND id > ?)");
            args.add(afterId.toString());
        } else if (afterNextTriggerAt != null && afterId != null) {
            sql.append(" AND ((next_trigger_at IS NOT NULL AND (next_trigger_at > ?");
            sql.append(" OR (next_trigger_at = ? AND id > ?))) OR next_trigger_at IS NULL)");
            Timestamp cursorNextTriggerAt = Timestamp.from(afterNextTriggerAt);
            args.add(cursorNextTriggerAt);
            args.add(cursorNextTriggerAt);
            args.add(afterId.toString());
        }
        sql.append(" ORDER BY (next_trigger_at IS NULL) ASC, next_trigger_at ASC, id ASC LIMIT ?");
        args.add(limit);
        return jdbcTemplate.query(sql.toString(), rowMapper, args.toArray());
    }

    @Override
    public List<ReminderRow> findByUserAndSourceUids(UUID tenantId, UUID userId, String sourceType,
            List<String> sourceUids) {
        if (sourceUids.isEmpty()) {
            return List.of();
        }
        String placeholders = sourceUids.stream().map(uid -> "?").collect(Collectors.joining(", "));
        List<Object> args = new ArrayList<>();
        args.add(tenantId.toString());
        args.add(userId.toString());
        args.add(sourceType);
        args.addAll(sourceUids);
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM reminder"
                        + " WHERE tenant_id = ? AND user_id = ? AND source_type = ?"
                        + " AND source_uid IN (" + placeholders + ")",
                rowMapper,
                args.toArray());
    }

    @Override
    public int updateFromImport(UUID tenantId, ReminderRow reminder) {
        return jdbcTemplate.update("""
                        UPDATE reminder
                        SET title = ?, description = ?, rrule = ?, dtstart = ?, timezone = ?,
                            lead_minutes = ?,
                            exdates = ?, exdate_dates = ?, source_sequence = ?,
                            next_trigger_at = ?, version = version + 1, updated_at = ?
                        WHERE tenant_id = ? AND id = ? AND user_id = ?
                        """,
                reminder.title(),
                reminder.description(),
                reminder.rrule(),
                Timestamp.from(reminder.dtstart()),
                reminder.timezone(),
                reminder.leadMinutes(),
                serializeExdates(reminder.exdates()),
                serializeExdateDates(reminder.exdateDates()),
                reminder.sourceSequence(),
                toTimestamp(reminder.nextTriggerAt()),
                Timestamp.from(reminder.updatedAt()),
                tenantId.toString(),
                reminder.id().toString(),
                reminder.userId().toString());
    }

    private ReminderRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new ReminderRow(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("user_id")),
                rs.getString("title"),
                rs.getString("description"),
                rs.getString("rrule"),
                toInstant(rs.getTimestamp("dtstart")),
                rs.getString("timezone"),
                rs.getInt("lead_minutes"),
                deserializeExdates(rs.getString("exdates")),
                deserializeExdateDates(rs.getString("exdate_dates")),
                ReminderStatus.from(rs.getString("status")),
                rs.getString("source_type"),
                rs.getString("source_uid"),
                rs.getInt("source_sequence"),
                rs.getString("idempotency_key"),
                toInstant(rs.getTimestamp("next_trigger_at")),
                toInstant(rs.getTimestamp("last_triggered_at")),
                rs.getLong("version"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")));
    }

    private String serializeExdates(List<Instant> exdates) {
        if (exdates == null || exdates.isEmpty()) {
            return null;
        }
        List<String> values = exdates.stream()
                .map(instant -> instant.truncatedTo(ChronoUnit.SECONDS).toString())
                .toList();
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize reminder exdates", ex);
        }
    }

    private List<Instant> deserializeExdates(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(value, STRING_LIST).stream()
                    .map(Instant::parse)
                    .toList();
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to deserialize reminder exdates", ex);
        }
    }

    private String serializeExdateDates(List<LocalDate> exdateDates) {
        if (exdateDates == null || exdateDates.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(exdateDates.stream().map(LocalDate::toString).toList());
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize reminder exdate dates", ex);
        }
    }

    private List<LocalDate> deserializeExdateDates(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(value, STRING_LIST).stream()
                    .map(LocalDate::parse)
                    .toList();
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to deserialize reminder exdate dates", ex);
        }
    }

    private static Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
