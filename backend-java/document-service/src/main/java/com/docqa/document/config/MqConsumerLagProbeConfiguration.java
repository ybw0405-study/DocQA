package com.docqa.document.config;

import com.docqa.common.metrics.BusinessMetrics;
import com.docqa.common.metrics.MqConsumerLagProbe;
import com.docqa.mq.RocketMqAdminOffsetReader;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 文档服务的消费者 lag 探针。
 *
 * <p>两个消费组都要探：{@code parse-result} 堵了表现为「文档一直解析中」，{@code vector-result} 堵了
 * 表现为「新文档搜不到」。两者是不同的用户可见症状，合成一个指标会丢掉区分度。
 *
 * <p>{@code docqa.rocketmq.enabled=false} 时不装配——没有 MQ 就没有 lag，注册一个永远
 * 采集失败的探针只会制造噪声告警。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "docqa.rocketmq.enabled", havingValue = "true", matchIfMissing = true)
public class MqConsumerLagProbeConfiguration {

    @Bean(destroyMethod = "close")
    public MqConsumerLagProbe mqConsumerLagProbe(
            RocketMqProperties properties,
            BusinessMetrics metrics,
            @Value("${docqa.rocketmq.lag-probe-interval-ms:30000}") long intervalMs) {
        MqConsumerLagProbe probe = new MqConsumerLagProbe(
                "document-service",
                new RocketMqAdminOffsetReader(properties.getNamesrv()),
                metrics,
                List.of(properties.getConsumerGroup(), properties.getVectorResultConsumerGroup()));
        probe.start(Duration.ofMillis(intervalMs));
        return probe;
    }
}
