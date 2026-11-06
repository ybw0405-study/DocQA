package com.docqa.common.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 装配顺序回归测试。
 *
 * <p>被回归的缺陷：{@code @ConditionalOnBean(MeterRegistry.class)} 的求值时机取决于自动配置的
 * 处理顺序。当本配置先于提供 {@code MeterRegistry} 的配置被处理时，条件不成立、整个 bean 被
 * 静默跳过，服务启动直接失败（S11-10 实测：document-service / reminder-service /
 * agent-gateway 三个模块 required a bean of type BusinessMetrics）。普通单测
 * {@code new BusinessMetrics(registry)} 永远发现不了这类问题，因为缺陷只在容器里成立。
 *
 * <p>为什么用 {@link AnnotationConfigApplicationContext} 手动注册而不是
 * {@code ApplicationContextRunner}：后者的 {@code withBean} 属于用户配置，**必然**早于任何
 * 自动配置被解析，顺序缺陷根本不会出现；手动注册才能把「先注册本配置、后注册 registry」
 * 这个真实顺序固定下来。
 */
class BusinessMetricsAutoConfigurationTest {

    /** 提供 registry 的配置，刻意在测试里后注册：模拟「MeterRegistry 定义晚于本配置」。 */
    @Configuration(proxyBeanMethods = false)
    static class RegistryConfiguration {

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    /** 业务方自己提供了一个门面：自动配置不得再注册第二个。 */
    @Configuration(proxyBeanMethods = false)
    static class CustomMetricsConfiguration {

        @Bean
        BusinessMetrics customMetrics(MeterRegistry registry) {
            return new BusinessMetrics(registry);
        }
    }

    @Test
    @DisplayName("registry 的定义晚于本配置时，BusinessMetrics 仍必须被创建")
    void beanIsCreatedEvenWhenRegistryIsDefinedLater() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            // 顺序即被测对象：本配置先注册，registry 后注册。
            context.register(BusinessMetricsAutoConfiguration.class);
            context.register(RegistryConfiguration.class);
            context.refresh();

            assertThat(context.getBeanNamesForType(BusinessMetrics.class)).hasSize(1);
        }
    }

    @Test
    @DisplayName("容器里已有业务门面时不得重复注册")
    void existingBeanIsNotOverridden() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(RegistryConfiguration.class);
            context.register(CustomMetricsConfiguration.class);
            context.register(BusinessMetricsAutoConfiguration.class);
            context.refresh();

            assertThat(context.getBeanNamesForType(BusinessMetrics.class)).hasSize(1);
        }
    }
}
