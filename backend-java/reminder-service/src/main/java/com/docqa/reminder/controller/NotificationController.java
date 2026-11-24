package com.docqa.reminder.controller;

import com.docqa.common.api.ApiResponse;
import com.docqa.reminder.dto.NotificationListData;
import com.docqa.reminder.dto.NotificationReadData;
import com.docqa.reminder.dto.NotificationReadRequest;
import com.docqa.reminder.service.NotificationService;
import com.docqa.reminder.web.JwtAuthenticationFilter;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    public ApiResponse<NotificationListData> list(
            @RequestAttribute(JwtAuthenticationFilter.USER_ID_ATTRIBUTE) UUID userId,
            @RequestParam(name = "unreadOnly", required = false) Boolean unreadOnly,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return ApiResponse.ok(notificationService.list(userId, Boolean.TRUE.equals(unreadOnly), cursor, limit));
    }

    /**
     * 有意不要求 {@code Idempotency-Key}，也不计入用户级限流桶：已读是「只由空变有」的收敛操作，
     * 重复提交没有第二次副作用（契约 2.3.0 的显式例外，理由见）。
     */
    @PostMapping("/read")
    public ApiResponse<NotificationReadData> markRead(
            @RequestAttribute(JwtAuthenticationFilter.USER_ID_ATTRIBUTE) UUID userId,
            @Valid @RequestBody NotificationReadRequest request) {
        return ApiResponse.ok(notificationService.markRead(userId, request));
    }
}
