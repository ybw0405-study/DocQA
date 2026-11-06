package com.docqa.agentgateway.controller;

import com.docqa.agentgateway.dto.AskData;
import com.docqa.agentgateway.dto.AskRequest;
import com.docqa.agentgateway.service.AskService;
import com.docqa.agentgateway.web.JwtAuthenticationFilter;
import com.docqa.common.api.ApiResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/conversations")
public class AskController {

    private final AskService askService;

    public AskController(AskService askService) {
        this.askService = askService;
    }

    @PostMapping("/{conversationId}/ask")
    public ApiResponse<AskData> ask(
            @RequestAttribute(JwtAuthenticationFilter.USER_ID_ATTRIBUTE) UUID userId,
            @PathVariable UUID conversationId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody AskRequest request) {
        return ApiResponse.ok(askService.ask(userId, conversationId, idempotencyKey, request));
    }
}
