package com.docqa.common.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * lag 探针的行为契约。
 *
 * <p>断言分两层：**看得见的部分**用 {@code registry.scrape()} 的导出文本（名字、标签），
 * **容易写错的部分**用假 reader 驱动真实分支——尤其是「采集失败时 lag 保留旧值」这条，
 * 一旦被写成「失败就清零」，Prometheus 上看到的就是「积压突然消失」，比没有指标更危险。
 */
class MqConsumerLagProbeTest {

    private PrometheusMeterRegistry registry;
    private BusinessMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        metrics = new BusinessMetrics(registry);
    }

    @Test
    @DisplayName("采集成功：lag 按 topic/consumer_group 导出，探针健康度为 1")
    void exportsLagAndProbeSuccess() {
        FakeReader reader = new FakeReader();
        reader.answers.put("document-parse-result", Map.of("parse-completed", 12L, "parse-failed", 3L));
        MqConsumerLagProbe probe = new MqConsumerLagProbe("document", reader, metrics, List.of("document-parse-result"));

        probe.poll();

        String scrape = registry.scrape();
        assertThat(scrape).contains("docqa_mq_consumer_lag_messages{");
        assertThat(scrape).contains("topic=\"parse-completed\"");
        assertThat(scrape).contains("topic=\"parse-failed\"");
        assertThat(scrape).contains("consumer_group=\"document-parse-result\"");
        assertThat(scrape).contains("docqa_mq_consumer_lag_probe_success{");
        assertThat(valueOfGauge("docqa_mq_consumer_lag_messages", "topic", "parse-completed")).isEqualTo(12.0);
        assertThat(valueOfGauge("docqa_mq_consumer_lag_probe_success", "consumer_group", "document-parse-result"))
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("采集失败：lag 保留上一次读数，探针健康度打 0")
    void keepsLastReadingWhenProbeFails() {
        FakeReader reader = new FakeReader();
        reader.answers.put("document-parse-result", Map.of("parse-completed", 42L));
        MqConsumerLagProbe probe = new MqConsumerLagProbe("document", reader, metrics, List.of("document-parse-result"));
        probe.poll();

        reader.failing.add("document-parse-result");
        probe.poll();

        // 关键：不是 0，而是上一轮的 42；否则「broker 不可达」会被误读成「积压清空」。
        assertThat(valueOfGauge("docqa_mq_consumer_lag_messages", "topic", "parse-completed")).isEqualTo(42.0);
        assertThat(valueOfGauge("docqa_mq_consumer_lag_probe_success", "consumer_group", "document-parse-result"))
                .isEqualTo(0.0);
    }

    @Test
    @DisplayName("一个消费组失败不污染另一个消费组的读数与健康度")
    void isolatesFailuresPerConsumerGroup() {
        FakeReader reader = new FakeReader();
        reader.answers.put("document-parse-result", Map.of("parse-completed", 5L));
        reader.answers.put("document-vector-result", Map.of("vector-change-completed", 0L));
        reader.failing.add("document-parse-result");
        MqConsumerLagProbe probe = new MqConsumerLagProbe(
                "document", reader, metrics, List.of("document-parse-result", "document-vector-result"));

        probe.poll();

        assertThat(valueOfGauge("docqa_mq_consumer_lag_probe_success", "consumer_group", "document-parse-result"))
                .isEqualTo(0.0);
        assertThat(valueOfGauge("docqa_mq_consumer_lag_probe_success", "consumer_group", "document-vector-result"))
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("负 lag 被夹到 0，不产出负值序列")
    void clampsNegativeLag() {
        FakeReader reader = new FakeReader();
        reader.answers.put("group", Map.of("topic", -7L));
        MqConsumerLagProbe probe = new MqConsumerLagProbe("svc", reader, metrics, List.of("group"));

        probe.poll();

        assertThat(valueOfGauge("docqa_mq_consumer_lag_messages", "topic", "topic")).isEqualTo(0.0);
    }

    @Test
    @DisplayName("close 释放 reader，重复调用不抛异常")
    void closesReaderOnce() {
        FakeReader reader = new FakeReader();
        MqConsumerLagProbe probe = new MqConsumerLagProbe("svc", reader, metrics, List.of("group"));
        probe.start(Duration.ofMillis(5_000));

        probe.close();
        probe.close();

        assertThat(reader.closeCount).isEqualTo(2);
    }

    private double valueOfGauge(String name, String tagKey, String tagValue) {
        return registry.get(name).tag(tagKey, tagValue).gauge().value();
    }

    private static final class FakeReader implements ConsumerOffsetReader {

        private final Map<String, Map<String, Long>> answers = new HashMap<>();

        private final Set<String> failing = new HashSet<>();

        private int closeCount;

        @Override
        public Map<String, Long> lagByTopic(String consumerGroup) throws Exception {
            if (failing.contains(consumerGroup)) {
                throw new IllegalStateException("broker unreachable");
            }
            return answers.getOrDefault(consumerGroup, Map.of());
        }

        @Override
        public void close() {
            closeCount++;
        }
    }
}
