package com.docqa.reminder.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.docqa.reminder.config.RocketMqProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "docqa.rocketmq.enabled", havingValue = "true", matchIfMissing = true)
public class RocketMqReminderDuePublisher implements ReminderDuePublisher {

    private final RocketMqProperties properties;
    private final ObjectMapper objectMapper;
    private final DefaultMQProducer producer;

    public RocketMqReminderDuePublisher(RocketMqProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.producer = new DefaultMQProducer(properties.getProducerGroup());
        this.producer.setNamesrvAddr(properties.getNamesrv());
        this.producer.setSendMsgTimeout(properties.getSendTimeoutMs());
        this.producer.setRetryTimesWhenSendFailed(2);
    }

    @PostConstruct
    void start() {
        try {
            producer.start();
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to start reminder RocketMQ producer", ex);
        }
    }

    @PreDestroy
    void shutdown() {
        producer.shutdown();
    }

    @Override
    public void publish(ReminderDueEvent event) {
        try {
            Message message = new Message(
                    properties.getDueTopic(),
                    objectMapper.writeValueAsString(event).getBytes(StandardCharsets.UTF_8));
            message.setKeys(event.reminderId().toString());
            message.putUserProperty("traceId", event.traceId());
            message.putUserProperty(
                    "occurrenceStartUtc",
                    Long.toString(event.occurrenceStartUtc().getEpochSecond()));
            SendResult result = producer.send(message);
            if (result.getSendStatus() != SendStatus.SEND_OK) {
                throw new ReminderDuePublishException(
                        "RocketMQ send status is " + result.getSendStatus(),
                        null);
            }
        } catch (JsonProcessingException ex) {
            throw new ReminderDuePublishException("Unable to serialize reminder due event", ex);
        } catch (ReminderDuePublishException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ReminderDuePublishException("Unable to publish reminder due event", ex);
        }
    }
}
