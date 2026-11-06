package com.docqa.agentgateway.controller;

import com.docqa.agentgateway.dto.AiQuota;
import com.docqa.agentgateway.dto.AiQuotaListData;
import com.docqa.agentgateway.dto.UpdateAiQuotaRequest;
import com.docqa.agentgateway.service.AdminQuotaService;
import com.docqa.agentgateway.web.JwtAuthenticationFilter;
import com.docqa.common.api.ApiResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/ai/quotas")
public class AdminAiQuotaController {

    private final AdminQuotaService quotaService;

    public AdminAiQuotaController(AdminQuotaService quotaService) {
        this.quotaService = quotaService;
    }

    @GetMapping
    public ApiResponse<AiQuotaListData> list(
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        return ApiResponse.ok(quotaService.list(cursor, limit));
    }

    @PutMapping("/{userId}")
    public ApiResponse<AiQuota> update(
            @RequestAttribute(JwtAuthenticationFilter.USER_ID_ATTRIBUTE) UUID adminId,
            @PathVariable UUID userId,
            @Valid @RequestBody UpdateAiQuotaRequest request) {
        return ApiResponse.ok(quotaService.update(
                adminId, userId, request.dailyQuestionLimit(), request.dailyTokenLimit()));
    }
}
