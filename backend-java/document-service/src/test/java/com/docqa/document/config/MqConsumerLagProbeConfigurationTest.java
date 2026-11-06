package com.docqa.document.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.docqa.common.metrics.BusinessMetrics;
import com.docqa.common.metrics.MqConsumerLagProbe;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 探针的装配条件。
 *
 * <p>只断言 bean 的有无，不去断言指标值——装配测试的职责是「开关是否生效」。真值由
 * {@code MqConsumerLagProbeTest}（假 reader）与 {@code RocketMqAdminOffsetReaderIT}（真 broker）覆盖。
 *
 * <p>关掉 MQ 时必须**不**创建探针：否则没有消费组的部署会周期性打 nameserver 并刷失败日志，
 * 而运维看到的是「服务没开 MQ 却报了 MQ 故障」。
 */
class MqConsumerLagProbeConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(MqConsumerLagProbeConfiguration.class)
            .withBean(RocketMqProperties.class, () -> {
                RocketMqProperties properties = new RocketMqProperties();
                properties.setNamesrv("127.0.0.1:9876");
                properties.setProducerGroup("document-service-producer");
                properties.setConsumerGroup("document-service-parse-result");
                properties.setParseRequestedTopic("parse-requested");
                properties.setParseCompletedTopic("parse-completed");
                properties.setParseFailedTopic("parse-failed");
                properties.setVectorChangeRequestedTopic("vector-change-requested");
                properties.setVectorChangeCompletedTopic("vector-change-completed");
                properties.setVectorResultConsumerGroup("document-vector-result");
                return properties;
            })
            .withBean(BusinessMetrics.class, () -> new BusinessMetrics(new SimpleMeterRegistry()));

    @Test
    @DisplayName("docqa.rocketmq.enabled=true 时装配探针")
    void createsProbeWhenMqEnabled() {
        runner.withPropertyValues("docqa.rocketmq.enabled=true", "docqa.rocketmq.lag-probe-interval-ms=600000")
                .run(context -> assertThat(context).hasSingleBean(MqConsumerLagProbe.class));
    }

    @Test
    @DisplayName("docqa.rocketmq.enabled=false 时不装配，避免无 MQ 的部署刷失败日志")
    void skipsProbeWhenMqDisabled() {
        runner.withPropertyValues("docqa.rocketmq.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(MqConsumerLagProbe.class));
    }
}
