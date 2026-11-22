package com.docqa.common.error;

import java.net.URI;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;

public enum ErrorCode {

    COM_VALIDATION_001(
            "COM-VALIDATION-001",
            HttpStatus.BAD_REQUEST,
            "validation",
            "Request validation failed",
            "请求参数校验失败。"),
    COM_RATE_LIMIT_001(
            "COM-RATE_LIMIT-001",
            HttpStatus.TOO_MANY_REQUESTS,
            "rate-limit",
            "Too many requests",
            "请求过于频繁，请稍后重试。"),
    COM_INTERNAL_001(
            "COM-INTERNAL-001",
            HttpStatus.INTERNAL_SERVER_ERROR,
            "internal",
            "Internal server error",
            "服务暂不可用，请稍后重试。"),
    COM_NOT_FOUND_001(
            "COM-NOT_FOUND-001",
            HttpStatus.NOT_FOUND,
            "not-found",
            "Not found",
            "请求的资源不存在。"),
    COM_METHOD_NOT_ALLOWED_001(
            "COM-METHOD_NOT_ALLOWED-001",
            HttpStatus.METHOD_NOT_ALLOWED,
            "method-not-allowed",
            "Method not allowed",
            "请求方法不被该资源支持。"),
    COM_UNSUPPORTED_MEDIA_001(
            "COM-UNSUPPORTED_MEDIA-001",
            HttpStatus.UNSUPPORTED_MEDIA_TYPE,
            "unsupported-media-type",
            "Unsupported media type",
            "请求的媒体类型不被支持。"),
    USR_AUTH_001(
            "USR-AUTH-001",
            HttpStatus.UNAUTHORIZED,
            "authentication",
            "Authentication required",
            "请重新登录。"),
    USR_RATE_LIMIT_001(
            "USR-RATE_LIMIT-001",
            HttpStatus.TOO_MANY_REQUESTS,
            "user-rate-limit",
            "Too many login attempts",
            "登录失败次数过多，请稍后再试。"),
    USR_NOT_FOUND_001(
            "USR-NOT_FOUND-001",
            HttpStatus.NOT_FOUND,
            "user-not-found",
            "User not found",
            "用户不存在。"),
    USR_CONFLICT_001(
            "USR-CONFLICT-001",
            HttpStatus.CONFLICT,
            "username-conflict",
            "Username already taken",
            "该用户名已被占用。"),
    USR_FORBIDDEN_001(
            "USR-FORBIDDEN-001",
            HttpStatus.FORBIDDEN,
            "user-forbidden",
            "Administrator role required",
            "当前账号没有执行该操作的权限。"),
    USR_DISABLED_001(
            "USR-DISABLED-001",
            HttpStatus.FORBIDDEN,
            "account-disabled",
            "Account disabled",
            "账号已被停用，请联系管理员。"),
    USR_REFRESH_001(
            "USR-REFRESH-001",
            HttpStatus.UNAUTHORIZED,
            "refresh-rejected",
            "Session credential rejected",
            "登录状态已失效，请重新登录。"),
    USR_VALIDATION_001(
            "USR-VALIDATION-001",
            HttpStatus.UNPROCESSABLE_ENTITY,
            "user-policy-validation",
            "User policy validation failed",
            "口令强度或授权变更不满足策略要求。"),
    USR_IDEMPOTENCY_001(
            "USR-IDEMPOTENCY-001",
            HttpStatus.CONFLICT,
            "user-idempotency-conflict",
            "Idempotency conflict",
            "同一 Idempotency-Key 已用于不同请求。"),
    DOC_VALIDATION_001(
            "DOC-VALIDATION-001",
            HttpStatus.PAYLOAD_TOO_LARGE,
            "document-too-large",
            "Document too large",
            "文件不能超过 50MiB。"),
    DOC_VALIDATION_002(
            "DOC-VALIDATION-002",
            HttpStatus.UNSUPPORTED_MEDIA_TYPE,
            "document-type",
            "Unsupported document type",
            "仅支持 PDF、PPTX、DOCX 和 TXT。"),
    DOC_VALIDATION_003(
            "DOC-VALIDATION-003",
            HttpStatus.UNPROCESSABLE_ENTITY,
            "document-encrypted",
            "Document is encrypted",
            "无法解析加密的 PDF，请先解除密码保护。"),
    DOC_VALIDATION_004(
            "DOC-VALIDATION-004",
            HttpStatus.UNPROCESSABLE_ENTITY,
            "upload-validation",
            "Upload validation failed",
            "合并后的对象大小或 MD5 与初始化信息不一致。"),
    DOC_NOT_FOUND_001(
            "DOC-NOT_FOUND-001",
            HttpStatus.NOT_FOUND,
            "document-not-found",
            "Document not found",
            "文档不存在。"),
    DOC_CONFLICT_001(
            "DOC-CONFLICT-001",
            HttpStatus.CONFLICT,
            "document-conflict",
            "Document state conflict",
            "文档当前状态不允许执行该操作。"),
    AGT_VALIDATION_001(
            "AGT-VALIDATION-001",
            HttpStatus.BAD_REQUEST,
            "agent-validation",
            "Agent request validation failed",
            "问答请求参数校验失败。"),
    AGT_VALIDATION_002(
            "AGT-VALIDATION-002",
            HttpStatus.UNPROCESSABLE_ENTITY,
            "agent-scope-validation",
            "Agent document scope invalid",
            "文档范围不合法或尚未完成索引。"),
    AGT_VALIDATION_003(
            "AGT-VALIDATION-003",
            HttpStatus.UNPROCESSABLE_ENTITY,
            "pii-blocked",
            "Sensitive data blocked",
            "检测到未脱敏的敏感信息，请移除后重试。"),
    AGT_NOT_FOUND_001(
            "AGT-NOT_FOUND-001",
            HttpStatus.NOT_FOUND,
            "conversation-not-found",
            "Conversation not found",
            "会话不存在。"),
    AGT_CONFLICT_001(
            "AGT-CONFLICT-001",
            HttpStatus.CONFLICT,
            "agent-conflict",
            "Conversation busy",
            "该会话正在处理另一个问题，请稍后重试。"),
    AGT_CONFLICT_002(
            "AGT-CONFLICT-002",
            HttpStatus.CONFLICT,
            "conversation-create-conflict",
            "Conversation creation conflict",
            "同一幂等键已用于不同的会话创建请求。"),
    AGT_UPSTREAM_001(
            "AGT-UPSTREAM-001",
            HttpStatus.SERVICE_UNAVAILABLE,
            "agent-unavailable",
            "AI service unavailable",
            "AI 能力暂不可用，请稍后重试。"),
    AGT_UPSTREAM_002(
            "AGT-UPSTREAM-002",
            HttpStatus.GATEWAY_TIMEOUT,
            "agent-timeout",
            "AI service timeout",
            "AI 服务响应超时，请稍后重试。"),
    AGT_UPSTREAM_003(
            "AGT-UPSTREAM-003",
            HttpStatus.SERVICE_UNAVAILABLE,
            "ai-disabled",
            "AI disabled",
            "AI 功能已暂停。"),
    AGT_UPSTREAM_004(
            "AGT-UPSTREAM-004",
            HttpStatus.BAD_GATEWAY,
            "agent-protocol",
            "Agent protocol violation",
            "AI 服务返回了不符合治理契约的结果，请联系管理员并提供 traceId。"),
    AGT_INTERNAL_001(
            "AGT-INTERNAL-001",
            HttpStatus.SERVICE_UNAVAILABLE,
            "audit-unavailable",
            "Audit unavailable",
            "审计存储暂不可用，本次回答未返回，请稍后重试。"),
    REM_VALIDATION_001(
            "REM-VALIDATION-001",
            HttpStatus.UNPROCESSABLE_ENTITY,
            "reminder-validation",
            "Reminder rule validation failed",
            "RRULE 或时区非法，请检查后重试。"),
    REM_CONFLICT_001(
            "REM-CONFLICT-001",
            HttpStatus.CONFLICT,
            "reminder-conflict",
            "Reminder idempotency conflict",
            "同一 Idempotency-Key 已用于不同请求。"),
    REM_VALIDATION_002(
            "REM-VALIDATION-002",
            HttpStatus.PAYLOAD_TOO_LARGE,
            "schedule-too-large",
            "Schedule file too large",
            "课表文件不能超过 2 MiB。"),
    REM_VALIDATION_003(
            "REM-VALIDATION-003",
            HttpStatus.UNSUPPORTED_MEDIA_TYPE,
            "schedule-content-type",
            "Unsupported schedule content type",
            "课表文件必须是 ICS 文本（text/calendar 或 application/octet-stream）。"),
    REM_CONFLICT_002(
            "REM-CONFLICT-002",
            HttpStatus.CONFLICT,
            "schedule-source-conflict",
            "Schedule source conflict",
            "同一课程的导入请求正在进行，请稍后重试。"),
    REM_NOT_FOUND_001(
            "REM-NOT_FOUND-001",
            HttpStatus.NOT_FOUND,
            "reminder-not-found",
            "Reminder not found",
            "提醒不存在。"),
    REM_CONFLICT_003(
            "REM-CONFLICT-003",
            HttpStatus.CONFLICT,
            "reminder-version-conflict",
            "Reminder version conflict",
            "提醒已被更新，请刷新后重试。"),
    REM_CONFLICT_004(
            "REM-CONFLICT-004",
            HttpStatus.CONFLICT,
            "reminder-state-transition",
            "Reminder state transition conflict",
            "该提醒当前状态不允许此变更。");

    private static final Map<String, ErrorCode> BY_CODE = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(ErrorCode::code, Function.identity()));

    private final String code;
    private final HttpStatus status;
    private final String problemType;
    private final String title;
    private final String defaultDetail;

    ErrorCode(String code, HttpStatus status, String problemSlug, String title, String defaultDetail) {
        if (!code.matches("[A-Z]{2,4}-[A-Z_]+-[0-9]{3}")) {
            throw new IllegalArgumentException("Invalid error code: " + code);
        }
        this.code = code;
        this.status = status;
        this.problemType = "https://docqa.example/problems/" + problemSlug;
        this.title = title;
        this.defaultDetail = defaultDetail;
    }

    public String code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }

    public URI problemType() {
        return URI.create(problemType);
    }

    public String title() {
        return title;
    }

    public String defaultDetail() {
        return defaultDetail;
    }

    public static Optional<ErrorCode> find(String code) {
        return Optional.ofNullable(BY_CODE.get(code));
    }
}
