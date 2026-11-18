package com.docqa.reminder.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.docqa.common.error.BusinessException;
import com.docqa.common.error.ErrorCode;
import com.docqa.common.error.ProblemDetailFactory;
import com.docqa.common.tenant.TenantContext;
import com.docqa.common.web.PublicPaths;
import com.docqa.reminder.service.AuthenticatedUser;
import com.docqa.reminder.service.JwtAuthenticator;
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

/**
 * 提醒服务的 Bearer 门禁。判定形态与 {@code agent-gateway} 的同名过滤器一致：<strong>默认拦截</strong>
 * {@code /api/v1/} 下的全部请求，只放行预检与 {@link PublicPaths} 登记的免 Bearer 端点。
 *
 * <p>此处曾是「只拦已知前缀」的白名单（仅 {@code /api/v1/reminders}），{@code /api/v1/notifications}
 * 因此整体跳过鉴权、{@code USER_ID_ATTRIBUTE} 从不写入，两个通知操作恒返回 500（{@code S10-H1}）。
 * 白名单要人记得来登记，默认拦截则由结构本身保证新端点落到鉴权一侧。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String USER_ID_ATTRIBUTE = "docqa.userId";

    /** 业务 API 的统一前缀；{@code /actuator/**} 等非 API 路径不在此门禁之内。 */
    private static final String API_PATH_PREFIX = "/api/v1/";

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
        if (!path.startsWith(API_PATH_PREFIX)) {
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
            request.setAttribute(USER_ID_ATTRIBUTE, user.userId());
            request.setAttribute(TenantContext.REQUEST_ATTRIBUTE, user.tenantId());
            TenantContext.set(user.tenantId());
            try {
                filterChain.doFilter(request, response);
            } finally {
                TenantContext.clear();
            }
        } catch (BusinessException ex) {
            writeUnauthorized(request, response);
        }
    }

    private void writeUnauthorized(HttpServletRequest request, HttpServletResponse response) throws IOException {
        ProblemDetail problem = ProblemDetailFactory.create(
                ErrorCode.USR_AUTH_001,
                null,
                List.of(),
                request.getRequestURI());
        response.setStatus(ErrorCode.USR_AUTH_001.status().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
