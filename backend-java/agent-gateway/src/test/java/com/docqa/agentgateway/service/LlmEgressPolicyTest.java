package com.docqa.agentgateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.docqa.agentgateway.config.ModelConnectionProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 出网判定用 IP 字面量做用例，避免依赖 DNS：判定逻辑本身与解析结果无关，
 * 只有「主机名解析到私网」这一条需要真实解析，单独用 .invalid 域覆盖。
 */
class LlmEgressPolicyTest {

    private static final String PUBLIC_IP_URL = "https://93.184.216.34/v1";

    private final ModelConnectionProperties properties = new ModelConnectionProperties();

    private LlmEgressPolicy policy() {
        return new LlmEgressPolicy(properties);
    }

    @Test
    void acceptsPublicHttpsWithPathAndNormalizesIt() {
        LlmEgressPolicy.ValidatedEndpoint endpoint = policy().validate("https://93.184.216.34/compatible-mode/v1");

        assertThat(endpoint.baseUrl()).isEqualTo("https://93.184.216.34/compatible-mode/v1");
        assertThat(endpoint.host()).isEqualTo("93.184.216.34");
        assertThat(endpoint.port()).isEqualTo(443);
    }

    @Test
    void keepsExplicitPortAndDropsQueryAndFragment() {
        LlmEgressPolicy.ValidatedEndpoint endpoint =
                policy().validate("https://93.184.216.34:8443/v1?token=abc#frag");

        assertThat(endpoint.baseUrl()).isEqualTo("https://93.184.216.34:8443/v1");
        assertThat(endpoint.port()).isEqualTo(8443);
    }

    @Test
    void rejectsEmptyOverlongAndNonHttpUrls() {
        assertThatThrownBy(() -> policy().validate(null)).isInstanceOf(LlmEgressPolicy.EgressRejected.class);
        assertThatThrownBy(() -> policy().validate("   ")).isInstanceOf(LlmEgressPolicy.EgressRejected.class);
        assertThatThrownBy(() -> policy().validate("https://93.184.216.34/" + "a".repeat(2100)))
                .isInstanceOf(LlmEgressPolicy.EgressRejected.class)
                .hasMessageContaining("too long");
        assertThatThrownBy(() -> policy().validate("ftp://93.184.216.34/v1"))
                .isInstanceOf(LlmEgressPolicy.EgressRejected.class)
                .hasMessageContaining("only http and https");
    }

    /** 嵌入凭据的 URL 会把密钥带进日志与重定向，一律拒绝。 */
    @Test
    void rejectsEmbeddedCredentials() {
        assertThatThrownBy(() -> policy().validate("https://user:pass@93.184.216.34/v1"))
                .isInstanceOf(LlmEgressPolicy.EgressRejected.class)
                .hasMessageContaining("embedded credentials");
    }

    @Test
    void rejectsUnroutableHostnames() {
        for (String host : List.of("localhost", "foo.localhost", "printer.local", "db.internal", "x.home.arpa")) {
            assertThatThrownBy(() -> policy().validate("https://" + host + "/v1"))
                    .as("%s 必须被拒", host)
                    .isInstanceOf(LlmEgressPolicy.EgressRejected.class)
                    .hasMessageContaining("not routable");
        }
    }

    @Test
    void rejectsPrivateAndReservedAddresses() {
        List<String> forbidden = List.of(
                "https://127.0.0.1/v1",
                "http://10.1.2.3/v1",
                "http://192.168.1.1/v1",
                "http://172.16.0.1/v1",
                "http://169.254.169.254/latest/meta-data",
                "http://0.0.0.0/v1",
                "http://100.64.0.1/v1",
                "http://240.0.0.1/v1",
                "http://[::1]/v1",
                "http://[fc00::1]/v1",
                "http://[fe80::1]/v1");
        for (String url : forbidden) {
            assertThatThrownBy(() -> policy().validate(url))
                    .as("%s 必须被拒", url)
                    .isInstanceOf(LlmEgressPolicy.EgressRejected.class)
                    .hasMessageContaining("private or reserved");
        }
    }

    @Test
    void allowPrivateOpensLoopbackForSelfHostedEndpoints() {
        properties.setAllowPrivate(true);

        LlmEgressPolicy.ValidatedEndpoint endpoint = policy().validate("http://127.0.0.1:11434/v1");

        assertThat(endpoint.baseUrl()).isEqualTo("http://127.0.0.1:11434/v1");
        assertThat(endpoint.port()).isEqualTo(11434);
    }

    @Test
    void allowlistMatchesHostAndSubdomainsOnly() {
        properties.setAllowPrivate(true);
        properties.setAllowedHosts(List.of("example.com"));

        assertThat(policy().validate("https://api.example.com/v1").host()).isEqualTo("api.example.com");
        assertThat(policy().validate("https://example.com/v1").host()).isEqualTo("example.com");
        assertThatThrownBy(() -> policy().validate("https://notexample.com/v1"))
                .isInstanceOf(LlmEgressPolicy.EgressRejected.class)
                .hasMessageContaining("allowlist");
        assertThatThrownBy(() -> policy().validate("https://example.com.evil.net/v1"))
                .isInstanceOf(LlmEgressPolicy.EgressRejected.class)
                .hasMessageContaining("allowlist");
    }

    @Test
    void emptyAllowlistStillRejectsPrivateAddresses() {
        properties.setAllowedHosts(List.of());

        assertThat(policy().validate(PUBLIC_IP_URL).host()).isEqualTo("93.184.216.34");
        assertThatThrownBy(() -> policy().validate("http://127.0.0.1/v1"))
                .isInstanceOf(LlmEgressPolicy.EgressRejected.class);
    }

    @Test
    void rejectsInvalidPorts() {
        properties.setAllowPrivate(true);

        assertThatThrownBy(() -> policy().validate("https://127.0.0.1:0/v1"))
                .isInstanceOf(LlmEgressPolicy.EgressRejected.class)
                .hasMessageContaining("port");
        assertThatThrownBy(() -> policy().validate("https://127.0.0.1:99999/v1"))
                .isInstanceOf(LlmEgressPolicy.EgressRejected.class)
                .hasMessageContaining("port");
    }

    @Test
    void rejectsHostnamesThatDoNotResolve() {
        assertThatThrownBy(() -> policy().validate("https://nonexistent.invalid/v1"))
                .isInstanceOf(LlmEgressPolicy.EgressRejected.class)
                .hasMessageContaining("does not resolve");
    }

    @Test
    void stripsTrailingDotFromHostAndLowercasesIt() {
        properties.setAllowPrivate(true);
        properties.setAllowedHosts(List.of("example.com"));

        LlmEgressPolicy.ValidatedEndpoint endpoint = policy().validate("https://API.Example.COM./v1");

        assertThat(endpoint.host()).isEqualTo("api.example.com");
        assertThat(endpoint.baseUrl()).isEqualTo("https://api.example.com/v1");
    }
}
