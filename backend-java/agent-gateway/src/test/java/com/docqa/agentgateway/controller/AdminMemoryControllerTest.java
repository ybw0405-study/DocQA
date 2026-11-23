package com.docqa.agentgateway.controller;

import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.docqa.agentgateway.dto.MemoryItem;
import com.docqa.agentgateway.dto.MemoryListData;
import com.docqa.agentgateway.service.PythonMemoryClient;
import com.docqa.agentgateway.web.AgentExceptionHandler;
import com.docqa.common.error.BusinessException;
import com.docqa.common.error.ErrorCode;
import com.docqa.common.tenant.TenantContext;
import com.docqa.common.trace.TraceIdContext;
import com.docqa.common.web.GlobalExceptionHandler;
import com.docqa.common.web.TraceIdFilter;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
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
class AdminMemoryControllerTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID MEMORY_KEY = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final String PATH = "/api/v1/admin/ai/memories";

    @Mock
    private PythonMemoryClient client;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        objectMapper.addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class);
        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter(objectMapper);
        converter.setSupportedMediaTypes(List.of(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON));
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AdminMemoryController(client))
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
    void listsMemoriesWithTheTenantFromTheVerifiedJwt() throws Exception {
        when(client.list(eq(TENANT_ID), eq(USER_ID), any()))
                .thenReturn(new MemoryListData(List.of(
                        new MemoryItem(MEMORY_KEY, "用户偏好表格", Instant.parse("2026-11-22T10:12:03.004Z")))));

        mockMvc.perform(get(PATH)
                        .param("userId", USER_ID.toString())
                        .requestAttr(TenantContext.REQUEST_ATTRIBUTE, TENANT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.items[0].key").value(MEMORY_KEY.toString()))
                .andExpect(jsonPath("$.data.items[0].text").value("用户偏好表格"));

        verify(client).list(eq(TENANT_ID), eq(USER_ID), any());
    }

    @Test
    void requiresTheUserIdQueryParameter() throws Exception {
        mockMvc.perform(get(PATH).requestAttr(TenantContext.REQUEST_ATTRIBUTE, TENANT_ID))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deletesWithNoContent() throws Exception {
        when(client.delete(eq(TENANT_ID), eq(USER_ID), eq(MEMORY_KEY), any())).thenReturn(true);

        mockMvc.perform(delete(PATH + "/" + MEMORY_KEY)
                        .param("userId", USER_ID.toString())
                        .requestAttr(TenantContext.REQUEST_ATTRIBUTE, TENANT_ID))
                .andExpect(status().isNoContent());

        verify(client).delete(eq(TENANT_ID), eq(USER_ID), eq(MEMORY_KEY), any());
    }

    @Test
    void mapsAMissingMemoryTo404() throws Exception {
        when(client.delete(eq(TENANT_ID), eq(USER_ID), eq(MEMORY_KEY), any())).thenReturn(false);

        mockMvc.perform(delete(PATH + "/" + MEMORY_KEY)
                        .param("userId", USER_ID.toString())
                        .requestAttr(TenantContext.REQUEST_ATTRIBUTE, TENANT_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COM-NOT_FOUND-001"));
    }

    @Test
    void surfacesDataPlaneUnavailabilityAs503WithTraceId() throws Exception {
        when(client.list(eq(TENANT_ID), eq(USER_ID), any()))
                .thenThrow(new BusinessException(ErrorCode.AGT_UPSTREAM_001, "Python memory surface is unavailable"));

        mockMvc.perform(get(PATH)
                        .param("userId", USER_ID.toString())
                        .requestAttr(TenantContext.REQUEST_ATTRIBUTE, TENANT_ID))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("AGT-UPSTREAM-001"))
                .andExpect(jsonPath("$.traceId").value(matchesPattern("[0-9a-f]{32}")));
    }
}