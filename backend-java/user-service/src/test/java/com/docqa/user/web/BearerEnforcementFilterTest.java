package com.docqa.user.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** 方案 4.2 步 4：默认拦截 + PublicPaths 白名单的四种路径。 */
class BearerEnforcementFilterTest {

    private final BearerEnforcementFilter filter = new BearerEnforcementFilter(new ObjectMapper());

    private static final class RecordingChain implements FilterChain {
        private boolean reached;

        @Override
        public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) {
            reached = true;
        }
    }

    @Test
    void publicPathsAreExemptWithoutAnyHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        MockHttpServletResponse response = new MockHttpServletResponse();
        RecordingChain chain = new RecordingChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.reached).isTrue();
    }

    @Test
    void optionsAndNonApiPathsAreExempt() throws Exception {
        for (String[] spec : new String[][] {
                {"OPTIONS", "/api/v1/users/1"},
                {"GET", "/actuator/health"},
        }) {
            MockHttpServletRequest request = new MockHttpServletRequest(spec[0], spec[1]);
            RecordingChain chain = new RecordingChain();
            filter.doFilter(request, new MockHttpServletResponse(), chain);
            assertThat(chain.reached).as(spec[0] + " " + spec[1]).isTrue();
        }
    }

    @Test
    void apiPathWithoutBearerIsRejectedBeforeReachingTheController() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/users/1");
        request.setRequestURI("/api/v1/users/1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        RecordingChain chain = new RecordingChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.reached).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void apiPathWithBearerReachesTheController() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/users/1");
        request.addHeader("Authorization", "Bearer header.payload.signature");
        RecordingChain chain = new RecordingChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.reached).isTrue();
    }

    @Test
    void shapeCheckRejectsMalformedBearerValues() {
        assertThat(BearerEnforcementFilter.hasBearerShape(null)).isFalse();
        assertThat(BearerEnforcementFilter.hasBearerShape("Basic dXNlcjpwYXNz")).isFalse();
        assertThat(BearerEnforcementFilter.hasBearerShape("Bearer ")).isFalse();
        assertThat(BearerEnforcementFilter.hasBearerShape("Bearer a b")).isFalse();
        assertThat(BearerEnforcementFilter.hasBearerShape("Bearer token")).isTrue();
    }
}
