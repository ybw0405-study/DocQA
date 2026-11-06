package com.docqa.agentgateway.service;

import com.docqa.agentgateway.dto.PythonAuditEvent;
import com.docqa.common.error.ErrorCode;
import java.util.List;

public class PythonCallException extends RuntimeException {

    private final ErrorCode errorCode;
    private final List<PythonAuditEvent> auditEvents;
    private final boolean auditGap;

    public PythonCallException(
            ErrorCode errorCode,
            String detail,
            List<PythonAuditEvent> auditEvents,
            boolean auditGap) {
        super(detail);
        this.errorCode = errorCode;
        this.auditEvents = auditEvents == null ? List.of() : List.copyOf(auditEvents);
        this.auditGap = auditGap;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public List<PythonAuditEvent> getAuditEvents() {
        return auditEvents;
    }

    public boolean isAuditGap() {
        return auditGap;
    }
}
