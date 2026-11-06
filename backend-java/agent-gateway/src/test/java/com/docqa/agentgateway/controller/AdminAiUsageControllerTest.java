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
import com.docqa.agentgateway.dto.UsageDayPoint;
import com.docqa.agentgateway.dto.UsageStatusCount;
import com.docqa.agentgateway.dto.UsageSummary;
import com.docqa.agentgateway.dto.UsageTopUser;
import com.docqa.agentgateway.dto.UsageTotals;
import com.docqa.agentgateway.service.AdminAiUsageService;
import com.docqa.agentgateway.web.AgentExceptionHandler;
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
class AdminAiUsageControllerTest {

    private static final Instant FROM = Instant.parse("2026-11-02T09:00:00Z");
    private static final Instant TO = Instant.parse("2026-11-03T09:00:00Z");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final String PATH = "/api/v1/admin/ai/usage";

    @Mock
    private AdminAiUsageService usageService;

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
                .standaloneSetup(new AdminAiUsageController(usageService))
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
    void usesTheContractDefaultsAndRendersEverySection() throws Exception {
        when(usageService.summarize(null, null, null, 10)).thenReturn(summary(2140.5));

        mockMvc.perform(get(PATH))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.from").value("2026-11-02T09:00:00Z"))
                .andExpect(jsonPath("$.data.to").value("2026-11-03T09:00:00Z"))
                .andExpect(jsonPath("$.data.bucket").value("day"))
                .andExpect(jsonPath("$.data.totals.questions").value(128))
                .andExpect(jsonPath("$.data.totals.budgetExhausted").value(6))
                .andExpect(jsonPath("$.data.totals.failed").value(9))
                .andExpect(jsonPath("$.data.totals.tokens").value(412882))
                .andExpect(jsonPath("$.data.totals.avgLatencyMs").value(2140.5))
                .andExpect(jsonPath("$.data.byDay[0].date").value("2026-11-02"))
                .andExpect(jsonPath("$.data.byStatus[0].status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.topUsers[0].username").value("zhang.wei"));
    }

    @Test
    void rendersANullAverageAsAbsent() throws Exception {
        when(usageService.summarize(null, null, null, 10)).thenReturn(summary(null));

        mockMvc.perform(get(PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totals.avgLatencyMs").doesNotExist());
    }

    @Test
    void forwardsTheWindowBucketAndTopUserCount() throws Exception {
        when(usageService.summarize(
                "2026-11-02T09:00:00Z", "2026-11-03T09:00:00Z", "day", 50))
                .thenReturn(summary(1.0));

        mockMvc.perform(get(PATH)
                        .param("from", "2026-11-02T09:00:00Z")
                        .param("to", "2026-11-03T09:00:00Z")
                        .param("bucket", "day")
                        .param("topUsers", "50"))
                .andExpect(status().isOk());
    }

    @Test
    void mapsServiceValidationFailuresToFourHundred() throws Exception {
        when(usageService.summarize(any(), any(), eq("hour"), eq(10)))
                .thenThrow(new BusinessException(ErrorCode.COM_VALIDATION_001, "unsupported bucket"));

        mockMvc.perform(get(PATH).param("bucket", "hour"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COM-VALIDATION-001"));
    }

    @Test
    void mapsNonNumericTopUserCountsToGatewayValidation() throws Exception {
        mockMvc.perform(get(PATH).param("topUsers", "many"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AGT-VALIDATION-001"))
                .andExpect(jsonPath("$.errors[0].field").value("topUsers"));
    }

    private static UsageSummary summary(Double avgLatencyMs) {
        return new UsageSummary(
                FROM,
                TO,
                "day",
                new UsageTotals(128, 113, 6, 9, 412_882, 74, 301, 17, avgLatencyMs),
                List.of(new UsageDayPoint("2026-11-02", 61, 198_402, 5)),
                List.of(new UsageStatusCount("SUCCESS", 113)),
                List.of(new UsageTopUser(USER_ID, "zhang.wei", 44, 142_731)));
    }
}
