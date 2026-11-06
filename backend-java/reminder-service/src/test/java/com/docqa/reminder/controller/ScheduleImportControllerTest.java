package com.docqa.reminder.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.docqa.common.error.BusinessException;
import com.docqa.common.error.ErrorCode;
import com.docqa.common.trace.TraceIdContext;
import com.docqa.common.web.GlobalExceptionHandler;
import com.docqa.common.web.TraceIdFilter;
import com.docqa.reminder.dto.ScheduleImportData;
import com.docqa.reminder.dto.ScheduleImportItemData;
import com.docqa.reminder.dto.SchedulePreviewData;
import com.docqa.reminder.service.IcsUpload;
import com.docqa.reminder.service.ImportScheduleResult;
import com.docqa.reminder.service.ScheduleImportService;
import com.docqa.reminder.web.JwtAuthenticationFilter;
import com.docqa.reminder.web.ReminderExceptionHandler;
import java.nio.charset.StandardCharsets;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockPart;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 两个 ICS 端点的 HTTP 形态：multipart 三个 Part 的绑定、状态码分岔、以及「哪些非法落在控制器、
 * 哪些落在服务层」。服务层的准入判据（2 MiB、内容类型、未知时区）各有专测，这里只证明边界。
 */
@ExtendWith(MockitoExtension.class)
class ScheduleImportControllerTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID REMINDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final Instant MONDAY = Instant.parse("2026-09-07T00:00:00Z");
    private static final String PREVIEW_PATH = "/api/v1/reminders/ics/preview";
    private static final String IMPORT_PATH = "/api/v1/reminders/ics/import";
    private static final String KEY = "idem-key-0001";
    private static final String UID = "linear-algebra-2026-fall-mon-0800";
    private static final String ZONE = "Asia/Shanghai";
    private static final String TRACE_ID_PATTERN = "^[0-9a-f]{32}$";
    /** 与控制器注解上的取值逐字一致；漂移会直接让下面两条 {@code must match} 断言变红。 */
    private static final String TIMEZONE_PATTERN = "^UTC$|^[A-Za-z]+(?:/[A-Za-z0-9_+\\-]+)+$";
    private static final byte[] ICS = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\n".getBytes(StandardCharsets.UTF_8);

    @Mock
    private ScheduleImportService scheduleImportService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper()
                .findAndRegisterModules()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        objectMapper.addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class);
        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter(objectMapper);
        converter.setSupportedMediaTypes(
                List.of(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON));
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ScheduleImportController(scheduleImportService))
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
    void previewReturnsPerEventJudgements() throws Exception {
        when(scheduleImportService.preview(eq(USER_ID), eq(KEY), any(IcsUpload.class), eq(ZONE), eq(10)))
                .thenReturn(preview(1, 0, 0, 0));

        mockMvc.perform(icsRequest(PREVIEW_PATH, ZONE, "10", KEY))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.itemCount").value(1))
                .andExpect(jsonPath("$.data.createCount").value(1))
                .andExpect(jsonPath("$.data.fallbackTimezone").value(ZONE))
                .andExpect(jsonPath("$.data.leadMinutes").value(10))
                .andExpect(jsonPath("$.data.items[0].uid").value(UID))
                .andExpect(jsonPath("$.data.items[0].action").value("CREATE"))
                .andExpect(jsonPath("$.data.items[0].nextTriggerAt").value("2026-09-06T23:50:00Z"))
                .andExpect(jsonPath("$.traceId").value(matchesPattern(TRACE_ID_PATTERN)));
    }

    @Test
    void previewForwardsTheRawFileBytesAndContentType() throws Exception {
        givenPreviewReturnsOneCreate();

        mockMvc.perform(icsRequest(PREVIEW_PATH, ZONE, "10", KEY)).andExpect(status().isOk());

        ArgumentCaptor<IcsUpload> upload = ArgumentCaptor.forClass(IcsUpload.class);
        verify(scheduleImportService).preview(eq(USER_ID), eq(KEY), upload.capture(), eq(ZONE), eq(10));
        // 控制器只做形态搬运：字节必须原样，否则解析器的行号取证与 SHA-256 指纹都对不上客户端发来的文件。
        assertThat(upload.getValue().content()).isEqualTo(ICS);
        assertThat(upload.getValue().contentType()).isEqualTo("text/calendar");
    }

    @Test
    void previewDoesNotRequireAnIdempotencyKey() throws Exception {
        when(scheduleImportService.preview(eq(USER_ID), eq(null), any(IcsUpload.class), eq(ZONE), eq(10)))
                .thenReturn(preview(1, 0, 0, 0));

        mockMvc.perform(icsRequest(PREVIEW_PATH, ZONE, "10", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.itemCount").value(1));
    }

    @Test
    void previewForwardsTheKeyVerbatimWithoutValidatingItsShape() throws Exception {
        // 键的准入规则集中在 IdempotencyKeys（与创建提醒共用同一判据），控制器不能提前按另一套判据拦一次。
        givenPreviewReturnsOneCreate();

        mockMvc.perform(icsRequest(PREVIEW_PATH, ZONE, "10", "short"))
                .andExpect(status().isOk());

        verify(scheduleImportService).preview(eq(USER_ID), eq("short"), any(IcsUpload.class), eq(ZONE), eq(10));
    }

    @Test
    void importReturnsCreatedOnFirstCommit() throws Exception {
        when(scheduleImportService.importSchedule(eq(USER_ID), eq(KEY), any(IcsUpload.class), eq(ZONE), eq(10)))
                .thenReturn(new ImportScheduleResult(importData(1, 1, false), true));

        mockMvc.perform(icsRequest(IMPORT_PATH, ZONE, "10", KEY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.idempotentReplay").value(false))
                .andExpect(jsonPath("$.data.createCount").value(1))
                .andExpect(jsonPath("$.data.items[0].reminderId").value(REMINDER_ID.toString()))
                .andExpect(jsonPath("$.traceId").value(matchesPattern(TRACE_ID_PATTERN)));
    }

    @Test
    void importReturnsOkOnIdempotentReplayWithEmptyItems() throws Exception {
        when(scheduleImportService.importSchedule(eq(USER_ID), eq(KEY), any(IcsUpload.class), eq(ZONE), eq(10)))
                .thenReturn(new ImportScheduleResult(importData(1, 1, true), false));

        mockMvc.perform(icsRequest(IMPORT_PATH, ZONE, "10", KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.idempotentReplay").value(true))
                .andExpect(jsonPath("$.data.itemCount").value(1))
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    void importRequiresAnIdempotencyKey() throws Exception {
        mockMvc.perform(icsRequest(IMPORT_PATH, ZONE, "10", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COM-VALIDATION-001"))
                .andExpect(jsonPath("$.errors[0].field").value("Idempotency-Key"))
                .andExpect(jsonPath("$.errors[0].message").value("required header is missing"));
    }

    @Test
    void missingLeadMinutesPartIsReportedAsAMissingField() throws Exception {
        // 文本 Part 是表单字段，容器把它暴露成请求参数，所以缺失形态与 query 参数同判据（400）。
        // 415 从此只表示「声称是 multipart 但信封解析不开」（MultipartException），
        // 与「顶层 Content-Type 根本不是 multipart」落 500（R-02）是两回事，后者在本类末尾钉住。
        mockMvc.perform(multipart(IMPORT_PATH)
                        .file(new MockMultipartFile("file", "schedule.ics", "text/calendar", ICS))
                        .part(part("fallbackTimezone", ZONE))
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID)
                        .header("Idempotency-Key", KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COM-VALIDATION-001"))
                .andExpect(jsonPath("$.errors[0].field").value("leadMinutes"))
                .andExpect(jsonPath("$.errors[0].message").value("required parameter is missing"));
    }

    @Test
    void missingFilePartIsReportedWithItsOwnName() throws Exception {
        mockMvc.perform(multipart(IMPORT_PATH)
                        .part(part("fallbackTimezone", ZONE))
                        .part(part("leadMinutes", "10"))
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID)
                        .header("Idempotency-Key", KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COM-VALIDATION-001"))
                .andExpect(jsonPath("$.errors[0].field").value("file"))
                .andExpect(jsonPath("$.errors[0].message").value("required multipart part is missing"));
    }

    @Test
    void leadMinutesBeyondOneWeekIsRejectedAtTheBoundary() throws Exception {
        mockMvc.perform(icsRequest(PREVIEW_PATH, ZONE, "10081", KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COM-VALIDATION-001"))
                // 内建方法校验给出的 field 就是参数名本身，不是 AOP 形态的「preview.leadMinutes」；
                // 每条约束各出一条 message，所以越界只说上界，不说区间。
                .andExpect(jsonPath("$.errors[0].field").value("leadMinutes"))
                .andExpect(jsonPath("$.errors[0].message").value("must be less than or equal to 10080"));
    }

    @Test
    void negativeLeadMinutesIsRejected() throws Exception {
        mockMvc.perform(icsRequest(PREVIEW_PATH, ZONE, "-1", KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("leadMinutes"))
                .andExpect(jsonPath("$.errors[0].message").value("must be greater than or equal to 0"));
    }

    @Test
    void blankFallbackTimezoneIsRejectedByShapeNotBySemantics() throws Exception {
        // 一个空白值同时违反 @NotBlank 与 @Pattern，而单参数的多条违规顺序不保证
        // （整批跑与单独跑会给出不同顺序），所以这里按集合断言，不按下标取第一条。
        MvcResult result = mockMvc.perform(icsRequest(PREVIEW_PATH, " ", "10", KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COM-VALIDATION-001"))
                .andReturn();
        JsonNode errors = jsonOf(result).path("errors");
        assertThat(errors.findValuesAsText("field")).containsOnly("fallbackTimezone");
        assertThat(errors.findValuesAsText("message"))
                .containsExactlyInAnyOrder("must not be blank", patternMessage());
    }

    @Test
    void offsetStyleTimezoneIsRejectedByThePatternBeforeAnyLookup() throws Exception {
        mockMvc.perform(icsRequest(PREVIEW_PATH, "+08:00", "10", KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("fallbackTimezone"))
                .andExpect(jsonPath("$.errors[0].message").value(patternMessage()));
    }

    @Test
    void nonNumericLeadMinutesIsRejectedBeforeTheServiceIsCalled() throws Exception {
        mockMvc.perform(icsRequest(PREVIEW_PATH, ZONE, "ten", KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COM-VALIDATION-001"))
                .andExpect(jsonPath("$.errors[0].field").value("leadMinutes"));
    }

    @Test
    void wellFormedButUnknownTimezoneReachesTheServiceLayer() throws Exception {
        // 形态过关、语义未知：这是服务层 422 的入口，控制器不该自己查时区库。
        when(scheduleImportService.preview(eq(USER_ID), eq(KEY), any(IcsUpload.class), eq("Mars/Phobos"), eq(10)))
                .thenThrow(new BusinessException(ErrorCode.REM_VALIDATION_001, null, List.of()));

        mockMvc.perform(icsRequest(PREVIEW_PATH, "Mars/Phobos", "10", KEY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("REM-VALIDATION-001"));
    }

    @Test
    void nonMultipartRequestContentTypeFallsThroughToTheCatchAll() throws Exception {
        // R-02：顶层 Content-Type 非 multipart 时，HttpMediaTypeNotSupportedException 没有被任何
        // 专用 handler 接住，落进 GlobalExceptionHandler 的 Exception.class 兜底成 500。
        // 钉成断言是为了让修复时的行为变化可见——修好后这条断言必须同步改成 415。
        mockMvc.perform(post(PREVIEW_PATH)
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("COM-INTERNAL-001"));
    }

    private void givenPreviewReturnsOneCreate() {
        when(scheduleImportService.preview(eq(USER_ID), any(), any(IcsUpload.class), eq(ZONE), eq(10)))
                .thenReturn(preview(1, 0, 0, 0));
    }

    /**
     * 三个 Part 的形态对齐真实客户端：curl 的 {@code -F name=value} 与浏览器的 FormData 都不会
     * 给文本 Part 写 Content-Type，所以这里用 {@code MockPart(name, byte[])} 而不是带媒体类型的重载。
     */
    private MockHttpServletRequestBuilder icsRequest(String path, String timezone, String leadMinutes, String key) {
        MockMultipartHttpServletRequestBuilder builder = multipart(path)
                .file(new MockMultipartFile("file", "schedule.ics", "text/calendar", ICS))
                .part(part("fallbackTimezone", timezone))
                .part(part("leadMinutes", leadMinutes));
        if (key != null) {
            builder.header("Idempotency-Key", key);
        }
        return builder.requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID);
    }

    private static MockPart part(String name, String value) {
        return new MockPart(name, value.getBytes(StandardCharsets.UTF_8));
    }

    private static String patternMessage() {
        return "must match \"" + TIMEZONE_PATTERN + "\"";
    }

    private JsonNode jsonOf(MvcResult result) throws Exception {
        return new ObjectMapper().readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static SchedulePreviewData preview(int create, int update, int unchanged, int stale) {
        return new SchedulePreviewData(
                create + update + unchanged + stale, create, update, unchanged, stale,
                ZONE, 10, List.of(item()));
    }

    private static ScheduleImportData importData(int itemCount, int createCount, boolean replay) {
        return new ScheduleImportData(
                itemCount, createCount, 0, 0, 0, replay, ZONE, 10,
                replay ? List.of() : List.of(item()));
    }

    private static ScheduleImportItemData item() {
        return new ScheduleImportItemData(
                UID, "线性代数", 1, null, "CREATE", MONDAY, ZONE, "FREQ=WEEKLY;BYDAY=MO",
                List.of(), MONDAY.minusSeconds(600), MONDAY.plusSeconds(604800).minusSeconds(600),
                REMINDER_ID, null);
    }
}
