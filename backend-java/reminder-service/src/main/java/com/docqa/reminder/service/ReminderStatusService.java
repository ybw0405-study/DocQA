package com.docqa.reminder.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.docqa.common.error.BusinessException;
import com.docqa.common.error.ErrorCode;
import com.docqa.common.tenant.TenantContext;
import com.docqa.reminder.domain.IdempotencyOperation;
import com.docqa.reminder.domain.ReminderRow;
import com.docqa.reminder.domain.ReminderStatus;
import com.docqa.reminder.dto.ReminderStatusUpdateData;
import com.docqa.reminder.dto.ReminderStatusUpdateRequest;
import com.docqa.reminder.recurrence.RecurrenceCalculator;
import com.docqa.reminder.recurrence.Rrule;
import com.docqa.reminder.recurrence.RruleParser;
import com.docqa.reminder.repository.ReminderRepository;
import com.docqa.reminder.support.Fingerprints;
import com.docqa.reminder.support.IdempotencyKeys;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 提醒状态机（契约 2.3.0 的 {@code PATCH /api/v1/reminders/{reminderId}}）。
 *
 * <p>判定顺序固定为：幂等守卫 → 404 → 同态 no-op → 非法边 409 → 恢复重算无结果 409 → CAS 0 行 409，
 * 五种结果互不重叠，因此同一个 409 只可能是三种成因之一，客户端能按错误码分流。
 */
@Service
public class ReminderStatusService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReminderStatusService.class);

    private final ReminderRepository reminderRepository;
    private final IdempotencyStore idempotencyStore;
    private final RateLimiter rateLimiter;
    private final RruleParser rruleParser;
    private final RecurrenceCalculator calculator;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public ReminderStatusService(
            ReminderRepository reminderRepository,
            IdempotencyStore idempotencyStore,
            RateLimiter rateLimiter,
            RruleParser rruleParser,
            RecurrenceCalculator calculator,
            Clock clock,
            ObjectMapper objectMapper) {
        this.reminderRepository = reminderRepository;
        this.idempotencyStore = idempotencyStore;
        this.rateLimiter = rateLimiter;
        this.rruleParser = rruleParser;
        this.calculator = calculator;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    public ReminderStatusUpdateData update(
            UUID userId,
            UUID reminderId,
            String idempotencyKey,
            ReminderStatusUpdateRequest request) {
        UUID tenantId = TenantContext.require();
        String key = IdempotencyKeys.requireValid(idempotencyKey);
        rateLimiter.check(userId);
        String fingerprint = fingerprint(reminderId, request);

        Optional<IdempotencyRecord> existing =
                idempotencyStore.find(userId, IdempotencyOperation.REMINDER_UPDATE, key);
        if (existing.isPresent()) {
            return replay(existing.get(), fingerprint);
        }
        if (!idempotencyStore.tryStart(userId, IdempotencyOperation.REMINDER_UPDATE, key, fingerprint, clock.instant())) {
            IdempotencyRecord record = idempotencyStore.find(userId, IdempotencyOperation.REMINDER_UPDATE, key)
                    .orElseThrow(() -> new BusinessException(
                            ErrorCode.REM_CONFLICT_001,
                            "The same Idempotency-Key is still in progress"));
            return replay(record, fingerprint);
        }

        boolean finished = false;
        try {
            ReminderStatusUpdateData data = transition(tenantId, userId, reminderId, request);
            completeIdempotency(userId, key, fingerprint, data);
            finished = true;
            return data;
        } finally {
            if (!finished) {
                clearIdempotency(userId, key);
            }
        }
    }

    private ReminderStatusUpdateData transition(UUID tenantId, UUID userId, UUID reminderId,
            ReminderStatusUpdateRequest request) {
        ReminderStatus target = request.status();
        ReminderRow row = reminderRepository.findOwned(tenantId, userId, reminderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REM_NOT_FOUND_001, null, List.of()));
        long expectedVersion = request.version();

        if (row.status() == target) {
            // no-op：不校验 version、不写库、不推进版本。连点两次「暂停」应当成功。
            return new ReminderStatusUpdateData(
                    row.id(), row.status().name(), row.nextTriggerAt(), row.version(), row.updatedAt(), false);
        }
        if (!row.status().canTransitionTo(target)) {
            throw new BusinessException(ErrorCode.REM_CONFLICT_004, null, List.of());
        }

        Instant nextTriggerAt = resolveNextTriggerAt(row, target);
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        int updated = reminderRepository.updateStatus(
                tenantId, reminderId, userId, expectedVersion, target, nextTriggerAt, now);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.REM_CONFLICT_003, null, List.of());
        }
        return new ReminderStatusUpdateData(reminderId, target.name(), nextTriggerAt, expectedVersion + 1, now, false);
    }

    /**
     * 四种目标各有明确取值：恢复是**重算**而不是沿用暂停前的过期时刻；完成与取消置空，
     * 让 {@code idx_reminder_due} 的扫描（谓词含 {@code status = 'ACTIVE'}）立即不再命中；
     * 暂停**保留原值**，看板因此仍能显示「本来下次是几点」。
     */
    private Instant resolveNextTriggerAt(ReminderRow row, ReminderStatus target) {
        if (target == ReminderStatus.PAUSED) {
            return row.nextTriggerAt();
        }
        if (target == ReminderStatus.COMPLETED || target == ReminderStatus.CANCELLED) {
            return null;
        }
        Rrule rule = rruleParser.parse(row.rrule());
        ZoneId zone = ZoneId.of(row.timezone());
        // 计算器工作在上课时刻域，而 next_trigger_at 物化的是「上课时刻 − 提前量」，进出都要换算。
        Optional<Instant> nextOccurrence = calculator.nextAtOrAfter(
                rule,
                row.dtstart(),
                zone,
                Set.copyOf(row.exdates()),
                Set.copyOf(row.exdateDates()),
                clock.instant().truncatedTo(ChronoUnit.SECONDS));
        Instant next = nextOccurrence
                .map(occurrence -> occurrence.minusSeconds(row.leadMinutes() * 60L))
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.REM_CONFLICT_004,
                        "the rule has no occurrence at or after now, so the reminder cannot be resumed",
                        List.of()));
        return next;
    }

    private String fingerprint(UUID reminderId, ReminderStatusUpdateRequest request) {
        return Fingerprints.sha256(new StringBuilder()
                .append(reminderId)
                .append('|')
                .append(request.status().name())
                .append('|')
                .append(request.version())
                .toString());
    }

    private ReminderStatusUpdateData replay(IdempotencyRecord record, String fingerprint) {
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
            ReminderStatusUpdateData data =
                    objectMapper.readValue(record.responseJson(), ReminderStatusUpdateData.class);
            return data.withIdempotentReplay();
        } catch (JsonProcessingException ex) {
            throw new BusinessException(
                    ErrorCode.COM_INTERNAL_001,
                    "Idempotency record is unreadable, please retry later",
                    ex);
        }
    }

    private void completeIdempotency(UUID userId, String key, String fingerprint, ReminderStatusUpdateData data) {
        try {
            String responseJson = objectMapper.writeValueAsString(data);
            idempotencyStore.complete(
                    userId,
                    IdempotencyOperation.REMINDER_UPDATE,
                    key,
                    fingerprint,
                    responseJson,
                    clock.instant());
        } catch (JsonProcessingException ex) {
            LOGGER.error("Unable to serialize reminder update response: reminderId={}", data.reminderId());
            clearIdempotency(userId, key);
        } catch (RuntimeException ex) {
            LOGGER.warn("Unable to store reminder update idempotency response: reminderId={}", data.reminderId());
            clearIdempotency(userId, key);
        }
    }

    private void clearIdempotency(UUID userId, String key) {
        try {
            idempotencyStore.clear(userId, IdempotencyOperation.REMINDER_UPDATE, key);
        } catch (RuntimeException ex) {
            LOGGER.warn("Unable to clear reminder update idempotency record");
        }
    }
}
