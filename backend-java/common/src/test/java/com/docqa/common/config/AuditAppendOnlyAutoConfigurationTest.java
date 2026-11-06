package com.docqa.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.docqa.common.db.AuditAppendOnlyGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

class AuditAppendOnlyAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AuditAppendOnlyAutoConfiguration.class))
            .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class));

    @Test
    @DisplayName("没有配置项时完全不装配：避免无关服务每次启动刷三条与自己无关的告警")
    void disabledWithoutProperty() {
        runner.run(context -> assertThat(context).doesNotHaveBean(AuditAppendOnlyGuard.class));
    }

    @Test
    @DisplayName("配了模式才装配，且把模式读进来")
    void enabledByProperty() {
        runner.withPropertyValues("docqa.audit.append-only-guard=enforce")
                .run(context -> {
                    assertThat(context).hasSingleBean(AuditAppendOnlyGuard.class);
                    assertThat(context.getBean(AuditAppendOnlyGuard.class).mode())
                            .isEqualTo(AuditAppendOnlyGuard.Mode.ENFORCE);
                });
    }

    @Test
    @DisplayName("模式拼错时上下文启动失败，而不是悄悄退回 warn")
    void invalidModeFailsContextStartup() {
        runner.withPropertyValues("docqa.audit.append-only-guard=enfroce")
                .run(context -> assertThat(context).hasFailed());
    }
}
