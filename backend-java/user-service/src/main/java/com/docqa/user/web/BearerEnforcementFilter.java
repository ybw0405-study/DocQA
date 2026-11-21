package com.docqa.user.web;

import com.docqa.common.error.ErrorCode;
import com.docqa.common.error.ProblemDetailFactory;
import com.docqa.common.web.PublicPaths;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * 方案 4.2 步 4：user-service 改为「默认拦截 + PublicPaths 白名单」。
 *
 * <p>此前 user-service 没有统一门禁，鉴权全靠各 controller 自己调用
 * {@code AccessTokenAuthenticator}；新增端点一旦忘记调用就会裸奔（其它三个服务早已是
 * 默认拦截形态）。这里补上结构性的一层：{@code /api/v1/**} 下除 {@link PublicPaths}
 * 登记的免 Bearer 端点外，一律要求 Authorization 头是 Bearer 形态。
 *
 * <p><b>不碰业务边界</b>：本过滤器只校验「头存在且形态正确」，不验签、不解析角色、
 * 不写 TenantContext。真正的验签、撤销判定与角色校验仍由各 controller 的
 * {@code AccessTokenAuthenticator} 负责，因此这层守卫不改变任何业务语义，
 * 也不会把身份来源从已验签 JWT 挪走。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class BearerEnforcementFilter extends OncePerRequestFilter {

    private static final String API_PATH_PREFIX = "/api/v1/";
    private static final String BEARER_PREFIX = "Bearer ";

    private final ObjectMapper objectMapper;

    public BearerEnforcementFilter(ObjectMapper objectMapper) {
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
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!hasBearerShape(request.getHeader(HttpHeaders.AUTHORIZATION))) {
            ProblemDetail problem = ProblemDetailFactory.create(
                    ErrorCode.USR_AUTH_001, null, List.of(), request.getRequestURI());
            response.setStatus(ErrorCode.USR_AUTH_001.status().value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            objectMapper.writeValue(response.getOutputStream(), problem);
            return;
        }
        filterChain.doFilter(request, response);
    }

    /** 只判形态，不判有效性；空 token 与内嵌空白同样拒绝，与 AccessTokenAuthenticator 同口径。 */
    static boolean hasBearerShape(String header) {
        if (header == null) {
            return false;
        }
        String value = header.trim();
        if (!value.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return false;
        }
        String token = value.substring(BEARER_PREFIX.length()).trim();
        return !token.isEmpty() && token.indexOf(' ') < 0 && token.indexOf('\t') < 0;
    }
}
