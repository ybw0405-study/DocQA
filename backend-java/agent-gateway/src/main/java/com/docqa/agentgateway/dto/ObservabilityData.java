package com.docqa.agentgateway.dto;

/** 可观测性入口；langfuseBaseUrl 为空串表示未部署 LangFuse，前端不渲染追踪链接。 */
public record ObservabilityData(String langfuseBaseUrl) {
}
