package com.docqa.agentgateway.controller;

import com.docqa.agentgateway.dto.UsageSummary;
import com.docqa.agentgateway.service.AdminAiUsageService;
import com.docqa.common.api.ApiResponse;
import com.docqa.common.tenant.TenantContext;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 管理面 AI 用量汇总；只读聚合，不触发 LLM。 */
@RestController
@RequestMapping("/api/v1/admin/ai/usage")
public class AdminAiUsageController {

    private final AdminAiUsageService usageService;

    public AdminAiUsageController(AdminAiUsageService usageService) {
        this.usageService = usageService;
    }

    @GetMapping
    public ApiResponse<UsageSummary> usage(
            @RequestAttribute(TenantContext.REQUEST_ATTRIBUTE) UUID tenantId,
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            @RequestParam(name = "bucket", required = false) String bucket,
            @RequestParam(name = "topUsers", defaultValue = "10") int topUsers) {
        return ApiResponse.ok(usageService.summarize(tenantId, from, to, bucket, topUsers));
    }
}
