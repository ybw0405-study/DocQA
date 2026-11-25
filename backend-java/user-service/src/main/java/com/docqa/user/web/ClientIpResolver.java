package com.docqa.user.web;

import com.docqa.user.config.TrustedProxyProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 解析「真实客户端 IP」。所有流量经 agent-gateway 转发时 {@code remoteAddr} 恒为网关地址，
 * 直接拿它做限流键会让全站共享同一份配额。
 *
 * <p>只在 {@code remoteAddr} 命中可信代理白名单时才读取 {@code X-Forwarded-For}：直接来自
 * 公网的请求可以任意伪造 XFF，不设白名单等于把限流键交给攻击者控制。XFF 从右向左跳过
 * 可信代理后取第一个不可信跳——这是与客户端相邻的那一跳，也是伪造者无法抹掉的边界
 * （伪造值只能追加在最左侧）。全部跳都可信时退回最左首跳，无 XFF 时退回 remoteAddr。
 */
@Component
public class ClientIpResolver {

    public static final String FORWARDED_FOR_HEADER = "X-Forwarded-For";

    private final Set<String> trustedProxies;

    @Autowired
    public ClientIpResolver(TrustedProxyProperties properties) {
        this(properties.getTrustedProxies());
    }

    /** 显式传入白名单，供单测与不启动 Spring 上下文的调用方使用。 */
    public ClientIpResolver(Collection<String> trustedProxies) {
        Set<String> normalized = new LinkedHashSet<>();
        if (trustedProxies != null) {
            for (String proxy : trustedProxies) {
                String value = normalize(proxy);
                if (!value.isEmpty()) {
                    normalized.add(value);
                }
            }
        }
        this.trustedProxies = Set.copyOf(normalized);
    }

    public String resolve(HttpServletRequest request) {
        String remote = normalize(request.getRemoteAddr());
        if (!trustedProxies.contains(remote)) {
            // 非可信来源：忽略 XFF，防伪造。
            return remote;
        }
        String forwardedFor = request.getHeader(FORWARDED_FOR_HEADER);
        if (forwardedFor == null || forwardedFor.isBlank()) {
            return remote;
        }
        String[] hops = forwardedFor.split(",");
        for (int i = hops.length - 1; i >= 0; i--) {
            String hop = normalize(hops[i]);
            if (!hop.isEmpty() && !trustedProxies.contains(hop)) {
                return hop;
            }
        }
        for (String hop : hops) {
            String value = normalize(hop);
            if (!value.isEmpty()) {
                return value;
            }
        }
        return remote;
    }

    /** 统一 IPv4-mapped IPv6、方括号、端口与大小写，避免白名单比较因格式差异漏判。 */
    static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            return value;
        }
        if (value.startsWith("[")) {
            int end = value.indexOf(']');
            value = end > 0 ? value.substring(1, end) : value.substring(1);
        } else {
            int colon = value.indexOf(':');
            if (colon > 0 && colon == value.lastIndexOf(':')
                    && value.substring(colon + 1).matches("\\d+")) {
                value = value.substring(0, colon);
            }
        }
        if (value.startsWith("::ffff:")) {
            value = value.substring("::ffff:".length());
        }
        if ("0:0:0:0:0:0:0:1".equals(value)) {
            value = "::1";
        }
        return value;
    }
}
