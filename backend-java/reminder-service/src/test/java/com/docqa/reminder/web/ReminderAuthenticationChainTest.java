package com.docqa.reminder.web;

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
import com.docqa.reminder.service.JwtAuthenticator;
import com.docqa.reminder.service.NotificationService;
import com.docqa.reminder.service.ReminderService;
import com.docqa.reminder.service.ReminderStatusService;
import com.docqa.reminder.service.ScheduleImportService;
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
 * 跨越真实过滤器链的鉴权取证（{@code S10-H1} 的回归位）。
 *
 * <p>本模块既有控制器测试都是 standalone MockMvc 并显式 {@code .requestAttr(...)} 注入用户身份，
 * 等于绕过了 {@link JwtAuthenticationFilter}：过滤器写不进属性时那些用例照样绿。
 * 这里用真实 Web 应用上下文，请求先过过滤器再进 DispatcherServlet，身份只能由过滤器写入，
 * 所以「属性没写入」会如实暴露成 401/500，而不是被测试桩掩盖。
 *
 * <p>{@code @Import(CommonWebAutoConfiguration.class)} 是取证成立的前提：该自动配置由 common 经
 * {@code AutoConfiguration.imports} 注册，{@code @WebMvcTest} 不扫描别的模块，缺了它就没有
 * {@code GlobalExceptionHandler}，绑定失败只会落到 Spring 默认的 400 而不是生产环境的
 * 500 {@code COM-INTERNAL-001}——症状被裁剪掉了，反例也就测不出来。
 */
@WebMvcTest
@Import(CommonWebAutoConfiguration.class)
class ReminderAuthenticationChainTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String NOTIFICATIONS = "/api/v1/notifications";
    private static final String NOTIFICATIONS_READ = "/api/v1/notifications/read";
    private static final String CONTROLLER_PACKAGE_PREFIX = "com.docqa.reminder.controller";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @MockBean
    private JwtAuthenticator jwtAuthenticator;

    @MockBean
    private NotificationService notificationService;

    @MockBean
    private ReminderService reminderService;

    @MockBean
    private ReminderStatusService reminderStatusService;

    @MockBean
    private ScheduleImportService scheduleImportService;

    @Test
    void listNotificationsWithoutTokenIsRejectedBeforeTheController() throws Exception {
        when(jwtAuthenticator.authenticate(null)).thenThrow(new BusinessException(ErrorCode.USR_AUTH_001));

        mockMvc.perform(get(NOTIFICATIONS))
                .andExpect(status().isUnauthorized())
                // 兼容匹配而非精确：common 的 JsonCharsetFilter 会在出参时补 charset=UTF-8，
                // 契约只规定 problem+json，字符集不是本用例要守的东西。
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("USR-AUTH-001"));

        verifyNoInteractions(notificationService);
    }

    @Test
    void markReadWithoutTokenIsRejectedBeforeTheController() throws Exception {
        when(jwtAuthenticator.authenticate(null)).thenThrow(new BusinessException(ErrorCode.USR_AUTH_001));

        mockMvc.perform(post(NOTIFICATIONS_READ)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"all\":true}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("USR-AUTH-001"));

        verifyNoInteractions(notificationService);
    }

    /** 带合法令牌时属性由过滤器写入，{@code @RequestAttribute} 绑定成功，控制器拿到同一个用户。 */
    @Test
    void listNotificationsWithTokenBindsUserIdFromTheFilter() throws Exception {
        when(jwtAuthenticator.authenticate("Bearer token")).thenReturn(USER_ID);

        mockMvc.perform(get(NOTIFICATIONS).header(HttpHeaders.AUTHORIZATION, "Bearer token"))
                .andExpect(status().isOk());

        verify(notificationService).list(eq(USER_ID), eq(false), any(), anyInt());
    }

    @Test
    void reminderListIsGuardedByTheSameFilter() throws Exception {
        when(jwtAuthenticator.authenticate(null)).thenThrow(new BusinessException(ErrorCode.USR_AUTH_001));

        mockMvc.perform(get("/api/v1/reminders"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(reminderService);
    }

    /**
     * 覆盖守卫：本模块每条业务映射路径都必须被过滤器拦截，「新增控制器却没被鉴权覆盖」当场落红。
     * 这正是 {@code S10-H1} 的成因形态——旧白名单只认 {@code /api/v1/reminders}，通知前缀整体漏网。
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
        assertThat(mapped).contains(NOTIFICATIONS, NOTIFICATIONS_READ);
        for (String path : mapped) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
            assertThat(filter.shouldNotFilter(request))
                    .as("路径 %s 必须经过 Bearer 鉴权", path)
                    .isFalse();
        }
    }
}
