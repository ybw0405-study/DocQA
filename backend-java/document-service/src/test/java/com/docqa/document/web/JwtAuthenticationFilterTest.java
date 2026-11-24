package com.docqa.document.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.docqa.common.error.BusinessException;
import com.docqa.common.error.ErrorCode;
import com.docqa.common.tenant.TenantContext;
import com.docqa.document.service.AuthenticatedUser;
import com.docqa.document.service.JwtAuthenticator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class JwtAuthenticationFilterTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private final JwtAuthenticator jwtAuthenticator = mock(JwtAuthenticator.class);
    private final ObjectMapper objectMapper = new ObjectMapper()
            .addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class);
    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        filter = new JwtAuthenticationFilter(jwtAuthenticator, objectMapper);
    }

    @Test
    void validTokenSetsUserIdAttribute() throws Exception {
        when(jwtAuthenticator.authenticateIdentity("Bearer token"))
                .thenReturn(new AuthenticatedUser(USER_ID, TENANT_ID));
        MockHttpServletRequest request = documentRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(request.getAttribute(JwtAuthenticationFilter.USER_ID_ATTRIBUTE)).isEqualTo(USER_ID);
        assertThat(request.getAttribute(TenantContext.REQUEST_ATTRIBUTE)).isEqualTo(TENANT_ID);
        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void missingTokenReturnsProblemWithoutCallingChain() throws Exception {
        when(jwtAuthenticator.authenticateIdentity(null))
                .thenThrow(new BusinessException(ErrorCode.USR_AUTH_001));
        MockHttpServletRequest request = documentRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(response.getContentAsString()).contains("USR-AUTH-001");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void optionsRequestsAreNotFiltered() throws Exception {
        MockHttpServletRequest request = documentRequest();
        request.setMethod("OPTIONS");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        verifyNoInteractions(jwtAuthenticator);
    }

    @Test
    void nonApiPathsAreNotFiltered() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        verifyNoInteractions(jwtAuthenticator);
    }

    @Test
    void authenticatorIsInvokedForDocumentPaths() throws Exception {
        when(jwtAuthenticator.authenticateIdentity(null)).thenThrow(new BusinessException(ErrorCode.USR_AUTH_001));
        MockHttpServletRequest request = documentRequest();

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        verify(jwtAuthenticator).authenticateIdentity(null);
        assertThat(List.of(request.getRequestURI())).contains("/api/v1/documents/upload/init");
    }

    /**
     * {@code } 的单元级回归：旧白名单只认 {@code /api/v1/documents}，其他业务前缀整体漏网、
     * 属性从不写入。默认拦截后，任何 {@code /api/v1/} 路径都要先过鉴权——包括本服务未注册的路径。
     */
    @Test
    void pathsOutsideTheDocumentsPrefixAreFiltered() throws Exception {
        when(jwtAuthenticator.authenticateIdentity(null)).thenThrow(new BusinessException(ErrorCode.USR_AUTH_001));
        MockHttpServletRequest request = apiRequest("GET", "/api/v1/reminders");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        verify(jwtAuthenticator).authenticateIdentity(null);
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
    }

    /**
     * 反方向：{@link com.docqa.common.web.PublicPaths} 登记的免 Bearer 端点必须放行。
     * 缺了这条，「把门禁收紧成拦截一切」也能让上面几条全绿——守卫要双向承重。
     */
    @Test
    void bearerExemptPathsArePassedThrough() throws Exception {
        MockHttpServletRequest request = apiRequest("POST", "/api/v1/auth/login");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        verifyNoInteractions(jwtAuthenticator);
    }

    /**
     * 默认拦截的必然结果：{@code /api/v1/} 下未映射的路径先被 401，再由 MVC 报 404。
     * 失败方向是安全的一侧，代价是枚举不存在路径的攻击者会看到 401 而不是 404。
     */
    @Test
    void unmappedApiPathsAreStillAuthenticatedBeforeReachingMvc() throws Exception {
        when(jwtAuthenticator.authenticateIdentity(null)).thenThrow(new BusinessException(ErrorCode.USR_AUTH_001));
        MockHttpServletRequest request = apiRequest("GET", "/api/v1/not-mapped");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        verify(jwtAuthenticator).authenticateIdentity(null);
        assertThat(response.getStatus()).isEqualTo(401);
    }

    private MockHttpServletRequest documentRequest() {
        return new MockHttpServletRequest("POST", "/api/v1/documents/upload/init");
    }

    private MockHttpServletRequest apiRequest(String method, String path) {
        return new MockHttpServletRequest(method, path);
    }
}
