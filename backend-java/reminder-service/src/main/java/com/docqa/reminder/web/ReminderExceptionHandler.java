package com.docqa.reminder.web;

import com.docqa.common.api.FieldViolation;
import com.docqa.common.error.ErrorCode;
import com.docqa.common.error.ProblemDetailFactory;
import com.docqa.reminder.service.RateLimitExceededException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/**
 * 提醒服务的错误映射。这里只登记课表上传的三条 multipart 异常，
 * 其余客户端异常仍由 {@code GlobalExceptionHandler} 兜住。
 *
 * <p>不在此处映射 {@code HttpMediaTypeNotSupportedException}（顶层 Content-Type 不是
 * {@code multipart/form-data} 的情形）：本 advice 作用于整个服务，那样会让
 * {@code POST /api/v1/reminders} 的 {@code text/plain} 请求收到课表专属文案。
 * 该情形连同「HTTP 方法不支持」由 {@code GlobalExceptionHandler} 的全局策略统一处理
 * 。
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ReminderExceptionHandler {

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ProblemDetail> handleRateLimit(
            RateLimitExceededException exception,
            HttpServletRequest request) {
        ProblemDetail problem = ProblemDetailFactory.create(
                ErrorCode.COM_RATE_LIMIT_001,
                null,
                List.of(),
                request.getRequestURI());
        return ResponseEntity.status(ErrorCode.COM_RATE_LIMIT_001.status())
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(exception.getRetryAfterSeconds()))
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    /**
     * 容器侧的大小限制先于业务判定触发。它和 {@code ScheduleImportService} 自己的 2 MiB 判定
     * 同为 413 {@code REM-VALIDATION-002}，调用方看到的契约结果与是否先被容器截断无关。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ProblemDetail> handleTooLarge(
            MaxUploadSizeExceededException exception,
            HttpServletRequest request) {
        return problem(ErrorCode.REM_VALIDATION_002, "file", "uploaded file exceeds the container size limit",
                request);
    }

    /** 报文残缺（boundary 非法、Part 头缺失等）：无法判定文件内容，按媒体类型不接受处理。 */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ProblemDetail> handleMultipartFailure(
            MultipartException exception,
            HttpServletRequest request) {
        return problem(ErrorCode.REM_VALIDATION_003, "request", "multipart request is malformed", request);
    }

    /**
     * 必需 Part 缺失。{@code MissingServletRequestPartException} 继承自
     * {@code jakarta.servlet.ServletException} 而<strong>不是</strong> {@code MultipartException}，
     * 因此必须单独登记，否则会落到通用 {@code Exception} 分支变成 500。
     *
     * <p>按 400 而不是 415 报：缺的是字段，媒体类型本身没被拒绝。另外两个文本 Part 是表单字段，
     * 容器把它们暴露成请求参数，缺失时走 {@code MissingServletRequestParameterException}
     * 并由 {@code GlobalExceptionHandler} 给出 400 {@code COM-VALIDATION-001}；文件 Part 同判据，
     * 否则同一个「少传一个 Part」的失误会因为 Part 类型不同得到两种状态码。
     */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ProblemDetail> handleMissingPart(
            MissingServletRequestPartException exception,
            HttpServletRequest request) {
        return problem(ErrorCode.COM_VALIDATION_001, exception.getRequestPartName(),
                "required multipart part is missing", request);
    }

    private ResponseEntity<ProblemDetail> problem(
            ErrorCode errorCode,
            String field,
            String message,
            HttpServletRequest request) {
        ProblemDetail problem = ProblemDetailFactory.create(
                errorCode,
                null,
                List.of(FieldViolation.of(field, message)),
                request.getRequestURI());
        return ResponseEntity.status(errorCode.status())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }
}
