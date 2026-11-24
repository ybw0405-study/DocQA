package com.docqa.common.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * 业务指标门面的装配点。
 *
 * <p>为什么用 {@link ObjectProvider} 而不是 {@code @ConditionalOnBean(MeterRegistry.class)}：
 * {@code @ConditionalOnBean} 的求值时机取决于自动配置的**处理顺序**，而 {@code MeterRegistry}
 * 本身也是由 {@code MetricsAutoConfiguration} 提供的自动配置 bean。两者没有显式顺序约束时，
 * 本类可能先被求值、看不见 registry，于是整个 bean 被静默跳过——服务启动时表现为
 * 「required a bean of type BusinessMetrics that could not be found」，而单测直接
 * {@code new BusinessMetrics(registry)} 永远发现不了。 的真实启动复现了这一点
 * （document-service / reminder-service / agent-gateway 三个模块启动即失败）。
 *
 * <p>{@link ObjectProvider#getIfAvailable()} 在**实例化**阶段才解析依赖，那时所有 bean
 * 定义都已注册完毕，因此不存在顺序竞争。
 *
 * <p>双条件激活的初衷不变：{@link BusinessMetrics} 编译期只依赖 micrometer-core 的 API，
 * 但 {@code audit-service} 是无 Web 端口的纯消息消费者，POM 里既没有 actuator 也没有
 * micrometer-registry。若不加 {@code @ConditionalOnClass}，该服务的 ApplicationContext 会
 * 因为找不到 {@link MeterRegistry} 而启动失败——它的指标（审计只追加）不在 §4.2 的
 * 23 项里，本就不该被要求背一个 registry。
 *
 * <p>回退到 {@link SimpleMeterRegistry} 而非返回 null：有 micrometer-core 但没有 registry
 * 的服务（例如只引了 common 的测试切片）仍能拿到一个可用但无人导出的门面，业务代码不必
 * 到处写判空。序列不会被 scrape 到，这一点由「没有 registry 就没有 /actuator/prometheus」
 * 的事实自然保证。
 */
@AutoConfiguration
@ConditionalOnClass(MeterRegistry.class)
public class BusinessMetricsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public BusinessMetrics businessMetrics(ObjectProvider<MeterRegistry> registries) {
        MeterRegistry registry = registries.getIfAvailable();
        if (registry == null) {
            registry = new SimpleMeterRegistry();
        }
        return new BusinessMetrics(registry);
    }
}
