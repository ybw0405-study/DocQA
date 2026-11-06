package com.docqa.agentgateway.controller;

import com.docqa.agentgateway.dto.AskRequest;
import com.docqa.agentgateway.service.AskService;
import com.docqa.agentgateway.web.JwtAuthenticationFilter;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/conversations")
public class AskStreamController {

    private final AskService askService;

    public AskStreamController(AskService askService) {
        this.askService = askService;
    }

    /**
     * 流式问答（SSE）。首字节前的治理拒绝由 askStream 抛 BusinessException，
     * 经全局异常处理器输出 JSON ProblemDetail；成功路径返回 text/event-stream 事件流。
     */
    @PostMapping("/{conversationId}/ask:stream")
    public SseEmitter askStream(
            @RequestAttribute(JwtAuthenticationFilter.USER_ID_ATTRIBUTE) UUID userId,
            @PathVariable UUID conversationId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody AskRequest request) {
        return askService.askStream(userId, conversationId, idempotencyKey, request);
    }
}
