package com.docqa.reminder.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.docqa.common.api.FieldViolation;
import com.docqa.common.error.BusinessException;
import com.docqa.common.error.ErrorCode;
import com.docqa.common.tenant.TenantContext;
import com.docqa.reminder.domain.IdempotencyOperation;
import com.docqa.reminder.domain.ReminderRow;
import com.docqa.reminder.domain.ReminderStatus;
import com.docqa.reminder.domain.ScheduleImportAction;
import com.docqa.reminder.dto.ScheduleImportData;
import com.docqa.reminder.dto.SchedulePreviewData;
import com.docqa.reminder.recurrence.RruleParser;
import com.docqa.reminder.schedule.IcsEvent;
import com.docqa.reminder.schedule.IcsParser;
import com.docqa.reminder.schedule.ScheduleDecision;
import com.docqa.reminder.schedule.ScheduleEvaluator;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

/**
 * 课表导入与预览的服务编排：准入判据、幂等占用、整批写入与重放还原。
 * 解析与判定本身各有专测，这里用 mock 把它们固定成可控输入，只观察编排顺序与契约承诺。
 */
class ScheduleImportServiceTest {

    private static final UUID TENANT_ID = TenantContext.DEFAULT_TENANT_ID;
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID REMINDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant MONDAY = Instant.parse("2026-09-07T00:00:00Z");
    private static final String UID = "linear-algebra-2026-fall-mon-0800";
    private static final String KEY = "idem-key-0001";
    private static final String ZONE = "Asia/Shanghai";
    private static final int TWO_MIB = 2 * 1024 * 1024;

    private final IcsParser parser = mock(IcsParser.class);
    private final ScheduleEvaluator evaluator = mock(ScheduleEvaluator.class);
    private final ScheduleImportWriter writer = mock(ScheduleImportWriter.class);
    private final IdempotencyStore idempotencyStore = mock(IdempotencyStore.class);
    private final RateLimiter rateLimiter = mock(RateLimiter.class);

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final byte[] file = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\n".getBytes(StandardCharsets.UTF_8);
    private ScheduleImportService service;

    @BeforeEach
    void setUp() {
        service = new ScheduleImportService(parser, evaluator, writer, idempotencyStore, rateLimiter,
                objectMapper, Clock.fixed(NOW, ZoneId.of("UTC")));
        TenantContext.set(TENANT_ID);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void previewAcceptsButDoesNotConsumeAWellFormedIdempotencyKey() {
        givenDecisions(decision(ScheduleImportAction.CREATE, 1, null));

        SchedulePreviewData data = service.preview(USER_ID, KEY, upload("text/calendar"), ZONE, 10);

        assertThat(data.itemCount()).isEqualTo(1);
        verifyNoInteractions(idempotencyStore);
    }

    @Test
    void previewRejectsAMalformedIdempotencyKeyWithoutTouchingTheFile() {
        assertThatThrownBy(() -> service.preview(USER_ID, "short", upload("text/calendar"), ZONE, 10))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.COM_VALIDATION_001);
                    assertThat(ex.getFieldViolations()).extracting(FieldViolation::field)
                            .containsExactly("Idempotency-Key");
                });
        verifyNoInteractions(parser, evaluator, idempotencyStore);
    }

    @Test
    void previewCountsAllFourOutcomesAndHidesOnlyThePendingCreateIdentity() {
        givenDecisions(
                decision(ScheduleImportAction.CREATE, 3, null),
                decision(ScheduleImportAction.UPDATE, 5, 4),
                decision(ScheduleImportAction.UNCHANGED, 3, 3),
                decision(ScheduleImportAction.STALE, 1, 4));

        SchedulePreviewData data = service.preview(USER_ID, null, upload("text/calendar"), ZONE, 10);

        assertThat(data.createCount()).isEqualTo(1);
        assertThat(data.updateCount()).isEqualTo(1);
        assertThat(data.unchangedCount()).isEqualTo(1);
        assertThat(data.staleCount()).isEqualTo(1);
        // CREATE 还没落库，预览不能承诺一个 ID；其余三种都指向库里已有的那一行。
        assertThat(data.items()).first().satisfies(item -> {
            assertThat(item.action()).isEqualTo("CREATE");
            assertThat(item.reminderId()).isNull();
        });
        assertThat(data.items()).extracting(item -> item.reminderId() == null).containsExactly(true, false, false, false);
    }

    @Test
    void oversizedUploadIsRejectedBeforeTheParserSeesAByte() {
        byte[] huge = new byte[TWO_MIB + 1];

        assertThatThrownBy(() -> service.preview(USER_ID, null,
                new IcsUpload("text/calendar", huge), ZONE, 10))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.REM_VALIDATION_002));
        verifyNoInteractions(parser);
    }

    @Test
    void exactlyTwoMebibytesIsStillLegal() {
        givenDecisions();

        service.preview(USER_ID, null, new IcsUpload("text/calendar", new byte[TWO_MIB]), ZONE, 10);

        verify(parser).parse(any(), any());
    }

    @Test
    void onlyTheFilePartsContentTypeIsJudgedAndParametersAreIgnored() {
        givenDecisions();

        service.preview(USER_ID, null, upload("text/calendar;charset=utf-8"), ZONE, 10);
        service.preview(USER_ID, null, upload("application/octet-stream"), ZONE, 10);
        service.preview(USER_ID, null, upload(null), ZONE, 10);
        service.preview(USER_ID, null, upload(""), ZONE, 10);

        verify(parser, org.mockito.Mockito.times(4)).parse(any(), any());
    }

    @Test
    void aNonCalendarFileTypeIsRefusedWith415() {
        assertThatThrownBy(() -> service.preview(USER_ID, null, upload("application/pdf"), ZONE, 10))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.REM_VALIDATION_003);
                    assertThat(ex.getFieldViolations()).isEmpty();
                });
        verifyNoInteractions(parser);
    }

    @Test
    void anUnusableFallbackTimezoneIsRefusedWith422NamingThePart() {
        assertThatThrownBy(() -> service.preview(USER_ID, null, upload("text/calendar"), "Mars/Phobos", 10))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.REM_VALIDATION_001);
                    assertThat(ex.getFieldViolations()).extracting(FieldViolation::field)
                            .containsExactly("fallbackTimezone");
                });
        // 偏移量不是区名：它无法还原夏令时规则，date-only 停课也就无法按当地日期判重。
        assertThatThrownBy(() -> service.preview(USER_ID, null, upload("text/calendar"), "+08:00", 10))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getFieldViolations()).extracting(FieldViolation::field)
                                .containsExactly("fallbackTimezone"));
        verifyNoInteractions(parser);
    }

    @Test
    void theRateLimitIsJudgedBeforeTheFileIsTouched() {
        org.mockito.Mockito.doThrow(new RateLimitExceededException(30))
                .when(rateLimiter).check(USER_ID);

        assertThatThrownBy(() -> service.preview(USER_ID, null, upload("text/calendar"), ZONE, 10))
                .isInstanceOf(RateLimitExceededException.class);
        verifyNoInteractions(parser, evaluator);
    }

    @Test
    void firstImportPersistsTheBatchAndIsReportedAsCreated() {
        givenIdempotencyAbsent();
        givenDecisions(decision(ScheduleImportAction.CREATE, 3, null));

        ImportScheduleResult result = service.importSchedule(USER_ID, KEY, upload("text/calendar"), ZONE, 10);

        assertThat(result.created()).isTrue();
        assertThat(result.data().idempotentReplay()).isFalse();
        assertThat(result.data().items()).singleElement()
                .satisfies(item -> assertThat(item.reminderId()).isEqualTo(REMINDER_ID));
        verify(writer).persist(eq(TENANT_ID), anyList());
    }

    @Test
    void theIdempotencyRecordStoresOnlyTheCountSummary() throws Exception {
        givenIdempotencyAbsent();
        givenDecisions(decision(ScheduleImportAction.CREATE, 3, null));

        service.importSchedule(USER_ID, KEY, upload("text/calendar"), ZONE, 10);

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(idempotencyStore).complete(eq(USER_ID), eq(IdempotencyOperation.SCHEDULE_IMPORT),
                eq(KEY), anyString(), json.capture(), eq(NOW));
        ScheduleImportData stored = objectMapper.readValue(json.getValue(), ScheduleImportData.class);
        assertThat(stored.items()).isEmpty();
        assertThat(stored.itemCount()).isEqualTo(1);
        // 500 条项目明细约为摘要的百倍，重放时不值得把它们再取一遍；需要明细就重新预览。
        assertThat(json.getValue()).doesNotContain(UID);
    }

    @Test
    void replayRestoresTheFirstResultWithoutASecondWrite() throws Exception {
        ScheduleImportData stored = new ScheduleImportData(3, 1, 1, 0, 1, false, ZONE, 10, List.of());
        String fingerprint = fingerprintOf(file, ZONE, 10);
        when(idempotencyStore.find(USER_ID, IdempotencyOperation.SCHEDULE_IMPORT, KEY))
                .thenReturn(Optional.of(new IdempotencyRecord(
                        IdempotencyStatus.COMPLETED, fingerprint,
                        objectMapper.writeValueAsString(stored), NOW)));

        ImportScheduleResult result = service.importSchedule(USER_ID, KEY, upload("text/calendar"), ZONE, 10);

        assertThat(result.created()).isFalse();
        assertThat(result.data().idempotentReplay()).isTrue();
        assertThat(result.data().items()).isEmpty();
        assertThat(result.data().itemCount()).isEqualTo(3);
        verifyNoInteractions(parser, evaluator, writer);
        verify(idempotencyStore, never()).tryStart(any(), any(), any(), any(), any());
    }

    @Test
    void theSameKeyWithADifferentRequestConflicts() {
        when(idempotencyStore.find(USER_ID, IdempotencyOperation.SCHEDULE_IMPORT, KEY))
                .thenReturn(Optional.of(new IdempotencyRecord(
                        IdempotencyStatus.COMPLETED, "other-fingerprint", "{}", NOW)));

        assertThatThrownBy(() -> service.importSchedule(USER_ID, KEY, upload("text/calendar"), ZONE, 10))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.REM_CONFLICT_001));
        verifyNoInteractions(writer);
    }

    @Test
    void theSameKeyWhileAnotherAttemptIsInProgressConflicts() {
        when(idempotencyStore.find(USER_ID, IdempotencyOperation.SCHEDULE_IMPORT, KEY))
                .thenReturn(Optional.of(new IdempotencyRecord(
                        IdempotencyStatus.IN_PROGRESS, "f", null, NOW)));

        assertThatThrownBy(() -> service.importSchedule(USER_ID, KEY, upload("text/calendar"), ZONE, 10))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.REM_CONFLICT_001));
        verifyNoInteractions(writer);
    }

    @Test
    void losingTheClaimRaceFallsBackToReplay() throws Exception {
        ScheduleImportData stored = new ScheduleImportData(1, 1, 0, 0, 0, false, ZONE, 10, List.of());
        String fingerprint = fingerprintOf(file, ZONE, 10);
        when(idempotencyStore.find(USER_ID, IdempotencyOperation.SCHEDULE_IMPORT, KEY))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(new IdempotencyRecord(
                        IdempotencyStatus.COMPLETED, fingerprint,
                        objectMapper.writeValueAsString(stored), NOW)));
        when(idempotencyStore.tryStart(USER_ID, IdempotencyOperation.SCHEDULE_IMPORT, KEY, fingerprint, NOW))
                .thenReturn(false);

        ImportScheduleResult result = service.importSchedule(USER_ID, KEY, upload("text/calendar"), ZONE, 10);

        assertThat(result.created()).isFalse();
        assertThat(result.data().idempotentReplay()).isTrue();
        verifyNoInteractions(writer);
    }

    @Test
    void aConcurrentImportOfTheSameCourseReturnsConflictAndFreesTheSlot() {
        givenIdempotencyAbsent();
        givenDecisions(decision(ScheduleImportAction.CREATE, 3, null));
        doThrow(new DuplicateKeyException("uk_reminder_user_source"))
                .when(writer).persist(eq(TENANT_ID), anyList());

        assertThatThrownBy(() -> service.importSchedule(USER_ID, KEY, upload("text/calendar"), ZONE, 10))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.REM_CONFLICT_002));
        // 整批已回滚且重试可能成功，所以占位必须释放，否则 60 秒内的重试都只会撞到 IN_PROGRESS。
        verify(idempotencyStore).clear(USER_ID, IdempotencyOperation.SCHEDULE_IMPORT, KEY);
        verify(idempotencyStore, never()).complete(any(), any(), any(), any(), any(), any());
    }

    @Test
    void theFingerprintCoversTheFileAndBothJudgementParameters() {
        givenIdempotencyAbsent();
        givenDecisions();

        service.importSchedule(USER_ID, KEY, upload("text/calendar"), ZONE, 10);
        service.importSchedule(USER_ID, KEY, upload("text/calendar"), ZONE, 15);

        ArgumentCaptor<String> fingerprints = ArgumentCaptor.forClass(String.class);
        verify(idempotencyStore, org.mockito.Mockito.times(2)).tryStart(
                eq(USER_ID), eq(IdempotencyOperation.SCHEDULE_IMPORT), eq(KEY),
                fingerprints.capture(), eq(NOW));
        assertThat(fingerprints.getAllValues()).doesNotHaveDuplicates();
        assertThat(fingerprints.getAllValues().get(0)).isEqualTo(fingerprintOf(file, ZONE, 10));
    }

    private void givenIdempotencyAbsent() {
        when(idempotencyStore.find(USER_ID, IdempotencyOperation.SCHEDULE_IMPORT, KEY))
                .thenReturn(Optional.empty());
        when(idempotencyStore.tryStart(eq(USER_ID), eq(IdempotencyOperation.SCHEDULE_IMPORT),
                eq(KEY), anyString(), eq(NOW))).thenReturn(true);
    }

    private void givenDecisions(ScheduleDecision... decisions) {
        when(parser.parse(any(), any())).thenReturn(List.of());
        when(evaluator.evaluate(any(), any(), anyString(), anyInt(), anyList())).thenReturn(List.of(decisions));
    }

    private IcsUpload upload(String contentType) {
        return new IcsUpload(contentType, file);
    }

    /** 与 ScheduleImportService.fingerprint 同一算法，用来断言指纹覆盖的到底是什么。 */
    private static String fingerprintOf(byte[] content, String timezone, int leadMinutes) {
        return com.docqa.reminder.support.Fingerprints.sha256(
                com.docqa.reminder.support.Fingerprints.sha256(content)
                        + "|" + timezone + "|" + leadMinutes);
    }

    private static ScheduleDecision decision(ScheduleImportAction action, int fileSequence, Integer stored) {
        IcsEvent event = new IcsEvent(UID, "线性代数", "说明", fileSequence,
                "FREQ=WEEKLY;BYDAY=MO", new RruleParser().parse("FREQ=WEEKLY;BYDAY=MO"),
                MONDAY, ZONE, List.of(), List.of());
        ReminderRow row = action == ScheduleImportAction.UNCHANGED || action == ScheduleImportAction.STALE
                ? null
                : new ReminderRow(REMINDER_ID, USER_ID, "线性代数", "说明", "FREQ=WEEKLY;BYDAY=MO",
                        MONDAY, ZONE, 10, List.of(), List.of(), ReminderStatus.ACTIVE,
                        "COURSE", UID, fileSequence, "row-key", MONDAY, null, 0L, NOW, NOW);
        return new ScheduleDecision(event, action, stored, REMINDER_ID,
                MONDAY.minusSeconds(600), MONDAY.plusSeconds(604800).minusSeconds(600), row);
    }
}
