package com.docqa.reminder.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.docqa.common.api.FieldViolation;
import com.docqa.common.error.BusinessException;
import com.docqa.common.error.ErrorCode;
import com.docqa.reminder.domain.IdempotencyOperation;
import com.docqa.reminder.domain.ScheduleImportAction;
import com.docqa.reminder.dto.ScheduleImportData;
import com.docqa.reminder.dto.ScheduleImportItemData;
import com.docqa.reminder.dto.SchedulePreviewData;
import com.docqa.reminder.schedule.IcsEvent;
import com.docqa.reminder.schedule.IcsParser;
import com.docqa.reminder.schedule.ScheduleDecision;
import com.docqa.reminder.schedule.ScheduleEvaluator;
import com.docqa.reminder.support.Fingerprints;
import com.docqa.reminder.support.IdempotencyKeys;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * ICS 课表导入与预览。两个操作共用同一条判定链（解析 → 逐事件判定），差别只在导入多了一次
 * 幂等占用与整批写入，所以「预览与导入判定同源」不是约定而是代码事实。
 *
 * <p>本服务只写 MySQL：不投递 MQ、不调用 Python。课表行与手工创建的规则同构，
 * 触发时刻由既有调度器按 {@code next_trigger_at} 物化列扫表得到。
 */
@Service
public class ScheduleImportService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ScheduleImportService.class);

    /** 与契约的单文件上限一致（2 MiB）；容器侧的 max-file-size 同值，两道判据都给 413。 */
    private static final int MAX_FILE_BYTES = 2 * 1024 * 1024;

    /**
     * 允许的 {@code file} 部分媒体类型。空值同样放行：浏览器对 {@code .ics} 给什么类型因内核而异，
     * 只认 {@code text/calendar} 会在 Safari 上稳定误拒；真正的格式合法性由解析器以 422 表达。
     */
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of("text/calendar", "application/octet-stream");

    /** 预览不写库，行上的幂等键只是占位；导入用的是请求键，落库前还会按行拼 UID。 */
    private static final String PREVIEW_REQUEST_KEY = "preview";

    private final IcsParser icsParser;
    private final ScheduleEvaluator evaluator;
    private final ScheduleImportWriter writer;
    private final IdempotencyStore idempotencyStore;
    private final RateLimiter rateLimiter;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ScheduleImportService(
            IcsParser icsParser,
            ScheduleEvaluator evaluator,
            ScheduleImportWriter writer,
            IdempotencyStore idempotencyStore,
            RateLimiter rateLimiter,
            ObjectMapper objectMapper,
            Clock clock) {
        this.icsParser = icsParser;
        this.evaluator = evaluator;
        this.writer = writer;
        this.idempotencyStore = idempotencyStore;
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public SchedulePreviewData preview(
            UUID userId,
            String idempotencyKey,
            IcsUpload upload,
            String fallbackTimezone,
            int leadMinutes) {
        // 契约规定预览接受但不消费幂等键：非法值仍 400，合法值用完即弃，不写幂等记录。
        if (idempotencyKey != null) {
            IdempotencyKeys.requireValid(idempotencyKey);
        }
        rateLimiter.check(userId);
        ZoneId zone = accept(upload, fallbackTimezone);
        List<ScheduleDecision> decisions = evaluator.evaluate(
                userId, PREVIEW_REQUEST_KEY, leadMinutes, parse(upload, zone));
        return new SchedulePreviewData(
                decisions.size(),
                count(decisions, ScheduleImportAction.CREATE),
                count(decisions, ScheduleImportAction.UPDATE),
                count(decisions, ScheduleImportAction.UNCHANGED),
                count(decisions, ScheduleImportAction.STALE),
                zone.getId(),
                leadMinutes,
                decisions.stream().map(decision -> toItem(decision, true)).toList());
    }

    public ImportScheduleResult importSchedule(
            UUID userId,
            String requestKey,
            IcsUpload upload,
            String fallbackTimezone,
            int leadMinutes) {
        String key = IdempotencyKeys.requireValid(requestKey);
        rateLimiter.check(userId);
        ZoneId zone = accept(upload, fallbackTimezone);
        String fingerprint = fingerprint(upload, zone.getId(), leadMinutes);

        Optional<IdempotencyRecord> existing =
                idempotencyStore.find(userId, IdempotencyOperation.SCHEDULE_IMPORT, key);
        if (existing.isPresent()) {
            return replay(existing.get(), fingerprint);
        }
        if (!idempotencyStore.tryStart(userId, IdempotencyOperation.SCHEDULE_IMPORT, key, fingerprint, clock.instant())) {
            IdempotencyRecord record = idempotencyStore.find(userId, IdempotencyOperation.SCHEDULE_IMPORT, key)
                    .orElseThrow(() -> new BusinessException(
                            ErrorCode.REM_CONFLICT_001,
                            "The same Idempotency-Key is still in progress"));
            return replay(record, fingerprint);
        }

        boolean finished = false;
        try {
            List<ScheduleDecision> decisions = evaluator.evaluate(
                    userId, key, leadMinutes, parse(upload, zone));
            persist(decisions);
            ScheduleImportData data = new ScheduleImportData(
                    decisions.size(),
                    count(decisions, ScheduleImportAction.CREATE),
                    count(decisions, ScheduleImportAction.UPDATE),
                    count(decisions, ScheduleImportAction.UNCHANGED),
                    count(decisions, ScheduleImportAction.STALE),
                    false,
                    zone.getId(),
                    leadMinutes,
                    decisions.stream().map(decision -> toItem(decision, false)).toList());
            completeIdempotency(userId, key, fingerprint, data);
            finished = true;
            return new ImportScheduleResult(data, true);
        } finally {
            if (!finished) {
                clearIdempotency(userId, key);
            }
        }
    }

    /** 准入三判据：文件媒体类型 415、大小 413、兜底时区 422。全部通过才允许进入解析与判定。 */
    private ZoneId accept(IcsUpload upload, String fallbackTimezone) {
        String contentType = baseType(upload.contentType());
        if (contentType != null && !ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new BusinessException(ErrorCode.REM_VALIDATION_003);
        }
        if (upload.content().length > MAX_FILE_BYTES) {
            throw new BusinessException(ErrorCode.REM_VALIDATION_002);
        }
        return fallbackZone(fallbackTimezone);
    }

    private List<IcsEvent> parse(IcsUpload upload, ZoneId fallbackZone) {
        return icsParser.parse(upload.content(), fallbackZone);
    }

    private void persist(List<ScheduleDecision> decisions) {
        try {
            writer.persist(decisions);
        } catch (DuplicateKeyException ex) {
            // 并发导入同一 UID 时 uk_reminder_user_source 命中；整批已回滚，重试即可，所以是可恢复的 409 而不是 500。
            throw new BusinessException(ErrorCode.REM_CONFLICT_002, null, ex);
        }
    }

    /**
     * 请求指纹：文件 SHA-256 与两个判定参数。用文件摘要而不是上传元数据，
     * 因为「同一份课表 + 同一份参数」才是重放判据；换浏览器、换文件名都不该被当成新请求。
     */
    private String fingerprint(IcsUpload upload, String fallbackTimezone, int leadMinutes) {
        return Fingerprints.sha256(Fingerprints.sha256(upload.content())
                + "|" + fallbackTimezone
                + "|" + leadMinutes);
    }

    private ScheduleImportItemData toItem(ScheduleDecision decision, boolean preview) {
        IcsEvent event = decision.event();
        return new ScheduleImportItemData(
                event.uid(),
                event.summary(),
                event.sequence(),
                decision.currentSequence(),
                decision.action().name(),
                event.dtstart(),
                event.timezone(),
                event.rrule(),
                event.exdateValues(),
                decision.nextTriggerAt(),
                decision.followingTriggerAt(),
                // 契约：CREATE 在预览里还没有落库，reminderId 必须是 null；导入响应才给出新生成的 ID。
                preview && decision.action() == ScheduleImportAction.CREATE ? null : decision.reminderId(),
                event.description());
    }

    private static int count(List<ScheduleDecision> decisions, ScheduleImportAction action) {
        return (int) decisions.stream().filter(decision -> decision.action() == action).count();
    }

    private ZoneId fallbackZone(String value) {
        try {
            ZoneId zone = ZoneId.of(value);
            if (zone instanceof ZoneOffset) {
                throw invalid("fallbackTimezone", "must be an IANA region such as Asia/Shanghai");
            }
            return zone;
        } catch (DateTimeException ex) {
            throw invalid("fallbackTimezone", "unknown IANA timezone: " + value);
        }
    }

    private static String baseType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return null;
        }
        int separator = contentType.indexOf(';');
        String value = separator < 0 ? contentType : contentType.substring(0, separator);
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private BusinessException invalid(String field, String message) {
        return new BusinessException(
                ErrorCode.REM_VALIDATION_001,
                null,
                List.of(FieldViolation.of(field, message)));
    }

    private ImportScheduleResult replay(IdempotencyRecord record, String fingerprint) {
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
            ScheduleImportData data = objectMapper.readValue(record.responseJson(), ScheduleImportData.class);
            return new ImportScheduleResult(data.withIdempotentReplay(), false);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(
                    ErrorCode.COM_INTERNAL_001,
                    "Idempotency record is unreadable, please retry later",
                    ex);
        }
    }

    private void completeIdempotency(UUID userId, String key, String fingerprint, ScheduleImportData data) {
        try {
            // 落进幂等记录的形态已经丢掉明细：重放只还原计数摘要，需要明细时调用方重新预览。
            String responseJson = objectMapper.writeValueAsString(data.withoutItems());
            idempotencyStore.complete(
                    userId,
                    IdempotencyOperation.SCHEDULE_IMPORT,
                    key,
                    fingerprint,
                    responseJson,
                    clock.instant());
        } catch (JsonProcessingException ex) {
            LOGGER.error("Unable to serialize schedule import idempotency response: itemCount={}",
                    data.itemCount());
            clearIdempotency(userId, key);
        } catch (RuntimeException ex) {
            LOGGER.warn("Unable to store schedule import idempotency response: itemCount={}", data.itemCount());
            clearIdempotency(userId, key);
        }
    }

    private void clearIdempotency(UUID userId, String key) {
        try {
            idempotencyStore.clear(userId, IdempotencyOperation.SCHEDULE_IMPORT, key);
        } catch (RuntimeException ex) {
            LOGGER.warn("Unable to clear schedule import idempotency record");
        }
    }
}
