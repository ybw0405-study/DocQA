package com.docqa.audit.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "docqa.audit")
public class AuditProperties {

    @NotNull
    private Duration mqIdempotencyTtl = Duration.ofDays(7);

    @Min(1)
    private int queryDefaultLimit = 100;

    @Min(1)
    private int queryMaxLimit = 500;

    @NotNull
    private Duration occurredAtSkew = Duration.ofMinutes(5);

    @AssertTrue(message = "queryDefaultLimit must not exceed queryMaxLimit")
    public boolean isQueryLimitValid() {
        return queryDefaultLimit <= queryMaxLimit;
    }

    @AssertTrue(message = "occurredAtSkew must not be negative")
    public boolean isOccurredAtSkewValid() {
        return !occurredAtSkew.isNegative();
    }

    @AssertTrue(message = "mqIdempotencyTtl must be positive")
    public boolean isMqIdempotencyTtlValid() {
        return !mqIdempotencyTtl.isZero() && !mqIdempotencyTtl.isNegative();
    }

    public Duration getMqIdempotencyTtl() {
        return mqIdempotencyTtl;
    }

    public void setMqIdempotencyTtl(Duration mqIdempotencyTtl) {
        this.mqIdempotencyTtl = mqIdempotencyTtl;
    }

    public int getQueryDefaultLimit() {
        return queryDefaultLimit;
    }

    public void setQueryDefaultLimit(int queryDefaultLimit) {
        this.queryDefaultLimit = queryDefaultLimit;
    }

    public int getQueryMaxLimit() {
        return queryMaxLimit;
    }

    public void setQueryMaxLimit(int queryMaxLimit) {
        this.queryMaxLimit = queryMaxLimit;
    }

    public Duration getOccurredAtSkew() {
        return occurredAtSkew;
    }

    public void setOccurredAtSkew(Duration occurredAtSkew) {
        this.occurredAtSkew = occurredAtSkew;
    }
}
