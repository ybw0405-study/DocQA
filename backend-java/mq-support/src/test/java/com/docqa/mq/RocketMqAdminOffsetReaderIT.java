package com.docqa.mq;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * 真实 broker 上的采集冒烟：**默认跳过**，只有显式打开才跑。
 *
 * <p>为什么不做成普通单测：它要求本机有可用的 nameserver 与一个真实消费组，CI 里没有；
 * 让它常驻会被「跳过」的绿灯掩盖真实问题，也会在无 broker 的机器上制造 5 秒超时等待。
 *
 * <p>打开方式：
 *
 * <pre>{@code
 * DOCQA_MQ_IT=true DOCQA_ROCKETMQ_NAMESRV=127.0.0.1:9876 \
 *   mvn -pl mq-support test -Dtest=RocketMqAdminOffsetReaderIT
 * }</pre>
 *
 * <p>判据刻意宽松：只要求「连得上、能解析、lag 非负」。lag 的具体数值取决于本机积压，
 * 断言具体数字等于把测试绑在环境状态上。
 */
@EnabledIfEnvironmentVariable(named = "DOCQA_MQ_IT", matches = "true")
class RocketMqAdminOffsetReaderIT {

    private static String namesrv() {
        String value = System.getenv("DOCQA_ROCKETMQ_NAMESRV");
        return value == null || value.isBlank() ? "127.0.0.1:9876" : value;
    }

    private static String consumerGroup() {
        String value = System.getenv("DOCQA_MQ_IT_GROUP");
        return value == null || value.isBlank() ? "document-service-parse-result" : value;
    }

    @Test
    @DisplayName("真实 nameserver：能读到消费组位点，lag 全部非负")
    void readsRealConsumerOffsets() throws Exception {
        try (RocketMqAdminOffsetReader reader = new RocketMqAdminOffsetReader(namesrv())) {
            Map<String, Long> lags = reader.lagByTopic(consumerGroup());

            System.out.printf("namesrv=%s group=%s lags=%s%n", namesrv(), consumerGroup(), lags);
            assertThat(lags).allSatisfy((topic, lag) -> assertThat(lag).isGreaterThanOrEqualTo(0L));
        }
    }
}
