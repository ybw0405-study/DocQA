package com.docqa.audit.messaging;

import com.docqa.audit.config.RocketMqProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.List;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "docqa.rocketmq.enabled", havingValue = "true", matchIfMissing = true)
public class RocketMqAuditConsumer implements MessageListenerConcurrently {

    private final RocketMqProperties properties;
    private final AuditMessageHandler handler;
    private final DefaultMQPushConsumer consumer;

    public RocketMqAuditConsumer(RocketMqProperties properties, AuditMessageHandler handler) {
        this.properties = properties;
        this.handler = handler;
        this.consumer = new DefaultMQPushConsumer(properties.getConsumerGroup());
        this.consumer.setNamesrvAddr(properties.getNamesrv());
        this.consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        this.consumer.setConsumeThreadMin(properties.getConsumeThreadMin());
        this.consumer.setConsumeThreadMax(properties.getConsumeThreadMax());
        this.consumer.setMaxReconsumeTimes(properties.getMaxReconsumeTimes());
    }

    @PostConstruct
    void start() {
        try {
            consumer.subscribe(properties.getTopic(), "*");
            consumer.registerMessageListener(this);
            consumer.start();
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to start audit RocketMQ consumer", ex);
        }
    }

    @PreDestroy
    void shutdown() {
        consumer.shutdown();
    }

    @Override
    public ConsumeConcurrentlyStatus consumeMessage(
            List<MessageExt> messages,
            ConsumeConcurrentlyContext context) {
        for (MessageExt message : messages) {
            ConsumeResult result = handler.handle(message.getBody(), message.getUserProperty("traceId"));
            if (result == ConsumeResult.RETRY) {
                return ConsumeConcurrentlyStatus.RECONSUME_LATER;
            }
        }
        return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
    }
}
