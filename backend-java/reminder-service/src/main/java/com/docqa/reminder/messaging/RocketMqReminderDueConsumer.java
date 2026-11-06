package com.docqa.reminder.messaging;

import com.docqa.common.metrics.BusinessMetrics;
import com.docqa.reminder.config.RocketMqProperties;
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

/**
 * {@code reminder-due} 的第一个消费者：在此之前这条消息只有生产者，通知恒不存在。
 * 订阅失败即启动失败（与 {@code RocketMqAuditConsumer} 同形状）——静默不消费比拒绝启动更难发现。
 */
@Component
@ConditionalOnProperty(name = "docqa.rocketmq.enabled", havingValue = "true", matchIfMissing = true)
public class RocketMqReminderDueConsumer implements MessageListenerConcurrently {

    /** 消费侧拿不到稳定的业务错误码，统一用这个值，区分度留在 {@code topic} 标签上。 */
    private static final String DLQ_ERROR_CODE = "MSG-CONSUME-FAILED";

    private final RocketMqProperties properties;
    private final ReminderDueMessageHandler handler;
    private final BusinessMetrics metrics;
    private final DefaultMQPushConsumer consumer;

    public RocketMqReminderDueConsumer(
            RocketMqProperties properties, ReminderDueMessageHandler handler, BusinessMetrics metrics) {
        this.properties = properties;
        this.handler = handler;
        this.metrics = metrics;
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
            consumer.subscribe(properties.getDueTopic(), "*");
            consumer.registerMessageListener(this);
            consumer.start();
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to start reminder due RocketMQ consumer", ex);
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
            ConsumeResult result = handler.handle(message.getBody(), message.getMsgId());
            if (result == ConsumeResult.RETRY) {
                // 本次 RECONSUME_LATER 之后 broker 会把重投次数 +1；到达上限即改投 DLQ，
                // 所以「本次是最后一次重投」就是毒消息的那一刻。
                if (message.getReconsumeTimes() + 1 >= properties.getMaxReconsumeTimes()) {
                    metrics.mqDlq(message.getTopic(), DLQ_ERROR_CODE);
                }
                return ConsumeConcurrentlyStatus.RECONSUME_LATER;
            }
        }
        return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
    }
}
