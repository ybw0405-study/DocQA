package com.docqa.agentgateway.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 两个字段都必须出现（可显式为 null）；显式 null 表示该维度不设上限，
 * 两项都为 null 表示清除覆盖。@JsonProperty(required = true) 让「漏传」在校验前就变成 400，
 * 而不是被当成「清除该维度」。
 */
public record UpdateAiQuotaRequest(
        @JsonProperty(required = true)
        @Min(1)
        @Max(4_294_967_295L)
        Long dailyQuestionLimit,
        @JsonProperty(required = true)
        @Min(1)
        @Max(4_294_967_295L)
        Long dailyTokenLimit) {
}
