package com.docqa.document.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.docqa.common.config.CommonWebAutoConfiguration;
import com.docqa.common.error.BusinessException;
import com.docqa.common.error.ErrorCode;
import com.docqa.common.tenant.TenantContext;
import com.docqa.document.service.AuthenticatedUser;
import com.docqa.document.service.DocumentService;
import com.docqa.document.service.JwtAuthenticator;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * 跨越真实过滤器链的鉴权取证（{@code S10-H1} 在文档服务的推广位，D-236）。
 *
 * <p>本模块既有控制器测试是 standalone MockMvc 并显式 {@code .requestAttr(...)} 注入用户身份，
 * 等于绕过了 {@link JwtAuthenticationFilter}：过滤器写不进属性时那些用例照样绿。这里用真实 Web
 * 应用上下文，请求先过过滤器再进 DispatcherServlet，身份只能由过滤器写入，所以「属性没写入」会
 * 如实暴露成 401/500，而不是被测试桩掩盖。
 *
 * <p>{@code @Import(CommonWebAutoConfiguration.class)} 是取证成立的前提：该自动配置由 common 经
 * {@code AutoConfiguration.imports} 注册，{@code @WebMvcTest} 不扫描别的模块，缺了它就没有
 * {@code GlobalExceptionHandler}，绑定失败只会落到 Spring 默认的 400 而不是生产环境的
 * 500 {@code COM-INTERNAL-001}——症状被裁剪掉了，反例也就测不出来。
 *
 * <p>带合法令牌的一支只取两条 GET（D-239）：七个写操作都要求 {@code Idempotency-Key} 头与请求体，
 * 缺任何一项都会先落 400，届时「200」证明的是别的东西。写侧属性绑定由 {@code DocumentControllerTest}
 * 的 standalone 用例覆盖，幂等语义归 S11-6。
 */
@WebMvcTest
@Import(CommonWebAutoConfiguration.class)
class DocumentAuthenticationChainTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TENANT_ID = TenantContext.DEFAULT_TENANT_ID;
    private static final UUID DOCUMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final String DOCUMENTS = "/api/v1/documents";
    private static final String DOCUMENT_STATUS = "/api/v1/documents/{documentId}/status";
    private static final String UPLOAD_INIT = "/api/v1/documents/upload/init";
    private static final String CONTROLLER_PACKAGE_PREFIX = "com.docqa.document.controller";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @MockBean
    private JwtAuthenticator jwtAuthenticator;

    @MockBean
    private DocumentService documentService;

    @Test
    void listWithoutTokenIsRejectedBeforeTheController() throws Exception {
        when(jwtAuthenticator.authenticateIdentity(null)).thenThrow(new BusinessException(ErrorCode.USR_AUTH_001));

        mockMvc.perform(get(DOCUMENTS))
                .andExpect(status().isUnauthorized())
                // 兼容匹配而非精确：common 的 JsonCharsetFilter 会在出参时补 charset=UTF-8，
                // 契约只规定 problem+json，字符集不是本用例要守的东西。
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("USR-AUTH-001"));

        verifyNoInteractions(documentService);
    }

    @Test
    void statusWithoutTokenIsRejectedBeforeTheController() throws Exception {
        when(jwtAuthenticator.authenticateIdentity(null)).thenThrow(new BusinessException(ErrorCode.USR_AUTH_001));

        mockMvc.perform(get("/api/v1/documents/" + DOCUMENT_ID + "/status"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("USR-AUTH-001"));

        verifyNoInteractions(documentService);
    }

    /** 带合法令牌时属性由过滤器写入，{@code @RequestAttribute} 绑定成功，控制器拿到同一个用户。 */
    @Test
    void listWithTokenBindsUserIdFromTheFilter() throws Exception {
        when(jwtAuthenticator.authenticateIdentity("Bearer token"))
                .thenReturn(new AuthenticatedUser(USER_ID, TENANT_ID));

        mockMvc.perform(get(DOCUMENTS).header(HttpHeaders.AUTHORIZATION, "Bearer token"))
                .andExpect(status().isOk());

        verify(documentService).list(eq(USER_ID), any(), any(), any(), any(), anyInt());
    }

    @Test
    void statusWithTokenBindsUserIdFromTheFilter() throws Exception {
        when(jwtAuthenticator.authenticateIdentity("Bearer token"))
                .thenReturn(new AuthenticatedUser(USER_ID, TENANT_ID));

        mockMvc.perform(get("/api/v1/documents/" + DOCUMENT_ID + "/status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer token"))
                .andExpect(status().isOk());

        verify(documentService).status(eq(USER_ID), eq(DOCUMENT_ID));
    }

    /**
     * 门禁的优先序：写端点缺 Bearer 时先 401，轮不到「缺 {@code Idempotency-Key} 头」的 400。
     * 这条与 D-239 的取舍不冲突——它断言的正是鉴权发生在参数校验之前。
     */
    @Test
    void uploadInitWithoutTokenIsRejectedBeforeHeaderValidation() throws Exception {
        when(jwtAuthenticator.authenticateIdentity(null)).thenThrow(new BusinessException(ErrorCode.USR_AUTH_001));

        mockMvc.perform(post(UPLOAD_INIT).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("USR-AUTH-001"));

        verifyNoInteractions(documentService);
    }

    /**
     * 覆盖守卫：本模块每条业务映射路径都必须被过滤器拦截，「新增控制器却没被鉴权覆盖」当场落红。
     * 这正是 {@code S10-H1} 的成因形态——旧白名单只认 {@code /api/v1/documents}，其余前缀整体漏网。
     *
     * <p>按控制器所在包筛选，而不是把 {@code /error} 逐条排除：{@code BasicErrorController} 与业务
     * 控制器共用同一张 MVC 映射表，它注册在 {@code org.springframework.boot.autoconfigure.web}，
     * 是框架的错误派发目标、本来就不该要求 Bearer。以包为界，新增业务控制器会自动进集合。
     */
    @Test
    void everyMappedPathIsAuthenticated() {
        // 按 bean 名取 MVC 的映射表：actuator 的端点映射同样继承 RequestMappingHandlerMapping，
        // 只声明类型会把它一起注入，而 /actuator/** 本就不该要求 Bearer。
        RequestMappingHandlerMapping handlerMapping = webApplicationContext.getBean(
                "requestMappingHandlerMapping", RequestMappingHandlerMapping.class);
        JwtAuthenticationFilter filter = webApplicationContext.getBean(JwtAuthenticationFilter.class);

        Set<String> mapped = new TreeSet<>();
        handlerMapping.getHandlerMethods().forEach((info, method) -> {
            if (method.getBeanType().getPackageName().startsWith(CONTROLLER_PACKAGE_PREFIX)) {
                mapped.addAll(info.getPatternValues());
            }
        });

        assertThat(mapped).as("本模块业务控制器映射到的路径集合非空").isNotEmpty();
        assertThat(mapped).contains(DOCUMENTS, DOCUMENT_STATUS, UPLOAD_INIT);
        for (String path : mapped) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
            assertThat(filter.shouldNotFilter(request))
                    .as("路径 %s 必须经过 Bearer 鉴权", path)
                    .isFalse();
        }
    }
}
