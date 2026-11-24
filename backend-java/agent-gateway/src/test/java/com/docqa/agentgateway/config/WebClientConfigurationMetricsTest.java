package com.docqa.agentgateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * -M3 的回归网：熔断指标必须真的被绑进 {@link MeterRegistry}。
 *
 * <p>这条用例是为了防「不报错但不干活」——缺陷形态就是熔断功能完全正常、
 * 单测和接口全绿，唯独 {@code resilience4j_*} 序列长期 0 条，导致任何基于
 * 该指标族的告警面板都是空的。用 {@code SimpleMeterRegistry} 直接查 meter 名，
 * 绕开 Prometheus 命名转换，判据落到「meter 是否存在」而不是「字符串是否出现」。
 */
class WebClientConfigurationMetricsTest {

    /** 只装配被测配置，不启动完整应用：这里要验的是绑定关系，不是整条启动链。 */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(WebClientConfiguration.class)
            .withBean(WebClient.Builder.class, WebClient::builder)
            .withBean(PythonProperties.class, PythonProperties::new)
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new);

    @Test
    @DisplayName("熔断状态必须导出到 MeterRegistry（否则告警静默失效）")
    void circuitBreakerStateIsBoundToMeterRegistry() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();

            CircuitBreakerRegistry registry = context.getBean(CircuitBreakerRegistry.class);
            MeterRegistry meterRegistry = context.getBean(MeterRegistry.class);

            // 模拟 WebClientPythonAgentClient 构造器的行为：熔断器实例晚于 registry 绑定才出现。
            // 绑定发生在 bean 创建期，靠 onEntryAdded 捕获后续实例，这里正是要锁住这条路径。
            registry.circuitBreaker("pythonAgent");
            registry.circuitBreaker("pythonAgentStream");

            assertThat(meterNames(meterRegistry))
                    .as("熔断状态 Gauge 必须存在，否则 resilience4j_* 指标族全空")
                    .contains("resilience4j.circuitbreaker.state");
            assertThat(meterNames(meterRegistry))
                    .as("熔断状态 Gauge 必须带 name 标签区分两个熔断器")
                    .contains("resilience4j.circuitbreaker.state");
            assertThat(meterRegistry.getMeters())
                    .as("两个熔断器实例都应被采集")
                    .anyMatch(meter -> "pythonAgent".equals(meter.getId().getTag("name")))
                    .anyMatch(meter -> "pythonAgentStream".equals(meter.getId().getTag("name")));
        });
    }

    @Test
    @DisplayName("熔断调用计数与失败率也必须导出，不能只有 state")
    void circuitBreakerCallMetricsAreBound() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();

            CircuitBreakerRegistry registry = context.getBean(CircuitBreakerRegistry.class);
            MeterRegistry meterRegistry = context.getBean(MeterRegistry.class);
            registry.circuitBreaker("pythonAgent");

            // state 只能回答「现在开着还是关着」，回答不了「为什么开」；
            // 失败率与调用数是熔断调参和根因排查的必需品。
            assertThat(meterNames(meterRegistry))
                    .contains("resilience4j.circuitbreaker.calls")
                    .contains("resilience4j.circuitbreaker.failure.rate")
                    .contains("resilience4j.circuitbreaker.buffered.calls");
        });
    }

    private static java.util.Set<String> meterNames(MeterRegistry meterRegistry) {
        return meterRegistry.getMeters().stream()
                .map(meter -> meter.getId().getName())
                .collect(java.util.stream.Collectors.toSet());
    }
}
