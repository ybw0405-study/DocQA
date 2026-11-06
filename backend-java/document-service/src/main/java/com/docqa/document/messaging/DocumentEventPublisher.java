package com.docqa.document.messaging;

public interface DocumentEventPublisher {

    void publishParseRequested(ParseRequestedEvent event);

    void publishParseRequested(ParseRequestedEvent event, int delayLevel);

    void publishVectorChangeRequested(VectorChangeRequestedEvent event);
}
