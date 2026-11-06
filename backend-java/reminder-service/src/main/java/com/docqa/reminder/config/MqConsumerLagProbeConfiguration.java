package com.docqa.reminder.config;

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
 * 提醒服务的消费者 lag 探针。
 *
 * <p>消费组 {@code reminder-due} 积压的直接后果是「到点了但通知没发」——用户侧完全无感的那种
 * 故障，只能靠这个指标发现。探针自带单线程调度器，不复用 {@code @EnableScheduling} 的默认池
 * （该池同时被到点扫描占用，见 {@code MqConsumerLagProbe} 类注释）。
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
                "reminder-service",
                new RocketMqAdminOffsetReader(properties.getNamesrv()),
                metrics,
                List.of(properties.getConsumerGroup()));
        probe.start(Duration.ofMillis(intervalMs));
        return probe;
    }
}
