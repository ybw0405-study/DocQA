package com.docqa.common.error;

import com.docqa.common.api.FieldViolation;
import com.docqa.common.trace.TraceIdContext;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import org.springframework.http.ProblemDetail;

public final class ProblemDetailFactory {

    private static final int MAX_DETAIL_LENGTH = 1000;
    private static final int MAX_ERRORS = 20;

    private ProblemDetailFactory() {
    }

    public static ProblemDetail create(
            ErrorCode errorCode,
            String detail,
            List<FieldViolation> fieldViolations,
            String instance) {
        Objects.requireNonNull(errorCode, "errorCode");

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(errorCode.status(), safeDetail(errorCode, detail));
        problem.setType(errorCode.problemType());
        problem.setTitle(errorCode.title());
        problem.setInstance(safeInstance(instance));
        problem.setProperty("code", errorCode.code());
        problem.setProperty("traceId", TraceIdContext.currentOrCreate());

        List<FieldViolation> safeViolations = safeViolations(fieldViolations);
        if (!safeViolations.isEmpty()) {
            problem.setProperty("errors", safeViolations);
        }
        return problem;
    }

    private static String safeDetail(ErrorCode errorCode, String detail) {
        String value = detail == null ? "" : detail.trim();
        if (value.isEmpty()) {
            return errorCode.defaultDetail();
        }
        return value.length() <= MAX_DETAIL_LENGTH ? value : value.substring(0, MAX_DETAIL_LENGTH);
    }

    private static URI safeInstance(String instance) {
        String value = instance == null ? "" : instance.trim();
        if (value.isEmpty()) {
            return URI.create("/");
        }
        try {
            return URI.create(value);
        } catch (IllegalArgumentException ex) {
            return URI.create("/");
        }
    }

    private static List<FieldViolation> safeViolations(List<FieldViolation> fieldViolations) {
        if (fieldViolations == null || fieldViolations.isEmpty()) {
            return List.of();
        }
        return fieldViolations.stream()
                .filter(Objects::nonNull)
                .limit(MAX_ERRORS)
                .toList();
    }
}
