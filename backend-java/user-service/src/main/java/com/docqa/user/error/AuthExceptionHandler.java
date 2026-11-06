package com.docqa.user.error;

import com.docqa.common.error.ErrorCode;
import com.docqa.common.error.ProblemDetailFactory;
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

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AuthExceptionHandler {

    @ExceptionHandler(RateLimitedException.class)
    public ResponseEntity<ProblemDetail> handleRateLimited(
            RateLimitedException exception,
            HttpServletRequest request) {
        ErrorCode errorCode = exception.getErrorCode();
        ProblemDetail problem = ProblemDetailFactory.create(
                errorCode,
                exception.getDetail(),
                List.of(),
                request.getRequestURI());
        return ResponseEntity.status(errorCode.status())
                .header(HttpHeaders.RETRY_AFTER, Long.toString(exception.getRetryAfterSeconds()))
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }
}
