package com.docqa.agentgateway.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "docqa.audit")
public class AuditProperties {

    private boolean enabled = true;

    @NotBlank
    private String namesrv = "127.0.0.1:9876";

    @NotBlank
    private String producerGroup = "agent-gateway-audit-producer";

    @NotBlank
    private String topic = "audit-event";

    private Duration sendTimeout = Duration.ofSeconds(3);

    @Min(100)
    private int relayIntervalMs = 5000;

    @Min(1)
    private int relayBatchSize = 100;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getNamesrv() {
        return namesrv;
    }

    public void setNamesrv(String namesrv) {
        this.namesrv = namesrv;
    }

    public String getProducerGroup() {
        return producerGroup;
    }

    public void setProducerGroup(String producerGroup) {
        this.producerGroup = producerGroup;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public Duration getSendTimeout() {
        return sendTimeout;
    }

    public void setSendTimeout(Duration sendTimeout) {
        this.sendTimeout = sendTimeout;
    }

    public int getRelayIntervalMs() {
        return relayIntervalMs;
    }

    public void setRelayIntervalMs(int relayIntervalMs) {
        this.relayIntervalMs = relayIntervalMs;
    }

    public int getRelayBatchSize() {
        return relayBatchSize;
    }

    public void setRelayBatchSize(int relayBatchSize) {
        this.relayBatchSize = relayBatchSize;
    }
}
