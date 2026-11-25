package com.docqa.agentgateway.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "docqa.python")
public class PythonProperties {

    @NotBlank
    private String baseUrl = "http://127.0.0.1:8000";

    private Duration connectTimeout = Duration.ofSeconds(2);

    private Duration responseTimeout = Duration.ofMillis(15500);

    private Duration deadline = Duration.ofSeconds(15);

    @Min(1)
    private int tokenBudget = 8000;

    @Min(1)
    private int maxToolCalls = 3;

    @Min(1)
    private int maxRounds = 3;

    @NotBlank
    private String promptVersion = "p1";

    @NotBlank
    private String maskingVersion = "pii-v2";

    @NotEmpty
    private List<String> allowedTools = new ArrayList<>(List.of(
            "document_search",
            "calculator",
            "current_time",
            "web_fetch",
            "web_search",
            "academic_search",
            "wikipedia_lookup"));

    @Valid
    private CircuitBreaker circuitBreaker = new CircuitBreaker();

    /**
     * 网关 → Python 数据面 HMAC 共享密钥。非空时，每次请求按
     * {@code com.docqa.agentgateway.security.HmacSigner} 规范签名（与
     * {@code ai-python/src/docqa_ai/api/internal_auth.py} 同口径），Python 侧
     * 以签名为唯一凭据、静态 {@code X-Internal-Token} 不再单独放行。
     * 空（默认）= 保留静态 token 兼容期，仅用于 dev / local / test。
     */
    private String internalHmacSecret = "";

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getResponseTimeout() {
        return responseTimeout;
    }

    public void setResponseTimeout(Duration responseTimeout) {
        this.responseTimeout = responseTimeout;
    }

    public Duration getDeadline() {
        return deadline;
    }

    public void setDeadline(Duration deadline) {
        this.deadline = deadline;
    }

    public int getTokenBudget() {
        return tokenBudget;
    }

    public void setTokenBudget(int tokenBudget) {
        this.tokenBudget = tokenBudget;
    }

    public int getMaxToolCalls() {
        return maxToolCalls;
    }

    public void setMaxToolCalls(int maxToolCalls) {
        this.maxToolCalls = maxToolCalls;
    }

    public int getMaxRounds() {
        return maxRounds;
    }

    public void setMaxRounds(int maxRounds) {
        this.maxRounds = maxRounds;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public void setPromptVersion(String promptVersion) {
        this.promptVersion = promptVersion;
    }

    public String getMaskingVersion() {
        return maskingVersion;
    }

    public void setMaskingVersion(String maskingVersion) {
        this.maskingVersion = maskingVersion;
    }

    public List<String> getAllowedTools() {
        return allowedTools;
    }

    public void setAllowedTools(List<String> allowedTools) {
        this.allowedTools = allowedTools;
    }

    public CircuitBreaker getCircuitBreaker() {
        return circuitBreaker;
    }

    public void setCircuitBreaker(CircuitBreaker circuitBreaker) {
        this.circuitBreaker = circuitBreaker;
    }

    public String getInternalHmacSecret() {
        return internalHmacSecret;
    }

    public void setInternalHmacSecret(String internalHmacSecret) {
        this.internalHmacSecret = internalHmacSecret;
    }

    public static class CircuitBreaker {

        @DecimalMin("1.0")
        @DecimalMax("100.0")
        private float failureRateThreshold = 50.0f;

        @Min(1)
        private int slidingWindowSize = 20;

        @Min(1)
        private int minimumNumberOfCalls = 10;

        private Duration waitDurationInOpenState = Duration.ofSeconds(30);

        @Min(1)
        private int permittedNumberOfCallsInHalfOpenState = 3;

        public float getFailureRateThreshold() {
            return failureRateThreshold;
        }

        public void setFailureRateThreshold(float failureRateThreshold) {
            this.failureRateThreshold = failureRateThreshold;
        }

        public int getSlidingWindowSize() {
            return slidingWindowSize;
        }

        public void setSlidingWindowSize(int slidingWindowSize) {
            this.slidingWindowSize = slidingWindowSize;
        }

        public int getMinimumNumberOfCalls() {
            return minimumNumberOfCalls;
        }

        public void setMinimumNumberOfCalls(int minimumNumberOfCalls) {
            this.minimumNumberOfCalls = minimumNumberOfCalls;
        }

        public Duration getWaitDurationInOpenState() {
            return waitDurationInOpenState;
        }

        public void setWaitDurationInOpenState(Duration waitDurationInOpenState) {
            this.waitDurationInOpenState = waitDurationInOpenState;
        }

        public int getPermittedNumberOfCallsInHalfOpenState() {
            return permittedNumberOfCallsInHalfOpenState;
        }

        public void setPermittedNumberOfCallsInHalfOpenState(int permittedNumberOfCallsInHalfOpenState) {
            this.permittedNumberOfCallsInHalfOpenState = permittedNumberOfCallsInHalfOpenState;
        }
    }
}
