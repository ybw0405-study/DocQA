package com.docqa.document.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointProperties;
import org.springframework.boot.actuate.autoconfigure.health.HealthEndpointProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.ClassUtils;

/**
 * actuator 暴露面与健康分组是运维契约。键名写错时 Spring 不会报错，只会静默按默认值启动，
 * 所以这里把 application.yml 绑定到 Spring 自己的属性模型再断言，而不是断言文本。
 */
class ActuatorManagementConfigTest {

    private static final StandardEnvironment ENVIRONMENT = loadEnvironment();

    @Test
    void exposesOnlyOperationalEndpoints() {
        WebEndpointProperties endpoints = Binder.get(ENVIRONMENT)
                .bind("management.endpoints.web", Bindable.of(WebEndpointProperties.class))
                .orElseThrow(() -> new AssertionError("application.yml 缺少 management.endpoints.web"));
        assertThat(endpoints.getExposure().getInclude())
                .as("只暴露 health / info / prometheus")
                .containsExactlyInAnyOrder("health", "info", "prometheus");
        assertThat(endpoints.getExposure().getExclude()).isEmpty();
    }

    @Test
    void enablesAvailabilityProbes() {
        assertThat(Binder.get(ENVIRONMENT)
                        .bind("management.endpoint.health.probes.enabled", Bindable.of(Boolean.class))
                        .orElse(false))
                .as("liveness / readiness 探针必须由 probes.enabled 打开")
                .isTrue();
    }

    @Test
    void livenessAndReadinessGroupsMatchDesign() {
        HealthEndpointProperties health = Binder.get(ENVIRONMENT)
                .bind("management.endpoint.health", Bindable.of(HealthEndpointProperties.class))
                .orElseThrow(() -> new AssertionError("application.yml 缺少 management.endpoint.health"));
        assertThat(health.getGroup().get("liveness").getInclude())
                .as("liveness 只看进程自身，不因下游抖动被杀")
                .containsExactlyInAnyOrder("livenessState", "ping");
        assertThat(health.getGroup().get("readiness").getInclude())
                .as("readiness 纳入 db / redis，下游不可用时如实摘流")
                .containsExactlyInAnyOrder("readinessState", "db", "redis");
    }

    @Test
    void keepsManagementEndpointsOnTheServicePort() {
        assertThat(ENVIRONMENT.getProperty("management.server.port"))
                .as("Prometheus 直接抓各服务端口；另起管理端口需要同步改抓取与编排配置")
                .isNull();
        assertThat(ENVIRONMENT.getProperty("management.endpoints.web.base-path"))
                .as("保持默认 /actuator 基路径")
                .isNull();
    }

    @Test
    void shipsPrometheusRegistryOnTheClasspath() {
        assertThat(ClassUtils.isPresent(
                        "io.micrometer.prometheusmetrics.PrometheusMeterRegistry", getClass().getClassLoader()))
                .as("缺少 micrometer-registry-prometheus 时 /actuator/prometheus 会静默变成 404")
                .isTrue();
    }

    private static StandardEnvironment loadEnvironment() {
        MutablePropertySources sources = new MutablePropertySources();
        try {
            for (PropertySource<?> source :
                    new YamlPropertySourceLoader().load("application.yml", new ClassPathResource("application.yml"))) {
                sources.addLast(source);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("无法加载 application.yml", exception);
        }
        StandardEnvironment environment = new StandardEnvironment();
        sources.forEach(source -> environment.getPropertySources().addFirst(source));
        return environment;
    }
}
