package com.docqa.user.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.docqa.common.error.BusinessException;
import com.docqa.common.error.ErrorCode;
import com.docqa.common.trace.TraceIdContext;
import com.docqa.common.web.GlobalExceptionHandler;
import com.docqa.common.web.TraceIdFilter;
import com.docqa.user.dto.CreateTenantRequest;
import com.docqa.user.dto.TenantCreatedData;
import com.docqa.user.service.TenantProvisioningService;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class TenantAdminControllerTest {

    private static final String URL = "/api/v1/admin/tenants";
    private static final String TOKEN = "bootstrap-secret-with-enough-entropy";
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    private TenantProvisioningService provisioningService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        provisioningService = mock(TenantProvisioningService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new TenantAdminController(provisioningService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new TraceIdFilter())
                .build();
        TraceIdContext.clear();
    }

    @AfterEach
    void tearDown() {
        TraceIdContext.clear();
    }

    @Test
    void returnsCreatedTenantWithTraceId() throws Exception {
        when(provisioningService.create(eq(TOKEN), any(CreateTenantRequest.class)))
                .thenReturn(new TenantCreatedData(TENANT_ID, "acme", "ACTIVE"));

        mockMvc.perform(post(URL)
                        .header(TenantAdminController.TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"slug":"acme","name":"Acme Inc","tier":"STANDARD","isolation":"SHARED"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(TENANT_ID.toString()))
                .andExpect(jsonPath("$.data.slug").value("acme"))
                .andExpect(jsonPath("$.traceId").value(matchesPattern("^[0-9a-f]{32}$")));

        verify(provisioningService).create(eq(TOKEN), any(CreateTenantRequest.class));
    }

    @Test
    void mapsForbiddenTokenTo403() throws Exception {
        when(provisioningService.create(any(), any(CreateTenantRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.USR_FORBIDDEN_001, "租户供给令牌无效"));

        mockMvc.perform(post(URL)
                        .header(TenantAdminController.TOKEN_HEADER, "wrong")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"slug":"acme","name":"Acme Inc","tier":"STANDARD","isolation":"SHARED"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.USR_FORBIDDEN_001.code()));
    }

    @Test
    void rejectsInvalidTierBeforeReachingService() throws Exception {
        mockMvc.perform(post(URL)
                        .header(TenantAdminController.TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"slug":"acme","name":"Acme Inc","tier":"GOLD","isolation":"SHARED"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void forwardsNullTokenWhenHeaderAbsent() throws Exception {
        when(provisioningService.create(any(), any(CreateTenantRequest.class)))
                .thenReturn(new TenantCreatedData(TENANT_ID, "acme", "ACTIVE"));

        mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"slug":"acme","name":"Acme Inc","tier":"STANDARD","isolation":"SHARED"}
                                """))
                .andExpect(status().isCreated());

        verify(provisioningService).create(eq(null), any(CreateTenantRequest.class));
    }
}
