package com.docqa.reminder.schedule;

import com.docqa.reminder.config.ReminderProperties;
import com.docqa.reminder.domain.ReminderRow;
import com.docqa.reminder.domain.ReminderStatus;
import com.docqa.reminder.domain.ScheduleImportAction;
import com.docqa.reminder.recurrence.RecurrenceCalculator;
import com.docqa.reminder.recurrence.Rrule;
import com.docqa.reminder.recurrence.RruleParser;
import com.docqa.reminder.repository.ReminderRepository;
import com.docqa.reminder.support.Fingerprints;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 课表导入的逐事件判定：把解析出的 {@code VEVENT} 与库内同 UID 的 {@code COURSE} 行比较，
 * 得出 {@code CREATE} / {@code UPDATE} / {@code UNCHANGED} / {@code STALE} 并算出触发时刻。
 * 预览与导入共用本类，这是「预览与导入判定同源」这条契约承诺的实现位置。
 *
 * <p>本类<strong>不写库</strong>：{@code CREATE} 与 {@code UPDATE} 只准备好待写行放在
 * {@link ScheduleDecision#row()} 里，由 {@code ScheduleImportWriter} 在单个事务中落库。
 * 因此预览可以毫无副作用地跑完整棵决策树。
 */
@Component
public class ScheduleEvaluator {

    private static final String SOURCE_TYPE_COURSE = "COURSE";

    private final ReminderRepository reminderRepository;
    private final RruleParser rruleParser;
    private final RecurrenceCalculator calculator;
    private final ReminderProperties properties;
    private final Clock clock;

    public ScheduleEvaluator(
            ReminderRepository reminderRepository,
            RruleParser rruleParser,
            RecurrenceCalculator calculator,
            ReminderProperties properties,
            Clock clock) {
        this.reminderRepository = reminderRepository;
        this.rruleParser = rruleParser;
        this.calculator = calculator;
        this.properties = properties;
        this.clock = clock;
    }

    public List<ScheduleDecision> evaluate(
            UUID userId,
            String requestKey,
            int leadMinutes,
            List<IcsEvent> events) {
        Map<String, ReminderRow> existingByUid = reminderRepository
                .findByUserAndSourceUids(userId, SOURCE_TYPE_COURSE, events.stream().map(IcsEvent::uid).toList())
                .stream()
                .collect(Collectors.toMap(ReminderRow::sourceUid, row -> row));
        Instant now = clock.instant();
        List<ScheduleDecision> decisions = new ArrayList<>(events.size());
        for (IcsEvent event : events) {
            decisions.add(decide(userId, requestKey, leadMinutes, now, event, existingByUid.get(event.uid())));
        }
        return decisions;
    }

    private ScheduleDecision decide(
            UUID userId,
            String requestKey,
            int leadMinutes,
            Instant now,
            IcsEvent event,
            ReminderRow existing) {
        if (existing == null) {
            UUID reminderId = UUID.randomUUID();
            Triggers triggers = newTriggers(event, leadMinutes, now);
            return new ScheduleDecision(
                    event,
                    ScheduleImportAction.CREATE,
                    null,
                    reminderId,
                    triggers.next(),
                    triggers.following(),
                    toRow(userId, requestKey, leadMinutes, now, event, reminderId, ReminderStatus.ACTIVE, null,
                            triggers.next()));
        }
        ScheduleImportAction action = compare(event.sequence(), existing.sourceSequence());
        if (action == ScheduleImportAction.UNCHANGED || action == ScheduleImportAction.STALE) {
            // 不写库，所以触发时刻以库内行为准：库内行才是真正会响的那一行，它的 lead_minutes 也可能是上一次导入的旧值。
            Instant following = followingOfStored(existing);
            return new ScheduleDecision(
                    event, action, existing.sourceSequence(), existing.id(),
                    existing.nextTriggerAt(), following, null);
        }
        Triggers triggers = newTriggers(event, leadMinutes, now);
        return new ScheduleDecision(
                event,
                action,
                existing.sourceSequence(),
                existing.id(),
                triggers.next(),
                triggers.following(),
                toRow(userId, requestKey, leadMinutes, now, event, existing.id(), existing.status(), existing,
                        triggers.next()));
    }

    private static ScheduleImportAction compare(int fileSequence, int storedSequence) {
        if (fileSequence > storedSequence) {
            return ScheduleImportAction.UPDATE;
        }
        return fileSequence == storedSequence
                ? ScheduleImportAction.UNCHANGED
                : ScheduleImportAction.STALE;
    }

    private Triggers newTriggers(IcsEvent event, int leadMinutes, Instant now) {
        Instant threshold = now.minus(properties.getPastGrace()).plusSeconds(leadMinutes * 60L);
        Set<Instant> moments = Set.copyOf(event.exdates());
        Set<LocalDate> dates = Set.copyOf(event.exdateDates());
        ZoneId zone = ZoneId.of(event.timezone());
        Optional<Instant> first = calculator.nextAtOrAfter(
                event.rule(), event.dtstart(), zone, moments, dates, threshold);
        Optional<Instant> second = first.flatMap(occurrence -> calculator.nextAfter(
                event.rule(), event.dtstart(), zone, moments, dates, occurrence));
        return new Triggers(toTrigger(first, leadMinutes), toTrigger(second, leadMinutes));
    }

    /**
     * 库内行的下一次提醒时刻。已存的 {@code next_trigger_at} 就是「下一次会响」的权威值，
     * 只需在它之后求再下一次；因此先加回提前量进 occurrence 域，求完再减回来。
     */
    private Instant followingOfStored(ReminderRow row) {
        if (row.nextTriggerAt() == null) {
            return null;
        }
        int lead = row.leadMinutes();
        Instant occurrence = row.nextTriggerAt().plusSeconds(lead * 60L);
        return calculator.nextAfter(
                        rowRule(row), row.dtstart(), ZoneId.of(row.timezone()),
                        Set.copyOf(row.exdates()), Set.copyOf(row.exdateDates()), occurrence)
                .map(next -> next.minusSeconds(lead * 60L))
                .orElse(null);
    }

    /** 库内行的 {@code rrule} 由写入路径校验过，这里重新解析只为拿到结构化规则。 */
    private Rrule rowRule(ReminderRow row) {
        return rruleParser.parse(row.rrule());
    }

    private ReminderRow toRow(
            UUID userId,
            String requestKey,
            int leadMinutes,
            Instant now,
            IcsEvent event,
            UUID id,
            ReminderStatus status,
            ReminderRow previous,
            Instant nextTriggerAt) {
        return new ReminderRow(
                id,
                userId,
                event.summary(),
                event.description(),
                event.rrule(),
                event.dtstart(),
                event.timezone(),
                leadMinutes,
                event.exdates(),
                event.exdateDates(),
                status,
                SOURCE_TYPE_COURSE,
                event.uid(),
                event.sequence(),
                rowIdempotencyKey(requestKey, event.uid()),
                nextTriggerAt,
                previous == null ? null : previous.lastTriggeredAt(),
                previous == null ? 0L : previous.version(),
                previous == null ? now : previous.createdAt(),
                now);
    }

    /**
     * 一次导入涉及多行，请求级的 {@code Idempotency-Key} 不能直接落列（会撞
     * {@code uk_reminder_user_idempotency}），因此每行存 {@code sha256(请求键 | UID)}。
     */
    private static String rowIdempotencyKey(String requestKey, String uid) {
        return Fingerprints.sha256(requestKey + "|" + uid);
    }

    private static Instant toTrigger(Optional<Instant> occurrence, int leadMinutes) {
        return occurrence.map(value -> value.minusSeconds(leadMinutes * 60L)).orElse(null);
    }

    private record Triggers(Instant next, Instant following) {
    }
}
