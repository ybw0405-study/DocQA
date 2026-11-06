package com.docqa.user.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.docqa.common.trace.TraceIdContext;
import com.docqa.common.web.GlobalExceptionHandler;
import com.docqa.common.web.TraceIdFilter;
import com.docqa.user.config.AuthProperties;
import com.docqa.user.config.JwtProperties;
import com.docqa.user.domain.AppUserRow;
import com.docqa.user.domain.RefreshTokenStatus;
import com.docqa.user.domain.UserCredentials;
import com.docqa.user.domain.UserRole;
import com.docqa.user.domain.UserStatus;
import com.docqa.user.dto.ChangePasswordRequest;
import com.docqa.user.dto.LoginRequest;
import com.docqa.user.error.AuthExceptionHandler;
import com.docqa.user.repository.RoleRepository;
import com.docqa.user.repository.UserRepository;
import com.docqa.user.service.AccessTokenAuthenticator;
import com.docqa.user.service.AuthService;
import com.docqa.user.service.IdempotencyGuard;
import com.docqa.user.service.JwtService;
import com.docqa.user.service.LoginRateLimiter;
import com.docqa.user.service.MutableClock;
import com.docqa.user.service.PasswordPolicy;
import com.docqa.user.service.RateLimitDecision;
import com.docqa.user.service.RefreshTokenService;
import com.docqa.user.service.RegistrationRateLimiter;
import com.docqa.user.support.InMemoryIdempotencyStore;
import com.docqa.user.support.InMemoryRefreshTokenRepository;
import com.docqa.user.support.RecordingRevocationStore;
import com.docqa.user.web.RefreshCookieFactory;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AuthControllerTest {

    private static final String USERNAME = "demo";
    private static final String PASSWORD = "test-password-123";
    private static final String NEW_PASSWORD = "new-battery-11";
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String TRACE_ID_PATTERN = "^[0-9a-f]{32}$";
    private static final String IDEMPOTENCY_KEY = "idem-key-0001";
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .findAndRegisterModules();

    private AuthProperties authProperties;
    private UserRepository userRepository;
    private RoleRepository roleRepository;
    private InMemoryRefreshTokenRepository refreshTokenRepository;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        objectMapper.addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class);
        MappingJackson2HttpMessageConverter converter =
                new MappingJackson2HttpMessageConverter(objectMapper);
        converter.setSupportedMediaTypes(
                List.of(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON));

        MutableClock clock = new MutableClock(NOW);
        PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);
        userRepository = mock(UserRepository.class);
        roleRepository = mock(RoleRepository.class);
        refreshTokenRepository = new InMemoryRefreshTokenRepository();

        UserCredentials credentials = new UserCredentials(
                USER_ID, USERNAME, passwordEncoder.encode(PASSWORD), UserStatus.ACTIVE);
        when(userRepository.findCredentialsByUsername(USERNAME)).thenReturn(Optional.of(credentials));
        when(userRepository.findCredentialsById(USER_ID)).thenReturn(Optional.of(credentials));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(
                new AppUserRow(USER_ID, USERNAME, UserStatus.ACTIVE, null, NOW)));
        when(userRepository.existsByUsername(any())).thenReturn(false);
        when(roleRepository.findRoles(any())).thenReturn(Set.of(UserRole.USER));

        authProperties = new AuthProperties();
        authProperties.setMaxLoginFailures(5);
        authProperties.setLoginFailureWindow(Duration.ofSeconds(60));
        authProperties.setRefreshTtl(Duration.ofDays(14));
        authProperties.setCookieSecure(false);

        JwtProperties jwtProperties = new JwtProperties();
        jwtProperties.setIssuer("test-issuer");
        jwtProperties.setAudience("test-audience");
        jwtProperties.setTtl(Duration.ofMinutes(15));
        jwtProperties.setSecret("test-jwt-secret-with-at-least-32-bytes-1234567890");
        JwtService jwtService = new JwtService(jwtProperties, clock);
        AccessTokenAuthenticator authenticator =
                new AccessTokenAuthenticator(jwtService, new RecordingRevocationStore());

        RegistrationRateLimiter registrationRateLimiter = mock(RegistrationRateLimiter.class);
        when(registrationRateLimiter.check(any())).thenReturn(RateLimitDecision.permit());

        AuthService authService = new AuthService(
                userRepository,
                roleRepository,
                new RefreshTokenService(refreshTokenRepository, authProperties, clock),
                authenticator,
                new PasswordPolicy(),
                new IdempotencyGuard(new InMemoryIdempotencyStore(), objectMapper, clock),
                registrationRateLimiter,
                new LoginRateLimiter(authProperties, clock),
                jwtService,
                passwordEncoder,
                authProperties,
                clock);

        mockMvc = MockMvcBuilders
                .standaloneSetup(new AuthController(
                        authService, authenticator, new RefreshCookieFactory(authProperties)))
                .setControllerAdvice(new AuthExceptionHandler(), new GlobalExceptionHandler())
                .addFilters(new TraceIdFilter())
                .setMessageConverters(converter)
                .build();
    }

    @AfterEach
    void tearDown() {
        TraceIdContext.clear();
    }

    @Test
    void loginReturnsTokenAndSetsRefreshCookie() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(USERNAME, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.expiresIn").value(900))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.traceId").value(matchesPattern(TRACE_ID_PATTERN)))
                .andReturn();

        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie)
                .contains("docqa_refresh=")
                .contains("HttpOnly")
                .contains("SameSite=Lax")
                .contains("Path=/api/v1/auth")
                .contains("Max-Age=1209600");
        assertThat(setCookie).doesNotContain("Secure");
        assertThat(result.getResponse().getContentAsString()).doesNotContain(setCookieToken(setCookie));
    }

    @Test
    void loginRejectsWrongPassword() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(USERNAME, "wrong-password")))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("USR-AUTH-001"))
                .andExpect(jsonPath("$.traceId").value(matchesPattern(TRACE_ID_PATTERN)));
    }

    @Test
    void loginRejectsBlankUsername() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("", PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("COM-VALIDATION-001"))
                .andExpect(jsonPath("$.errors[0].field").value("username"));
    }

    @Test
    void loginRejectsUnknownJsonField() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"demo\",\"password\":\"test-password-123\",\"extra\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COM-VALIDATION-001"));
    }

    @Test
    void loginRateLimitReturnsRetryAfter() throws Exception {
        for (int attempt = 0; attempt < 5; attempt++) {
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginJson(USERNAME, "wrong-password")))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(USERNAME, PASSWORD)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "60"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("USR-RATE_LIMIT-001"));
    }

    @Test
    void registerReturnsCreatedAndSetsCookie() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("new.user", "correct-horse-9")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andReturn();

        assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE))
                .contains("docqa_refresh=");
        assertThat(refreshTokenRepository.countByStatus(RefreshTokenStatus.ACTIVE)).isEqualTo(1);
    }

    @Test
    void registerReplayOmitsTheRefreshCookie() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("new.user", "correct-horse-9")))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/auth/register")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("new.user", "correct-horse-9")))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));

        assertThat(refreshTokenRepository.countByStatus(RefreshTokenStatus.ACTIVE)).isEqualTo(1);
    }

    @Test
    void registerRequiresIdempotencyKey() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("new.user", "correct-horse-9")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COM-VALIDATION-001"))
                .andExpect(jsonPath("$.errors[0].field").value("Idempotency-Key"));
    }

    @Test
    void refreshRotatesTheCookie() throws Exception {
        String original = setCookieToken(loginAndReadCookie());

        MvcResult result = mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie("docqa_refresh", original)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andReturn();

        String rotated = setCookieToken(result.getResponse().getHeader(HttpHeaders.SET_COOKIE));
        assertThat(rotated).isNotBlank().isNotEqualTo(original);
        assertThat(refreshTokenRepository.countByStatus(RefreshTokenStatus.USED)).isEqualTo(1);
        assertThat(refreshTokenRepository.countByStatus(RefreshTokenStatus.ACTIVE)).isEqualTo(1);
    }

    @Test
    void refreshRejectsMissingCookie() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("USR-REFRESH-001"));
    }

    @Test
    void logoutClearsTheCookieAndReturnsNoContent() throws Exception {
        String setCookie = loginAndReadCookie();
        String accessToken = loginAndReadAccessToken();

        MvcResult result = mockMvc.perform(post("/api/v1/auth/logout")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .cookie(new Cookie("docqa_refresh", setCookieToken(setCookie))))
                .andExpect(status().isNoContent())
                .andReturn();

        assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE))
                .contains("docqa_refresh=")
                .contains("Max-Age=0");
        assertThat(refreshTokenRepository.countByStatus(RefreshTokenStatus.REVOKED)).isEqualTo(1);
    }

    @Test
    void changePasswordReturnsNoContent() throws Exception {
        String accessToken = loginAndReadAccessToken();

        mockMvc.perform(post("/api/v1/auth/password")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ChangePasswordRequest(PASSWORD, NEW_PASSWORD))))
                .andExpect(status().isNoContent());
    }

    @Test
    void changePasswordRequiresBearerToken() throws Exception {
        mockMvc.perform(post("/api/v1/auth/password")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ChangePasswordRequest(PASSWORD, NEW_PASSWORD))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("USR-AUTH-001"));
    }

    @Test
    void changePasswordRequiresIdempotencyKey() throws Exception {
        String accessToken = loginAndReadAccessToken();

        mockMvc.perform(post("/api/v1/auth/password")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ChangePasswordRequest(PASSWORD, NEW_PASSWORD))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("Idempotency-Key"));
    }

    @Test
    void currentUserReturnsTokenRolesAndFreshStatus() throws Exception {
        String accessToken = loginAndReadAccessToken();

        mockMvc.perform(get("/api/v1/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(USER_ID.toString()))
                .andExpect(jsonPath("$.data.username").value(USERNAME))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.roles[0]").value("USER"))
                .andExpect(jsonPath("$.data.accessTokenExpiresIn").value(900));
    }

    @Test
    void currentUserRejectsMissingToken() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("USR-AUTH-001"));
    }

    private String loginAndReadCookie() throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(USERNAME, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getHeader(HttpHeaders.SET_COOKIE);
    }

    private String loginAndReadAccessToken() throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(USERNAME, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).path("data").path("accessToken").asText();
    }

    private String setCookieToken(String setCookieHeader) {
        String pair = setCookieHeader.split(";", 2)[0];
        return pair.substring(pair.indexOf('=') + 1);
    }

    private String loginJson(String username, String password) throws Exception {
        return objectMapper.writeValueAsString(new LoginRequest(username, password));
    }

    private String registerJson(String username, String password) throws Exception {
        return "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
    }
}
