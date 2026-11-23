package com.docqa.agentgateway.controller;

import com.docqa.agentgateway.config.ObservabilityProperties;
import com.docqa.agentgateway.dto.ObservabilityData;
import com.docqa.common.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理面可观测性入口；ADMIN 守卫在 JwtAuthenticationFilter 的 {@code /api/v1/admin/} 前缀上。
 * 只读部署配置，不投递 MQ、不调用 Python、不触发 LLM。
 */
@RestController
@RequestMapping("/api/v1/admin/observability")
public class AdminObservabilityController {

    private final ObservabilityProperties observabilityProperties;

    public AdminObservabilityController(ObservabilityProperties observabilityProperties) {
        this.observabilityProperties = observabilityProperties;
    }

    @GetMapping
    public ApiResponse<ObservabilityData> get() {
        return ApiResponse.ok(new ObservabilityData(observabilityProperties.getLangfuseBaseUrl()));
    }
}
