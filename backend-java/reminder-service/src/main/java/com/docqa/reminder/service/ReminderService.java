package com.docqa.reminder.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.docqa.common.api.FieldViolation;
import com.docqa.common.api.KeysetCursor;
import com.docqa.common.error.BusinessException;
import com.docqa.common.error.ErrorCode;
import com.docqa.common.tenant.TenantContext;
import com.docqa.reminder.config.ReminderProperties;
import com.docqa.reminder.domain.IdempotencyOperation;
import com.docqa.reminder.domain.ReminderKind;
import com.docqa.reminder.domain.ReminderRow;
import com.docqa.reminder.domain.ReminderStatus;
import com.docqa.reminder.dto.CustomReminderCreateRequest;
import com.docqa.reminder.dto.DeadlineReminderCreateRequest;
import com.docqa.reminder.dto.ReminderCreateData;
import com.docqa.reminder.dto.ReminderCreateRequest;
import com.docqa.reminder.dto.ReminderListData;
import com.docqa.reminder.dto.ReminderListItemData;
import com.docqa.reminder.recurrence.RecurrenceCalculator;
import com.docqa.reminder.recurrence.ReminderRuleException;
import com.docqa.reminder.recurrence.Rrule;
import com.docqa.reminder.recurrence.RruleParser;
import com.docqa.reminder.repository.ReminderRepository;
import com.docqa.reminder.support.Fingerprints;
import com.docqa.reminder.support.IdempotencyKeys;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class ReminderService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReminderService.class);

    private static final int MIN_PAGE_SIZE = 1;
    private static final int MAX_PAGE_SIZE = 100;
    private static final String DEADLINE_RRULE = "FREQ=DAILY;COUNT=1";

    private final ReminderRepository reminderRepository;
    private final IdempotencyStore idempotencyStore;
    private final RateLimiter rateLimiter;
    private final RruleParser rruleParser;
    private final RecurrenceCalculator calculator;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ReminderProperties properties;

    public ReminderService(
            ReminderRepository reminderRepository,
            IdempotencyStore idempotencyStore,
            RateLimiter rateLimiter,
            RruleParser rruleParser,
            RecurrenceCalculator calculator,
            Clock clock,
            ObjectMapper objectMapper,
            ReminderProperties properties) {
        this.reminderRepository = reminderRepository;
        this.idempotencyStore = idempotencyStore;
        this.rateLimiter = rateLimiter;
        this.rruleParser = rruleParser;
        this.calculator = calculator;
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public CreateReminderResult create(
            UUID userId,
            String idempotencyKey,
            ReminderCreateRequest request) {
        UUID tenantId = TenantContext.require();
        String key = IdempotencyKeys.requireValid(idempotencyKey);
        rateLimiter.check(userId);
        String fingerprint = fingerprint(request);

        Optional<IdempotencyRecord> existing =
                idempotencyStore.find(userId, IdempotencyOperation.REMINDER_CREATE, key);
        if (existing.isPresent()) {
            return replay(existing.get(), fingerprint);
        }
        if (!idempotencyStore.tryStart(userId, IdempotencyOperation.REMINDER_CREATE, key, fingerprint, clock.instant())) {
            IdempotencyRecord record = idempotencyStore.find(userId, IdempotencyOperation.REMINDER_CREATE, key)
                    .orElseThrow(() -> new BusinessException(
                            ErrorCode.REM_CONFLICT_001,
                            "The same Idempotency-Key is still in progress"));
            return replay(record, fingerprint);
        }

        boolean finished = false;
        try {
            ReminderRow row = toRow(userId, key, prepare(request));
            reminderRepository.insert(tenantId, row);
            ReminderCreateData data = toData(row, false);
            completeIdempotency(userId, key, fingerprint, data);
            finished = true;
            return new CreateReminderResult(data, true);
        } catch (DuplicateKeyException ex) {
            ReminderRow existingRow = reminderRepository.findByUserAndIdempotencyKey(tenantId, userId, key)
                    .orElseThrow(() -> new BusinessException(
                            ErrorCode.COM_INTERNAL_001,
                            "Unable to create reminder",
                            ex));
            ReminderCreateData data = toData(existingRow, true);
            completeIdempotency(userId, key, fingerprint, data);
            finished = true;
            return new CreateReminderResult(data, false);
        } finally {
            if (!finished) {
                clearIdempotency(userId, key);
            }
        }
    }

    private PreparedReminder prepare(ReminderCreateRequest request) {
        if (request instanceof DeadlineReminderCreateRequest deadline) {
            return prepareDeadline(deadline);
        }
        if (request instanceof CustomReminderCreateRequest custom) {
            return prepareCustom(custom);
        }
        throw new BusinessException(ErrorCode.REM_VALIDATION_001, "Unsupported reminder kind");
    }

    private PreparedReminder prepareDeadline(DeadlineReminderCreateRequest request) {
        ZoneId zone = zone(request.timezone());
        int leadMinutes = request.leadMinutes() == null ? 0 : request.leadMinutes();
        Instant triggerAt = request.dueAt()
                .truncatedTo(ChronoUnit.SECONDS)
                .minusSeconds(leadMinutes * 60L);
        Instant threshold = clock.instant().minus(properties.getPastGrace());
        if (triggerAt.isBefore(threshold)) {
            throw ruleError(
                    "dueAt",
                    "dueAt minus leadMinutes must not be more than "
                            + properties.getPastGrace().toMinutes() + " minutes in the past");
        }
        Rrule rule = Rrule.dailyOnce();
        return new PreparedReminder(
                normalizeTitle(request.title()),
                normalizeDescription(request.description()),
                DEADLINE_RRULE,
                rule,
                triggerAt,
                zone.getId(),
                List.of(),
                triggerAt,
                "DEADLINE");
    }

    private PreparedReminder prepareCustom(CustomReminderCreateRequest request) {
        ZoneId zone = zone(request.timezone());
        Rrule rule = parseRule(request.rrule());
        Instant dtstart = request.dtstart().truncatedTo(ChronoUnit.SECONDS);
        List<Instant> exdates = normalizeExdates(request.exdates());
        Instant threshold = clock.instant().minus(properties.getPastGrace());
        Instant nextTriggerAt = calculator
                .nextAtOrAfter(rule, dtstart, zone, Set.copyOf(exdates), threshold)
                .orElseThrow(() -> ruleError(
                        "rrule",
                        "rule produced no occurrence in the future; check dtstart, COUNT, UNTIL and EXDATE"));
        return new PreparedReminder(
                normalizeTitle(request.title()),
                normalizeDescription(request.description()),
                request.rrule(),
                rule,
                dtstart,
                zone.getId(),
                exdates,
                nextTriggerAt,
                "CUSTOM");
    }

    private Rrule parseRule(String value) {
        try {
            return rruleParser.parse(value);
        } catch (ReminderRuleException ex) {
            throw ruleError(ex.getField(), ex.getMessage());
        }
    }

    private ZoneId zone(String timezone) {
        try {
            ZoneId zone = ZoneId.of(timezone);
            if (zone instanceof ZoneOffset) {
                throw ruleError("timezone", "must be an IANA region such as Asia/Shanghai");
            }
            return zone;
        } catch (DateTimeException ex) {
            throw ruleError("timezone", "unknown IANA timezone: " + timezone);
        }
    }

    private List<Instant> normalizeExdates(List<Instant> exdates) {
        if (exdates == null || exdates.isEmpty()) {
            return List.of();
        }
        if (exdates.size() > properties.getMaxExdates()) {
            throw ruleError("exdates", "must contain at most " + properties.getMaxExdates() + " values");
        }
        Set<Instant> unique = exdates.stream()
                .map(instant -> instant.truncatedTo(ChronoUnit.SECONDS))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return List.copyOf(unique);
    }

    private ReminderRow toRow(UUID userId, String idempotencyKey, PreparedReminder prepared) {
        Instant now = clock.instant();
        return new ReminderRow(
                UUID.randomUUID(),
                userId,
                prepared.title(),
                prepared.description(),
                prepared.rruleText(),
                prepared.dtstart(),
                prepared.timezone(),
                // CUSTOM/DEADLINE 的 occurrence 本身就是提醒时刻；提前量只在 COURSE 行上参与重算。
                0,
                prepared.exdates(),
                // 创建接口的 exdates 只接受 date-time 形态（契约 2.1.0），日期型排除只由课表导入产生。
                List.of(),
                ReminderStatus.ACTIVE,
                prepared.sourceType(),
                null,
                0,
                idempotencyKey,
                prepared.nextTriggerAt(),
                null,
                0L,
                now,
                now);
    }

    public ReminderListData list(UUID userId, String statusParam, String cursorToken, int limit) {
        UUID tenantId = TenantContext.require();
        if (limit < MIN_PAGE_SIZE || limit > MAX_PAGE_SIZE) {
            throw new BusinessException(ErrorCode.COM_VALIDATION_001, "limit out of range",
                    List.of(FieldViolation.of("limit", "must be between 1 and 100")));
        }
        ReminderStatus status = parseStatusFilter(statusParam);
        KeysetCursor cursor = parseCursor(cursorToken);
        List<ReminderRow> rows = reminderRepository.findUserPage(
                tenantId,
                userId,
                status,
                cursor == null ? null : cursor.sortKey(),
                cursor == null ? null : cursor.id(),
                cursor != null && !cursor.hasSortKey(),
                limit + 1);
        boolean hasMore = rows.size() > limit;
        List<ReminderRow> page = hasMore ? rows.subList(0, limit) : rows;
        List<ReminderListItemData> items = page.stream()
                .map(row -> new ReminderListItemData(
                        row.id(),
                        ReminderKind.fromSourceType(row.sourceType()).name(),
                        row.status().name(),
                        row.title(),
                        row.description(),
                        row.rrule(),
                        row.dtstart(),
                        row.timezone(),
                        row.exdates(),
                        // 创建路径把提前量并进了 dtstart 并把该列写成 0，只有课表行携带真实值，
                        // 因此这里直接透传就满足契约「DEADLINE/CUSTOM 恒为 0」的口径。
                        row.leadMinutes(),
                        row.nextTriggerAt(),
                        row.lastTriggeredAt(),
                        row.version(),
                        row.createdAt(),
                        row.updatedAt()))
                .toList();
        String nextCursor = hasMore
                ? KeysetCursor.of(page.get(page.size() - 1).nextTriggerAt(), page.get(page.size() - 1).id()).encode()
                : null;
        return new ReminderListData(items, nextCursor, limit);
    }

    private static ReminderStatus parseStatusFilter(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (ReminderStatus candidate : ReminderStatus.values()) {
            if (candidate.name().equals(value)) {
                return candidate;
            }
        }
        throw new BusinessException(ErrorCode.COM_VALIDATION_001, "unknown reminder status",
                List.of(FieldViolation.of("status", "must be ACTIVE, PAUSED, COMPLETED or CANCELLED")));
    }

    private static KeysetCursor parseCursor(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        return KeysetCursor.decode(token).orElseThrow(() -> new BusinessException(
                ErrorCode.COM_VALIDATION_001, "invalid cursor",
                List.of(FieldViolation.of("cursor", "must come from a previous response"))));
    }

    private ReminderCreateData toData(ReminderRow row, boolean idempotentReplay) {
        return new ReminderCreateData(
                row.id(),
                row.status().name(),
                ReminderKind.fromSourceType(row.sourceType()).name(),
                row.title(),
                row.rrule(),
                row.dtstart(),
                row.timezone(),
                row.exdates(),
                row.nextTriggerAt(),
                row.version(),
                idempotentReplay,
                row.createdAt(),
                row.updatedAt());
    }

    private CreateReminderResult replay(IdempotencyRecord record, String fingerprint) {
        if (record.status() == IdempotencyStatus.IN_PROGRESS) {
            throw new BusinessException(
                    ErrorCode.REM_CONFLICT_001,
                    "The same Idempotency-Key is still in progress");
        }
        if (!fingerprint.equals(record.fingerprint())) {
            throw new BusinessException(
                    ErrorCode.REM_CONFLICT_001,
                    "The same Idempotency-Key was used with a different request");
        }
        try {
            ReminderCreateData data = objectMapper.readValue(record.responseJson(), ReminderCreateData.class);
            return new CreateReminderResult(data.withIdempotentReplay(), false);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(
                    ErrorCode.COM_INTERNAL_001,
                    "Idempotency record is unreadable, please retry later",
                    ex);
        }
    }

    private void completeIdempotency(
            UUID userId,
            String key,
            String fingerprint,
            ReminderCreateData data) {
        try {
            String responseJson = objectMapper.writeValueAsString(data);
            idempotencyStore.complete(
                    userId,
                    IdempotencyOperation.REMINDER_CREATE,
                    key,
                    fingerprint,
                    responseJson,
                    clock.instant());
        } catch (JsonProcessingException ex) {
            LOGGER.error("Unable to serialize reminder idempotency response: reminderId={}", data.reminderId());
            clearIdempotency(userId, key);
        } catch (RuntimeException ex) {
            LOGGER.warn("Unable to store reminder idempotency response: reminderId={}", data.reminderId());
            clearIdempotency(userId, key);
        }
    }

    private void clearIdempotency(UUID userId, String key) {
        try {
            idempotencyStore.clear(userId, IdempotencyOperation.REMINDER_CREATE, key);
        } catch (RuntimeException ex) {
            LOGGER.warn("Unable to clear reminder idempotency record");
        }
    }

    private String fingerprint(ReminderCreateRequest request) {
        StringBuilder value = new StringBuilder(request.kind().name())
                .append('|')
                .append(request.title())
                .append('|')
                .append(request.description() == null ? "" : request.description())
                .append('|')
                .append(request.timezone());
        if (request instanceof DeadlineReminderCreateRequest deadline) {
            value.append('|')
                    .append(deadline.dueAt())
                    .append('|')
                    .append(deadline.leadMinutes() == null ? 0 : deadline.leadMinutes());
        } else if (request instanceof CustomReminderCreateRequest custom) {
            value.append('|')
                    .append(custom.rrule())
                    .append('|')
                    .append(custom.dtstart())
                    .append('|');
            if (custom.exdates() != null) {
                value.append(custom.exdates().stream()
                        .map(Instant::toString)
                        .sorted()
                        .collect(Collectors.joining(",")));
            }
        }
        return Fingerprints.sha256(value.toString());
    }

    private String normalizeTitle(String title) {
        return title.trim();
    }

    private String normalizeDescription(String description) {
        if (description == null) {
            return null;
        }
        String value = description.trim();
        return value.isEmpty() ? null : value;
    }

    private BusinessException ruleError(String field, String message) {
        return new BusinessException(
                ErrorCode.REM_VALIDATION_001,
                null,
                List.of(FieldViolation.of(field, message)));
    }

    private record PreparedReminder(
            String title,
            String description,
            String rruleText,
            Rrule rule,
            Instant dtstart,
            String timezone,
            List<Instant> exdates,
            Instant nextTriggerAt,
            String sourceType) {
    }
}
