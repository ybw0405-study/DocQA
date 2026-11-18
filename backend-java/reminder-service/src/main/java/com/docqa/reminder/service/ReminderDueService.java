package com.docqa.reminder.service;

import com.docqa.common.trace.TraceIdContext;
import com.docqa.common.metrics.BusinessMetrics;
import com.docqa.common.tenant.TenantContext;
import com.docqa.reminder.config.ReminderProperties;
import com.docqa.reminder.domain.ReminderRow;
import com.docqa.reminder.messaging.ReminderDueEvent;
import com.docqa.reminder.messaging.ReminderDuePublisher;
import com.docqa.reminder.recurrence.RecurrenceCalculator;
import com.docqa.reminder.recurrence.Rrule;
import com.docqa.reminder.recurrence.RruleParser;
import com.docqa.reminder.repository.ReminderRepository;
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

@Service
public class ReminderDueService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReminderDueService.class);

    private final ReminderRepository reminderRepository;
    private final FiredOccurrenceStore firedOccurrenceStore;
    private final ReminderDuePublisher reminderDuePublisher;
    private final RruleParser rruleParser;
    private final RecurrenceCalculator calculator;
    private final Clock clock;
    private final ReminderProperties properties;
    private final BusinessMetrics metrics;

    public ReminderDueService(
            ReminderRepository reminderRepository,
            FiredOccurrenceStore firedOccurrenceStore,
            ReminderDuePublisher reminderDuePublisher,
            RruleParser rruleParser,
            RecurrenceCalculator calculator,
            Clock clock,
            ReminderProperties properties,
            BusinessMetrics metrics) {
        this.reminderRepository = reminderRepository;
        this.firedOccurrenceStore = firedOccurrenceStore;
        this.reminderDuePublisher = reminderDuePublisher;
        this.rruleParser = rruleParser;
        this.calculator = calculator;
        this.clock = clock;
        this.properties = properties;
        this.metrics = metrics;
    }

    public void dispatchDue() {
        String traceId = TraceIdContext.currentOrCreate();
        boolean ownsTrace = TraceIdContext.current() == null;
        if (ownsTrace) {
            TraceIdContext.set(traceId);
        }
        try {
            // P0: 定时任务没有 HTTP 租户上下文，存量数据全在默认租户；P1 改为遍历所有 ACTIVE 租户。
            UUID tenantId = TenantContext.DEFAULT_TENANT_ID;
            Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
            List<ReminderRow> due = reminderRepository.findDue(tenantId, now, properties.getScanBatchSize());
            int published = 0;
            int duplicates = 0;
            int skipped = 0;
            int failed = 0;
            for (ReminderRow row : due) {
                try {
                    Outcome outcome = dispatch(tenantId, row, now, traceId);
                    // §4.2：按终态计数。OUTCOME 枚举本身就是标签域，直接 name() 落标签，
                    // 避免再维护一份「枚举 → 字符串」映射而两处漂移。
                    metrics.reminderDuePublished(outcome.name());
                    if (outcome == Outcome.PUBLISHED) {
                        published++;
                    } else if (outcome == Outcome.DUPLICATE) {
                        duplicates++;
                    } else {
                        skipped++;
                    }
                } catch (RuntimeException ex) {
                    metrics.reminderDuePublished(Outcome.FAILED.name());
                    failed++;
                    LOGGER.error(
                            "Reminder dispatch failed: reminderId={}, occurrenceStartUtc={}",
                            row.id(),
                            row.nextTriggerAt(),
                            ex);
                }
            }
            if (!due.isEmpty()) {
                LOGGER.info(
                        "Reminder scan finished: scanned={}, published={}, duplicates={}, skipped={}, failed={}",
                        due.size(),
                        published,
                        duplicates,
                        skipped,
                        failed);
            }
        } finally {
            if (ownsTrace) {
                TraceIdContext.clear();
            }
        }
    }

    private Outcome dispatch(UUID tenantId, ReminderRow row, Instant now, String traceId) {
        Instant occurrence = row.nextTriggerAt().truncatedTo(ChronoUnit.SECONDS);
        Instant graceThreshold = now.minus(properties.getPastGrace());
        if (occurrence.isBefore(graceThreshold)) {
            Optional<Instant> next = nextOccurrenceAtOrAfter(row, graceThreshold);
            LOGGER.info(
                    "Reminder occurrence is beyond the past grace window; skipping: reminderId={}, "
                            + "occurrenceStartUtc={}, nextTriggerAt={}",
                    row.id(),
                    occurrence,
                    next.orElse(null));
            advance(tenantId, row, row.lastTriggeredAt(), next);
            return Outcome.SKIPPED;
        }
        Optional<Instant> next = nextOccurrence(row, occurrence);
        FiredOccurrenceStore.Claim claim = firedOccurrenceStore.claim(row.id(), occurrence);
        if (claim == FiredOccurrenceStore.Claim.IN_PROGRESS) {
            LOGGER.debug(
                    "Reminder occurrence is being published by another instance: reminderId={}, occurrenceStartUtc={}",
                    row.id(),
                    occurrence);
            return Outcome.SKIPPED;
        }
        if (claim == FiredOccurrenceStore.Claim.FIRED) {
            LOGGER.info(
                    "Reminder occurrence already published; advancing state only: reminderId={}, occurrenceStartUtc={}",
                    row.id(),
                    occurrence);
            advance(tenantId, row, occurrence, next);
            return Outcome.DUPLICATE;
        }

        ReminderDueEvent event = new ReminderDueEvent(
                UUID.randomUUID(),
                traceId,
                row.id(),
                row.userId(),
                row.title(),
                row.description(),
                row.sourceType(),
                row.rrule(),
                row.timezone(),
                occurrence,
                next.orElse(null),
                now);
        try {
            reminderDuePublisher.publish(event);
        } catch (RuntimeException ex) {
            firedOccurrenceStore.release(row.id(), occurrence);
            LOGGER.warn(
                    "Unable to publish reminder.due; occurrence will be retried: reminderId={}, occurrenceStartUtc={}",
                    row.id(),
                    occurrence,
                    ex);
            return Outcome.FAILED;
        }

        firedOccurrenceStore.markFired(row.id(), occurrence);
        advance(tenantId, row, occurrence, next);
        return Outcome.PUBLISHED;
    }

    private Optional<Instant> nextOccurrence(ReminderRow row, Instant triggerAt) {
        Rrule rule = rruleParser.parse(row.rrule());
        ZoneId zone = ZoneId.of(row.timezone());
        return calculator.nextAfter(
                        rule,
                        row.dtstart(),
                        zone,
                        Set.copyOf(row.exdates()),
                        Set.copyOf(row.exdateDates()),
                        occurrenceOf(triggerAt, row))
                .map(occurrence -> triggerOf(occurrence, row));
    }

    private Optional<Instant> nextOccurrenceAtOrAfter(ReminderRow row, Instant threshold) {
        Rrule rule = rruleParser.parse(row.rrule());
        ZoneId zone = ZoneId.of(row.timezone());
        return calculator.nextAtOrAfter(
                        rule,
                        row.dtstart(),
                        zone,
                        Set.copyOf(row.exdates()),
                        Set.copyOf(row.exdateDates()),
                        occurrenceOf(threshold, row))
                .map(occurrence -> triggerOf(occurrence, row));
    }

    /**
     * `rrule`、`dtstart` 与两种 EXDATE 都定义在事件时刻（课表的上课时刻）上，
     * 而 `next_trigger_at` 物化的是 occurrence - leadMinutes，因此进出计算器都要换算。
     * CUSTOM / DEADLINE 行的 leadMinutes 恒为 0，换算即恒等。
     */
    private static Instant occurrenceOf(Instant triggerAt, ReminderRow row) {
        return triggerAt.plusSeconds(row.leadMinutes() * 60L);
    }

    private static Instant triggerOf(Instant occurrence, ReminderRow row) {
        return occurrence.minusSeconds(row.leadMinutes() * 60L);
    }

    private void advance(UUID tenantId, ReminderRow row, Instant lastTriggeredAt, Optional<Instant> next) {
        int updated;
        if (next.isPresent()) {
            updated = reminderRepository.advance(tenantId, row.id(), row.version(), next.get(), lastTriggeredAt);
        } else {
            updated = reminderRepository.complete(tenantId, row.id(), row.version(), lastTriggeredAt);
        }
        if (updated == 0) {
            LOGGER.info(
                    "Reminder was advanced by another instance: reminderId={}, lastTriggeredAt={}",
                    row.id(),
                    lastTriggeredAt);
        }
    }

    private enum Outcome {
        PUBLISHED,
        DUPLICATE,
        SKIPPED,
        FAILED
    }
}
