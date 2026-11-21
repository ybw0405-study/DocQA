package com.docqa.user.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 可信反向代理白名单。只有来自这些地址的 {@code X-Forwarded-For} 才会被解析；
 * 直接来自公网的请求可以任意伪造该头，不设白名单等于把限流键交给攻击者控制。
 *
 * <p>默认只信任本机回环地址。user-service 部署在 agent-gateway 后面时，看到的
 * {@code remoteAddr} 恒为网关地址：必须在 {@code docqa.trusted-proxies}（或环境变量
 * {@code DOCQA_TRUSTED_PROXIES}）里显式登记网关的内网地址，XFF 解析才会生效。
 * 网关地址因部署形态而异，仓库不硬编码任何具体值。
 */
@ConfigurationProperties(prefix = "docqa")
public class TrustedProxyProperties {

    private List<String> trustedProxies = new ArrayList<>(List.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1"));

    public List<String> getTrustedProxies() {
        return trustedProxies;
    }

    public void setTrustedProxies(List<String> trustedProxies) {
        this.trustedProxies = trustedProxies == null ? new ArrayList<>() : new ArrayList<>(trustedProxies);
    }
}
