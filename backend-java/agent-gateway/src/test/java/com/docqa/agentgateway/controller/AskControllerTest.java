package com.docqa.agentgateway.controller;

import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import com.docqa.common.web.TraceIdFilter;
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

@ExtendWith(MockitoExtension.class)
class AskControllerTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID CONVERSATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID DOCUMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID MESSAGE_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final String KEY = "ask-key-000001";
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
                .standaloneSetup(new AskController(askService))
                .setControllerAdvice(new AgentExceptionHandler(), new GlobalExceptionHandler())
                .addFilters(new TraceIdFilter())
                .setMessageConverters(converter)
                .build();
    }

    @AfterEach
    void tearDown() {
        TraceIdContext.clear();
    }

    @Test
    void returnsAnswer() throws Exception {
        when(askService.ask(eq(USER_ID), eq(CONVERSATION_ID), eq(KEY), any()))
                .thenReturn(new AskData(
                        CONVERSATION_ID, MESSAGE_ID, "答案", List.of(), 0.9, "OK",
                        120, 1, 2, false, false, NOW, null));

        mockMvc.perform(post("/api/v1/conversations/{conversationId}/ask", CONVERSATION_ID)
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID)
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Trace-Id", matchesPattern("^[0-9a-f]{32}$")))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.answer").value("答案"))
                .andExpect(jsonPath("$.data.budgetStatus").value("OK"))
                .andExpect(jsonPath("$.traceId").value(matchesPattern("^[0-9a-f]{32}$")));
    }

    @Test
    void mapsConversationNotFoundTo404() throws Exception {
        when(askService.ask(eq(USER_ID), eq(CONVERSATION_ID), eq(KEY), any()))
                .thenThrow(new BusinessException(ErrorCode.AGT_NOT_FOUND_001));

        mockMvc.perform(post("/api/v1/conversations/{conversationId}/ask", CONVERSATION_ID)
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID)
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("AGT-NOT_FOUND-001"));
    }

    @Test
    void mapsBusyConversationTo409() throws Exception {
        when(askService.ask(eq(USER_ID), eq(CONVERSATION_ID), eq(KEY), any()))
                .thenThrow(new BusinessException(ErrorCode.AGT_CONFLICT_001));

        mockMvc.perform(post("/api/v1/conversations/{conversationId}/ask", CONVERSATION_ID)
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID)
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AGT-CONFLICT-001"));
    }

    @Test
    void mapsPiiBlockTo422() throws Exception {
        when(askService.ask(eq(USER_ID), eq(CONVERSATION_ID), eq(KEY), any()))
                .thenThrow(new BusinessException(ErrorCode.AGT_VALIDATION_003));

        mockMvc.perform(post("/api/v1/conversations/{conversationId}/ask", CONVERSATION_ID)
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID)
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("AGT-VALIDATION-003"));
    }

    @Test
    void mapsKillSwitchTo503() throws Exception {
        when(askService.ask(eq(USER_ID), eq(CONVERSATION_ID), eq(KEY), any()))
                .thenThrow(new BusinessException(ErrorCode.AGT_UPSTREAM_003));

        mockMvc.perform(post("/api/v1/conversations/{conversationId}/ask", CONVERSATION_ID)
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID)
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AGT-UPSTREAM-003"));
    }

    @Test
    void mapsTimeoutTo504() throws Exception {
        when(askService.ask(eq(USER_ID), eq(CONVERSATION_ID), eq(KEY), any()))
                .thenThrow(new BusinessException(ErrorCode.AGT_UPSTREAM_002));

        mockMvc.perform(post("/api/v1/conversations/{conversationId}/ask", CONVERSATION_ID)
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID)
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.code").value("AGT-UPSTREAM-002"));
    }

    @Test
    void rejectsBlankQuestion() throws Exception {
        mockMvc.perform(post("/api/v1/conversations/{conversationId}/ask", CONVERSATION_ID)
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID)
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"   \",\"documentIds\":[\"" + DOCUMENT_ID + "\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AGT-VALIDATION-001"));
    }

    @Test
    void rejectsEmptyDocumentScope() throws Exception {
        mockMvc.perform(post("/api/v1/conversations/{conversationId}/ask", CONVERSATION_ID)
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID)
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"问题\",\"documentIds\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AGT-VALIDATION-001"));
    }

    private String body() {
        return "{\"question\":\"请解释梯度下降\",\"documentIds\":[\"" + DOCUMENT_ID + "\"]}";
    }
}
