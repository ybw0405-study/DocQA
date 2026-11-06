package com.docqa.mq;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.rocketmq.common.message.MessageQueue;
import org.apache.rocketmq.remoting.protocol.admin.OffsetWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link RocketMqAdminOffsetReader#aggregateByTopic} 的算术契约。
 *
 * <p>单独测这个方法而不是测整个 reader：真正需要 broker 的只有
 * {@code examineConsumeStats} 那一行 RPC，而楼下的求和逻辑有两处一旦写反就会让告警长期不响
 * ——多队列要相加、负差值要先夹到 0 再求和。
 */
class RocketMqAdminOffsetReaderTest {

    @Test
    @DisplayName("同一 topic 的多个队列相加")
    void sumsQueuesOfSameTopic() {
        Map<MessageQueue, OffsetWrapper> table = new LinkedHashMap<>();
        table.put(queue("parse-completed", 0), offset(120L, 100L));
        table.put(queue("parse-completed", 1), offset(80L, 50L));
        table.put(queue("parse-failed", 0), offset(10L, 10L));

        Map<String, Long> lags = RocketMqAdminOffsetReader.aggregateByTopic(table);

        assertThat(lags)
                .containsEntry("parse-completed", 50L)
                .containsEntry("parse-failed", 0L);
    }

    @Test
    @DisplayName("位点超前的队列被夹到 0，不抵掉另一个队列的真实积压")
    void clampsBeforeSumming() {
        Map<MessageQueue, OffsetWrapper> table = new LinkedHashMap<>();
        // 队列 0 积压 30；队列 1 的 consumerOffset 反而大于 brokerOffset（位点被重置过）。
        table.put(queue("parse-completed", 0), offset(130L, 100L));
        table.put(queue("parse-completed", 1), offset(100L, 150L));

        Map<String, Long> lags = RocketMqAdminOffsetReader.aggregateByTopic(table);

        // 先求和再 clamp 会得到 130-100 + 100-150 = -20 → 0，把真实积压藏起来。
        assertThat(lags).containsEntry("parse-completed", 30L);
    }

    @Test
    @DisplayName("系统 topic（%RETRY% 等）不进入指标，避免标签基数随消费组增长")
    void skipsSystemTopics() {
        Map<MessageQueue, OffsetWrapper> table = new LinkedHashMap<>();
        table.put(queue("parse-completed", 0), offset(10L, 10L));
        // 真实 broker 上 examineConsumeStats 一定会带出这条，且它代表重试积压，不是主链路积压。
        table.put(queue("%RETRY%document-service-parse-result", 0), offset(500L, 0L));

        Map<String, Long> lags = RocketMqAdminOffsetReader.aggregateByTopic(table);

        assertThat(lags).containsOnlyKeys("parse-completed");
    }

    @Test
    @DisplayName("空表与 null 表都返回空 map，不抛 NPE")
    void toleratesEmptyTable() {
        assertThat(RocketMqAdminOffsetReader.aggregateByTopic(null)).isEmpty();
        assertThat(RocketMqAdminOffsetReader.aggregateByTopic(Map.of())).isEmpty();
    }

    private static MessageQueue queue(String topic, int queueId) {
        return new MessageQueue(topic, "broker-a", queueId);
    }

    private static OffsetWrapper offset(long brokerOffset, long consumerOffset) {
        OffsetWrapper wrapper = new OffsetWrapper();
        wrapper.setBrokerOffset(brokerOffset);
        wrapper.setConsumerOffset(consumerOffset);
        return wrapper;
    }
}
