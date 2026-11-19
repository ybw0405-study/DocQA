package com.docqa.agentgateway.controller;

import com.docqa.agentgateway.dto.AdminAiControls;
import com.docqa.agentgateway.dto.KillSwitchState;
import com.docqa.agentgateway.dto.UpdateAiControlsRequest;
import com.docqa.agentgateway.service.AdminAiControlsService;
import com.docqa.agentgateway.web.JwtAuthenticationFilter;
import com.docqa.common.api.ApiResponse;
import com.docqa.common.tenant.TenantContext;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/ai/controls")
public class AdminAiControlsController {

    private final AdminAiControlsService controlsService;

    public AdminAiControlsController(AdminAiControlsService controlsService) {
        this.controlsService = controlsService;
    }

    @GetMapping
    public ApiResponse<AdminAiControls> get() {
        return ApiResponse.ok(controlsService.getControls());
    }

    @PutMapping
    public ApiResponse<AdminAiControls> update(
            @RequestAttribute(TenantContext.REQUEST_ATTRIBUTE) UUID tenantId,
            @RequestAttribute(JwtAuthenticationFilter.USER_ID_ATTRIBUTE) UUID adminId,
            @Valid @RequestBody UpdateAiControlsRequest request) {
        return ApiResponse.ok(
                controlsService.updateKillSwitch(tenantId, adminId, request.killSwitchActive()));
    }

    /** 租户级 kill switch：只影响 JWT 所属租户，全局一键全停仍由上面的端点控制。 */
    @GetMapping("/tenant")
    public ApiResponse<KillSwitchState> getTenant(
            @RequestAttribute(TenantContext.REQUEST_ATTRIBUTE) UUID tenantId) {
        return ApiResponse.ok(controlsService.getTenantKillSwitch(tenantId));
    }

    @PutMapping("/tenant")
    public ApiResponse<KillSwitchState> updateTenant(
            @RequestAttribute(TenantContext.REQUEST_ATTRIBUTE) UUID tenantId,
            @RequestAttribute(JwtAuthenticationFilter.USER_ID_ATTRIBUTE) UUID adminId,
            @Valid @RequestBody UpdateAiControlsRequest request) {
        return ApiResponse.ok(
                controlsService.updateTenantKillSwitch(tenantId, adminId, request.killSwitchActive()));
    }
}
