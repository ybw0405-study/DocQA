package com.docqa.agentgateway.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "docqa.agent")
public class AgentProperties {

    private Duration rateLimitWindow = Duration.ofSeconds(120);

    @Min(1)
    private int rateLimitMaxRequests = 20;

    private Duration conversationLockTtl = Duration.ofSeconds(30);

    private Duration idempotencyInProgressTtl = Duration.ofSeconds(60);

    private Duration idempotencyResultTtl = Duration.ofHours(24);

    private Duration qaCacheTtl = Duration.ofMinutes(10);

    private Duration budgetTtl = Duration.ofMinutes(30);

    private Duration stateTtl = Duration.ofMinutes(30);

    @Min(1)
    private int historyLimit = 6;

    @Min(1)
    private int historyMaxChars = 8000;

    private Duration killSwitchCacheTtl = Duration.ofSeconds(30);

    @NotBlank
    private String promptVersion = "p1";

    @Min(1)
    private int maxCitations = 20;

    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double lowConfidenceThreshold = 0.55;

    public Duration getRateLimitWindow() {
        return rateLimitWindow;
    }

    public void setRateLimitWindow(Duration rateLimitWindow) {
        this.rateLimitWindow = rateLimitWindow;
    }

    public int getRateLimitMaxRequests() {
        return rateLimitMaxRequests;
    }

    public void setRateLimitMaxRequests(int rateLimitMaxRequests) {
        this.rateLimitMaxRequests = rateLimitMaxRequests;
    }

    public Duration getConversationLockTtl() {
        return conversationLockTtl;
    }

    public void setConversationLockTtl(Duration conversationLockTtl) {
        this.conversationLockTtl = conversationLockTtl;
    }

    public Duration getIdempotencyInProgressTtl() {
        return idempotencyInProgressTtl;
    }

    public void setIdempotencyInProgressTtl(Duration idempotencyInProgressTtl) {
        this.idempotencyInProgressTtl = idempotencyInProgressTtl;
    }

    public Duration getIdempotencyResultTtl() {
        return idempotencyResultTtl;
    }

    public void setIdempotencyResultTtl(Duration idempotencyResultTtl) {
        this.idempotencyResultTtl = idempotencyResultTtl;
    }

    public Duration getQaCacheTtl() {
        return qaCacheTtl;
    }

    public void setQaCacheTtl(Duration qaCacheTtl) {
        this.qaCacheTtl = qaCacheTtl;
    }

    public Duration getBudgetTtl() {
        return budgetTtl;
    }

    public void setBudgetTtl(Duration budgetTtl) {
        this.budgetTtl = budgetTtl;
    }

    public Duration getStateTtl() {
        return stateTtl;
    }

    public void setStateTtl(Duration stateTtl) {
        this.stateTtl = stateTtl;
    }

    public int getHistoryLimit() {
        return historyLimit;
    }

    public void setHistoryLimit(int historyLimit) {
        this.historyLimit = historyLimit;
    }

    public int getHistoryMaxChars() {
        return historyMaxChars;
    }

    public void setHistoryMaxChars(int historyMaxChars) {
        this.historyMaxChars = historyMaxChars;
    }

    public Duration getKillSwitchCacheTtl() {
        return killSwitchCacheTtl;
    }

    public void setKillSwitchCacheTtl(Duration killSwitchCacheTtl) {
        this.killSwitchCacheTtl = killSwitchCacheTtl;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public void setPromptVersion(String promptVersion) {
        this.promptVersion = promptVersion;
    }

    public int getMaxCitations() {
        return maxCitations;
    }

    public void setMaxCitations(int maxCitations) {
        this.maxCitations = maxCitations;
    }

    public double getLowConfidenceThreshold() {
        return lowConfidenceThreshold;
    }

    public void setLowConfidenceThreshold(double lowConfidenceThreshold) {
        this.lowConfidenceThreshold = lowConfidenceThreshold;
    }
}
