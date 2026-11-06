package com.docqa.document.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.docqa.document.config.RocketMqProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "docqa.rocketmq.enabled", havingValue = "true", matchIfMissing = true)
public class RocketMqDocumentEventPublisher implements DocumentEventPublisher {

    private final RocketMqProperties properties;
    private final ObjectMapper objectMapper;
    private final DefaultMQProducer producer;

    public RocketMqDocumentEventPublisher(RocketMqProperties properties, ObjectMapper objectMapper) {
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
            throw new IllegalStateException("Unable to start RocketMQ producer", ex);
        }
    }

    @PreDestroy
    void shutdown() {
        producer.shutdown();
    }

    @Override
    public void publishParseRequested(ParseRequestedEvent event) {
        publishParseRequested(event, 0);
    }

    @Override
    public void publishParseRequested(ParseRequestedEvent event, int delayLevel) {
        send(properties.getParseRequestedTopic(), event, event.documentId(), event.traceId(), delayLevel);
    }

    @Override
    public void publishVectorChangeRequested(VectorChangeRequestedEvent event) {
        send(properties.getVectorChangeRequestedTopic(), event, event.documentId(), event.traceId(), 0);
    }

    private void send(String topic, Object payload, UUID documentId, String traceId, int delayLevel) {
        try {
            Message message = new Message(
                    topic,
                    objectMapper.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8));
            message.setKeys(documentId.toString());
            message.putUserProperty("traceId", traceId);
            if (delayLevel > 0) {
                message.setDelayTimeLevel(delayLevel);
            }
            SendResult result = producer.send(message);
            if (result.getSendStatus() != SendStatus.SEND_OK) {
                throw new DocumentEventPublishException(
                        "RocketMQ send status is " + result.getSendStatus(),
                        null);
            }
        } catch (JsonProcessingException ex) {
            throw new DocumentEventPublishException("Unable to serialize document event", ex);
        } catch (DocumentEventPublishException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new DocumentEventPublishException("Unable to publish document event", ex);
        }
    }
}
