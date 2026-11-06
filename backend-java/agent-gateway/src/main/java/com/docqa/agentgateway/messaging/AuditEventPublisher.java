package com.docqa.agentgateway.messaging;

import com.docqa.agentgateway.dto.AuditEvent;

public interface AuditEventPublisher {

    void publish(AuditEvent event);
}
