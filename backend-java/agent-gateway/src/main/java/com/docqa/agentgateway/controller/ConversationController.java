package com.docqa.agentgateway.controller;

import com.docqa.agentgateway.dto.ConversationData;
import com.docqa.agentgateway.dto.ConversationListData;
import com.docqa.agentgateway.dto.CreateConversationRequest;
import com.docqa.agentgateway.dto.MessageHistoryData;
import com.docqa.agentgateway.service.ConversationHistoryService;
import com.docqa.agentgateway.service.ConversationListService;
import com.docqa.agentgateway.service.ConversationService;
import com.docqa.agentgateway.web.JwtAuthenticationFilter;
import com.docqa.common.api.ApiResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/conversations")
public class ConversationController {

    private final ConversationService conversationService;
    private final ConversationListService conversationListService;
    private final ConversationHistoryService conversationHistoryService;

    public ConversationController(
            ConversationService conversationService,
            ConversationListService conversationListService,
            ConversationHistoryService conversationHistoryService) {
        this.conversationService = conversationService;
        this.conversationListService = conversationListService;
        this.conversationHistoryService = conversationHistoryService;
    }

    @GetMapping
    public ApiResponse<ConversationListData> list(
            @RequestAttribute(JwtAuthenticationFilter.USER_ID_ATTRIBUTE) UUID userId,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return ApiResponse.ok(conversationListService.list(userId, cursor, limit));
    }

    @GetMapping("/{conversationId}/messages")
    public ApiResponse<MessageHistoryData> listMessages(
            @RequestAttribute(JwtAuthenticationFilter.USER_ID_ATTRIBUTE) UUID userId,
            @PathVariable UUID conversationId,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return ApiResponse.ok(conversationHistoryService.list(userId, conversationId, cursor, limit));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ConversationData>> create(
            @RequestAttribute(JwtAuthenticationFilter.USER_ID_ATTRIBUTE) UUID userId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateConversationRequest request) {
        ConversationData data = conversationService.create(userId, idempotencyKey, request);
        HttpStatus status = data.idempotentReplay() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(ApiResponse.ok(data));
    }
}
