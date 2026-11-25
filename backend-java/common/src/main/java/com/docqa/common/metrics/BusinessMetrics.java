package com.docqa.common.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 业务指标的唯一注册点。
 *
 * <p>为什么要有这层门面：指标名、标签名与基数约束是**跨服务契约**。若让每个服务直接
 * 调 {@code Counter.builder(...)}，同一个序列在不同服务里很容易长出不同标签集，Prometheus 侧
 * 表现为同一个指标名下的多条互不相交的时间序列，{@code sum by (status)} 之类的表达式会静默
 * 少算。所有命名与标签拼装因此收在这一处，调用方只表达语义。
 *
 * <p><b>命名约定（Micrometer → Prometheus 的两个后缀规则）</b>，这是本类最容易写错的地方：
 *
 * <ul>
 * <li>Counter 若名字不以 {@code _total} 结尾，导出时会被追加上 {@code _total}；本类统一写成
 * 文档里的完整名（带 {@code _total}），既不会再被追加，也与 §4.3 的告警表达式逐字一致。
 * <li>Timer 的导出名 = Micrometer 名 + baseTimeUnit 后缀（{@code _seconds}）。因此 Timer 的
 * Micrometer 名必须**不带** {@code _seconds}，否则真实序列会变成 {@code _seconds_seconds}。
 * </ul>
 *
 * <p>Gauge 与 DistributionSummary 不做后缀改写，写文档名即可。
 *
 * <p>基数约束：所有标签值都必须是**有界枚举**（状态码、错误码、topic、模型名）。调用方不得把
 * 用户输入、traceId、异常消息传进来——§4.1 明确禁止，且漏一个就会让序列数随流量发散。
 * {@code null} 与空串统一折叠成 {@code NULL}，避免出现「标签缺失」和「标签为空」两种序列。
 */
public final class BusinessMetrics {

    /** §4.1 规定的统一前缀。 */
    public static final String PREFIX = "docqa_";

    /** 熔断器的三个稳定状态；Gauge 按「当前状态 = 该值则 1，否则 0」暴露。 */
    private static final String[] BREAKER_STATES = {"CLOSED", "OPEN", "HALF_OPEN"};

    private static final String NULL_TAG = "NULL";

    private final MeterRegistry registry;

    private final AtomicInteger cacheHits = new AtomicInteger();
    private final AtomicInteger cacheMisses = new AtomicInteger();
    private final AtomicInteger outboxPending = new AtomicInteger();
    private final AtomicLong outboxOldestAgeSeconds = new AtomicLong();

    /** key = {@code breakerName + '|' + state}；每条时间序列一个可变载体。 */
    private final Map<String, AtomicInteger> breakerStates = new ConcurrentHashMap<>();

    /** key = {@code topic + '|' + consumerGroup}；lag 是 Gauge，载体必须与指标同寿命。 */
    private final Map<String, AtomicLong> consumerLags = new ConcurrentHashMap<>();

    /** key = {@code consumerGroup}；探针健康度，采集失败时保留旧 lag 靠它区分真伪。 */
    private final Map<String, AtomicInteger> lagProbeSuccess = new ConcurrentHashMap<>();

    public BusinessMetrics(MeterRegistry registry) {
        this.registry = registry;
        // 这两个 Gauge 必须绑定到「本对象持有的可变载体」而不是局部变量：Gauge 只持弱引用，
        // 局部对象被回收后读数会静默变成 NaN，是 Micrometer 最常见的静默失效形态。
        Gauge.builder(PREFIX + "qa_cache_hit_ratio", this, BusinessMetrics::cacheHitRatio)
                .description("问答缓存命中率 hit/(hit+miss)；分母为 0 时记 0")
                .register(registry);
        Gauge.builder(PREFIX + "audit_outbox_pending", outboxPending, AtomicInteger::get)
                .description("agent_execution.audit_outbox 待投递执行数")
                .register(registry);
        Gauge.builder(PREFIX + "audit_outbox_oldest_age_seconds", outboxOldestAgeSeconds, AtomicLong::get)
                .description("最老待投递 outbox 的年龄（秒）")
                .register(registry);
    }

    // ---------------------------------------------------------------- 问答

    /** {@code docqa_qa_requests_total}。{@code status} ∈ SUCCESS/FAILED/BUDGET_EXHAUSTED。 */
    public void qaRequest(String status, String budgetStatus, boolean cacheHit) {
        Counter.builder(PREFIX + "qa_requests_total")
                .description("问答请求总数，按终态计数")
                .tag("status", tag(status))
                .tag("budget_status", tag(budgetStatus))
                .tag("cache_hit", Boolean.toString(cacheHit))
                .register(registry)
                .increment();
    }

    /** {@code docqa_qa_latency_seconds}。开启 percentile histogram 供 histogram_quantile 计算 P95/P99。 */
    public void qaLatency(Duration duration, String outcome, boolean cacheHit) {
        timer(PREFIX + "qa_latency", "从网关收到请求到返回响应的总耗时（秒）", "outcome", tag(outcome))
                .tag("cache_hit", Boolean.toString(cacheHit))
                .register(registry)
                .record(nonNegative(duration));
    }

    /** {@code docqa_qa_cache_requests_total}，同时驱动 {@code qa_cache_hit_ratio} 的分母。 */
    public void qaCacheLookup(boolean hit) {
        if (hit) {
            cacheHits.incrementAndGet();
        } else {
            cacheMisses.incrementAndGet();
        }
        Counter.builder(PREFIX + "qa_cache_requests_total")
                .description("问答缓存查询次数")
                .tag("result", hit ? "hit" : "miss")
                .register(registry)
                .increment();
    }

    private double cacheHitRatio() {
        int total = cacheHits.get() + cacheMisses.get();
        return total == 0 ? 0.0 : (double) cacheHits.get() / total;
    }

    // ---------------------------------------------------------------- Java → Python

    /** {@code docqa_python_requests_total}；分母必须包含成功与失败（§4.2 补充说明）。 */
    public void pythonRequest(String status, String errorCode) {
        Counter.builder(PREFIX + "python_requests_total")
                .description("Java 调用 Python 的请求数")
                .tag("status", tag(status))
                .tag("error_code", tag(errorCode))
                .register(registry)
                .increment();
    }

    /** {@code docqa_python_request_timeout_total}。 */
    public void pythonTimeout(String endpoint) {
        Counter.builder(PREFIX + "python_request_timeout_total")
                .description("Python 请求超时次数")
                .tag("endpoint", tag(endpoint))
                .register(registry)
                .increment();
    }

    // ---------------------------------------------------------------- MQ

    /** {@code docqa_mq_dlq_total}：消费重试次数达到上限、broker 即将投递死信时计数。 */
    public void mqDlq(String topic, String errorCode) {
        Counter.builder(PREFIX + "mq_dlq_total")
                .description("进入 DLQ 的消息数")
                .tag("topic", tag(topic))
                .tag("error_code", tag(errorCode))
                .register(registry)
                .increment();
    }

    /**
     * {@code docqa_mq_consumer_lag_messages}：消费者积压（消息条数）。
     *
     * <p>与 {@link #circuitBreakerState} 同一套路——「遇到新的标签组合才注册 Gauge」，且 Gauge
     * 绑定在 map 里的 {@code AtomicLong} 上。若每次采集都 {@code Gauge.builder(...).register()}
     * 一个新对象，Micrometer 会按标签复用已注册的 meter，但**弱引用**指向的那个局部 {@code AtomicLong}
     * 会被 GC 掉，读数随即静默变成 NaN。
     *
     * <p>只由探针调用（{@link MqConsumerLagProbe}），调用频率等于采集间隔，不在请求路径上。
     */
    public void mqConsumerLag(String topic, String consumerGroup, long lag) {
        String topicTag = tag(topic);
        String groupTag = tag(consumerGroup);
        AtomicLong holder = consumerLags.computeIfAbsent(topicTag + '|' + groupTag, key -> {
            AtomicLong value = new AtomicLong();
            Gauge.builder(PREFIX + "mq_consumer_lag_messages", value, AtomicLong::get)
                    .description("消费者积压消息数：broker 位点减已提交消费位点，按 topic 汇总")
                    .tag("topic", topicTag)
                    .tag("consumer_group", groupTag)
                    .register(registry);
            return value;
        });
        holder.set(Math.max(0L, lag));
    }

    /**
     * {@code docqa_mq_consumer_lag_probe_success}：探针最近一轮采集是否成功（1/0）。
     *
     * <p>存在的理由：采集失败时 lag 会保留上一次的值（置 0 会被误读成「积压清空了」），
     * 于是「数值陈旧」本身必须有个信号。告警侧用它区分「lag 高」和「lag 不可信」。
     */
    public void mqConsumerLagProbeSuccess(String consumerGroup, boolean success) {
        String groupTag = tag(consumerGroup);
        lagProbeSuccess
                .computeIfAbsent(groupTag, key -> {
                    AtomicInteger value = new AtomicInteger();
                    Gauge.builder(PREFIX + "mq_consumer_lag_probe_success", value, AtomicInteger::get)
                            .description("lag 探针最近一轮采集是否成功：1=成功，0=失败（此时 lag 为上次读数）")
                            .tag("consumer_group", groupTag)
                            .register(registry);
                    return value;
                })
                .set(success ? 1 : 0);
    }

    // ---------------------------------------------------------------- 审计 outbox

    /** 由 relay 每轮刷新；{@code oldestAge} 为 null 表示当前无待投递行。 */
    public void auditOutboxBacklog(int pending, Duration oldestAge) {
        outboxPending.set(Math.max(0, pending));
        outboxOldestAgeSeconds.set(oldestAge == null ? 0L : Math.max(0L, oldestAge.toSeconds()));
    }

    // ---------------------------------------------------------------- 熔断

    /** 把 resilience4j 的当前状态映射为三条 0/1 时间序列，便于 {@code max by (name)} 直接取状态。 */
    public void circuitBreakerState(String name, String currentState) {
        for (String state : BREAKER_STATES) {
            AtomicInteger holder = breakerStates.computeIfAbsent(name + '|' + state, key -> {
                AtomicInteger value = new AtomicInteger();
                Gauge.builder(PREFIX + "circuit_breaker_state", value, AtomicInteger::get)
                        .description("熔断器状态：当前状态为 1，其余为 0")
                        .tag("name", name)
                        .tag("state", state)
                        .register(registry);
                return value;
            });
            holder.set(state.equals(currentState) ? 1 : 0);
        }
    }

    // ---------------------------------------------------------------- 文档解析

    /** {@code docqa_document_parse_duration_seconds}。 */
    public void documentParse(Duration duration, String status) {
        timer(PREFIX + "document_parse_duration", "文档解析耗时（秒）", "status", tag(status))
                .register(registry)
                .record(nonNegative(duration));
    }

    /** {@code docqa_document_parse_failures_total}。 */
    public void documentParseFailure(String errorCode, boolean retryable) {
        Counter.builder(PREFIX + "document_parse_failures_total")
                .description("解析失败次数")
                .tag("error_code", tag(errorCode))
                .tag("retryable", Boolean.toString(retryable))
                .register(registry)
                .increment();
    }

    // ---------------------------------------------------------------- 提醒

    /** {@code docqa_reminder_due_published_total}；{@code status} ∈ PUBLISHED/DUPLICATE/FAILED/SKIPPED。 */
    public void reminderDuePublished(String status) {
        Counter.builder(PREFIX + "reminder_due_published_total")
                .description("到点提醒发布次数")
                .tag("status", tag(status))
                .register(registry)
                .increment();
    }

    // ---------------------------------------------------------------- 内部

    private Timer.Builder timer(String name, String description, String tagKey, String tagValue) {
        return Timer.builder(name)
                .description(description)
                .tag(tagKey, tagValue)
                // §4.1：Java Timer 必须开 percentile histogram，分位数只能由 histogram_quantile 算，
                // 不能用 Gauge 手工维护。SLO 边界取 §4.3 的 8s / 12s 及其两侧。
                .publishPercentileHistogram()
                .serviceLevelObjectives(
                        Duration.ofMillis(200),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(4),
                        Duration.ofSeconds(8),
                        Duration.ofSeconds(12));
    }

    private static Duration nonNegative(Duration duration) {
        if (duration == null || duration.isNegative()) {
            return Duration.ZERO;
        }
        return duration;
    }

    private static String tag(String value) {
        return value == null || value.isBlank() ? NULL_TAG : value;
    }
}
