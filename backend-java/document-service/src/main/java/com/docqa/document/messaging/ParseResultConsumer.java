package com.docqa.document.messaging;

import com.docqa.common.metrics.BusinessMetrics;
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
public class ParseResultConsumer implements MessageListenerConcurrently {

    private static final Logger LOGGER = LoggerFactory.getLogger(ParseResultConsumer.class);

    /** 消费侧无法从异常里稳定取到业务错误码，统一记这个值，把区分度留在 {@code topic} 标签上。 */
    private static final String DLQ_ERROR_CODE = "MSG-CONSUME-FAILED";

    private final RocketMqProperties properties;
    private final ParseResultHandler handler;
    private final BusinessMetrics metrics;
    private final DefaultMQPushConsumer consumer;

    public ParseResultConsumer(RocketMqProperties properties, ParseResultHandler handler, BusinessMetrics metrics) {
        this.properties = properties;
        this.handler = handler;
        this.metrics = metrics;
        this.consumer = new DefaultMQPushConsumer(properties.getConsumerGroup());
        this.consumer.setNamesrvAddr(properties.getNamesrv());
        this.consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        this.consumer.setConsumeThreadMin(2);
        this.consumer.setConsumeThreadMax(4);
        this.consumer.setMaxReconsumeTimes(properties.getMaxReconsumeTimes());
    }

    @PostConstruct
    void start() {
        try {
            consumer.subscribe(properties.getParseCompletedTopic(), "*");
            consumer.subscribe(properties.getParseFailedTopic(), "*");
            consumer.registerMessageListener(this);
            consumer.start();
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to start RocketMQ consumer", ex);
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
            try {
                if (properties.getParseCompletedTopic().equals(message.getTopic())) {
                    handler.handleCompleted(message.getMsgId(), message.getBody());
                } else if (properties.getParseFailedTopic().equals(message.getTopic())) {
                    handler.handleFailed(message.getMsgId(), message.getBody());
                } else {
                    LOGGER.warn("Ignoring unsupported RocketMQ topic: {}", message.getTopic());
                }
            } catch (RuntimeException ex) {
                LOGGER.error(
                        "parse result processing failed: topic={}, messageId={}",
                        message.getTopic(),
                        message.getMsgId(),
                        ex);
                // 本次返回 RECONSUME_LATER 之后，broker 会把消费次数 +1 再重投；
                // 一旦达到 maxReconsumeTimes 就改投 DLQ，因此「本次是最后一次重投」即为毒消息。
                if (message.getReconsumeTimes() + 1 >= properties.getMaxReconsumeTimes()) {
                    metrics.mqDlq(message.getTopic(), DLQ_ERROR_CODE);
                }
                return ConsumeConcurrentlyStatus.RECONSUME_LATER;
            }
        }
        return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
    }
}
