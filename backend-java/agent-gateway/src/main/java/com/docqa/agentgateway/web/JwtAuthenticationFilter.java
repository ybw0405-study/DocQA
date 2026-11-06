package com.docqa.agentgateway.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.docqa.agentgateway.service.AuthenticatedUser;
import com.docqa.agentgateway.service.JwtAuthenticator;
import com.docqa.common.error.BusinessException;
import com.docqa.common.error.ErrorCode;
import com.docqa.common.error.ProblemDetailFactory;
import com.docqa.common.web.PublicPaths;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String USER_ID_ATTRIBUTE = "docqa.userId";

    static final String ADMIN_PATH_PREFIX = "/api/v1/admin/";

    private final JwtAuthenticator jwtAuthenticator;
    private final ObjectMapper objectMapper;

    public JwtAuthenticationFilter(JwtAuthenticator jwtAuthenticator, ObjectMapper objectMapper) {
        this.jwtAuthenticator = jwtAuthenticator;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        if (!path.startsWith("/api/v1/")) {
            return true;
        }
        return PublicPaths.isBearerExempt(path);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        try {
            AuthenticatedUser user = jwtAuthenticator.authenticateIdentity(
                    request.getHeader(HttpHeaders.AUTHORIZATION));
            if (request.getRequestURI().startsWith(ADMIN_PATH_PREFIX) && !user.isAdmin()) {
                writeProblem(request, response, ErrorCode.USR_FORBIDDEN_001);
                return;
            }
            request.setAttribute(USER_ID_ATTRIBUTE, user.userId());
            filterChain.doFilter(request, response);
        } catch (BusinessException ex) {
            writeProblem(request, response, ErrorCode.USR_AUTH_001);
        }
    }

    private void writeProblem(
            HttpServletRequest request,
            HttpServletResponse response,
            ErrorCode errorCode) throws IOException {
        ProblemDetail problem = ProblemDetailFactory.create(
                errorCode,
                null,
                List.of(),
                request.getRequestURI());
        response.setStatus(errorCode.status().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
