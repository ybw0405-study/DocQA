package com.docqa.agentgateway.domain;

import java.time.Instant;

/**
 * 审计发件箱的积压快照。
 *
 * <p>{@code pending} 是游标之后尚未 relay 的行数；{@code oldestRecordedAt} 是其中最早一行的
 * 记录时间，空表或已追平时为 {@code null}。两个字段一起暴露是刻意的：只有行数无法区分
 * 「积压 50 条但都是刚刚产生的」与「积压 1 条但卡了 20 分钟」，而后者才是审计合规事故。
 */
public record OutboxBacklog(int pending, Instant oldestRecordedAt) {
}
