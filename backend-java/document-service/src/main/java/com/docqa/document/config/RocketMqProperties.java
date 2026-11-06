package com.docqa.document.config;

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
    private String consumerGroup;

    @NotBlank
    @Pattern(regexp = TOPIC_PATTERN)
    private String parseRequestedTopic;

    @NotBlank
    @Pattern(regexp = TOPIC_PATTERN)
    private String parseCompletedTopic;

    @NotBlank
    @Pattern(regexp = TOPIC_PATTERN)
    private String parseFailedTopic;

    @NotBlank
    @Pattern(regexp = TOPIC_PATTERN)
    private String vectorChangeRequestedTopic;

    @NotBlank
    @Pattern(regexp = TOPIC_PATTERN)
    private String vectorChangeCompletedTopic;

    @NotBlank
    private String vectorResultConsumerGroup;

    @Min(100)
    private int sendTimeoutMs = 3000;

    /**
     * 并发消费的最大重投次数。默认值与 RocketMQ 客户端一致（16）。
     *
     * <p>显式声明而不是依赖客户端默认值：超过这个次数后 broker 会把消息投入
     * {@code %DLQ%<consumerGroup>}，而 {@code docqa_mq_dlq_total} 的判定正是
     * 「下一次重投将越过该阈值」。靠隐式默认值算阈值等于把指标和一个不可见的常量绑在一起。
     */
    @Min(1)
    private int maxReconsumeTimes = 16;

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

    public String getConsumerGroup() {
        return consumerGroup;
    }

    public void setConsumerGroup(String consumerGroup) {
        this.consumerGroup = consumerGroup;
    }

    public String getParseRequestedTopic() {
        return parseRequestedTopic;
    }

    public void setParseRequestedTopic(String parseRequestedTopic) {
        this.parseRequestedTopic = parseRequestedTopic;
    }

    public String getParseCompletedTopic() {
        return parseCompletedTopic;
    }

    public void setParseCompletedTopic(String parseCompletedTopic) {
        this.parseCompletedTopic = parseCompletedTopic;
    }

    public String getParseFailedTopic() {
        return parseFailedTopic;
    }

    public void setParseFailedTopic(String parseFailedTopic) {
        this.parseFailedTopic = parseFailedTopic;
    }

    public String getVectorChangeRequestedTopic() {
        return vectorChangeRequestedTopic;
    }

    public void setVectorChangeRequestedTopic(String vectorChangeRequestedTopic) {
        this.vectorChangeRequestedTopic = vectorChangeRequestedTopic;
    }

    public String getVectorChangeCompletedTopic() {
        return vectorChangeCompletedTopic;
    }

    public void setVectorChangeCompletedTopic(String vectorChangeCompletedTopic) {
        this.vectorChangeCompletedTopic = vectorChangeCompletedTopic;
    }

    public String getVectorResultConsumerGroup() {
        return vectorResultConsumerGroup;
    }

    public void setVectorResultConsumerGroup(String vectorResultConsumerGroup) {
        this.vectorResultConsumerGroup = vectorResultConsumerGroup;
    }

    public int getSendTimeoutMs() {
        return sendTimeoutMs;
    }

    public void setSendTimeoutMs(int sendTimeoutMs) {
        this.sendTimeoutMs = sendTimeoutMs;
    }

    public int getMaxReconsumeTimes() {
        return maxReconsumeTimes;
    }

    public void setMaxReconsumeTimes(int maxReconsumeTimes) {
        this.maxReconsumeTimes = maxReconsumeTimes;
    }
}
