package com.docqa.audit.service;

public class AuditEventRejectedException extends RuntimeException {

    public AuditEventRejectedException(String reason) {
        super(reason);
    }
}
