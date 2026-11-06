package com.docqa.reminder.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.docqa.common.error.BusinessException;
import com.docqa.common.error.ErrorCode;
import com.docqa.common.trace.TraceIdContext;
import com.docqa.common.web.GlobalExceptionHandler;
import com.docqa.common.web.TraceIdFilter;
import com.docqa.reminder.dto.NotificationData;
import com.docqa.reminder.dto.NotificationListData;
import com.docqa.reminder.dto.NotificationReadData;
import com.docqa.reminder.dto.NotificationReadRequest;
import com.docqa.reminder.service.NotificationService;
import com.docqa.reminder.web.JwtAuthenticationFilter;
import com.docqa.reminder.web.ReminderExceptionHandler;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
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

/**
 * 通知的两个端点共用同一套边界：{@code userId} 只来自 JWT 属性，标记已读**有意不要**幂等键（D-67）。
 */
@ExtendWith(MockitoExtension.class)
class NotificationControllerTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID REMINDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID NOTIFICATION_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final Instant PLANNED = Instant.parse("2026-08-31T00:50:00Z");
    private static final Instant DELIVERED = Instant.parse("2026-08-31T00:50:01Z");
    private static final String TRACE_ID_PATTERN = "^[0-9a-f]{32}$";

    @Mock
    private NotificationService notificationService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @BeforeEach
    void setUp() {
        objectMapper.addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class);
        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter(objectMapper);
        converter.setSupportedMediaTypes(
                List.of(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON));
        mockMvc = MockMvcBuilders
                .standaloneSetup(new NotificationController(notificationService))
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
    void listReturnsTheEnvelopeWithKeysetFields() throws Exception {
        when(notificationService.list(USER_ID, false, null, 20)).thenReturn(new NotificationListData(
                List.of(new NotificationData(
                        NOTIFICATION_ID, REMINDER_ID, "COURSE", "线性代数", "第三章",
                        PLANNED, PLANNED.plusSeconds(604800), DELIVERED, null, DELIVERED)),
                null,
                20));

        mockMvc.perform(get("/api/v1/notifications")
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.items[0].notificationId").value(NOTIFICATION_ID.toString()))
                .andExpect(jsonPath("$.data.items[0].kind").value("COURSE"))
                .andExpect(jsonPath("$.data.items[0].plannedTriggerAt").value("2026-08-31T00:50:00Z"))
                .andExpect(jsonPath("$.data.items[0].readAt").value(nullValue()))
                .andExpect(jsonPath("$.data.nextCursor").value(nullValue()))
                .andExpect(jsonPath("$.data.limit").value(20))
                .andExpect(jsonPath("$.traceId").value(matchesPattern(TRACE_ID_PATTERN)));
    }

    @Test
    void listForwardsTheUnreadFilterCursorAndLimit() throws Exception {
        when(notificationService.list(USER_ID, true, "cursor-token", 50))
                .thenReturn(new NotificationListData(List.of(), "next-token", 50));

        mockMvc.perform(get("/api/v1/notifications")
                        .param("unreadOnly", "true")
                        .param("cursor", "cursor-token")
                        .param("limit", "50")
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nextCursor").value("next-token"));
    }

    @Test
    void anAbsentUnreadOnlyReachesTheServiceAsFalseRatherThanAsNull() throws Exception {
        mockMvc.perform(get("/api/v1/notifications")
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID))
                .andExpect(status().isOk());

        // 三态布尔必须在边界上收敛掉：仓库层没有「按 null 过滤」这一档。
        verify(notificationService).list(eq(USER_ID), eq(false), isNull(), eq(20));
    }

    @Test
    void aNonBooleanUnreadFilterIsABadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/notifications")
                        .param("unreadOnly", "maybe")
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("COM-VALIDATION-001"));

        verify(notificationService, never()).list(any(), anyBoolean(), any(), anyInt());
    }

    @Test
    void aNonNumericLimitIsABadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/notifications")
                        .param("limit", "many")
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COM-VALIDATION-001"));

        verify(notificationService, never()).list(any(), anyBoolean(), any(), anyInt());
    }

    @Test
    void markReadIsTheDocumentedIdempotencyKeyException() throws Exception {
        when(notificationService.markRead(eq(USER_ID), any()))
                .thenReturn(new NotificationReadData(3, true));

        // 不带 Idempotency-Key 也必须 200：已读是收敛操作，重复提交没有第二次副作用（D-67）。
        mockMvc.perform(post("/api/v1/notifications/read")
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"all\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updated").value(3))
                .andExpect(jsonPath("$.data.all").value(true));

        verify(notificationService).markRead(eq(USER_ID), any());
    }

    @Test
    void markReadReturnsTheAffectedRowCountAndZeroIsStillSuccess() throws Exception {
        when(notificationService.markRead(eq(USER_ID), any())).thenReturn(new NotificationReadData(0, false));

        mockMvc.perform(post("/api/v1/notifications/read")
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"all\":false,\"ids\":[\"" + NOTIFICATION_ID + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updated").value(0));

        ArgumentCaptor<NotificationReadRequest> captor =
                ArgumentCaptor.forClass(NotificationReadRequest.class);
        verify(notificationService).markRead(eq(USER_ID), captor.capture());
        assertThat(captor.getValue().ids()).containsExactly(NOTIFICATION_ID);
    }

    @Test
    void aMissingAllFlagIsRejectedByTheBeanValidationLayer() throws Exception {
        mockMvc.perform(post("/api/v1/notifications/read")
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[\"" + NOTIFICATION_ID + "\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COM-VALIDATION-001"))
                .andExpect(jsonPath("$.errors[0].field").value("all"));

        verify(notificationService, never()).markRead(any(), any());
    }

    @Test
    void anEmptyIdListIsRejectedByTheSizeBoundRatherThanTheCrossFieldRule() throws Exception {
        mockMvc.perform(post("/api/v1/notifications/read")
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"all\":false,\"ids\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COM-VALIDATION-001"))
                .andExpect(jsonPath("$.errors[0].field").value("ids"));

        verify(notificationService, never()).markRead(any(), any());
    }

    @Test
    void moreThanFiveHundredIdsIsRejectedBeforeAnyUpdateIsAttempted() throws Exception {
        String ids = IntStream.range(0, 501)
                .mapToObj(i -> "\"" + UUID.randomUUID() + "\"")
                .reduce((left, right) -> left + "," + right)
                .orElseThrow();

        mockMvc.perform(post("/api/v1/notifications/read")
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"all\":false,\"ids\":[" + ids + "]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COM-VALIDATION-001"));

        verify(notificationService, never()).markRead(any(), any());
    }

    @Test
    void aNonUuidIdIsRejectedRatherThanSilentlyDropped() throws Exception {
        mockMvc.perform(post("/api/v1/notifications/read")
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"all\":false,\"ids\":[\"17\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COM-VALIDATION-001"));

        verify(notificationService, never()).markRead(any(), any());
    }

    @Test
    void unknownFieldsInTheReadBodyAreRejectedRatherThanIgnored() throws Exception {
        mockMvc.perform(post("/api/v1/notifications/read")
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"all\":true,\"markEverything\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COM-VALIDATION-001"));

        verify(notificationService, never()).markRead(any(), any());
    }

    @Test
    void aCursorTheServiceRefusesToDecodeReachesTheClientAsFourHundred() throws Exception {
        // 长度与字符集判据在 KeysetCursor 与 NotificationService 里，控制器只透传字符串，因此这里测的是映射不是校验。
        when(notificationService.list(eq(USER_ID), eq(false), anyString(), eq(20)))
                .thenThrow(new BusinessException(ErrorCode.COM_VALIDATION_001, "invalid cursor"));

        mockMvc.perform(get("/api/v1/notifications")
                        .param("cursor", "n".repeat(201))
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }
}
