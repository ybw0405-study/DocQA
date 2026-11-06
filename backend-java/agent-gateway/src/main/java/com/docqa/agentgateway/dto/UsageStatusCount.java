package com.docqa.agentgateway.dto;

/** 某个终态在时间窗内的执行次数。 */
public record UsageStatusCount(String status, long count) {
}
