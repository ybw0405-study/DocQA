package com.docqa.agentgateway.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 建立或更新一条模型连接（按租户内 name upsert）。
 *
 * <p>刻意不含 API Key：凭据走独立的 {@code PUT /{id}/credential}，
 * 因此创建请求体可以安全地进访问日志与错误回显，密钥只在那一个请求里出现。
 *
 * <p>同名重复提交收敛到同一行且**不改变生效状态**，所以本操作按值幂等，
 * 沿用 D-197「管理端不声明幂等头」。
 */
public record CreateModelConnectionRequest(
        @JsonProperty(required = true)
        @NotBlank
        @Size(max = 64)
        String name,
        @Size(max = 32)
        @Pattern(regexp = "^[a-z0-9][a-z0-9-]{0,31}$")
        String presetId,
        @JsonProperty(required = true)
        @NotBlank
        @Size(max = 512)
        String baseUrl,
        @JsonProperty(required = true)
        @NotBlank
        @Size(max = 64)
        String model,
        Boolean enabled) {
}
