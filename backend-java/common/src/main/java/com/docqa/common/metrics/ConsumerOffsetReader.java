package com.docqa.common.metrics;

import java.util.Map;

/**
 * 消费者位点读取端口：{@code consumerGroup → (topic → lag 消息数)}。
 *
 * <p>为什么抽这层接口而不是让探针直接持有 {@code DefaultMQAdminExt}：lag 探针的核心逻辑是
 * 「周期性采集 + 写指标 + 失败留痕」，这部分必须能脱离真实 broker 测试。真实实现要连
 * nameserver、要 admin 权限、要消费组真的存在，CI 里一样都没有；把采集退化成一次接口调用后，
 * 探针的调度、异常吞噬、失败时是否保留旧值这些**真正容易写错的地方**就能被单测覆盖。
 *
 * <p>约定：实现方负责把同一 topic 下多个 {@code MessageQueue} 的
 * {@code brokerOffset - consumerOffset} 求和；返回负数没有意义（位点超前说明 broker 侧数据被
 * 重置），实现方须 clamp 到 0。返回值只包含该消费组**确实订阅过**的 topic。
 */
public interface ConsumerOffsetReader extends AutoCloseable {

    /**
     * 读取该消费组各 topic 的 lag。
     *
     * @param consumerGroup 消费组名
     * @return topic → lag（消息条数），无订阅或无位点时返回空 map
     * @throws Exception broker 不可达、权限不足、消费组不存在等；由调用方决定如何留痕
     */
    Map<String, Long> lagByTopic(String consumerGroup) throws Exception;

    /** 释放底层 admin 连接。实现须幂等：探针关闭和容器销毁可能都会调到。 */
    @Override
    void close();
}
