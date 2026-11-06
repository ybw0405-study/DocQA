package com.docqa.reminder.service;

import com.docqa.common.trace.TraceIdContext;
import com.docqa.reminder.config.RocketMqProperties;
import com.docqa.reminder.domain.ReminderTriggerRow;
import com.docqa.reminder.messaging.MqIdempotencyMarker;
import com.docqa.reminder.messaging.ReminderDueEvent;
import com.docqa.reminder.messaging.ReminderDueRejectedException;
import com.docqa.reminder.repository.ReminderTriggerRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * {@code reminder-due} 的落库侧：一条已发布的触发写成 {@code reminder_trigger} 的一行，
 * 这一行同时就是站内通知（通知只是该行的 {@code read_at} 属性）。
 *
 * <p>三层幂等中本服务负责两层——{@code mq:idem} 加速与唯一键收敛（发布侧的 fired 标记在
 * {@code ReminderDueService} 里）。判定「是否已处理」只认 {@code (reminderId, plannedTriggerAt)}
 * 这个业务事实，不认 {@code eventId} 与 MQ {@code msgId}：发布失败后重发同一次 occurrence 时
 * 两个标识都是新值，拿它们判重会让用户为同一节课收到两条通知。
 */
@Service
public class ReminderTriggerIngestService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReminderTriggerIngestService.class);

    private static final Set<String> SOURCE_TYPES = Set.of("CUSTOM", "DEADLINE", "COURSE");
    private static final int MAX_TITLE_LENGTH = 200;
    private static final int MAX_DESCRIPTION_LENGTH = 1000;

    private final ReminderTriggerRepository reminderTriggerRepository;
    private final MqIdempotencyMarker idempotencyMarker;
    private final RocketMqProperties rocketMqProperties;
    private final Clock clock;

    public ReminderTriggerIngestService(
            ReminderTriggerRepository reminderTriggerRepository,
            MqIdempotencyMarker idempotencyMarker,
            RocketMqProperties rocketMqProperties,
            Clock clock) {
        this.reminderTriggerRepository = reminderTriggerRepository;
        this.idempotencyMarker = idempotencyMarker;
        this.rocketMqProperties = rocketMqProperties;
        this.clock = clock;
    }

    public void ingest(ReminderDueEvent event, String messageId) {
        validate(event);
        String traceId = resolveTraceId(event);
        String topic = rocketMqProperties.getDueTopic();
        if (messageId != null && !messageId.isBlank() && idempotencyMarker.alreadyProcessed(topic, messageId)) {
            LOGGER.debug("Reminder due message already processed: reminderId={}, traceId={}",
                    event.reminderId(), traceId);
            return;
        }

        // 与 delivered_at 同口径截断到秒：chk_reminder_trigger_read 要求 read_at >= delivered_at，
        // 带毫秒的落库时刻会让「同一秒内点开通知」撞上约束。
        Instant deliveredAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        ReminderTriggerRow row = new ReminderTriggerRow(
                UUID.randomUUID(),
                event.userId(),
                event.reminderId(),
                event.title(),
                event.description(),
                event.sourceType(),
                event.occurrenceStartUtc(),
                event.nextTriggerAt(),
                traceId,
                deliveredAt,
                null,
                deliveredAt,
                deliveredAt);
        try {
            reminderTriggerRepository.insert(row);
        } catch (DuplicateKeyException ex) {
            // 重投或发布失败后的重发：通知早已存在，不刷新 delivered_at，按「已处理」静默收敛。
            LOGGER.info("Reminder trigger already recorded; acknowledging duplicate: reminderId={}, traceId={}",
                    event.reminderId(), traceId);
            return;
        }
        if (messageId != null && !messageId.isBlank()) {
            idempotencyMarker.markProcessed(topic, messageId);
        }
        LOGGER.info("Reminder trigger stored: notificationId={}, reminderId={}, traceId={}",
                row.id(), event.reminderId(), traceId);
    }

    private void validate(ReminderDueEvent event) {
        if (event == null) {
            throw new ReminderDueRejectedException("event body is null");
        }
        require(event.eventId() != null, "eventId");
        require(event.reminderId() != null, "reminderId");
        require(event.userId() != null, "userId");
        require(event.sourceType() != null && SOURCE_TYPES.contains(event.sourceType()), "sourceType");
        require(event.rrule() != null && !event.rrule().isBlank(), "rrule");
        require(event.timezone() != null && !event.timezone().isBlank(), "timezone");
        require(event.occurrenceStartUtc() != null, "occurrenceStartUtc");
        require(event.occurredAt() != null, "occurredAt");
        require(event.title() != null && !event.title().isBlank(), "title");
        require(event.title().length() <= MAX_TITLE_LENGTH, "title length");
        require(event.description() == null || event.description().length() <= MAX_DESCRIPTION_LENGTH,
                "description length");
    }

    /**
     * 载荷的 {@code traceId} 是必填且必须是 32 位十六进制——它要落进 {@code CHAR(32) NOT NULL} 的列。
     * 非法即拒绝，不生成新 trace 顶替：那样会让一条通知追回不到任何调度扫描，把数据问题洗成看起来正常的行。
     */
    private String resolveTraceId(ReminderDueEvent event) {
        if (!TraceIdContext.isValid(event.traceId())) {
            throw new ReminderDueRejectedException("traceId must be 32 lowercase hex characters");
        }
        return event.traceId();
    }

    private static void require(boolean condition, String field) {
        if (!condition) {
            throw new ReminderDueRejectedException("required field is missing or invalid: " + field);
        }
    }
}
