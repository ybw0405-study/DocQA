package com.docqa.reminder.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.docqa.common.api.FieldViolation;
import com.docqa.common.error.BusinessException;
import com.docqa.common.error.ErrorCode;
import com.docqa.common.trace.TraceIdContext;
import com.docqa.common.web.GlobalExceptionHandler;
import com.docqa.common.web.TraceIdFilter;
import com.docqa.reminder.domain.ReminderStatus;
import com.docqa.reminder.dto.CustomReminderCreateRequest;
import com.docqa.reminder.dto.ReminderCreateData;
import com.docqa.reminder.dto.ReminderCreateRequest;
import com.docqa.reminder.dto.ReminderListData;
import com.docqa.reminder.dto.ReminderListItemData;
import com.docqa.reminder.dto.ReminderStatusUpdateData;
import com.docqa.reminder.dto.ReminderStatusUpdateRequest;
import com.docqa.reminder.service.CreateReminderResult;
import com.docqa.reminder.service.RateLimitExceededException;
import com.docqa.reminder.service.ReminderService;
import com.docqa.reminder.service.ReminderStatusService;
import com.docqa.reminder.web.JwtAuthenticationFilter;
import com.docqa.reminder.web.ReminderExceptionHandler;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class ReminderControllerTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID REMINDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final String IDEMPOTENCY_KEY = "idem-key-0001";
    private static final String TRACE_ID_PATTERN = "^[0-9a-f]{32}$";
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    @Mock
    private ReminderService reminderService;

    @Mock
    private ReminderStatusService reminderStatusService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @BeforeEach
    void setUp() {
        objectMapper.addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class);
        MappingJackson2HttpMessageConverter converter =
                new MappingJackson2HttpMessageConverter(objectMapper);
        converter.setSupportedMediaTypes(
                List.of(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON));
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ReminderController(reminderService, reminderStatusService))
                .setControllerAdvice(new ReminderExceptionHandler(), new GlobalExceptionHandler())
                .addFilters(new TraceIdFilter())
                .setMessageConverters(converter)
                .build();
    }

    @AfterEach
    void tearDown() {
        TraceIdContext.clear();
    }

    @Test
    void createDeadlineReturnsCreated() throws Exception {
        when(reminderService.create(eq(USER_ID), eq(IDEMPOTENCY_KEY), any()))
                .thenReturn(new CreateReminderResult(data(false), true));

        mockMvc.perform(post("/api/v1/reminders")
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"kind":"DEADLINE","title":"提交线性代数作业",\
                                "description":"第三章习题","timezone":"Asia/Shanghai",\
                                "dueAt":"2026-09-01T02:00:00Z","leadMinutes":120}
                                """))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.reminderId").value(REMINDER_ID.toString()))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.kind").value("DEADLINE"))
                .andExpect(jsonPath("$.data.rrule").value("FREQ=DAILY;COUNT=1"))
                .andExpect(jsonPath("$.data.dtstart").value("2026-09-01T00:00:00Z"))
                .andExpect(jsonPath("$.data.timezone").value("Asia/Shanghai"))
                .andExpect(jsonPath("$.data.nextTriggerAt").value("2026-09-01T00:00:00Z"))
                .andExpect(jsonPath("$.data.version").value(0))
                .andExpect(jsonPath("$.data.idempotentReplay").value(false))
                .andExpect(jsonPath("$.traceId").value(matchesPattern(TRACE_ID_PATTERN)));
    }

    @Test
    void replayReturnsOk() throws Exception {
        ReminderCreateData replay = data(true);
        when(reminderService.create(eq(USER_ID), eq(IDEMPOTENCY_KEY), any()))
                .thenReturn(new CreateReminderResult(replay, false));

        mockMvc.perform(post("/api/v1/reminders")
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"kind":"DEADLINE","title":"提交线性代数作业",\
                                "timezone":"Asia/Shanghai","dueAt":"2026-09-01T02:00:00Z"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.idempotentReplay").value(true));
    }

    @Test
    void customRequestDeserializesRuleAndExdates() throws Exception {
        when(reminderService.create(eq(USER_ID), eq(IDEMPOTENCY_KEY), any()))
                .thenReturn(new CreateReminderResult(data(false), true));

        mockMvc.perform(post("/api/v1/reminders")
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"kind":"CUSTOM","title":"高等数学","timezone":"Asia/Shanghai",\
                                "rrule":"FREQ=WEEKLY;BYDAY=MO;UNTIL=20261231T235959Z",\
                                "dtstart":"2026-09-07T00:00:00Z",\
                                "exdates":["2026-10-05T00:00:00Z"]}
                                """))
                .andExpect(status().isCreated());

        ArgumentCaptor<ReminderCreateRequest> captor =
                ArgumentCaptor.forClass(ReminderCreateRequest.class);
        verify(reminderService).create(eq(USER_ID), eq(IDEMPOTENCY_KEY), captor.capture());
        assertThat(captor.getValue()).isInstanceOf(CustomReminderCreateRequest.class);
        CustomReminderCreateRequest request = (CustomReminderCreateRequest) captor.getValue();
        assertThat(request.rrule()).isEqualTo("FREQ=WEEKLY;BYDAY=MO;UNTIL=20261231T235959Z");
        assertThat(request.dtstart()).isEqualTo(Instant.parse("2026-09-07T00:00:00Z"));
        assertThat(request.exdates()).containsExactly(Instant.parse("2026-10-05T00:00:00Z"));
    }

    @Test
    void missingIdempotencyKeyReturnsValidationProblem() throws Exception {
        mockMvc.perform(post("/api/v1/reminders")
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"kind":"DEADLINE","title":"作业","timezone":"Asia/Shanghai",\
                                "dueAt":"2026-09-01T02:00:00Z"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("COM-VALIDATION-001"))
                .andExpect(jsonPath("$.errors[0].field").value("Idempotency-Key"));
    }

    @Test
    void missingDeadlineFieldReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/reminders")
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"kind":"DEADLINE","title":"作业","timezone":"Asia/Shanghai"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COM-VALIDATION-001"))
                .andExpect(jsonPath("$.errors[0].field").value("dueAt"));
    }

    @Test
    void unknownFieldReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/reminders")
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"kind":"DEADLINE","title":"作业","timezone":"Asia/Shanghai",\
                                "dueAt":"2026-09-01T02:00:00Z","unexpected":true}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COM-VALIDATION-001"));
    }

    @Test
    void invalidTimestampReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/reminders")
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"kind":"DEADLINE","title":"作业","timezone":"Asia/Shanghai",\
                                "dueAt":"not-a-date"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COM-VALIDATION-001"));
    }

    @Test
    void idempotencyConflictReturnsConflict() throws Exception {
        when(reminderService.create(eq(USER_ID), eq(IDEMPOTENCY_KEY), any()))
                .thenThrow(new BusinessException(ErrorCode.REM_CONFLICT_001));

        mockMvc.perform(post("/api/v1/reminders")
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"kind":"DEADLINE","title":"作业","timezone":"Asia/Shanghai",\
                                "dueAt":"2026-09-01T02:00:00Z"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("REM-CONFLICT-001"));
    }

    @Test
    void invalidRuleReturnsUnprocessableEntity() throws Exception {
        when(reminderService.create(eq(USER_ID), eq(IDEMPOTENCY_KEY), any()))
                .thenThrow(new BusinessException(
                        ErrorCode.REM_VALIDATION_001,
                        null,
                        List.of(new FieldViolation("rrule", "unsupported"))));

        mockMvc.perform(post("/api/v1/reminders")
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"kind":"CUSTOM","title":"高等数学","timezone":"Asia/Shanghai",\
                                "rrule":"FREQ=HOURLY","dtstart":"2026-09-07T00:00:00Z"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("REM-VALIDATION-001"))
                .andExpect(jsonPath("$.errors[0].field").value("rrule"));
    }

    @Test
    void rateLimitReturnsTooManyRequestsWithRetryAfter() throws Exception {
        when(reminderService.create(eq(USER_ID), eq(IDEMPOTENCY_KEY), any()))
                .thenThrow(new RateLimitExceededException(42));

        mockMvc.perform(post("/api/v1/reminders")
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"kind":"DEADLINE","title":"作业","timezone":"Asia/Shanghai",\
                                "dueAt":"2026-09-01T02:00:00Z"}
                                """))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "42"))
                .andExpect(jsonPath("$.code").value("COM-RATE_LIMIT-001"));
    }

    @Test
    void listReturnsTheDashboardEnvelope() throws Exception {
        when(reminderService.list(USER_ID, null, null, 20)).thenReturn(new ReminderListData(
                List.of(new ReminderListItemData(
                        REMINDER_ID, "DEADLINE", "ACTIVE", "提交线性代数作业", null,
                        "FREQ=WEEKLY;BYDAY=MO;UNTIL=20261231T160000Z", NOW, "Asia/Shanghai",
                        List.of(NOW.plusSeconds(86400)), 0, null, null, 3L, NOW, NOW)),
                null,
                20));

        mockMvc.perform(get("/api/v1/reminders")
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.msg").value("success"))
                .andExpect(jsonPath("$.data.limit").value(20))
                .andExpect(jsonPath("$.data.items[0].reminderId").value(REMINDER_ID.toString()))
                .andExpect(jsonPath("$.data.items[0].kind").value("DEADLINE"))
                .andExpect(jsonPath("$.data.items[0].exdates.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].leadMinutes").value(0))
                .andExpect(jsonPath("$.data.items[0].nextTriggerAt").value(nullValue()))
                .andExpect(jsonPath("$.data.nextCursor").value(nullValue()))
                .andExpect(jsonPath("$.traceId").value(matchesPattern(TRACE_ID_PATTERN)));
    }

    @Test
    void listPassesStatusCursorAndLimitToTheService() throws Exception {
        when(reminderService.list(eq(USER_ID), eq("PAUSED"), eq("cursor-token"), eq(50)))
                .thenReturn(new ReminderListData(List.of(), "next-token", 50));

        mockMvc.perform(get("/api/v1/reminders")
                        .param("status", "PAUSED")
                        .param("cursor", "cursor-token")
                        .param("limit", "50")
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.nextCursor").value("next-token"));
    }

    @Test
    void listRejectsUnsupportedStatusFilter() throws Exception {
        when(reminderService.list(eq(USER_ID), eq("SOMETHING"), isNull(), eq(20)))
                .thenThrow(new BusinessException(ErrorCode.COM_VALIDATION_001, "unknown status"));

        mockMvc.perform(get("/api/v1/reminders")
                        .param("status", "SOMETHING")
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("COM-VALIDATION-001"));
    }

    @Test
    void statusUpdateReturnsTheAdvancedVersion() throws Exception {
        when(reminderStatusService.update(eq(USER_ID), eq(REMINDER_ID), eq(IDEMPOTENCY_KEY), any()))
                .thenReturn(new ReminderStatusUpdateData(
                        REMINDER_ID, "PAUSED", NOW, 4L, NOW, false));

        mockMvc.perform(patch("/api/v1/reminders/" + REMINDER_ID)
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PAUSED\",\"version\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.reminderId").value(REMINDER_ID.toString()))
                .andExpect(jsonPath("$.data.status").value("PAUSED"))
                .andExpect(jsonPath("$.data.nextTriggerAt").value("2026-09-01T00:00:00Z"))
                .andExpect(jsonPath("$.data.version").value(4))
                .andExpect(jsonPath("$.data.idempotentReplay").value(false))
                .andExpect(jsonPath("$.traceId").value(matchesPattern(TRACE_ID_PATTERN)));
    }

    @Test
    void aNoOpUpdateIsTwentyOhRatherThanAConflict() throws Exception {
        // 契约的「同态提交」：写入与 no-op 共用 200，客户端靠 version 是否 +1 分辨。
        when(reminderStatusService.update(eq(USER_ID), eq(REMINDER_ID), eq(IDEMPOTENCY_KEY), any()))
                .thenReturn(new ReminderStatusUpdateData(REMINDER_ID, "ACTIVE", NOW, 3L, NOW, false));

        mockMvc.perform(patch("/api/v1/reminders/" + REMINDER_ID)
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\",\"version\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(3));
    }

    @Test
    void statusUpdateBindsThePathIdentityAndTheHeaderIntoTheService() throws Exception {
        mockMvc.perform(patch("/api/v1/reminders/" + REMINDER_ID)
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PAUSED\",\"version\":3}"))
                .andExpect(status().isOk());

        verify(reminderStatusService).update(
                USER_ID, REMINDER_ID, IDEMPOTENCY_KEY,
                new ReminderStatusUpdateRequest(ReminderStatus.PAUSED, 3L));
    }

    @Test
    void statusUpdateRequiresAnIdempotencyKey() throws Exception {
        mockMvc.perform(patch("/api/v1/reminders/" + REMINDER_ID)
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PAUSED\",\"version\":3}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COM-VALIDATION-001"))
                .andExpect(jsonPath("$.errors[0].field").value("Idempotency-Key"));
    }

    @Test
    void statusUpdateRejectsAMissingVersion() throws Exception {
        mockMvc.perform(patch("/api/v1/reminders/" + REMINDER_ID)
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PAUSED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("version"));
    }

    @Test
    void statusUpdateRejectsANegativeVersion() throws Exception {
        mockMvc.perform(patch("/api/v1/reminders/" + REMINDER_ID)
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PAUSED\",\"version\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("version"));
    }

    @Test
    void statusUpdateRejectsAStatusOutsideTheStateMachine() throws Exception {
        mockMvc.perform(patch("/api/v1/reminders/" + REMINDER_ID)
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DONE\",\"version\":3}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COM-VALIDATION-001"));
    }

    @Test
    void statusUpdateRejectsFieldsThatAreNotInTheWriteSurface() throws Exception {
        //  的代价在这里有强制力：往本端点塞 title/rrule 不会被静默忽略，也不会悄悄扩大可改面。
        mockMvc.perform(patch("/api/v1/reminders/" + REMINDER_ID)
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PAUSED\",\"version\":3,\"title\":\"改名\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COM-VALIDATION-001"));
    }

    @Test
    void statusUpdateOnAMalformedReminderIdIsABadRequest() throws Exception {
        mockMvc.perform(patch("/api/v1/reminders/not-a-uuid")
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PAUSED\",\"version\":3}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COM-VALIDATION-001"));
    }

    @Test
    void aMissingOrForeignReminderIsFourOhFour() throws Exception {
        when(reminderStatusService.update(eq(USER_ID), eq(REMINDER_ID), eq(IDEMPOTENCY_KEY), any()))
                .thenThrow(new BusinessException(ErrorCode.REM_NOT_FOUND_001));

        mockMvc.perform(patch("/api/v1/reminders/" + REMINDER_ID)
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PAUSED\",\"version\":3}"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("REM-NOT_FOUND-001"));
    }

    @Test
    void aStaleVersionIsTheVersionConflictCode() throws Exception {
        when(reminderStatusService.update(eq(USER_ID), eq(REMINDER_ID), eq(IDEMPOTENCY_KEY), any()))
                .thenThrow(new BusinessException(ErrorCode.REM_CONFLICT_003));

        mockMvc.perform(patch("/api/v1/reminders/" + REMINDER_ID)
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PAUSED\",\"version\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REM-CONFLICT-003"));
    }

    @Test
    void anIllegalEdgeIsTheStateConflictCode() throws Exception {
        when(reminderStatusService.update(eq(USER_ID), eq(REMINDER_ID), eq(IDEMPOTENCY_KEY), any()))
                .thenThrow(new BusinessException(ErrorCode.REM_CONFLICT_004));

        mockMvc.perform(patch("/api/v1/reminders/" + REMINDER_ID)
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\",\"version\":7}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REM-CONFLICT-004"));
    }

    @Test
    void aRateLimitedStatusUpdateCarriesRetryAfter() throws Exception {
        when(reminderStatusService.update(eq(USER_ID), eq(REMINDER_ID), eq(IDEMPOTENCY_KEY), any()))
                .thenThrow(new RateLimitExceededException(30));

        mockMvc.perform(patch("/api/v1/reminders/" + REMINDER_ID)
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PAUSED\",\"version\":3}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "30"))
                .andExpect(jsonPath("$.code").value("COM-RATE_LIMIT-001"));
    }

    @Test
    void aCourseReminderCannotBeCreatedEvenThoughTheReadSideReturnsTheKind() throws Exception {
        //  的实证：COURSE 进了共享枚举但没有进 @JsonSubTypes，手写创建走不通，
        // 因为 COURSE 行必须带 source_uid 与 source_sequence 才维持得住幂等导入语义。
        mockMvc.perform(post("/api/v1/reminders")
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"COURSE\",\"title\":\"线性代数\",\"timezone\":\"Asia/Shanghai\","
                                + "\"rrule\":\"FREQ=WEEKLY;BYDAY=MO\",\"dtstart\":\"2026-09-07T00:00:00Z\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COM-VALIDATION-001"));

        verify(reminderService, never()).create(eq(USER_ID), eq(IDEMPOTENCY_KEY), any());
    }

    private ReminderCreateData data(boolean idempotentReplay) {
        return new ReminderCreateData(
                REMINDER_ID,
                "ACTIVE",
                "DEADLINE",
                "提交线性代数作业",
                "FREQ=DAILY;COUNT=1",
                NOW,
                "Asia/Shanghai",
                List.of(),
                NOW,
                0,
                idempotentReplay,
                NOW,
                NOW);
    }
}
