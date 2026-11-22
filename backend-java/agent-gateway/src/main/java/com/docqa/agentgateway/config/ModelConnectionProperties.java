package com.docqa.agentgateway.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 模型连接（界面配置的生成模型）的服务端配置。
 *
 * <p>主密钥刻意允许为空：没配 {@code DOCQA_CRED_MASTER_KEY} 的部署照样要能启动，
 * 只是保存凭据与探测会被明确拒绝，而不是让整个网关起不来。
 *
 * <p>出网白名单与 Python 侧读同一组环境变量（{@code DOCQA_LLM_ALLOWED_HOSTS} /
 * {@code DOCQA_LLM_ALLOW_PRIVATE}），两侧判定必须一致：网关探测放行的端点，
 * 数据面真正调用时也要放行，否则「测试通过但问答失败」。
 */
@Validated
@ConfigurationProperties(prefix = "docqa.ai.model-connection")
public class ModelConnectionProperties {

    /** base64 的 32 字节主密钥；空表示未配置，保存凭据与探测会被拒绝。 */
    private String masterKey = "";

    /** 轮换前的旧主密钥（base64，逗号分隔），只用于解封历史密文。 */
    private List<String> previousKeys = new ArrayList<>();

    /** 允许出网的主机后缀白名单；空表示放行任意公网主机（私网仍由 allowPrivate 决定）。 */
    private List<String> allowedHosts = new ArrayList<>();

    /** 是否放行回环/私网地址；本地 Ollama 这类自托管端点需要显式打开。 */
    private boolean allowPrivate = false;

    /** 生效连接的分发键前缀，与 Python 侧逐字一致。 */
    @NotBlank
    private String keyPrefix = "agent:model-connection:tenant:";

    private Duration probeTimeout = Duration.ofSeconds(5);

    @Min(1024)
    private int probeMaxBytes = 262144;

    public String getMasterKey() {
        return masterKey;
    }

    public void setMasterKey(String masterKey) {
        this.masterKey = masterKey;
    }

    public List<String> getPreviousKeys() {
        return previousKeys;
    }

    public void setPreviousKeys(List<String> previousKeys) {
        this.previousKeys = previousKeys;
    }

    public List<String> getAllowedHosts() {
        return allowedHosts;
    }

    public void setAllowedHosts(List<String> allowedHosts) {
        this.allowedHosts = allowedHosts;
    }

    public boolean isAllowPrivate() {
        return allowPrivate;
    }

    public void setAllowPrivate(boolean allowPrivate) {
        this.allowPrivate = allowPrivate;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    public Duration getProbeTimeout() {
        return probeTimeout;
    }

    public void setProbeTimeout(Duration probeTimeout) {
        this.probeTimeout = probeTimeout;
    }

    public int getProbeMaxBytes() {
        return probeMaxBytes;
    }

    public void setProbeMaxBytes(int probeMaxBytes) {
        this.probeMaxBytes = probeMaxBytes;
    }
}
