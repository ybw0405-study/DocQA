package com.docqa.audit.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.docqa.audit.config.RocketMqProperties;
import java.util.List;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.Test;

class RocketMqAuditConsumerTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    private final RocketMqProperties properties = properties();
    private final AuditMessageHandler handler = mock(AuditMessageHandler.class);
    private final RocketMqAuditConsumer consumer = new RocketMqAuditConsumer(properties, handler);

    @Test
    void acknowledgesTheWholeBatchWhenEveryMessageIsHandled() {
        when(handler.handle(any(), eq(TRACE_ID))).thenReturn(ConsumeResult.ACK);

        ConsumeConcurrentlyStatus status = consumer.consumeMessage(List.of(message()), null);

        assertThat(status).isEqualTo(ConsumeConcurrentlyStatus.CONSUME_SUCCESS);
    }

    @Test
    void asksRocketMqToRedeliverWhenHandlingMustBeRetried() {
        when(handler.handle(any(), eq(TRACE_ID))).thenReturn(ConsumeResult.RETRY);

        ConsumeConcurrentlyStatus status = consumer.consumeMessage(List.of(message()), null);

        assertThat(status).isEqualTo(ConsumeConcurrentlyStatus.RECONSUME_LATER);
    }

    @Test
    void stopsProcessingTheBatchAfterARetryableFailure() {
        when(handler.handle(any(), eq(TRACE_ID)))
                .thenReturn(ConsumeResult.RETRY)
                .thenReturn(ConsumeResult.ACK);

        ConsumeConcurrentlyStatus status = consumer.consumeMessage(
                List.of(message(), message()), null);

        assertThat(status).isEqualTo(ConsumeConcurrentlyStatus.RECONSUME_LATER);
        org.mockito.Mockito.verify(handler).handle(any(), eq(TRACE_ID));
    }

    private MessageExt message() {
        MessageExt message = mock(MessageExt.class);
        when(message.getBody()).thenReturn(new byte[] {1});
        when(message.getUserProperty("traceId")).thenReturn(TRACE_ID);
        return message;
    }

    private static RocketMqProperties properties() {
        RocketMqProperties properties = new RocketMqProperties();
        properties.setConsumerGroup("audit-service-audit-event-test");
        properties.setNamesrv("127.0.0.1:9876");
        properties.setTopic("audit-event");
        properties.setMaxReconsumeTimes(8);
        return properties;
    }
}
