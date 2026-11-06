package com.docqa.agentgateway.messaging;

import com.docqa.agentgateway.dto.AuditEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "docqa.audit", name = "enabled", havingValue = "false")
public class NoopAuditEventPublisher implements AuditEventPublisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(NoopAuditEventPublisher.class);

    @Override
    public void publish(AuditEvent event) {
        LOGGER.debug("Audit publishing disabled: eventType={}", event.eventType());
    }
}
