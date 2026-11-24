package com.docqa.agentgateway.controller;

import com.docqa.agentgateway.dto.AuthorizeModelConnectionRequest;
import com.docqa.agentgateway.dto.CreateModelConnectionRequest;
import com.docqa.agentgateway.dto.ModelConnection;
import com.docqa.agentgateway.dto.ModelConnectionListData;
import com.docqa.agentgateway.dto.ModelPresetListData;
import com.docqa.agentgateway.dto.ModelProbeData;
import com.docqa.agentgateway.dto.UpdateActivationRequest;
import com.docqa.agentgateway.service.ModelConnectionService;
import com.docqa.agentgateway.service.ModelPresetCatalog;
import com.docqa.agentgateway.web.JwtAuthenticationFilter;
import com.docqa.common.api.ApiResponse;
import com.docqa.common.tenant.TenantContext;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模型连接管理面。路径落在 {@code /api/v1/admin/} 下，ADMIN 判定由
 * {@link JwtAuthenticationFilter} 统一强制，这里不再重复写权限注解。
 *
 * <p>写操作按值幂等：创建按租户内 name upsert，凭据/生效状态都是覆盖式写入，
 * 因此不声明 Idempotency-Key 参数，与其余管理面一致。
 *
 * <p>凭据只进 {@code PUT /{id}/credential} 的请求体；所有读响应只有 hasCredential 布尔。
 */
@RestController
@RequestMapping("/api/v1/admin/ai/model-connections")
public class AdminModelConnectionController {

    private final ModelConnectionService service;
    private final ModelPresetCatalog presetCatalog;

    public AdminModelConnectionController(ModelConnectionService service, ModelPresetCatalog presetCatalog) {
        this.service = service;
        this.presetCatalog = presetCatalog;
    }

    /**
     * 厂商预设目录：公开端点与常见模型别名，不含任何密钥。
     * 由服务端下发是因为前端红线禁止生产源码出现绝对服务地址，且端点变化不必发前端版本。
     */
    @GetMapping("/presets")
    public ApiResponse<ModelPresetListData> presets() {
        return ApiResponse.ok(new ModelPresetListData(presetCatalog.presets()));
    }

    @GetMapping
    public ApiResponse<ModelConnectionListData> list(
            @RequestAttribute(TenantContext.REQUEST_ATTRIBUTE) UUID tenantId) {
        return ApiResponse.ok(service.list(tenantId));
    }

    @PostMapping
    public ApiResponse<ModelConnection> upsert(
            @RequestAttribute(TenantContext.REQUEST_ATTRIBUTE) UUID tenantId,
            @RequestAttribute(JwtAuthenticationFilter.USER_ID_ATTRIBUTE) UUID adminId,
            @Valid @RequestBody CreateModelConnectionRequest request) {
        return ApiResponse.ok(service.upsert(tenantId, adminId, request));
    }

    @PutMapping("/{connectionId}/credential")
    public ApiResponse<ModelConnection> authorize(
            @RequestAttribute(TenantContext.REQUEST_ATTRIBUTE) UUID tenantId,
            @RequestAttribute(JwtAuthenticationFilter.USER_ID_ATTRIBUTE) UUID adminId,
            @PathVariable UUID connectionId,
            @Valid @RequestBody AuthorizeModelConnectionRequest request) {
        return ApiResponse.ok(service.authorize(tenantId, adminId, connectionId, request.secret()));
    }

    @PostMapping("/{connectionId}/probe")
    public ApiResponse<ModelProbeData> probe(
            @RequestAttribute(TenantContext.REQUEST_ATTRIBUTE) UUID tenantId,
            @RequestAttribute(JwtAuthenticationFilter.USER_ID_ATTRIBUTE) UUID adminId,
            @PathVariable UUID connectionId) {
        return ApiResponse.ok(service.probe(tenantId, adminId, connectionId));
    }

    @PutMapping("/{connectionId}/activation")
    public ApiResponse<ModelConnection> setActivation(
            @RequestAttribute(TenantContext.REQUEST_ATTRIBUTE) UUID tenantId,
            @RequestAttribute(JwtAuthenticationFilter.USER_ID_ATTRIBUTE) UUID adminId,
            @PathVariable UUID connectionId,
            @Valid @RequestBody UpdateActivationRequest request) {
        return ApiResponse.ok(service.setActivation(tenantId, adminId, connectionId, request.active()));
    }

    @DeleteMapping("/{connectionId}")
    public ResponseEntity<Void> delete(
            @RequestAttribute(TenantContext.REQUEST_ATTRIBUTE) UUID tenantId,
            @RequestAttribute(JwtAuthenticationFilter.USER_ID_ATTRIBUTE) UUID adminId,
            @PathVariable UUID connectionId) {
        service.delete(tenantId, adminId, connectionId);
        return ResponseEntity.noContent().build();
    }
}
