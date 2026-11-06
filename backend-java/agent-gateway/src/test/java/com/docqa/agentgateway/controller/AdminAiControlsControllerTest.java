package com.docqa.agentgateway.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
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
import com.docqa.agentgateway.dto.AdminAiControls;
import com.docqa.agentgateway.dto.ExperimentBudgetState;
import com.docqa.agentgateway.dto.KillSwitchState;
import com.docqa.agentgateway.service.AdminAiControlsService;
import com.docqa.agentgateway.web.AgentExceptionHandler;
import com.docqa.agentgateway.web.JwtAuthenticationFilter;
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
class AdminAiControlsControllerTest {

    private static final UUID ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final Instant UPDATED_AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final String PATH = "/api/v1/admin/ai/controls";

    @Mock
    private AdminAiControlsService controlsService;

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
                .standaloneSetup(new AdminAiControlsController(controlsService))
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
    void returnsCurrentControls() throws Exception {
        when(controlsService.getControls()).thenReturn(controls());

        mockMvc.perform(get(PATH)
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, ADMIN_ID))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.killSwitch.active").value(true))
                .andExpect(jsonPath("$.data.killSwitch.updatedAt").value("2026-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.data.killSwitch.updatedBy").value(ADMIN_ID.toString()))
                .andExpect(jsonPath("$.data.experimentBudget.callsUsed").value(12))
                .andExpect(jsonPath("$.data.experimentBudget.tokensUsed").value(345))
                .andExpect(jsonPath("$.data.experimentBudget.callLimit").value(100))
                .andExpect(jsonPath("$.data.experimentBudget.tokenLimit").value(200000))
                .andExpect(jsonPath("$.data.experimentBudget.limitsKnown").value(true));
    }

    @Test
    void updatesKillSwitchForTheCallingAdministrator() throws Exception {
        when(controlsService.updateKillSwitch(eq(ADMIN_ID), eq(true))).thenReturn(controls());

        mockMvc.perform(put(PATH)
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, ADMIN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"killSwitchActive\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.killSwitch.active").value(true));

        verify(controlsService).updateKillSwitch(ADMIN_ID, true);
    }

    @Test
    void rejectsRequestWithoutKillSwitchFlag() throws Exception {
        mockMvc.perform(put(PATH)
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, ADMIN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("AGT-VALIDATION-001"))
                .andExpect(jsonPath("$.errors[0].field").value("killSwitchActive"));

        verify(controlsService, never()).updateKillSwitch(any(), anyBoolean());
    }

    @Test
    void rejectsUnknownRequestProperties() throws Exception {
        mockMvc.perform(put(PATH)
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, ADMIN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"killSwitchActive\":true,\"unexpected\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AGT-VALIDATION-001"));

        verify(controlsService, never()).updateKillSwitch(any(), anyBoolean());
    }

    private static AdminAiControls controls() {
        return new AdminAiControls(
                new KillSwitchState(true, UPDATED_AT, ADMIN_ID),
                new ExperimentBudgetState(12, 345, 100L, 200_000L, true));
    }
}
