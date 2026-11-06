package com.docqa.common.error;

import com.docqa.common.api.FieldViolation;
import java.util.List;
import java.util.Objects;

public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    private final String detail;
    private final List<FieldViolation> fieldViolations;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, null, List.of(), null);
    }

    public BusinessException(ErrorCode errorCode, String detail) {
        this(errorCode, detail, List.of(), null);
    }

    public BusinessException(ErrorCode errorCode, String detail, List<FieldViolation> fieldViolations) {
        this(errorCode, detail, fieldViolations, null);
    }

    public BusinessException(ErrorCode errorCode, String detail, Throwable cause) {
        this(errorCode, detail, List.of(), cause);
    }

    public BusinessException(ErrorCode errorCode, String detail, List<FieldViolation> fieldViolations, Throwable cause) {
        super(resolveDetail(errorCode, detail), cause);
        this.errorCode = errorCode;
        this.detail = resolveDetail(errorCode, detail);
        this.fieldViolations = fieldViolations == null ? List.of() : List.copyOf(fieldViolations);
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public String getDetail() {
        return detail;
    }

    public List<FieldViolation> getFieldViolations() {
        return fieldViolations;
    }

    private static String resolveDetail(ErrorCode errorCode, String detail) {
        Objects.requireNonNull(errorCode, "errorCode");
        return detail == null || detail.isBlank() ? errorCode.defaultDetail() : detail;
    }
}
