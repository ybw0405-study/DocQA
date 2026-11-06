package com.docqa.agentgateway.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.docqa.agentgateway.service.AuthenticatedUser;
import com.docqa.agentgateway.service.JwtAuthenticator;
import com.docqa.common.error.BusinessException;
import com.docqa.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Mock
    private JwtAuthenticator jwtAuthenticator;

    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        filter = new JwtAuthenticationFilter(jwtAuthenticator, new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void setsUserIdAttributeForAuthenticatedRequest() throws Exception {
        when(jwtAuthenticator.authenticateIdentity("Bearer token")).thenReturn(identity("USER"));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/conversations");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer token");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(request.getAttribute(JwtAuthenticationFilter.USER_ID_ATTRIBUTE)).isEqualTo(USER_ID);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void rejectsAdminEndpointForRegularUser() throws Exception {
        when(jwtAuthenticator.authenticateIdentity("Bearer token")).thenReturn(identity("USER"));
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/api/v1/admin/ai/controls");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        assertThat(response.getContentType()).contains(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(response.getContentAsString()).contains("USR-FORBIDDEN-001");
        assertThat(response.getContentAsString()).doesNotContain("ADMIN", "USER");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void allowsAdminEndpointForAdministrator() throws Exception {
        when(jwtAuthenticator.authenticateIdentity("Bearer token")).thenReturn(identity("ADMIN"));
        MockHttpServletRequest request =
                new MockHttpServletRequest("PUT", "/api/v1/admin/ai/controls");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer token");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(request.getAttribute(JwtAuthenticationFilter.USER_ID_ATTRIBUTE)).isEqualTo(USER_ID);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void writesProblemDetailWhenAuthenticationFails() throws Exception {
        when(jwtAuthenticator.authenticateIdentity(any()))
                .thenThrow(new BusinessException(ErrorCode.USR_AUTH_001));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/conversations/x/ask");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(response.getContentType()).contains(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(response.getContentAsString()).contains("USR-AUTH-001");
        assertThat(response.getContentAsString()).doesNotContain("stack");
    }

    @Test
    void skipsBearerExemptAuthEndpoints() throws Exception {
        for (String path : new String[] {
                "/api/v1/auth/login", "/api/v1/auth/register", "/api/v1/auth/refresh", "/api/v1/auth/logout"}) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", path);

            filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        }

        verify(jwtAuthenticator, never()).authenticateIdentity(any());
    }

    @Test
    void stillGuardsAuthenticatedAuthEndpoints() throws Exception {
        when(jwtAuthenticator.authenticateIdentity(any()))
                .thenThrow(new BusinessException(ErrorCode.USR_AUTH_001));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/auth/me");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        verify(jwtAuthenticator).authenticateIdentity(any());
    }

    @Test
    void skipsPreflightRequests() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/v1/conversations");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        verify(jwtAuthenticator, never()).authenticateIdentity(any());
    }

    @Test
    void skipsNonApiPaths() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        verify(jwtAuthenticator, never()).authenticateIdentity(any());
    }

    private static AuthenticatedUser identity(String... roles) {
        return new AuthenticatedUser(USER_ID, Set.of(roles));
    }
}
