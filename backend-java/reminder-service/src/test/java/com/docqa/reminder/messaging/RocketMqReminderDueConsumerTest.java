package com.docqa.reminder.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.docqa.common.metrics.BusinessMetrics;
import com.docqa.reminder.config.RocketMqProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.Test;

/**
 * 批量语义与 {@code RocketMqAuditConsumer} 同形：一批里出现一次 RETRY 就整批交还重投。
 * 这里只测映射，不测订阅——订阅失败必须拒绝启动，那条判定在 {@code start()} 里。
 */
class RocketMqReminderDueConsumerTest {

    private static final String MESSAGE_ID = "0A1B2C3D4E5F";

    private final RocketMqProperties properties = properties();
    private final ReminderDueMessageHandler handler = mock(ReminderDueMessageHandler.class);
    private final RocketMqReminderDueConsumer consumer =
            new RocketMqReminderDueConsumer(properties, handler, new BusinessMetrics(new SimpleMeterRegistry()));

    @Test
    void acknowledgesTheWholeBatchWhenEveryMessageIsHandled() {
        when(handler.handle(any(), eq(MESSAGE_ID))).thenReturn(ConsumeResult.ACK);

        ConsumeConcurrentlyStatus status = consumer.consumeMessage(List.of(message(), message()), null);

        assertThat(status).isEqualTo(ConsumeConcurrentlyStatus.CONSUME_SUCCESS);
    }

    @Test
    void asksRocketMqToRedeliverWhenHandlingMustBeRetried() {
        when(handler.handle(any(), eq(MESSAGE_ID))).thenReturn(ConsumeResult.RETRY);

        ConsumeConcurrentlyStatus status = consumer.consumeMessage(List.of(message()), null);

        assertThat(status).isEqualTo(ConsumeConcurrentlyStatus.RECONSUME_LATER);
    }

    @Test
    void stopsProcessingTheRestOfTheBatchAfterARetryableFailure() {
        when(handler.handle(any(), eq(MESSAGE_ID)))
                .thenReturn(ConsumeResult.RETRY)
                .thenReturn(ConsumeResult.ACK);

        ConsumeConcurrentlyStatus status = consumer.consumeMessage(List.of(message(), message()), null);

        // 后面的消息不处理：重投会以同样顺序再来一次，跳过会让第二条永远没有机会落库。
        assertThat(status).isEqualTo(ConsumeConcurrentlyStatus.RECONSUME_LATER);
        verify(handler).handle(any(), eq(MESSAGE_ID));
    }

    @Test
    void theTransportMessageIdIsWhatReachesTheHandlerForDeduplication() {
        when(handler.handle(any(), eq(MESSAGE_ID))).thenReturn(ConsumeResult.ACK);

        consumer.consumeMessage(List.of(message()), null);

        // 传的是 msgId 而不是载荷里的 eventId：后者在同一次 occurrence 重发时是新值，不能用来判重。
        verify(handler).handle(any(), eq(MESSAGE_ID));
    }

    private MessageExt message() {
        MessageExt message = mock(MessageExt.class);
        when(message.getBody()).thenReturn(new byte[] {1});
        when(message.getMsgId()).thenReturn(MESSAGE_ID);
        return message;
    }

    private static RocketMqProperties properties() {
        RocketMqProperties properties = new RocketMqProperties();
        properties.setNamesrv("127.0.0.1:9876");
        properties.setProducerGroup("reminder-service-producer");
        properties.setDueTopic("reminder-due");
        properties.setConsumerGroup("reminder-service-reminder-due");
        properties.setMaxReconsumeTimes(8);
        return properties;
    }
}
