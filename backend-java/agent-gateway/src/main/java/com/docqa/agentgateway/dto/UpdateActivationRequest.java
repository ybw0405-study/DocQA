package com.docqa.agentgateway.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

/**
 * 切换生效状态。active=true 时服务端在同一事务里先清掉租户内其它生效标记再设本条，
 * 存储层的 (tenant_id, active_slot) 唯一键保证任何时刻至多一条生效。
 */
public record UpdateActivationRequest(
        @JsonProperty(required = true)
        @NotNull
        Boolean active) {
}
