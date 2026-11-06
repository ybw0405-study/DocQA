package com.docqa.agentgateway.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.docqa.agentgateway.config.AuditProperties;
import com.docqa.agentgateway.dto.AuditEvent;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.message.Message;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "docqa.audit", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RocketMqAuditEventPublisher implements AuditEventPublisher, InitializingBean, DisposableBean {

    private final AuditProperties properties;
    private final ObjectMapper objectMapper;
    private final DefaultMQProducer producer;

    public RocketMqAuditEventPublisher(AuditProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.producer = new DefaultMQProducer(properties.getProducerGroup());
        producer.setNamesrvAddr(properties.getNamesrv());
        producer.setSendMsgTimeout(Math.toIntExact(properties.getSendTimeout().toMillis()));
    }

    @Override
    public void afterPropertiesSet() {
        try {
            producer.start();
        } catch (MQClientException ex) {
            throw new IllegalStateException("Unable to start audit event producer", ex);
        }
    }

    @Override
    public void publish(AuditEvent event) {
        try {
            Message message = new Message(
                    properties.getTopic(),
                    event.eventId().toString(),
                    objectMapper.writeValueAsBytes(event));
            message.setKeys(event.eventId().toString());
            message.putUserProperty("traceId", event.traceId());
            producer.send(message);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize audit event", ex);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to publish audit event", ex);
        }
    }

    @Override
    public void destroy() {
        producer.shutdown();
    }
}
