package com.docqa.agentgateway.web;

import com.docqa.agentgateway.service.RateLimitExceededException;
import com.docqa.common.api.FieldViolation;
import com.docqa.common.error.ErrorCode;
import com.docqa.common.error.ProblemDetailFactory;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AgentExceptionHandler {

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ProblemDetail> handleRateLimit(
            RateLimitExceededException exception,
            HttpServletRequest request) {
        ProblemDetail problem = ProblemDetailFactory.create(
                ErrorCode.COM_RATE_LIMIT_001,
                exception.getDetail(),
                List.of(),
                request.getRequestURI());
        return ResponseEntity.status(ErrorCode.COM_RATE_LIMIT_001.status())
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(exception.getRetryAfterSeconds()))
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception,
            HttpServletRequest request) {
        return validationResponse(bindingErrors(exception.getBindingResult()), request);
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<ProblemDetail> handleBindException(
            BindException exception,
            HttpServletRequest request) {
        return validationResponse(bindingErrors(exception.getBindingResult()), request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(
            ConstraintViolationException exception,
            HttpServletRequest request) {
        List<FieldViolation> errors = exception.getConstraintViolations().stream()
                .map(this::toFieldViolation)
                .toList();
        return validationResponse(errors, request);
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
        return validationResponse(errors, request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleUnreadableMessage(HttpServletRequest request) {
        return validationResponse(
                List.of(FieldViolation.of("request", "request body is malformed")),
                request);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ProblemDetail> handleMissingHeader(
            MissingRequestHeaderException exception,
            HttpServletRequest request) {
        return validationResponse(
                List.of(FieldViolation.of(exception.getHeaderName(), "required header is missing")),
                request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> handleTypeMismatch(
            MethodArgumentTypeMismatchException exception,
            HttpServletRequest request) {
        return validationResponse(
                List.of(FieldViolation.of(exception.getName(), "parameter has invalid type")),
                request);
    }

    private ResponseEntity<ProblemDetail> validationResponse(
            List<FieldViolation> errors,
            HttpServletRequest request) {
        ProblemDetail problem = ProblemDetailFactory.create(
                ErrorCode.AGT_VALIDATION_001,
                null,
                errors,
                request.getRequestURI());
        return ResponseEntity.status(ErrorCode.AGT_VALIDATION_001.status())
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
        return FieldViolation.of(violation.getPropertyPath().toString(), violation.getMessage());
    }
}
