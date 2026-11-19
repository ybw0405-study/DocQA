package com.docqa.user.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.docqa.common.error.BusinessException;
import com.docqa.common.error.ErrorCode;
import com.docqa.common.trace.TraceIdContext;
import com.docqa.common.web.GlobalExceptionHandler;
import com.docqa.common.web.TraceIdFilter;
import com.docqa.user.config.JwtProperties;
import com.docqa.user.domain.UserRole;
import com.docqa.user.domain.UserStatus;
import com.docqa.user.dto.UserListData;
import com.docqa.user.dto.UserSummaryData;
import com.docqa.user.service.AccessTokenAuthenticator;
import com.docqa.user.service.AdminUserService;
import com.docqa.user.service.JwtService;
import com.docqa.user.service.MutableClock;
import com.docqa.user.support.RecordingRevocationStore;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class UserControllerTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final UUID ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final UUID TARGET_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c3");
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d4");
    private static final UUID FOREIGN_TENANT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d5");
    private static final String IDEMPOTENCY_KEY = "idem-key-0001";

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private MutableClock clock;
    private JwtService jwtService;
    private RecordingRevocationStore revocationStore;
    private AdminUserService adminUserService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(NOW);
        JwtProperties jwtProperties = new JwtProperties();
        jwtProperties.setIssuer("test-issuer");
        jwtProperties.setAudience("test-audience");
        jwtProperties.setTtl(Duration.ofMinutes(15));
        jwtProperties.setSecret("test-jwt-secret-with-at-least-32-bytes-1234567890");
        jwtService = new JwtService(jwtProperties, clock);
        revocationStore = new RecordingRevocationStore();
        adminUserService = mock(AdminUserService.class);

        objectMapper.addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class);
        MappingJackson2HttpMessageConverter converter =
                new MappingJackson2HttpMessageConverter(objectMapper);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new UserController(
                        adminUserService, new AccessTokenAuthenticator(jwtService, revocationStore)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new TraceIdFilter())
                .setMessageConverters(converter)
                .build();
    }

    @AfterEach
    void tearDown() {
        TraceIdContext.clear();
    }

    @Test
    void adminCanListUsersWithFilters() throws Exception {
        UserSummaryData summary = summary(TARGET_ID, "zhang.wei");
        when(adminUserService.list(TENANT_ID, UserStatus.ACTIVE, UserRole.ADMIN, null, 5))
                .thenReturn(new UserListData(List.of(summary), "next-cursor", 5));

        mockMvc.perform(get("/api/v1/users")
                        .param("status", "ACTIVE")
                        .param("role", "ADMIN")
                        .param("limit", "5")
                        .header(HttpHeaders.AUTHORIZATION, bearer(ADMIN_ID, UserRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].userId").value(TARGET_ID.toString()))
                .andExpect(jsonPath("$.data.items[0].username").value("zhang.wei"))
                .andExpect(jsonPath("$.data.nextCursor").value("next-cursor"))
                .andExpect(jsonPath("$.data.limit").value(5));
    }

    @Test
    void listUsesTheDefaultPageSize() throws Exception {
        when(adminUserService.list(TENANT_ID, null, null, null, 20))
                .thenReturn(new UserListData(List.of(), null, 20));

        mockMvc.perform(get("/api/v1/users")
                        .header(HttpHeaders.AUTHORIZATION, bearer(ADMIN_ID, UserRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.limit").value(20));
        verify(adminUserService).list(TENANT_ID, null, null, null, 20);
    }

    @Test
    void listRejectsUnknownStatus() throws Exception {
        mockMvc.perform(get("/api/v1/users")
                        .param("status", "BANNED")
                        .header(HttpHeaders.AUTHORIZATION, bearer(ADMIN_ID, UserRole.ADMIN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COM-VALIDATION-001"))
                .andExpect(jsonPath("$.errors[0].field").value("status"));
        verify(adminUserService, never()).list(any(), any(), any(), any(), anyInt());
    }

    @Test
    void nonAdminIsForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/users")
                        .header(HttpHeaders.AUTHORIZATION, bearer(USER_ID, UserRole.USER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("USR-FORBIDDEN-001"));
    }

    @Test
    void missingTokenIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/users"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("USR-AUTH-001"));
    }

    @Test
    void unknownUserIsNotFound() throws Exception {
        when(adminUserService.get(TENANT_ID, TARGET_ID)).thenThrow(new BusinessException(ErrorCode.USR_NOT_FOUND_001));

        mockMvc.perform(get("/api/v1/users/{userId}", TARGET_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(ADMIN_ID, UserRole.ADMIN)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USR-NOT_FOUND-001"));
    }

    @Test
    void replaceRolesRequiresIdempotencyKey() throws Exception {
        mockMvc.perform(put("/api/v1/users/{userId}/roles", TARGET_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(ADMIN_ID, UserRole.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roles\":[\"ADMIN\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COM-VALIDATION-001"))
                .andExpect(jsonPath("$.errors[0].field").value("Idempotency-Key"));
    }

    @Test
    void replaceRolesDelegatesCallerIdentityAndTarget() throws Exception {
        when(adminUserService.replaceRoles(TENANT_ID, ADMIN_ID, TARGET_ID, IDEMPOTENCY_KEY, Set.of(UserRole.ADMIN)))
                .thenReturn(summary(TARGET_ID, "zhang.wei"));

        mockMvc.perform(put("/api/v1/users/{userId}/roles", TARGET_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(ADMIN_ID, UserRole.ADMIN))
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roles\":[\"ADMIN\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(TARGET_ID.toString()));

        verify(adminUserService)
                .replaceRoles(TENANT_ID, ADMIN_ID, TARGET_ID, IDEMPOTENCY_KEY, Set.of(UserRole.ADMIN));
    }

    @Test
    void updateStatusDelegatesToTheService() throws Exception {
        when(adminUserService.updateStatus(
                        TENANT_ID, ADMIN_ID, TARGET_ID, IDEMPOTENCY_KEY, UserStatus.DISABLED))
                .thenReturn(summary(TARGET_ID, "zhang.wei"));

        mockMvc.perform(patch("/api/v1/users/{userId}/status", TARGET_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(ADMIN_ID, UserRole.ADMIN))
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(TARGET_ID.toString()));
    }

    @Test
    void updateStatusRejectsUnknownStatus() throws Exception {
        mockMvc.perform(patch("/api/v1/users/{userId}/status", TARGET_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(ADMIN_ID, UserRole.ADMIN))
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"BANNED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COM-VALIDATION-001"));
        verify(adminUserService, never()).updateStatus(any(), any(), any(), any(), any());
    }

    @Test
    void revokedAccessTokenIsRejected() throws Exception {
        String token = bearer(ADMIN_ID, UserRole.ADMIN);
        revocationStore.revokeAccessToken(jwtService.verify(token.substring(7)).tokenId(), Duration.ofMinutes(15));

        mockMvc.perform(get("/api/v1/users")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("USR-AUTH-001"));
    }

    @Test
    void tokenIssuedBeforeTheUserWatermarkIsRejected() throws Exception {
        String token = bearer(ADMIN_ID, UserRole.ADMIN);
        revocationStore.revokeUser(ADMIN_ID, NOW.plusSeconds(1), Duration.ofMinutes(15));

        mockMvc.perform(get("/api/v1/users")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminTokenFromAnotherTenantCannotReadThisTenantsUser() throws Exception {
        when(adminUserService.get(FOREIGN_TENANT_ID, TARGET_ID))
                .thenThrow(new BusinessException(ErrorCode.USR_NOT_FOUND_001));

        mockMvc.perform(get("/api/v1/users/{userId}", TARGET_ID)
                        .header(HttpHeaders.AUTHORIZATION,
                                bearer(FOREIGN_TENANT_ID, ADMIN_ID, UserRole.ADMIN)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USR-NOT_FOUND-001"));

        verify(adminUserService).get(FOREIGN_TENANT_ID, TARGET_ID);
        verify(adminUserService, never()).get(TENANT_ID, TARGET_ID);
    }

    private String bearer(UUID userId, UserRole... roles) {
        return bearer(TENANT_ID, userId, roles);
    }

    private String bearer(UUID tenantId, UUID userId, UserRole... roles) {
        return "Bearer " + jwtService.issue(userId, tenantId, Set.of(roles));
    }

    private UserSummaryData summary(UUID userId, String username) {
        return new UserSummaryData(
                userId,
                username,
                UserStatus.ACTIVE,
                Set.of(UserRole.USER),
                NOW,
                null);
    }
}
