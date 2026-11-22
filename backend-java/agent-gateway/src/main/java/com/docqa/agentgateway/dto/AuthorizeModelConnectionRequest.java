package com.docqa.agentgateway.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 录入 API Key。**toString 必须脱敏**：record 默认会把 secret 原样拼进字符串，
 * 任何一处日志或异常回显带上这个对象就等于泄密。
 */
public record AuthorizeModelConnectionRequest(
        @JsonProperty(required = true)
        @NotBlank
        @Size(max = 512)
        String secret) {

    @Override
    public String toString() {
        return "AuthorizeModelConnectionRequest[secret=REDACTED]";
    }
}
