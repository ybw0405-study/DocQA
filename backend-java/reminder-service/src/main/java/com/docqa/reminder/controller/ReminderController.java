package com.docqa.reminder.controller;

import com.docqa.common.api.ApiResponse;
import com.docqa.reminder.dto.ReminderCreateData;
import com.docqa.reminder.dto.ReminderCreateRequest;
import com.docqa.reminder.dto.ReminderListData;
import com.docqa.reminder.dto.ReminderStatusUpdateData;
import com.docqa.reminder.dto.ReminderStatusUpdateRequest;
import com.docqa.reminder.service.CreateReminderResult;
import com.docqa.reminder.service.ReminderService;
import com.docqa.reminder.service.ReminderStatusService;
import com.docqa.reminder.web.JwtAuthenticationFilter;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reminders")
public class ReminderController {

    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final ReminderService reminderService;
    private final ReminderStatusService reminderStatusService;

    public ReminderController(ReminderService reminderService, ReminderStatusService reminderStatusService) {
        this.reminderService = reminderService;
        this.reminderStatusService = reminderStatusService;
    }

    @GetMapping
    public ApiResponse<ReminderListData> list(
            @RequestAttribute(JwtAuthenticationFilter.USER_ID_ATTRIBUTE) UUID userId,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return ApiResponse.ok(reminderService.list(userId, status, cursor, limit));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ReminderCreateData>> create(
            @RequestAttribute(JwtAuthenticationFilter.USER_ID_ATTRIBUTE) UUID userId,
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @Valid @RequestBody ReminderCreateRequest request) {
        CreateReminderResult result = reminderService.create(userId, idempotencyKey, request);
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(ApiResponse.ok(result.data()));
    }

    /** 写入与同态 no-op 都是 200，客户端用 {@code version} 是否 +1 判断本次是否真的改过。 */
    @PatchMapping("/{reminderId}")
    public ApiResponse<ReminderStatusUpdateData> updateStatus(
            @RequestAttribute(JwtAuthenticationFilter.USER_ID_ATTRIBUTE) UUID userId,
            @PathVariable UUID reminderId,
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @Valid @RequestBody ReminderStatusUpdateRequest request) {
        return ApiResponse.ok(reminderStatusService.update(userId, reminderId, idempotencyKey, request));
    }
}
