package com.docqa.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.docqa.common.web.GlobalExceptionHandler;
import com.docqa.common.web.JsonCharsetFilter;
import com.docqa.common.web.TraceIdFilter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

class CommonWebAutoConfigurationTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CommonWebAutoConfiguration.class));

    @Test
    void registersCommonWebBeans() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(TraceIdFilter.class);
            assertThat(context).hasSingleBean(GlobalExceptionHandler.class);
            assertThat(context).hasSingleBean(JsonCharsetFilter.class);
        });
    }
}
