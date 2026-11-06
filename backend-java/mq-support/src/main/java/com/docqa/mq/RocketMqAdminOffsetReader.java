package com.docqa.mq;

import com.docqa.common.metrics.ConsumerOffsetReader;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.common.message.MessageQueue;
import org.apache.rocketmq.remoting.protocol.admin.ConsumeStats;
import org.apache.rocketmq.remoting.protocol.admin.OffsetWrapper;
import org.apache.rocketmq.tools.admin.DefaultMQAdminExt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 用 RocketMQ 原生 admin API 读取消费者位点：{@code examineConsumeStats} 一次返回该消费组
 * 所有 {@code MessageQueue} 的 broker 位点与已提交消费位点。
 *
 * <p><b>为什么不用 RocketMQ Exporter</b>：Exporter 是一个独立进程，要单独部署、单独连 nameserver、
 * 单独配 scrape，而它导出的指标名与本项目 §4.2 的契约（{@code docqa_mq_consumer_lag_messages}、
 * 标签 {@code topic}/{@code consumer_group}）并不一致，还得再写一层 recording rule 做映射。
 * 服务自己已经握着 nameserver 地址与消费组名，多起一个 admin 连接比多部署一个组件便宜得多。
 *
 * <p><b>懒启动是刻意的</b>：{@code DefaultMQAdminExt.start()} 会拉起 netty 客户端与多个内部线程，
 * 若 nameserver 暂时不可达它会重试并抛 {@link MQClientException}。放在 {@code @PostConstruct}
 * 里意味着「broker 抖一下，整个服务起不来」——探针是观测设施，不能有这种权力。因此首次采集时才
 * 建连，失败就留痕、下次再试。
 */
public final class RocketMqAdminOffsetReader implements ConsumerOffsetReader {

    private static final Logger LOGGER = LoggerFactory.getLogger(RocketMqAdminOffsetReader.class);

    /** admin RPC 超时：与 {@code send-timeout-ms} 同量级，宁可判失败也不要挂住采集线程。 */
    private static final long ADMIN_TIMEOUT_MS = 5_000L;

    private final String namesrv;

    private final Object lock = new Object();

    /** 懒启动的 admin 连接；null 表示尚未建连或已关闭。 */
    private volatile DefaultMQAdminExt admin;

    public RocketMqAdminOffsetReader(String namesrv) {
        this.namesrv = namesrv;
    }

    @Override
    public Map<String, Long> lagByTopic(String consumerGroup) throws Exception {
        ConsumeStats stats = admin().examineConsumeStats(consumerGroup);
        return aggregateByTopic(stats == null ? null : stats.getOffsetTable());
    }

    /**
     * 按 topic 汇总各队列的 {@code brokerOffset - consumerOffset}。
     *
     * <p>两个容易写错的地方：
     *
     * <ul>
     *   <li><b>先 clamp 再求和</b>。某个队列的 consumerOffset 大于 brokerOffset（broker 侧数据被
     *       重置、或位点被手工回拨）时差值是负数。若先求和再 clamp，一个位点超前的队列会把另一个
     *       队列的真实积压抵掉，告警就永远不响。
     *   <li><b>不去重 topic</b>。同一 topic 的多个队列必须相加，这才是「还有多少条没消费」。
     *   <li><b>滤掉系统 topic</b>。真实 broker 上这个接口还会返回
     *       {@code %RETRY%<consumerGroup>}（重试队列），RocketMQ 的内部 topic 一律以 {@code %}
     *       开头。放进指标会让 {@code topic} 标签的基数随消费组数量增长，且
     *       {@code consumer_lag > 1000} 这条告警会被「重试队列积压」触发——那是另一个故障
     *       语义（毒消息），已由 {@code docqa_mq_dlq_total} 覆盖。
     * </ul>
     */
    static Map<String, Long> aggregateByTopic(Map<MessageQueue, OffsetWrapper> offsetTable) {
        Map<String, Long> lags = new LinkedHashMap<>();
        if (offsetTable == null) {
            return lags;
        }
        for (Map.Entry<MessageQueue, OffsetWrapper> entry : offsetTable.entrySet()) {
            MessageQueue queue = entry.getKey();
            OffsetWrapper offset = entry.getValue();
            if (queue == null || offset == null || queue.getTopic() == null) {
                continue;
            }
            if (queue.getTopic().startsWith("%")) {
                continue;
            }
            long diff = Math.max(0L, offset.getBrokerOffset() - offset.getConsumerOffset());
            lags.merge(queue.getTopic(), diff, Long::sum);
        }
        return lags;
    }

    private DefaultMQAdminExt admin() throws MQClientException {
        DefaultMQAdminExt current = admin;
        if (current != null) {
            return current;
        }
        synchronized (lock) {
            if (admin != null) {
                return admin;
            }
            String suffix = UUID.randomUUID().toString();
            // admin 组名只需全局唯一、不需要在 broker 上预先创建；每进程一个随机名可避免
            // 多实例互相顶掉对方的重连状态。
            DefaultMQAdminExt created = new DefaultMQAdminExt("docqa-lag-probe-" + suffix, ADMIN_TIMEOUT_MS);
            created.setNamesrvAddr(namesrv);
            created.setInstanceName("lag-probe-" + suffix);
            try {
                created.start();
            } catch (MQClientException e) {
                // 半启动的实例会留下客户端线程，不清掉就是慢性泄漏。
                created.shutdown();
                throw e;
            }
            admin = created;
            LOGGER.info("MQ consumer lag 探针已连接 nameserver: {}", namesrv);
            return created;
        }
    }

    @Override
    public void close() {
        DefaultMQAdminExt current;
        synchronized (lock) {
            current = admin;
            admin = null;
        }
        if (current == null) {
            return;
        }
        try {
            current.shutdown();
        } catch (RuntimeException e) {
            // 关停失败不该阻断容器销毁流程，记一笔就够了。
            LOGGER.warn("关闭 MQ admin 连接失败: {}", e.toString());
        }
    }
}
