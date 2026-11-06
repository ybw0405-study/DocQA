package com.docqa.reminder.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "docqa.rocketmq")
public class RocketMqProperties {

    public static final String TOPIC_PATTERN = "^[%|a-zA-Z0-9_-]+$";

    private boolean enabled = true;

    @NotBlank
    private String namesrv;

    @NotBlank
    private String producerGroup;

    @NotBlank
    @Pattern(regexp = TOPIC_PATTERN)
    private String dueTopic;

    @NotBlank
    private String consumerGroup;

    @Min(0)
    @Max(32)
    private int maxReconsumeTimes = 8;

    @Min(1)
    private int consumeThreadMin = 2;

    @Min(1)
    private int consumeThreadMax = 4;

    @Min(100)
    private int sendTimeoutMs = 3000;

    @AssertTrue(message = "consumeThreadMin must not exceed consumeThreadMax")
    public boolean isConsumeThreadRangeValid() {
        return consumeThreadMin <= consumeThreadMax;
    }

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

    public String getDueTopic() {
        return dueTopic;
    }

    public void setDueTopic(String dueTopic) {
        this.dueTopic = dueTopic;
    }

    public String getConsumerGroup() {
        return consumerGroup;
    }

    public void setConsumerGroup(String consumerGroup) {
        this.consumerGroup = consumerGroup;
    }

    public int getMaxReconsumeTimes() {
        return maxReconsumeTimes;
    }

    public void setMaxReconsumeTimes(int maxReconsumeTimes) {
        this.maxReconsumeTimes = maxReconsumeTimes;
    }

    public int getConsumeThreadMin() {
        return consumeThreadMin;
    }

    public void setConsumeThreadMin(int consumeThreadMin) {
        this.consumeThreadMin = consumeThreadMin;
    }

    public int getConsumeThreadMax() {
        return consumeThreadMax;
    }

    public void setConsumeThreadMax(int consumeThreadMax) {
        this.consumeThreadMax = consumeThreadMax;
    }

    public int getSendTimeoutMs() {
        return sendTimeoutMs;
    }

    public void setSendTimeoutMs(int sendTimeoutMs) {
        this.sendTimeoutMs = sendTimeoutMs;
    }
}
