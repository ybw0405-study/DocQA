package com.docqa.document.messaging;

import com.docqa.document.config.RocketMqProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.List;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.MessageExt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "docqa.rocketmq.enabled", havingValue = "true", matchIfMissing = true)
public class VectorChangeResultConsumer implements MessageListenerConcurrently {

    private static final Logger LOGGER = LoggerFactory.getLogger(VectorChangeResultConsumer.class);

    private final RocketMqProperties properties;
    private final VectorChangeResultHandler handler;
    private final MqIdempotencyMarker idempotencyMarker;
    private final DefaultMQPushConsumer consumer;

    public VectorChangeResultConsumer(RocketMqProperties properties, VectorChangeResultHandler handler,
                                      MqIdempotencyMarker idempotencyMarker) {
        this.properties = properties;
        this.handler = handler;
        this.idempotencyMarker = idempotencyMarker;
        this.consumer = new DefaultMQPushConsumer(properties.getVectorResultConsumerGroup());
        this.consumer.setNamesrvAddr(properties.getNamesrv());
        this.consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        this.consumer.setConsumeThreadMin(1);
        this.consumer.setConsumeThreadMax(2);
    }

    @PostConstruct
    void start() {
        try {
            consumer.subscribe(properties.getVectorChangeCompletedTopic(), "*");
            consumer.registerMessageListener(this);
            consumer.start();
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to start RocketMQ vector result consumer", ex);
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
            if (!properties.getVectorChangeCompletedTopic().equals(message.getTopic())) {
                LOGGER.warn("Ignoring unsupported RocketMQ topic: {}", message.getTopic());
                continue;
            }
            try {
                handler.handle(message.getMsgId(), message.getBody());
            } catch (RuntimeException ex) {
                LOGGER.error(
                        "vector change result processing failed: topic={}, messageId={}",
                        message.getTopic(),
                        message.getMsgId(),
                        ex);
                // P1-8：释放原子占位，允许重投重新处理
                idempotencyMarker.release(message.getTopic(), message.getMsgId());
                return ConsumeConcurrentlyStatus.RECONSUME_LATER;
            }
        }
        return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
    }
}
