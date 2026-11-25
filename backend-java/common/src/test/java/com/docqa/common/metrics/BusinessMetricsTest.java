package com.docqa.common.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 指标契约测试。
 *
 * <p>判据一律取自 {@code registry.scrape()} 的**导出文本**，而不是 registry 里的 meter 名：
 * §4.2 与 §4.3 的告警表达式引用的是 Prometheus 侧的序列名，中间隔着 Micrometer 的命名
 * 规范化（Timer 补 {@code _seconds}、Counter 补 {@code _total}）。只断言 meter 名会漏掉
 * 「{@code _seconds_seconds}」这类真实可踩的坑。
 */
class BusinessMetricsTest {

    private PrometheusMeterRegistry registry;
    private BusinessMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        metrics = new BusinessMetrics(registry);
    }

    @Test
    @DisplayName("qa_requests_total 的序列名、标签与文档逐字一致，且不出现双 _total")
    void qaRequestExportsDocumentedSequence() {
        metrics.qaRequest("SUCCESS", "OK", true);

        String scrape = registry.scrape();
        assertThat(scrape).contains("docqa_qa_requests_total{");
        assertThat(scrape).contains("budget_status=\"OK\"");
        assertThat(scrape).contains("status=\"SUCCESS\"");
        assertThat(scrape).contains("cache_hit=\"true\"");
        assertThat(scrape).doesNotContain("docqa_qa_requests_total_total");
    }

    @Test
    @DisplayName("qa_latency 只被追加一次 _seconds，并带 SLO 分桶")
    void qaLatencyAppendsSecondsSuffixExactlyOnce() {
        metrics.qaLatency(Duration.ofMillis(1500), "SUCCESS", false);

        String scrape = registry.scrape();
        assertThat(scrape).contains("docqa_qa_latency_seconds_count");
        assertThat(scrape).contains("docqa_qa_latency_seconds_bucket");
        assertThat(scrape).doesNotContain("docqa_qa_latency_seconds_seconds");
    }

    @Test
    @DisplayName("缓存命中率随 hit/miss 变化，分母为 0 时记 0 而不是 NaN")
    void cacheHitRatioTracksLookups() {
        assertThat(registry.get("docqa_qa_cache_hit_ratio").gauge().value()).isZero();

        metrics.qaCacheLookup(true);
        metrics.qaCacheLookup(true);
        metrics.qaCacheLookup(true);
        metrics.qaCacheLookup(false);

        assertThat(registry.get("docqa_qa_cache_hit_ratio").gauge().value()).isEqualTo(0.75);
        assertThat(registry.get("docqa_qa_cache_requests_total").tag("result", "hit").counter().count())
                .isEqualTo(3.0);
        assertThat(registry.get("docqa_qa_cache_requests_total").tag("result", "miss").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("空标签折叠成 NULL，避免同一指标裂成「无标签」与「空标签」两条序列")
    void blankTagsCollapseToNull() {
        metrics.qaRequest(null, " ", false);

        String scrape = registry.scrape();
        assertThat(scrape).contains("status=\"NULL\"");
        assertThat(scrape).contains("budget_status=\"NULL\"");
    }

    @Test
    @DisplayName("outbox 两个 Gauge 反映待投递行数与最老年龄，清空后归零")
    void outboxGaugesTrackBacklog() {
        metrics.auditOutboxBacklog(7, Duration.ofSeconds(42));
        assertThat(registry.get("docqa_audit_outbox_pending").gauge().value()).isEqualTo(7.0);
        assertThat(registry.get("docqa_audit_outbox_oldest_age_seconds").gauge().value()).isEqualTo(42.0);

        metrics.auditOutboxBacklog(0, null);
        assertThat(registry.get("docqa_audit_outbox_pending").gauge().value()).isZero();
        assertThat(registry.get("docqa_audit_outbox_oldest_age_seconds").gauge().value()).isZero();
    }

    @Test
    @DisplayName("熔断状态按 name 暴露三条 0/1 序列，当前状态为 1")
    void circuitBreakerStateExposesOnePerState() {
        metrics.circuitBreakerState("pythonAgent", "OPEN");

        assertThat(breakerValue("pythonAgent", "OPEN")).isEqualTo(1.0);
        assertThat(breakerValue("pythonAgent", "CLOSED")).isZero();
        assertThat(breakerValue("pythonAgent", "HALF_OPEN")).isZero();
    }

    @Test
    @DisplayName("其余九个 Java 侧序列名与 §4.2 逐字一致")
    void remainingSequencesMatchDocumentedNames() {
        metrics.pythonRequest("SUCCESS", "OK");
        metrics.pythonRequest("FAILED", "AGT-UPSTREAM-001");
        metrics.pythonTimeout("/agent/ask");
        metrics.mqDlq("parse-failed", "DOC-PARSE-001");
        metrics.documentParse(Duration.ofMillis(3200), "INDEXED");
        metrics.documentParseFailure("DOC-PARSE-002", true);
        metrics.reminderDuePublished("PUBLISHED");

        String scrape = registry.scrape();
        assertThat(scrape).contains("docqa_python_requests_total{");
        assertThat(scrape).contains("docqa_python_request_timeout_total{");
        assertThat(scrape).contains("docqa_mq_dlq_total{");
        assertThat(scrape).contains("docqa_document_parse_duration_seconds_count");
        assertThat(scrape).contains("docqa_document_parse_failures_total{");
        assertThat(scrape).contains("docqa_reminder_due_published_total{");
        assertThat(scrape).doesNotContain("docqa_document_parse_duration_seconds_seconds");
    }

    @Test
    @DisplayName("负耗时被夹到 0，不让时钟回拨把 percentile histogram 污染成负数")
    void negativeDurationIsClamped() {
        metrics.qaLatency(Duration.ofMillis(-5), "FAILED", false);

        // 注意这里的取值名是 docqa_qa_latency：_seconds 是 Prometheus 导出期补的后缀，
        // registry 里的 meter 名没有它。同一个坑恰好被这条用例自己暴露过一次。
        assertThat(registry.get("docqa_qa_latency").tag("outcome", "FAILED").timer().count())
                .isEqualTo(1L);
        assertThat(registry.get("docqa_qa_latency")
                        .tag("outcome", "FAILED")
                        .timer()
                        .totalTime(java.util.concurrent.TimeUnit.NANOSECONDS))
                .isZero();
    }

    private double breakerValue(String name, String state) {
        return registry.get("docqa_circuit_breaker_state")
                .tag("name", name)
                .tag("state", state)
                .gauge()
                .value();
    }
}
