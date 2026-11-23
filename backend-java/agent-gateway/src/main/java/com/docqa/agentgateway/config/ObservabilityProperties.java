package com.docqa.agentgateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 可观测性入口配置。网关只下发 LangFuse 的入口地址，不代理其 API、不携带任何凭据。
 *
 * <p>刻意允许为空：未部署 LangFuse 的环境照常启动，管理面拿到空串即不渲染追踪链接，
 * 其余功能不受影响（与 {@code DOCQA_CRED_MASTER_KEY} 留空仍可启动同一口径）。
 */
@ConfigurationProperties(prefix = "docqa.observability")
public class ObservabilityProperties {

    /** LangFuse UI 根地址；空表示禁用追踪链接。 */
    private String langfuseBaseUrl = "";

    public String getLangfuseBaseUrl() {
        return langfuseBaseUrl;
    }

    public void setLangfuseBaseUrl(String langfuseBaseUrl) {
        this.langfuseBaseUrl = langfuseBaseUrl;
    }
}
