package com.docqa.agentgateway.controller;

import com.docqa.agentgateway.dto.MemoryListData;
import com.docqa.agentgateway.service.PythonMemoryClient;
import com.docqa.common.api.ApiResponse;
import com.docqa.common.error.BusinessException;
import com.docqa.common.error.ErrorCode;
import com.docqa.common.tenant.TenantContext;
import com.docqa.common.trace.TraceIdContext;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 长期记忆管理面。路径落在 {@code /api/v1/admin/} 下，ADMIN 判定由
 * {@link com.docqa.agentgateway.web.JwtAuthenticationFilter} 统一强制。
 *
 * <p>记忆只由用户在问答里显式下达「记住：…」指令写入（数据面掩码后落库），
 * 管理面只有读与删两个操作；删除按值幂等，不声明 Idempotency-Key（同裁决）。
 * tenantId 取自已验签 JWT，userId 是被治理用户；数据面未启用记忆时代理回 503，不假装空清单。
 */
@RestController
@RequestMapping("/api/v1/admin/ai/memories")
public class AdminMemoryController {

    private final PythonMemoryClient client;

    public AdminMemoryController(PythonMemoryClient client) {
        this.client = client;
    }

    @GetMapping
    public ApiResponse<MemoryListData> list(
            @RequestAttribute(TenantContext.REQUEST_ATTRIBUTE) UUID tenantId,
            @RequestParam UUID userId) {
        return ApiResponse.ok(client.list(tenantId, userId, TraceIdContext.currentOrCreate()));
    }

    @DeleteMapping("/{memoryKey}")
    public ResponseEntity<Void> delete(
            @RequestAttribute(TenantContext.REQUEST_ATTRIBUTE) UUID tenantId,
            @RequestParam UUID userId,
            @PathVariable UUID memoryKey) {
        if (!client.delete(tenantId, userId, memoryKey, TraceIdContext.currentOrCreate())) {
            throw new BusinessException(ErrorCode.COM_NOT_FOUND_001, "记忆不存在或已被删除。");
        }
        return ResponseEntity.noContent().build();
    }
}