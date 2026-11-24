package com.docqa.common.metrics;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 消费者 lag 探针（{@code 指标口径文档} §4.2 的 {@code docqa_mq_consumer_lag_messages}）。
 *
 * <p><b>为什么自带线程池而不是 {@code @Scheduled}</b>：Spring 的默认调度器是
 * {@code ThreadPoolTaskScheduler}，默认池大小 1，且本项目已经有其他定时任务在用（提醒扫描、
 * 审计 outbox relay）。{@code examineConsumeStats} 是同步 RPC，broker 慢的时候会把整个调度器
 * 占住——这正是 {@code -M1} 暴露过的形态（两个不相干的定时任务共用一个小池子，一个慢
 * 全拖死）。观测设施不该有能力拖慢业务线程，所以这里起一个只有自己的、daemon 的单线程调度器。
 *
 * <p><b>失败时保留旧读数</b>：采集失败若把 gauge 置 0，Prometheus 侧看起来就是「积压突然清零」，
 * 是最容易误导排障的一种假信号。这里失败时不动 lag，只把
 * {@code docqa_mq_consumer_lag_probe_success} 打 0，让「值是否可信」与「值是多少」分离。
 *
 * <p><b>异常必须就地吞掉</b>：{@code scheduleWithFixedDelay} 的任务一旦抛出未捕获异常，
 * 后续轮次会被静默取消（{@code ScheduledFuture} 变完成态，日志里只有一条堆栈），表现为
 * 「探针上了线，跑了几小时后再也不更新」。因此 {@link #pollQuietly()} 兜住 {@code Throwable}。
 */
public final class MqConsumerLagProbe implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(MqConsumerLagProbe.class);

    /** 采集间隔下限：再密就是在拿 admin RPC 打 broker，对告警没有额外价值。 */
    private static final long MIN_INTERVAL_MS = 5_000L;

    private final String name;

    private final ConsumerOffsetReader reader;

    private final BusinessMetrics metrics;

    private final List<String> consumerGroups;

    private final ScheduledExecutorService scheduler;

    private final AtomicBoolean started = new AtomicBoolean();

    public MqConsumerLagProbe(
            String name, ConsumerOffsetReader reader, BusinessMetrics metrics, List<String> consumerGroups) {
        this.name = name;
        this.reader = reader;
        this.metrics = metrics;
        this.consumerGroups = List.copyOf(consumerGroups);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "mq-lag-probe-" + name);
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 启动周期采集。
     *
     * @param interval 采集间隔，小于 5s 会被抬到 5s（配置写错不该把 broker 打穿）
     */
    public void start(Duration interval) {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        long intervalMs = Math.max(MIN_INTERVAL_MS, interval == null ? MIN_INTERVAL_MS : interval.toMillis());
        // initialDelay = 0：容器起来后尽快让 gauge 有真值，而不是先对着「探针未成功」告警窗口。
        scheduler.scheduleWithFixedDelay(this::pollQuietly, 0L, intervalMs, TimeUnit.MILLISECONDS);
        LOGGER.info("MQ consumer lag 探针已启动: name={} groups={} intervalMs={}", name, consumerGroups, intervalMs);
    }

    private void pollQuietly() {
        try {
            poll();
        } catch (Throwable t) {
            // 见类注释：这里漏一个异常，整个探针就永久停摆。
            LOGGER.warn("MQ consumer lag 探针轮次异常（已吞掉，避免调度被取消）: name={}", name, t);
        }
    }

    /** 采一轮。包级可见是为了让单测直接驱动，不必等调度器。 */
    void poll() {
        for (String group : consumerGroups) {
            try {
                Map<String, Long> lags = reader.lagByTopic(group);
                lags.forEach((topic, lag) -> metrics.mqConsumerLag(topic, group, lag));
                metrics.mqConsumerLagProbeSuccess(group, true);
            } catch (Exception e) {
                metrics.mqConsumerLagProbeSuccess(group, false);
                LOGGER.warn(
                        "MQ consumer lag 采集失败，保留上次读数: name={} consumer_group={} error={}",
                        name,
                        group,
                        e.toString());
            }
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        reader.close();
    }
}
