package com.docqa.agentgateway.controller;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.docqa.agentgateway.dto.AskData;
import com.docqa.agentgateway.service.AskService;
import com.docqa.agentgateway.web.AgentExceptionHandler;
import com.docqa.agentgateway.web.JwtAuthenticationFilter;
import com.docqa.common.error.BusinessException;
import com.docqa.common.error.ErrorCode;
import com.docqa.common.trace.TraceIdContext;
import com.docqa.common.web.GlobalExceptionHandler;
import com.docqa.common.web.JsonCharsetFilter;
import com.docqa.common.web.TraceIdFilter;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@ExtendWith(MockitoExtension.class)
class AskStreamControllerTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID CONVERSATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID DOCUMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID MESSAGE_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final String KEY = "ask-stream-key-0001";
    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Mock
    private AskService askService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    @BeforeEach
    void setUp() {
        objectMapper.addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class);
        MappingJackson2HttpMessageConverter converter =
                new MappingJackson2HttpMessageConverter(objectMapper);
        converter.setSupportedMediaTypes(
                List.of(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON));
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AskStreamController(askService))
                .setControllerAdvice(new AgentExceptionHandler(), new GlobalExceptionHandler())
                .addFilters(new TraceIdFilter(), new JsonCharsetFilter())
                .setMessageConverters(converter)
                .build();
    }

    @AfterEach
    void tearDown() {
        TraceIdContext.clear();
    }

    @Test
    void streamsMetaAndDone() throws Exception {
        when(askService.askStream(eq(USER_ID), eq(CONVERSATION_ID), eq(KEY), any()))
                .thenAnswer(invocation -> completedEmitter(
                        "meta",
                        "{\"conversationId\":\"" + CONVERSATION_ID + "\",\"messageId\":\"" + MESSAGE_ID
                                + "\",\"traceId\":\"" + TRACE_ID + "\",\"cacheHit\":false}",
                        "done",
                        doneDataJson()));

        mockMvc.perform(post("/api/v1/conversations/{conversationId}/ask:stream", CONVERSATION_ID)
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID)
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isOk())
                // 精确断言而非「兼容」：这就是 no-rewrite 回归——
                // JsonCharsetFilter 已在这条链上，但它不得给事件流追加 charset。
                .andExpect(content().contentType("text/event-stream"))
                .andExpect(content().string(containsString("event:meta")))
                .andExpect(content().string(containsString("event:done")))
                .andExpect(content().string(containsString("\"messageId\":\"" + MESSAGE_ID + "\"")))
                .andExpect(content().string(containsString(TRACE_ID)));
    }

    @Test
    void mapsFrontSectionRejectionToJsonProblemDetail() throws Exception {
        when(askService.askStream(eq(USER_ID), eq(CONVERSATION_ID), eq(KEY), any()))
                .thenThrow(new BusinessException(ErrorCode.AGT_CONFLICT_001));

        mockMvc.perform(post("/api/v1/conversations/{conversationId}/ask:stream", CONVERSATION_ID)
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID)
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isConflict())
                // 反向对照：同一条链上 JSON 载荷确实被追加了 charset，
                // 因此上面那条「事件流没被追加」不是因为过滤器没生效。
                .andExpect(content().contentType("application/problem+json;charset=UTF-8"))
                .andExpect(jsonPath("$.code").value("AGT-CONFLICT-001"));
    }

    @Test
    void mapsKillSwitchTo503Json() throws Exception {
        when(askService.askStream(eq(USER_ID), eq(CONVERSATION_ID), eq(KEY), any()))
                .thenThrow(new BusinessException(ErrorCode.AGT_UPSTREAM_003));

        mockMvc.perform(post("/api/v1/conversations/{conversationId}/ask:stream", CONVERSATION_ID)
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID)
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("AGT-UPSTREAM-003"));
    }

    @Test
    void mapsTimeoutTo504Json() throws Exception {
        when(askService.askStream(eq(USER_ID), eq(CONVERSATION_ID), eq(KEY), any()))
                .thenThrow(new BusinessException(ErrorCode.AGT_UPSTREAM_002));

        mockMvc.perform(post("/api/v1/conversations/{conversationId}/ask:stream", CONVERSATION_ID)
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID)
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.code").value("AGT-UPSTREAM-002"));
    }

    @Test
    void mapsPiiBlockTo422Json() throws Exception {
        when(askService.askStream(eq(USER_ID), eq(CONVERSATION_ID), eq(KEY), any()))
                .thenThrow(new BusinessException(ErrorCode.AGT_VALIDATION_003));

        mockMvc.perform(post("/api/v1/conversations/{conversationId}/ask:stream", CONVERSATION_ID)
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID)
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("AGT-VALIDATION-003"));
    }

    @Test
    void rejectsBlankQuestion() throws Exception {
        mockMvc.perform(post("/api/v1/conversations/{conversationId}/ask:stream", CONVERSATION_ID)
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID)
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"   \",\"documentIds\":[\"" + DOCUMENT_ID + "\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AGT-VALIDATION-001"));
    }

    private SseEmitter completedEmitter(String event1, String data1, String event2, String data2)
            throws IOException {
        SseEmitter emitter = new SseEmitter(0L);
        emitter.send(SseEmitter.event().name(event1).data(data1));
        emitter.send(SseEmitter.event().name(event2).data(data2));
        emitter.complete();
        return emitter;
    }

    private String doneDataJson() {
        AskData data = new AskData(
                CONVERSATION_ID, MESSAGE_ID, "答案", List.of(), 0.9, "OK", 120, 1, 2, false, false, NOW);
        try {
            return objectMapper.writeValueAsString(data).replaceFirst("\\}$", ",\"traceId\":\"" + TRACE_ID + "\"}");
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private String body() {
        return "{\"question\":\"请解释梯度下降\",\"documentIds\":[\"" + DOCUMENT_ID + "\"]}";
    }
}
