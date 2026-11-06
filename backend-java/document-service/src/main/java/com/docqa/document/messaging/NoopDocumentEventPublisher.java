package com.docqa.document.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "docqa.rocketmq.enabled", havingValue = "false")
public class NoopDocumentEventPublisher implements DocumentEventPublisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(NoopDocumentEventPublisher.class);

    @Override
    public void publishParseRequested(ParseRequestedEvent event) {
        log(event.traceId(), event.documentId());
    }

    @Override
    public void publishParseRequested(ParseRequestedEvent event, int delayLevel) {
        log(event.traceId(), event.documentId());
    }

    @Override
    public void publishVectorChangeRequested(VectorChangeRequestedEvent event) {
        log(event.traceId(), event.documentId());
    }

    private void log(String traceId, Object documentId) {
        LOGGER.warn("RocketMQ is disabled; document event was not published: traceId={}, documentId={}",
                traceId, documentId);
    }
}
