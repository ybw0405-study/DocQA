package com.docqa.agentgateway.controller;

import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.docqa.agentgateway.dto.ConversationData;
import com.docqa.agentgateway.dto.ConversationListData;
import com.docqa.agentgateway.dto.ConversationListItemData;
import com.docqa.agentgateway.dto.MessageHistoryData;
import com.docqa.agentgateway.dto.MessageHistoryItemData;
import com.docqa.agentgateway.service.ConversationHistoryService;
import com.docqa.agentgateway.service.ConversationListService;
import com.docqa.agentgateway.service.ConversationService;
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
import java.util.stream.Collectors;
import java.util.stream.IntStream;
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
class ConversationControllerTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID CONVERSATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID DOCUMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID MESSAGE_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final String KEY = "conversation-key-01";
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Mock
    private ConversationService conversationService;
    @Mock
    private ConversationListService conversationListService;
    @Mock
    private ConversationHistoryService conversationHistoryService;

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
                .standaloneSetup(new ConversationController(
                        conversationService, conversationListService, conversationHistoryService))
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
    void createsConversationWith201() throws Exception {
        when(conversationService.create(eq(USER_ID), eq(KEY), any()))
                .thenReturn(new ConversationData(
                        CONVERSATION_ID, "线性代数", List.of(DOCUMENT_ID), false, NOW));

        mockMvc.perform(post("/api/v1/conversations")
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID)
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"线性代数\",\"documentScopeIds\":[\"" + DOCUMENT_ID + "\"]}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Trace-Id", matchesPattern("^[0-9a-f]{32}$")))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.conversationId").value(CONVERSATION_ID.toString()))
                .andExpect(jsonPath("$.data.title").value("线性代数"))
                .andExpect(jsonPath("$.data.idempotentReplay").value(false))
                .andExpect(jsonPath("$.traceId").value(matchesPattern("^[0-9a-f]{32}$")));
    }

    @Test
    void returns200ForIdempotentReplay() throws Exception {
        when(conversationService.create(eq(USER_ID), eq(KEY), any()))
                .thenReturn(new ConversationData(
                        CONVERSATION_ID, "线性代数", List.of(), true, NOW));

        mockMvc.perform(post("/api/v1/conversations")
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID)
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"线性代数\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.idempotentReplay").value(true));
    }

    @Test
    void mapsConversationConflictTo409() throws Exception {
        when(conversationService.create(eq(USER_ID), eq(KEY), any()))
                .thenThrow(new BusinessException(ErrorCode.AGT_CONFLICT_002));

        mockMvc.perform(post("/api/v1/conversations")
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID)
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"线性代数\"}"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("AGT-CONFLICT-002"))
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void rejectsOverlongTitleBeforeService() throws Exception {
        String body = "{\"title\":\"" + "a".repeat(201) + "\"}";

        mockMvc.perform(post("/api/v1/conversations")
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID)
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AGT-VALIDATION-001"));
    }

    @Test
    void rejectsMoreThanTwentyScopeDocuments() throws Exception {
        String ids = IntStream.range(0, 21)
                .mapToObj(index -> "\"" + UUID.randomUUID() + "\"")
                .collect(Collectors.joining(","));

        mockMvc.perform(post("/api/v1/conversations")
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID)
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentScopeIds\":[" + ids + "]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AGT-VALIDATION-001"));
    }

    @Test
    void rejectsUnknownField() throws Exception {
        mockMvc.perform(post("/api/v1/conversations")
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID)
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"t\",\"userId\":\"" + USER_ID + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AGT-VALIDATION-001"));
    }

    @Test
    void rejectsMalformedDocumentId() throws Exception {
        mockMvc.perform(post("/api/v1/conversations")
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID)
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentScopeIds\":[\"not-a-uuid\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AGT-VALIDATION-001"));
    }

    @Test
    void rejectsMissingBody() throws Exception {
        mockMvc.perform(post("/api/v1/conversations")
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID)
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AGT-VALIDATION-001"));
    }

    @Test
    void messageHistoryReturnsEnvelopeWithTheDefaultLimit() throws Exception {
        when(conversationHistoryService.list(USER_ID, CONVERSATION_ID, null, 20))
                .thenReturn(new MessageHistoryData(
                        CONVERSATION_ID,
                        List.of(new MessageHistoryItemData(
                                MESSAGE_ID, "user", "请解释 [EMAIL] 的特征值", 24,
                                "4bf92f3577b34da6a3ce929d0e0e4736", NOW)),
                        null,
                        20));

        mockMvc.perform(get("/api/v1/conversations/{conversationId}/messages", CONVERSATION_ID)
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.conversationId").value(CONVERSATION_ID.toString()))
                .andExpect(jsonPath("$.data.limit").value(20))
                .andExpect(jsonPath("$.data.items[0].messageId").value(MESSAGE_ID.toString()))
                .andExpect(jsonPath("$.data.items[0].content").value("请解释 [EMAIL] 的特征值"))
                .andExpect(jsonPath("$.data.items[0].traceId").value("4bf92f3577b34da6a3ce929d0e0e4736"))
                .andExpect(jsonPath("$.traceId").value(matchesPattern("^[0-9a-f]{32}$")));
    }

    @Test
    void messageHistoryPassesCursorAndLimitToTheService() throws Exception {
        when(conversationHistoryService.list(eq(USER_ID), eq(CONVERSATION_ID), eq("cursor-token"), eq(50)))
                .thenReturn(new MessageHistoryData(CONVERSATION_ID, List.of(), "next-token", 50));

        mockMvc.perform(get("/api/v1/conversations/{conversationId}/messages", CONVERSATION_ID)
                        .param("cursor", "cursor-token")
                        .param("limit", "50")
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.nextCursor").value("next-token"));
    }

    @Test
    void messageHistoryOfAnotherUsersConversationIsNotFound() throws Exception {
        when(conversationHistoryService.list(eq(USER_ID), eq(CONVERSATION_ID), isNull(), eq(20)))
                .thenThrow(new BusinessException(ErrorCode.AGT_NOT_FOUND_001));

        mockMvc.perform(get("/api/v1/conversations/{conversationId}/messages", CONVERSATION_ID)
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("AGT-NOT_FOUND-001"))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void conversationListReturnsEnvelopeWithTheDefaultLimit() throws Exception {
        when(conversationListService.list(USER_ID, null, 20)).thenReturn(new ConversationListData(
                List.of(new ConversationListItemData(
                        CONVERSATION_ID, "线性代数", List.of(DOCUMENT_ID), null, NOW)),
                null,
                20));

        mockMvc.perform(get("/api/v1/conversations")
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.limit").value(20))
                .andExpect(jsonPath("$.data.items[0].conversationId").value(CONVERSATION_ID.toString()))
                .andExpect(jsonPath("$.data.items[0].title").value("线性代数"))
                .andExpect(jsonPath("$.data.items[0].documentScopeIds[0]").value(DOCUMENT_ID.toString()))
                .andExpect(jsonPath("$.data.items[0].lastMessageAt").value(nullValue()))
                .andExpect(jsonPath("$.data.nextCursor").value(nullValue()))
                .andExpect(jsonPath("$.traceId").value(matchesPattern("^[0-9a-f]{32}$")));
    }

    @Test
    void conversationListPassesCursorAndLimitToTheService() throws Exception {
        when(conversationListService.list(eq(USER_ID), eq("cursor-token"), eq(50)))
                .thenReturn(new ConversationListData(List.of(), "next-token", 50));

        mockMvc.perform(get("/api/v1/conversations")
                        .param("cursor", "cursor-token")
                        .param("limit", "50")
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.nextCursor").value("next-token"));
    }

    @Test
    void conversationListRejectsLimitOutsideTheContractRange() throws Exception {
        when(conversationListService.list(eq(USER_ID), isNull(), eq(101)))
                .thenThrow(new BusinessException(ErrorCode.COM_VALIDATION_001, "limit out of range"));

        mockMvc.perform(get("/api/v1/conversations")
                        .param("limit", "101")
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, USER_ID))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("COM-VALIDATION-001"));
    }
}
