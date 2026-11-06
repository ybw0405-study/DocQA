package com.docqa.agentgateway.service;

import java.time.LocalDate;
import java.util.UUID;

public interface QuotaUsageStore {

    /**
     * 原子「检查 + 问答自增」。任一已达上限返回 false，且不做自增。
     * 上限为 null 表示该维度不设上限，仅累计。
     */
    boolean tryConsume(UUID userId, LocalDate day, Long questionLimit, Long tokenLimit);

    void addTokens(UUID userId, LocalDate day, long tokens);
}
