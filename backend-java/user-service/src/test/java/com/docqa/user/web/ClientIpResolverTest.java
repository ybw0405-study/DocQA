package com.docqa.user.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientIpResolverTest {

    private static final String GATEWAY = "10.0.0.1";

    private ClientIpResolver resolver() {
        return new ClientIpResolver(List.of("127.0.0.1", "::1", GATEWAY));
    }

    private static MockHttpServletRequest requestFrom(String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        return request;
    }

    @Test
    void ignoresSpoofedForwardedForFromAnUntrustedPeer() {
        MockHttpServletRequest request = requestFrom("203.0.113.9");
        request.addHeader(ClientIpResolver.FORWARDED_FOR_HEADER, "1.2.3.4, 5.6.7.8");

        // 来源不可信：伪造的 XFF 必须被完全忽略，限流键仍按真实对端计算。
        assertThat(resolver().resolve(request)).isEqualTo("203.0.113.9");
    }

    @Test
    void takesTheFirstUntrustedHopFromATrustedProxy() {
        MockHttpServletRequest request = requestFrom(GATEWAY);
        // 最左是攻击者追加的伪造值，右侧两跳是可信代理；真实客户端是右侧第一个不可信跳。
        request.addHeader(ClientIpResolver.FORWARDED_FOR_HEADER,
                "198.51.100.7, 203.0.113.5, " + GATEWAY + ", 127.0.0.1");

        assertThat(resolver().resolve(request)).isEqualTo("203.0.113.5");
    }

    @Test
    void resolvesTheLeftmostClientHopWhenOnlyTheGatewayIsTrusted() {
        MockHttpServletRequest request = requestFrom(GATEWAY);
        request.addHeader(ClientIpResolver.FORWARDED_FOR_HEADER, "203.0.113.5, " + GATEWAY);

        assertThat(resolver().resolve(request)).isEqualTo("203.0.113.5");
    }

    @Test
    void gatewayIpNoLongerCollapsesAllClientsIntoOneKey() {
        ClientIpResolver resolver = resolver();
        MockHttpServletRequest first = requestFrom(GATEWAY);
        first.addHeader(ClientIpResolver.FORWARDED_FOR_HEADER, "203.0.113.5, " + GATEWAY);
        MockHttpServletRequest second = requestFrom(GATEWAY);
        second.addHeader(ClientIpResolver.FORWARDED_FOR_HEADER, "198.51.100.7, " + GATEWAY);

        assertThat(resolver.resolve(first)).isNotEqualTo(resolver.resolve(second));
    }

    @Test
    void fallsBackToRemoteAddrWhenForwardedForIsAbsent() {
        assertThat(resolver().resolve(requestFrom(GATEWAY))).isEqualTo(GATEWAY);
    }
}
