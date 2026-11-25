package com.docqa.agentgateway.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.docqa.agentgateway.dto.AuthorizeModelConnectionRequest;
import com.docqa.agentgateway.dto.CreateModelConnectionRequest;
import com.docqa.agentgateway.dto.ModelConnection;
import com.docqa.agentgateway.dto.ModelConnectionListData;
import com.docqa.agentgateway.dto.ModelProbeData;
import com.docqa.agentgateway.dto.ModelProbeFailure;
import com.docqa.agentgateway.dto.UpdateActivationRequest;
import com.docqa.agentgateway.service.ModelConnectionService;
import com.docqa.agentgateway.service.ModelPresetCatalog;
import com.docqa.agentgateway.web.AgentExceptionHandler;
import com.docqa.agentgateway.web.JwtAuthenticationFilter;
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
class AdminModelConnectionControllerTest {

    private static final UUID ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CONNECTION_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final Instant NOW = Instant.parse("2026-11-22T10:15:42.010Z");
    private static final String PATH = "/api/v1/admin/ai/model-connections";
    private static final String SECRET = "sk-controller-test-0123456789";

    @Mock
    private ModelConnectionService service;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        objectMapper.addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class);
        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter(objectMapper);
        converter.setSupportedMediaTypes(List.of(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON));
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AdminModelConnectionController(service, new ModelPresetCatalog()))
                .setControllerAdvice(new AgentExceptionHandler(), new GlobalExceptionHandler())
                .addFilters(new TraceIdFilter())
                .setMessageConverters(converter)
                .build();
    }

    @Test
    void servesTheVendorPresetCatalogWithoutAnyCredentialMaterial() throws Exception {
        mockMvc.perform(get(PATH + "/presets").requestAttr(TenantContext.REQUEST_ATTRIBUTE, TENANT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.items[0].id").value("deepseek"))
                .andExpect(jsonPath("$.data.items[0].baseUrl").value("https://api.deepseek.com/v1"))
                .andExpect(jsonPath("$.data.items[0].models").isArray())
                .andExpect(jsonPath("$.data.items[0].keyUrl").isNotEmpty())
                .andExpect(jsonPath("$.data.items[0].secret").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].credential").doesNotExist());
    }

    @AfterEach
    void tearDown() {
        TraceIdContext.clear();
    }

    @Test
    void listsConnectionsWithoutAnyCredentialMaterial() throws Exception {
        when(service.list(TENANT_ID)).thenReturn(new ModelConnectionListData(List.of(connection(true))));

        mockMvc.perform(get(PATH).requestAttr(TenantContext.REQUEST_ATTRIBUTE, TENANT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.items[0].id").value(CONNECTION_ID.toString()))
                .andExpect(jsonPath("$.data.items[0].hasCredential").value(true))
                .andExpect(jsonPath("$.data.items[0].active").value(true))
                .andExpect(jsonPath("$.data.items[0].probeStatus").value("OK"))
                .andExpect(jsonPath("$.data.items[0].credential").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].credentialKeyId").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].secret").doesNotExist());
    }

    @Test
    void createsWithTheTenantAndAdminTakenFromTheVerifiedJwt() throws Exception {
        when(service.upsert(eq(TENANT_ID), eq(ADMIN_ID), any(CreateModelConnectionRequest.class)))
                .thenReturn(connection(false));

        mockMvc.perform(post(PATH)
                        .requestAttr(TenantContext.REQUEST_ATTRIBUTE, TENANT_ID)
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, ADMIN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"DeepSeek","presetId":"deepseek",\
                                "baseUrl":"https://api.deepseek.com/v1","model":"deepseek-chat"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.model").value("deepseek-chat"));

        verify(service).upsert(TENANT_ID, ADMIN_ID,
                new CreateModelConnectionRequest("DeepSeek", "deepseek", "https://api.deepseek.com/v1",
                        "deepseek-chat", null));
    }

    @Test
    void rejectsMissingRequiredFieldsAndUnknownFields() throws Exception {
        mockMvc.perform(post(PATH)
                        .requestAttr(TenantContext.REQUEST_ATTRIBUTE, TENANT_ID)
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, ADMIN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"presetId\":\"deepseek\",\"baseUrl\":\"https://api.deepseek.com/v1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AGT-VALIDATION-001"));

        mockMvc.perform(post(PATH)
                        .requestAttr(TenantContext.REQUEST_ATTRIBUTE, TENANT_ID)
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, ADMIN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"DeepSeek","baseUrl":"https://api.deepseek.com/v1",\
                                "model":"deepseek-chat","secret":"sk-smuggled"}"""))
                .andExpect(status().isBadRequest());
    }

    /** 凭据只进不出：响应里不得出现 secret，连回显都不允许。 */
    @Test
    void acceptsTheSecretAndNeverEchoesItBack() throws Exception {
        when(service.authorize(TENANT_ID, ADMIN_ID, CONNECTION_ID, SECRET)).thenReturn(connection(true));

        mockMvc.perform(put(PATH + "/" + CONNECTION_ID + "/credential")
                        .requestAttr(TenantContext.REQUEST_ATTRIBUTE, TENANT_ID)
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, ADMIN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AuthorizeModelConnectionRequest(SECRET))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hasCredential").value(true))
                .andExpect(content().string(not(containsString(SECRET))));
    }

    @Test
    void rejectsBlankSecrets() throws Exception {
        mockMvc.perform(put(PATH + "/" + CONNECTION_ID + "/credential")
                        .requestAttr(TenantContext.REQUEST_ATTRIBUTE, TENANT_ID)
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, ADMIN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"secret\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AGT-VALIDATION-001"));
    }

    @Test
    void returnsProbeOutcomeAsATwoHundredWithAFailureReason() throws Exception {
        when(service.probe(TENANT_ID, ADMIN_ID, CONNECTION_ID))
                .thenReturn(ModelProbeData.failure(ModelProbeFailure.HTTP_4XX, "端点返回 HTTP 401"));

        mockMvc.perform(post(PATH + "/" + CONNECTION_ID + "/probe")
                        .requestAttr(TenantContext.REQUEST_ATTRIBUTE, TENANT_ID)
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, ADMIN_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ok").value(false))
                .andExpect(jsonPath("$.data.failureReason").value("HTTP_4XX"))
                .andExpect(jsonPath("$.data.models").isArray());
    }

    @Test
    void activationRequiresAnExplicitNonNullBoolean() throws Exception {
        when(service.setActivation(TENANT_ID, ADMIN_ID, CONNECTION_ID, true)).thenReturn(connection(true));

        mockMvc.perform(put(PATH + "/" + CONNECTION_ID + "/activation")
                        .requestAttr(TenantContext.REQUEST_ATTRIBUTE, TENANT_ID)
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, ADMIN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateActivationRequest(true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active").value(true));

        mockMvc.perform(put(PATH + "/" + CONNECTION_ID + "/activation")
                        .requestAttr(TenantContext.REQUEST_ATTRIBUTE, TENANT_ID)
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, ADMIN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":null}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deletesWithNoContent() throws Exception {
        mockMvc.perform(delete(PATH + "/" + CONNECTION_ID)
                        .requestAttr(TenantContext.REQUEST_ATTRIBUTE, TENANT_ID)
                        .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, ADMIN_ID))
                .andExpect(status().isNoContent());

        verify(service).delete(TENANT_ID, ADMIN_ID, CONNECTION_ID);
    }

    @Test
    void mapsBusinessFailuresToProblemJsonWithCodeAndTraceId() throws Exception {
        when(service.list(TENANT_ID)).thenThrow(new BusinessException(ErrorCode.COM_INTERNAL_001, "主密钥未配置"));

        mockMvc.perform(get(PATH).requestAttr(TenantContext.REQUEST_ATTRIBUTE, TENANT_ID))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("COM-INTERNAL-001"))
                .andExpect(jsonPath("$.detail").value("主密钥未配置"))
                .andExpect(jsonPath("$.traceId").value(matchesPattern("[0-9a-f]{32}")));
    }

    private static ModelConnection connection(boolean hasCredential) {
        return new ModelConnection(CONNECTION_ID, "DeepSeek", "deepseek", "https://api.deepseek.com/v1",
                "deepseek-chat", true, true, hasCredential, "OK", NOW, NOW, NOW, ADMIN_ID);
    }
}
