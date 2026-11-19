package com.docqa.agentgateway.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.docqa.agentgateway.dto.AuditEventItem;
import com.docqa.agentgateway.dto.AuditEventListData;
import com.docqa.agentgateway.service.AdminAuditQueryService;
import com.docqa.agentgateway.web.AgentExceptionHandler;
import com.docqa.common.error.BusinessException;
import com.docqa.common.error.ErrorCode;
import com.docqa.common.tenant.TenantContext;
import com.docqa.common.trace.TraceIdContext;
import com.docqa.common.web.GlobalExceptionHandler;
import com.docqa.common.web.TraceIdFilter;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
class AdminAuditControllerTest {

    private static final UUID EVENT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d4");
    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final Instant OCCURRED_AT = Instant.parse("2026-11-03T10:00:01.120Z");
    private static final String PATH = "/api/v1/admin/audit/events";

    @Mock
    private AdminAuditQueryService auditQueryService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        objectMapper.addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class);
        MappingJackson2HttpMessageConverter converter =
                new MappingJackson2HttpMessageConverter(objectMapper);
        converter.setSupportedMediaTypes(
                List.of(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON));
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AdminAuditController(auditQueryService))
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
    void usesTheContractDefaultPageSize() throws Exception {
        when(auditQueryService.list(TENANT_ID, null, null, null, null, null, 100, null))
                .thenReturn(new AuditEventListData(List.of(item()), null, 100));

        mockMvc.perform(get(PATH).requestAttr(TenantContext.REQUEST_ATTRIBUTE, TENANT_ID))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.items[0].eventId").value(EVENT_ID.toString()))
                .andExpect(jsonPath("$.data.items[0].traceId").value(TRACE_ID))
                .andExpect(jsonPath("$.data.items[0].userId").value(USER_ID.toString()))
                .andExpect(jsonPath("$.data.items[0].eventType").value("RAG_RETRIEVED"))
                .andExpect(jsonPath("$.data.items[0].agentName").value("rag_retriever"))
                .andExpect(jsonPath("$.data.items[0].source").value("PYTHON"))
                .andExpect(jsonPath("$.data.items[0].severity").value("INFO"))
                .andExpect(jsonPath("$.data.items[0].payload.chunkCount").value(6))
                .andExpect(jsonPath("$.data.items[0].occurredAt").value("2026-11-03T10:00:01.120Z"))
                .andExpect(jsonPath("$.data.nextCursor").doesNotExist())
                .andExpect(jsonPath("$.data.limit").value(100));
    }

    @Test
    void forwardsEveryFilterAndTheCursor() throws Exception {
        when(auditQueryService.list(TENANT_ID,
                TRACE_ID, USER_ID.toString(), "RAG_RETRIEVED", "2026-11-02T09:00:00Z",
                "2026-11-03T09:00:00Z", 500, "cursor-1"))
                .thenReturn(new AuditEventListData(List.of(), null, 500));

        mockMvc.perform(get(PATH)
                        .requestAttr(TenantContext.REQUEST_ATTRIBUTE, TENANT_ID)
                        .param("traceId", TRACE_ID)
                        .param("userId", USER_ID.toString())
                        .param("eventType", "RAG_RETRIEVED")
                        .param("from", "2026-11-02T09:00:00Z")
                        .param("to", "2026-11-03T09:00:00Z")
                        .param("limit", "500")
                        .param("cursor", "cursor-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.limit").value(500));
    }

    @Test
    void mapsServiceValidationFailuresToFourHundred() throws Exception {
        when(auditQueryService.list(eq(TENANT_ID), any(), any(), any(), any(), any(), eq(501), any()))
                .thenThrow(new BusinessException(ErrorCode.COM_VALIDATION_001, "limit out of range"));

        mockMvc.perform(get(PATH).requestAttr(TenantContext.REQUEST_ATTRIBUTE, TENANT_ID).param("limit", "501"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COM-VALIDATION-001"));
    }

    @Test
    void mapsNonNumericLimitsToGatewayValidation() throws Exception {
        mockMvc.perform(get(PATH).requestAttr(TenantContext.REQUEST_ATTRIBUTE, TENANT_ID).param("limit", "many"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AGT-VALIDATION-001"))
                .andExpect(jsonPath("$.errors[0].field").value("limit"));
    }

    private static AuditEventItem item() {
        return new AuditEventItem(
                EVENT_ID,
                TRACE_ID,
                USER_ID,
                "RAG_RETRIEVED",
                "rag_retriever",
                "PYTHON",
                "INFO",
                Map.of("chunkCount", 6),
                OCCURRED_AT);
    }
}
