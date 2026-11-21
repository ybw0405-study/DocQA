package com.docqa.agentgateway.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.ChannelOption;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

@Configuration
public class WebClientConfiguration {

    private static final int MAX_IN_MEMORY_BYTES = 2 * 1024 * 1024;

    @Bean
    public WebClient pythonWebClient(
            WebClient.Builder builder,
            PythonProperties properties,
            @Value("${docqa.ai.internal-token:${DOCQA_AI_INTERNAL_TOKEN:}}") String internalToken) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.toIntExact(properties.getConnectTimeout().toMillis()))
                .responseTimeout(properties.getResponseTimeout());
        WebClient.Builder configured = builder
                .baseUrl(properties.getBaseUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY_BYTES));
        // 网关 → 数据面共享密钥：defaultHeader 覆盖同步 /agent/ask 与 SSE /agent/ask/stream
        // 全部请求；留空时保持本地 dev 与既有测试零改动（Python 侧同样不启用校验）。
        if (internalToken != null && !internalToken.isBlank()) {
            configured.defaultHeader("X-Internal-Token", internalToken);
        }
        return configured.build();
    }

    @Bean
    public CircuitBreakerRegistry pythonCircuitBreakerRegistry(PythonProperties properties) {
        PythonProperties.CircuitBreaker config = properties.getCircuitBreaker();
        CircuitBreakerConfig circuitBreakerConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(config.getFailureRateThreshold())
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(config.getSlidingWindowSize())
                .minimumNumberOfCalls(config.getMinimumNumberOfCalls())
                .waitDurationInOpenState(config.getWaitDurationInOpenState())
                .permittedNumberOfCallsInHalfOpenState(config.getPermittedNumberOfCallsInHalfOpenState())
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build();
        return CircuitBreakerRegistry.of(circuitBreakerConfig);
    }

    /**
     * 把上面手工创建的 {@link CircuitBreakerRegistry} 显式绑定到 Micrometer（S11-3-M3）。
     *
     * <p>不能只靠自动配置：resilience4j 2.1.0 的 {@code CircuitBreakerMetricsAutoConfiguration}
     * 里，唯一会调用 {@code bindTo(meterRegistry)} 的 {@code registerCircuitBreakerMetrics}
     * 挂着 {@code @ConditionalOnProperty(resilience4j.circuitbreaker.metrics.legacy.enabled=true)}，
     * 默认不满足；默认路径只创建 {@code TaggedCircuitBreakerMetricsPublisher} bean，
     * 而它不会替我们手工 {@code of()} 出来的 registry 绑定实例。
     *
     * <p>后果是「熔断功能正常、{@code resilience4j_*} 序列长期 0 条」——不报错但不干活，
     * 任何基于该指标族的告警面板都是空的。这里显式绑定，等于把这条链补上。
     * 绑定时刻 registry 里可能还没有熔断器实例，{@code TaggedCircuitBreakerMetrics}
     * 会监听 {@code onEntryAdded}，后续由 {@code WebClientPythonAgentClient} 创建的实例照样被采集。
     */
    @Bean
    public TaggedCircuitBreakerMetrics circuitBreakerMetrics(
            CircuitBreakerRegistry circuitBreakerRegistry, MeterRegistry meterRegistry) {
        TaggedCircuitBreakerMetrics metrics = TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(circuitBreakerRegistry);
        metrics.bindTo(meterRegistry);
        return metrics;
    }

    /** SSE 长连接的 keep-alive 注释行调度器（ask:stream 每 5s 一条 : keep-alive 注释帧）。 */
    @Bean
    public ThreadPoolTaskScheduler sseKeepAliveScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("sse-keepalive-");
        scheduler.setDaemon(true);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.initialize();
        return scheduler;
    }
}
