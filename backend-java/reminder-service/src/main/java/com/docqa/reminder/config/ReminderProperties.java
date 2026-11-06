package com.docqa.reminder.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "docqa.reminder")
public class ReminderProperties {

    @NotNull
    private Duration idempotencyResultTtl = Duration.ofHours(24);

    @NotNull
    private Duration idempotencyInProgressTtl = Duration.ofSeconds(60);

    @NotNull
    private Duration firedTtl = Duration.ofHours(48);

    @NotNull
    private Duration firedClaimTtl = Duration.ofSeconds(60);

    @NotNull
    private Duration rateLimitWindow = Duration.ofSeconds(120);

    @Min(1)
    private int rateLimitMaxRequests = 20;

    @Min(1000)
    private long scanIntervalMs = 5000;

    @Min(0)
    private long scanInitialDelayMs = 5000;

    @Min(1)
    private int scanBatchSize = 50;

    @NotNull
    private Duration pastGrace = Duration.ofMinutes(10);

    @Min(1)
    private int maxExdates = 365;

    /** {@code mq:idem:{topic}:{messageId}} 的存活期；只挡重投加速，通知的幂等收敛点是 MySQL 唯一键。 */
    @NotNull
    private Duration mqIdempotencyTtl = Duration.ofDays(7);

    public Duration getIdempotencyResultTtl() {
        return idempotencyResultTtl;
    }

    public void setIdempotencyResultTtl(Duration idempotencyResultTtl) {
        this.idempotencyResultTtl = idempotencyResultTtl;
    }

    public Duration getIdempotencyInProgressTtl() {
        return idempotencyInProgressTtl;
    }

    public void setIdempotencyInProgressTtl(Duration idempotencyInProgressTtl) {
        this.idempotencyInProgressTtl = idempotencyInProgressTtl;
    }

    public Duration getFiredTtl() {
        return firedTtl;
    }

    public void setFiredTtl(Duration firedTtl) {
        this.firedTtl = firedTtl;
    }

    public Duration getFiredClaimTtl() {
        return firedClaimTtl;
    }

    public void setFiredClaimTtl(Duration firedClaimTtl) {
        this.firedClaimTtl = firedClaimTtl;
    }

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

    public long getScanIntervalMs() {
        return scanIntervalMs;
    }

    public void setScanIntervalMs(long scanIntervalMs) {
        this.scanIntervalMs = scanIntervalMs;
    }

    public long getScanInitialDelayMs() {
        return scanInitialDelayMs;
    }

    public void setScanInitialDelayMs(long scanInitialDelayMs) {
        this.scanInitialDelayMs = scanInitialDelayMs;
    }

    public int getScanBatchSize() {
        return scanBatchSize;
    }

    public void setScanBatchSize(int scanBatchSize) {
        this.scanBatchSize = scanBatchSize;
    }

    public Duration getPastGrace() {
        return pastGrace;
    }

    public void setPastGrace(Duration pastGrace) {
        this.pastGrace = pastGrace;
    }

    public int getMaxExdates() {
        return maxExdates;
    }

    public void setMaxExdates(int maxExdates) {
        this.maxExdates = maxExdates;
    }

    public Duration getMqIdempotencyTtl() {
        return mqIdempotencyTtl;
    }

    public void setMqIdempotencyTtl(Duration mqIdempotencyTtl) {
        this.mqIdempotencyTtl = mqIdempotencyTtl;
    }
}
