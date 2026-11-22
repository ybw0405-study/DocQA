package com.docqa.common.web;

import com.docqa.common.api.FieldViolation;
import com.docqa.common.error.BusinessException;
import com.docqa.common.error.ErrorCode;
import com.docqa.common.error.ProblemDetailFactory;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.BindingResult;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class GlobalExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ProblemDetail> handleBusinessException(
            BusinessException exception,
            HttpServletRequest request) {
        return respond(
                exception.getErrorCode(),
                exception.getDetail(),
                exception.getFieldViolations(),
                request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception,
            HttpServletRequest request) {
        return respond(
                ErrorCode.COM_VALIDATION_001,
                null,
                bindingErrors(exception.getBindingResult()),
                request);
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<ProblemDetail> handleBindException(
            BindException exception,
            HttpServletRequest request) {
        return respond(
                ErrorCode.COM_VALIDATION_001,
                null,
                bindingErrors(exception.getBindingResult()),
                request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(
            ConstraintViolationException exception,
            HttpServletRequest request) {
        List<FieldViolation> errors = exception.getConstraintViolations().stream()
                .map(this::toFieldViolation)
                .toList();
        return respond(ErrorCode.COM_VALIDATION_001, null, errors, request);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ProblemDetail> handleHandlerMethodValidation(
            HandlerMethodValidationException exception,
            HttpServletRequest request) {
        List<FieldViolation> errors = exception.getAllValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> FieldViolation.of(
                                result.getMethodParameter().getParameterName(),
                                error.getDefaultMessage())))
                .toList();
        return respond(ErrorCode.COM_VALIDATION_001, null, errors, request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleUnreadableMessage(HttpServletRequest request) {
        return respond(
                ErrorCode.COM_VALIDATION_001,
                "请求体格式错误。",
                List.of(FieldViolation.of("request", "request body is malformed")),
                request);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ProblemDetail> handleMissingHeader(
            MissingRequestHeaderException exception,
            HttpServletRequest request) {
        return respond(
                ErrorCode.COM_VALIDATION_001,
                null,
                List.of(FieldViolation.of(exception.getHeaderName(), "required header is missing")),
                request);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ProblemDetail> handleMissingParameter(
            MissingServletRequestParameterException exception,
            HttpServletRequest request) {
        return respond(
                ErrorCode.COM_VALIDATION_001,
                null,
                List.of(FieldViolation.of(exception.getParameterName(), "required parameter is missing")),
                request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> handleTypeMismatch(
            MethodArgumentTypeMismatchException exception,
            HttpServletRequest request) {
        return respond(
                ErrorCode.COM_VALIDATION_001,
                null,
                List.of(FieldViolation.of(exception.getName(), "parameter has invalid type")),
                request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ProblemDetail> handleNoResourceFound(
            NoResourceFoundException exception,
            HttpServletRequest request) {
        return respond(ErrorCode.COM_NOT_FOUND_001, null, List.of(), request);
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ProblemDetail> handleNoHandlerFound(
            NoHandlerFoundException exception,
            HttpServletRequest request) {
        return respond(ErrorCode.COM_NOT_FOUND_001, null, List.of(), request);
    }

    /** R-02：框架级客户端拒绝（方法/媒体类型）不再落 500 兜底，按 4xx 登记码返回。 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException exception,
            HttpServletRequest request) {
        ProblemDetail problem = ProblemDetailFactory.create(
                ErrorCode.COM_METHOD_NOT_ALLOWED_001,
                null,
                List.of(),
                request.getRequestURI());
        LOGGER.debug(
                "Request rejected: code={}, traceId={}",
                ErrorCode.COM_METHOD_NOT_ALLOWED_001.code(),
                problem.getProperties().get("traceId"));
        ResponseEntity.BodyBuilder builder = ResponseEntity
                .status(ErrorCode.COM_METHOD_NOT_ALLOWED_001.status())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON);
        String[] supported = exception.getSupportedMethods();
        if (supported != null && supported.length > 0) {
            builder.header(HttpHeaders.ALLOW, String.join(", ", supported));
        }
        return builder.body(problem);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleUnsupportedMediaType(
            HttpMediaTypeNotSupportedException exception,
            HttpServletRequest request) {
        return respond(ErrorCode.COM_UNSUPPORTED_MEDIA_001, null, List.of(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpectedException(
            Exception exception,
            HttpServletRequest request) {
        ProblemDetail problem = ProblemDetailFactory.create(
                ErrorCode.COM_INTERNAL_001,
                null,
                List.of(),
                request.getRequestURI());
        LOGGER.error(
                "Unhandled exception: type={}, traceId={}",
                exception.getClass().getName(),
                problem.getProperties().get("traceId"));
        return response(ErrorCode.COM_INTERNAL_001, problem);
    }

    private ResponseEntity<ProblemDetail> respond(
            ErrorCode errorCode,
            String detail,
            List<FieldViolation> fieldViolations,
            HttpServletRequest request) {
        ProblemDetail problem = ProblemDetailFactory.create(
                errorCode,
                detail,
                fieldViolations,
                request.getRequestURI());
        if (errorCode.status().is5xxServerError()) {
            LOGGER.error(
                    "Request failed: code={}, traceId={}",
                    errorCode.code(),
                    problem.getProperties().get("traceId"));
        } else {
            LOGGER.debug(
                    "Request rejected: code={}, traceId={}",
                    errorCode.code(),
                    problem.getProperties().get("traceId"));
        }
        return response(errorCode, problem);
    }

    private ResponseEntity<ProblemDetail> response(ErrorCode errorCode, ProblemDetail problem) {
        return ResponseEntity.status(errorCode.status())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    private List<FieldViolation> bindingErrors(BindingResult bindingResult) {
        List<FieldViolation> errors = new ArrayList<>();
        bindingResult.getFieldErrors().forEach(error -> errors.add(
                FieldViolation.of(error.getField(), error.getDefaultMessage())));
        bindingResult.getGlobalErrors().forEach(error -> errors.add(
                FieldViolation.of("request", error.getDefaultMessage())));
        return errors;
    }

    private FieldViolation toFieldViolation(ConstraintViolation<?> violation) {
        String path = violation.getPropertyPath().toString();
        return FieldViolation.of(path, violation.getMessage());
    }
}
