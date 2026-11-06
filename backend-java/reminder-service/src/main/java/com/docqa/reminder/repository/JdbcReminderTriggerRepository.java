package com.docqa.reminder.repository;

import com.docqa.reminder.domain.ReminderTriggerRow;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcReminderTriggerRepository implements ReminderTriggerRepository {

    private static final String COLUMNS = """
            id, user_id, reminder_id, title, description, source_type,
            planned_trigger_at, next_trigger_at, trace_id, delivered_at, read_at, created_at, updated_at
            """;

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<ReminderTriggerRow> rowMapper;

    public JdbcReminderTriggerRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.rowMapper = this::mapRow;
    }

    @Override
    public void insert(ReminderTriggerRow row) {
        jdbcTemplate.update("""
                        INSERT INTO reminder_trigger (
                            id, user_id, reminder_id, title, description, source_type,
                            planned_trigger_at, next_trigger_at, trace_id, delivered_at, read_at,
                            created_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                row.id().toString(),
                row.userId().toString(),
                row.reminderId().toString(),
                row.title(),
                row.description(),
                row.sourceType(),
                Timestamp.from(row.plannedTriggerAt()),
                toTimestamp(row.nextTriggerAt()),
                row.traceId(),
                Timestamp.from(row.deliveredAt()),
                toTimestamp(row.readAt()),
                Timestamp.from(row.createdAt()),
                Timestamp.from(row.updatedAt()));
    }

    @Override
    public List<ReminderTriggerRow> findUserPage(UUID userId, boolean unreadOnly, Instant beforePlannedTriggerAt,
            UUID beforeId, int limit) {
        StringBuilder sql = new StringBuilder("SELECT ")
                .append(COLUMNS)
                .append(" FROM reminder_trigger WHERE user_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(userId.toString());
        if (unreadOnly) {
            sql.append(" AND read_at IS NULL");
        }
        if (beforePlannedTriggerAt != null && beforeId != null) {
            Timestamp cursor = Timestamp.from(beforePlannedTriggerAt);
            sql.append(" AND (planned_trigger_at < ? OR (planned_trigger_at = ? AND id < ?))");
            args.add(cursor);
            args.add(cursor);
            args.add(beforeId.toString());
        }
        sql.append(" ORDER BY planned_trigger_at DESC, id DESC LIMIT ?");
        args.add(limit);
        return jdbcTemplate.query(sql.toString(), rowMapper, args.toArray());
    }

    @Override
    public int markAllUnreadAsRead(UUID userId, Instant now) {
        return jdbcTemplate.update(
                "UPDATE reminder_trigger SET read_at = ? WHERE user_id = ? AND read_at IS NULL",
                Timestamp.from(now),
                userId.toString());
    }

    @Override
    public int markRead(UUID userId, List<UUID> notificationIds, Instant now) {
        String placeholders = notificationIds.stream().map(id -> "?").collect(Collectors.joining(", "));
        List<Object> args = new ArrayList<>();
        args.add(Timestamp.from(now));
        args.add(userId.toString());
        notificationIds.forEach(id -> args.add(id.toString()));
        return jdbcTemplate.update(
                "UPDATE reminder_trigger SET read_at = ? WHERE user_id = ? AND read_at IS NULL"
                        + " AND id IN (" + placeholders + ")",
                args.toArray());
    }

    private ReminderTriggerRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new ReminderTriggerRow(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("user_id")),
                UUID.fromString(rs.getString("reminder_id")),
                rs.getString("title"),
                rs.getString("description"),
                rs.getString("source_type"),
                toInstant(rs.getTimestamp("planned_trigger_at")),
                toInstant(rs.getTimestamp("next_trigger_at")),
                rs.getString("trace_id"),
                toInstant(rs.getTimestamp("delivered_at")),
                toInstant(rs.getTimestamp("read_at")),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")));
    }

    private static Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
