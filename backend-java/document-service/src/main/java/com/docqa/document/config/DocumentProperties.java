package com.docqa.document.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "docqa.document")
public class DocumentProperties {

    @NotNull
    private Duration sessionTtl = Duration.ofMinutes(45);

    @NotNull
    private Duration idempotencyTtl = Duration.ofHours(24);

    @NotNull
    private Duration mqIdempotencyTtl = Duration.ofDays(7);

    @Min(1000)
    private long reconcileIntervalMs = 300_000;

    @Min(0)
    private long reconcileInitialDelayMs = 60_000;

    @NotNull
    private Duration staleThreshold = Duration.ofMinutes(10);

    @Min(1)
    private int reconcileBatchSize = 50;

    @Min(1)
    private int defaultMaxRetry = 3;

    @NotBlank
    private String indexVersion = "v1";

    public Duration getSessionTtl() {
        return sessionTtl;
    }

    public void setSessionTtl(Duration sessionTtl) {
        this.sessionTtl = sessionTtl;
    }

    public Duration getIdempotencyTtl() {
        return idempotencyTtl;
    }

    public void setIdempotencyTtl(Duration idempotencyTtl) {
        this.idempotencyTtl = idempotencyTtl;
    }

    public Duration getMqIdempotencyTtl() {
        return mqIdempotencyTtl;
    }

    public void setMqIdempotencyTtl(Duration mqIdempotencyTtl) {
        this.mqIdempotencyTtl = mqIdempotencyTtl;
    }

    public long getReconcileIntervalMs() {
        return reconcileIntervalMs;
    }

    public void setReconcileIntervalMs(long reconcileIntervalMs) {
        this.reconcileIntervalMs = reconcileIntervalMs;
    }

    public long getReconcileInitialDelayMs() {
        return reconcileInitialDelayMs;
    }

    public void setReconcileInitialDelayMs(long reconcileInitialDelayMs) {
        this.reconcileInitialDelayMs = reconcileInitialDelayMs;
    }

    public Duration getStaleThreshold() {
        return staleThreshold;
    }

    public void setStaleThreshold(Duration staleThreshold) {
        this.staleThreshold = staleThreshold;
    }

    public int getReconcileBatchSize() {
        return reconcileBatchSize;
    }

    public void setReconcileBatchSize(int reconcileBatchSize) {
        this.reconcileBatchSize = reconcileBatchSize;
    }

    public int getDefaultMaxRetry() {
        return defaultMaxRetry;
    }

    public void setDefaultMaxRetry(int defaultMaxRetry) {
        this.defaultMaxRetry = defaultMaxRetry;
    }

    public String getIndexVersion() {
        return indexVersion;
    }

    public void setIndexVersion(String indexVersion) {
        this.indexVersion = indexVersion;
    }
}
