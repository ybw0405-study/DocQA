package com.docqa.agentgateway.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.docqa.agentgateway.dto.AiQuota;
import com.docqa.agentgateway.dto.AiQuotaListData;
import com.docqa.agentgateway.service.AdminQuotaService;
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
class AdminAiQuotaControllerTest {

    private static final UUID ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID TARGET_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final Instant UPDATED_AT = Instant.parse("2026-11-03T09:20:03.004Z");
    private static final String PATH = "/api/v1/admin/ai/quotas";

    @Mock
    private AdminQuotaService quotaService;

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
                .standaloneSetup(new AdminAiQuotaController(quotaService))
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
    void listsOverridesWithTheContractDefaults() throws Exception {
        when(quotaService.list(null, 50)).thenReturn(new AiQuotaListData(List.of(quota()), null, 50));

        mockMvc.perform(get(PATH))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.items[0].userId").value(TARGET_ID.toString()))
                .andExpect(jsonPath("$.data.items[0].username").value("zhang.wei"))
                .andExpect(jsonPath("$.data.items[0].dailyQuestionLimit").value(30))
                .andExpect(jsonPath("$.data.items[0].dailyTokenLimit").value(60000))
                .andExpect(jsonPath("$.data.items[0].updatedAt").value("2026-11-03T09:20:03.004Z"))
                .andExpect(jsonPath("$.data.items[0].updatedBy").value(ADMIN_ID.toString()))
                .andExpect(jsonPath("$.data.nextCursor").doesNotExist())
                .andExpect(jsonPath("$.data.limit").value(50));
    }

    @Test
    void forwardsTheCursorAndTheMaximumPageSize() throws Exception {
        when(quotaService.list("cursor-1", 200))
                .thenReturn(new AiQuotaListData(List.of(), null, 200));

        mockMvc.perform(get(PATH).param("cursor", "cursor-1").param("limit", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.limit").value(200));

        verify(quotaService).list("cursor-1", 200);
    }

    @Test
    void replacesBothLimitsForTheTargetUser() throws Exception {
        when(quotaService.update(ADMIN_ID, TARGET_ID, 30L, 60_000L)).thenReturn(quota());

        mockMvc.perform(put(PATH + "/" + TARGET_ID)
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, ADMIN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dailyQuestionLimit\":30,\"dailyTokenLimit\":60000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.dailyQuestionLimit").value(30))
                .andExpect(jsonPath("$.data.dailyTokenLimit").value(60000));

        verify(quotaService).update(ADMIN_ID, TARGET_ID, 30L, 60_000L);
    }

    @Test
    void acceptsExplicitNullsAsAClearIntent() throws Exception {
        when(quotaService.update(eq(ADMIN_ID), eq(TARGET_ID), isNull(), isNull()))
                .thenReturn(new AiQuota(TARGET_ID, "zhang.wei", null, null, UPDATED_AT, ADMIN_ID));

        mockMvc.perform(put(PATH + "/" + TARGET_ID)
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, ADMIN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dailyQuestionLimit\":null,\"dailyTokenLimit\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dailyQuestionLimit").doesNotExist())
                .andExpect(jsonPath("$.data.dailyTokenLimit").doesNotExist());

        verify(quotaService).update(eq(ADMIN_ID), eq(TARGET_ID), isNull(), isNull());
    }

    @Test
    void rejectsBodiesThatOmitOneLimitInsteadOfClearingIt() throws Exception {
        mockMvc.perform(put(PATH + "/" + TARGET_ID)
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, ADMIN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dailyQuestionLimit\":30}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("AGT-VALIDATION-001"));

        verify(quotaService, never()).update(any(), any(), any(), any());
    }

    @Test
    void rejectsOutOfRangeLimits() throws Exception {
        mockMvc.perform(put(PATH + "/" + TARGET_ID)
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, ADMIN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dailyQuestionLimit\":0,\"dailyTokenLimit\":60000}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AGT-VALIDATION-001"))
                .andExpect(jsonPath("$.errors[0].field").value("dailyQuestionLimit"));

        verify(quotaService, never()).update(any(), any(), any(), any());
    }

    @Test
    void rejectsUnknownRequestProperties() throws Exception {
        mockMvc.perform(put(PATH + "/" + TARGET_ID)
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, ADMIN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dailyQuestionLimit\":30,\"dailyTokenLimit\":60000,\"extra\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AGT-VALIDATION-001"));

        verify(quotaService, never()).update(any(), any(), any(), any());
    }

    @Test
    void mapsUnknownTargetUsersToNotFound() throws Exception {
        when(quotaService.update(eq(ADMIN_ID), eq(TARGET_ID), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.USR_NOT_FOUND_001));

        mockMvc.perform(put(PATH + "/" + TARGET_ID)
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, ADMIN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dailyQuestionLimit\":30,\"dailyTokenLimit\":60000}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USR-NOT_FOUND-001"));
    }

    @Test
    void rejectsMalformedUserIds() throws Exception {
        mockMvc.perform(put(PATH + "/not-a-uuid")
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, ADMIN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dailyQuestionLimit\":30,\"dailyTokenLimit\":60000}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AGT-VALIDATION-001"));

        verify(quotaService, never()).update(any(), any(), any(), any());
    }

    @Test
    void rejectsOutOfRangePagesFromTheService() throws Exception {
        when(quotaService.list(null, 201))
                .thenThrow(new BusinessException(ErrorCode.COM_VALIDATION_001, "limit out of range"));

        mockMvc.perform(get(PATH).param("limit", "201"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COM-VALIDATION-001"));
    }

    private static AiQuota quota() {
        return new AiQuota(TARGET_ID, "zhang.wei", 30L, 60_000L, UPDATED_AT, ADMIN_ID);
    }
}
