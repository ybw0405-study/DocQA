package com.docqa.agentgateway.controller;

import com.docqa.agentgateway.dto.AuditEventListData;
import com.docqa.agentgateway.service.AdminAuditQueryService;
import com.docqa.common.api.ApiResponse;
import com.docqa.common.tenant.TenantContext;
import java.util.UUID;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 管理面审计查询；ADMIN 守卫在 JwtAuthenticationFilter 的 `/api/v1/admin/` 前缀上。 */
@RestController
@RequestMapping("/api/v1/admin/audit/events")
public class AdminAuditController {

    private final AdminAuditQueryService auditQueryService;

    public AdminAuditController(AdminAuditQueryService auditQueryService) {
        this.auditQueryService = auditQueryService;
    }

    @GetMapping
    public ApiResponse<AuditEventListData> list(
            @RequestAttribute(TenantContext.REQUEST_ATTRIBUTE) UUID tenantId,
            @RequestParam(name = "traceId", required = false) String traceId,
            @RequestParam(name = "userId", required = false) String userId,
            @RequestParam(name = "eventType", required = false) String eventType,
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            @RequestParam(name = "limit", defaultValue = "100") int limit,
            @RequestParam(name = "cursor", required = false) String cursor) {
        return ApiResponse.ok(
                auditQueryService.list(tenantId, traceId, userId, eventType, from, to, limit, cursor));
    }
}
