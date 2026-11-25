package com.docqa.document.config;

import com.docqa.mq.RocketMqAdminOffsetReader;
import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 把 MQ / MinIO 两个真实依赖注册成 HealthIndicator，交由 readiness 组引用。
 *
 * <p>bean 名以 {@code HealthIndicator} 结尾，Spring Boot 的 HealthContributorRegistry 会去掉
 * 该后缀得到 contributor 名 {@code mq} / {@code minio}，与 application.yml readiness 组的
 * include 对齐。liveness 组刻意不含两者：下游抖动只摘流、不杀进程，与 Python 侧同口径。
 *
 * <p>依赖未启用时 indicator 仍然存在（否则 readiness 组引用了不存在的 contributor 会被静默
 * 忽略，等于门禁消失），但 {@code health()} 直接返回 UP + {@code status=disabled}，且不建连。
 */
@Configuration(proxyBeanMethods = false)
public class DependencyHealthConfiguration {

    @Bean(name = "mqHealthIndicator", destroyMethod = "close")
    public DependencyHealthIndicator mqHealthIndicator(RocketMqProperties properties) {
        boolean enabled = properties.isEnabled();
        // 懒启动：构造 reader 不建连，首次 health() 调用才做 nameserver RPC（见 reader 注释）。
        RocketMqAdminOffsetReader reader = enabled
                ? new RocketMqAdminOffsetReader(properties.getNamesrv())
                : null;
        return new DependencyHealthIndicator(
                "mq",
                enabled,
                DependencyHealthIndicator.DEFAULT_TIMEOUT,
                () -> {
                    if (reader == null) {
                        throw new IllegalStateException("MQ health probe is not configured");
                    }
                    // examineConsumeStats 成功即说明 nameserver / broker 可达且消费组在线。
                    reader.lagByTopic(properties.getConsumerGroup());
                },
                reader == null ? null : reader::close);
    }

    @Bean(name = "minioHealthIndicator", destroyMethod = "close")
    public DependencyHealthIndicator minioHealthIndicator(
            MinioProperties properties, ObjectProvider<MinioClient> minioClient) {
        boolean enabled = properties.isEnabled();
        return new DependencyHealthIndicator(
                "minio",
                enabled,
                DependencyHealthIndicator.DEFAULT_TIMEOUT,
                () -> {
                    MinioClient client = minioClient.getIfAvailable();
                    if (client == null) {
                        throw new IllegalStateException("MinIO client is not configured");
                    }
                    if (!client.bucketExists(
                            BucketExistsArgs.builder().bucket(properties.getBucket()).build())) {
                        throw new IllegalStateException("configured bucket is missing");
                    }
                },
                null);
    }
}
