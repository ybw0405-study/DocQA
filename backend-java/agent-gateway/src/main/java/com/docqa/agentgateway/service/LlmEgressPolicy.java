package com.docqa.agentgateway.service;

import com.docqa.agentgateway.config.ModelConnectionProperties;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * 模型连接端点的出网判定，与 Python 侧 {@code tools/web/urls.py::validate_url} 同一套规则。
 *
 * <p>为什么网关也要判一次：探测是真实出网，界面填进来的 base URL 在网关这里就已经是
 * 不可信输入；只靠数据面判定等于让网关替任意内网地址发起请求（SSRF）。
 * 两侧读同一组环境变量，判定必须一致——网关放行的端点数据面也要放行，
 * 否则会出现「测试连接通过、问答却失败」这种最难排查的形态。
 *
 * <p>判定顺序与 Python 一致：长度 → scheme → 嵌入凭据 → 主机 → 不可路由主机名 →
 * 端口 → 后缀白名单 → 私网/回环（除非显式放行）。
 */
@Component
public class LlmEgressPolicy {

    /** 与 Python 侧同一份上限，避免两侧对「太长」的判定不同。 */
    private static final int MAX_URL_CHARS = 2048;
    private static final int MAX_HOST_CHARS = 253;
    private static final List<String> FORBIDDEN_HOST_SUFFIXES =
            List.of(".localhost", ".local", ".internal", ".home.arpa");

    private final ModelConnectionProperties properties;

    public LlmEgressPolicy(ModelConnectionProperties properties) {
        this.properties = properties;
    }

    /** 校验并归一化 base URL；不合规抛 {@link EgressRejected}，由调用方翻成错误码或探测失败原因。 */
    public ValidatedEndpoint validate(String baseUrl) {
        String raw = baseUrl == null ? "" : baseUrl.strip();
        if (raw.isEmpty() || raw.length() > MAX_URL_CHARS) {
            throw new EgressRejected("URL is empty or too long");
        }
        URI uri;
        try {
            uri = URI.create(raw);
        } catch (IllegalArgumentException ex) {
            throw new EgressRejected("URL is not parseable");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new EgressRejected("only http and https URLs are allowed");
        }
        if (uri.getUserInfo() != null) {
            throw new EgressRejected("URLs with embedded credentials are not allowed");
        }
        String host = uri.getHost();
        if (host == null) {
            throw new EgressRejected("URL has no usable host");
        }
        host = host.toLowerCase(Locale.ROOT);
        while (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        if (host.isEmpty() || host.length() > MAX_HOST_CHARS) {
            throw new EgressRejected("URL has no usable host");
        }
        if (host.equals("localhost") || FORBIDDEN_HOST_SUFFIXES.stream().anyMatch(host::endsWith)) {
            throw new EgressRejected("URL host is not routable");
        }
        int port = uri.getPort();
        if (port == -1) {
            port = scheme.equals("https") ? 443 : 80;
        }
        if (port < 1 || port > 65535) {
            throw new EgressRejected("URL port is invalid");
        }
        if (!hostAllowed(host, properties.getAllowedHosts())) {
            throw new EgressRejected("URL host is outside the configured allowlist");
        }
        // DNS rebinding 防护：校验期解析一次 DNS 并固定结果，出网时用这个 IP 直连，
        // 避免「校验时公网 IP、连接时改指内网」的 DNS rebinding（TOCTOU）。
        String pinnedIp = null;
        if (!properties.isAllowPrivate()) {
            pinnedIp = resolveAndValidate(host);
        }
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        String netloc = host.contains(":") ? "[" + host + "]" : host;
        if (uri.getPort() != -1) {
            netloc = netloc + ":" + port;
        }
        // query 与 fragment 一律丢弃：OpenAI 兼容端点的路径里不该有它们，留着只会成为 smuggle 面。
        return new ValidatedEndpoint(scheme + "://" + netloc + path, host, port, pinnedIp);
    }

    private static boolean hostAllowed(String host, List<String> allowedHosts) {
        if (allowedHosts == null || allowedHosts.isEmpty()) {
            return true;
        }
        for (String entry : allowedHosts) {
            String suffix = entry == null ? "" : entry.strip().toLowerCase(Locale.ROOT);
            if (suffix.isEmpty()) {
                continue;
            }
            if (host.equals(suffix) || host.endsWith("." + suffix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 解析并校验全部 A/AAAA 记录均非私网，返回可供出网固定的那一个 IP（字符串形式）。
     * 全部记录都要检查：只要有一条指向私网就拒绝（防止 DNS 轮询下偶发连到内网）。
     */
    private static String resolveAndValidate(String host) {
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException ex) {
            throw new EgressRejected("URL host does not resolve");
        }
        if (addresses.length == 0) {
            throw new EgressRejected("URL host does not resolve");
        }
        for (InetAddress address : addresses) {
            if (isForbiddenAddress(address)) {
                throw new EgressRejected("URL resolves to a private or reserved address");
            }
        }
        return addresses[0].getHostAddress();
    }

    /**
     * 与 Python {@code is_forbidden_address} 对齐的私网/保留地址判定。
     *
     * <p>JDK 的 {@code isSiteLocalAddress} 只覆盖 IPv4 的三段私网，不覆盖 IPv6 唯一本地
     * （fc00::/7）与 IPv4 的 0/8、100.64/10、240/4；这几段必须显式补上，
     * 否则同一个地址在网关放行、在数据面被拒。
     */
    static boolean isForbiddenAddress(InetAddress address) {
        if (address.isLoopbackAddress() || address.isAnyLocalAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet6Address) {
            return (bytes[0] & 0xfe) == 0xfc;
        }
        int first = bytes[0] & 0xff;
        int second = bytes[1] & 0xff;
        if (first == 0 || first >= 240) {
            return true;
        }
        return first == 100 && second >= 64 && second <= 127;
    }

    /** 归一化后的端点：探测与落库都用它，避免同一份配置在两侧长成不同形状。
     * pinnedIp 是校验期解析并校验过的 IP，探测时固定用它出网。 */
    public record ValidatedEndpoint(String baseUrl, String host, int port, String pinnedIp) {
        /** 兼容旧调用方（测试 / 显式放行私网的场景）：无 pinnedIp 表示用默认 DNS 出网。 */
        public ValidatedEndpoint(String baseUrl, String host, int port) {
            this(baseUrl, host, port, null);
        }
    }

    /** 出网被拒；message 是可读原因，不含任何凭据材料。 */
    public static class EgressRejected extends RuntimeException {

        public EgressRejected(String reason) {
            super(reason);
        }
    }
}
